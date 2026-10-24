package eu.kanade.tachiyomi.ui.reader.viewer.pager

import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.diagnostic.DiagLog
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/** Komiho 诊断 TAG：翻书动画。 */
private const val KOMIHA_FLIP_TAG = "PageFlip"

/**
 * Komiho: 页漫「覆盖翻页」的驱动。
 *
 * ## 它做三件事
 *
 * 1. **钉住当前页**：把正在露出的那一页的 `translationX` 抵消掉，让它停在原位不动
 *    （这就是「覆盖」与「滑动」的分界 —— 滑动是两页一起走）；
 * 2. **隐藏新页**：它改由 [PageFlipView] 画在最上层，保证两个方向的层级都正确；
 * 3. 每帧把新页的**屏幕位置**交给叠加层，由它在交界处画一道压在当前页上的投影。
 *
 * 翻页的判定、速度、阈值、取消回弹仍然完全归 `ViewPager`，这里不碰。
 *
 * ## 为什么不用「前进 / 后退」判断方向
 *
 * 之前靠 `scrollX` 的位移符号推方向，再决定谁钉住、新页从哪侧进来。这在点击翻页
 * （`setCurrentItem`）上会判错：一旦判反，当前页会被推向错误的一侧、新页也从错误的一侧
 * 进来，收尾时两页「炸开」。
 *
 * 现在完全不判方向 —— 每帧直接读两页的 `left - scrollX`（它们在屏幕上的真实位置）：
 * 离屏幕原点近的那一页是当前页（钉住），另一页是新页（按它自己的真实位置绘制）。
 * 两个方向走的是同一段代码，天然对称。
 *
 * ## 前置条件
 *
 * 新页必须**已经渲染出图**（`ReaderPageImageView.isImageReady`）才接管。它通常在预载
 * 窗口里、已解码好；若还在解码（例如开着增强快速连翻），这一次就退回普通滑动 ——
 * 否则会把一张空白页盖到屏幕上，比没有动画更糟。
 */
internal class PageFlipController(private val viewer: PagerViewer) {

    /** 叠在 pager 之上的绘制层。由 [PagerViewer] 放进容器。 */
    val overlay = PageFlipView(viewer.activity)

    private var session: Session? = null

    private class Session(
        /** 被钉在原位的那一页（当前页）。 */
        val pinnedView: PagerPageHolder,
        /** 被隐藏、改由叠加层绘制的那一页（新页）。 */
        val sheetView: PagerPageHolder,
    )

    /** 当前滚动状态。只在真正滚动的帧里接管（瞬间跳页没有滚动过程）。 */
    private var scrollState = ViewPager.SCROLL_STATE_IDLE

    /** 本轮滚动是否已经打过启动日志（避免逐帧刷屏）。 */
    private var loggedThisScroll = false

    init {
        // 跨 View 绘制失败（极少数机型/页面）→ 立刻收尾，把页 View 复原，
        // 用户看到的就是一次普通滑动，而不是「页不见了」。
        overlay.onDrawFailed = { end() }
    }

    // region 对外入口

    /** `ViewPager` 的滚动回调，唯一的驱动源：拖动与 `setCurrentItem(…, true)` 都走这里。 */
    fun onPageScrolled(position: Int, offset: Float) {
        if (offset <= PageFlipView.MIN_PROGRESS || !isEnabled()) {
            end()
            return
        }

        val pager = viewer.pager
        val scrollX = pager.scrollX
        val current = session ?: startSession(position, scrollX) ?: return

        // 钉住当前页：让它相对视口停在 0（抵消 ViewPager 给它的位移）。
        current.pinnedView.translationX = -(current.pinnedView.left - scrollX).toFloat()
        // 新页按它自己的真实屏幕位置绘制，方向由位置本身决定，不需要额外判断。
        overlay.sheetView = current.sheetView
        overlay.sheetOffsetX = (current.sheetView.left - scrollX).toFloat()
        overlay.progress = offset
        overlay.invalidate()
    }

    /** 滚动状态变化：一旦停稳就复位。 */
    fun onScrollStateChanged(state: Int) {
        scrollState = state
        if (state == ViewPager.SCROLL_STATE_IDLE) {
            loggedThisScroll = false
            end()
        }
    }

    /** 立即结束并复位所有副作用（幂等）。 */
    fun end() {
        val current = session ?: return
        session = null
        current.sheetView.alpha = 1f
        current.pinnedView.translationX = 0f
        overlay.reset()
    }

    /** 释放（退出阅读器 / 切换阅读模式）。 */
    fun release() {
        end()
        scrollState = ViewPager.SCROLL_STATE_IDLE
    }

    // endregion

    /**
     * 会话启动：把参与这次滚动的两页找出来。
     *
     * 不做方向判断 —— 直接比 `left - scrollX`：滚动刚开始时，当前页几乎贴在视口原点
     * （偏移≈0），另一页还在一屏之外（偏移≈±w）。近的那个是当前页，另一个是新页。
     */
    private fun startSession(position: Int, scrollX: Int): Session? {
        val pager = viewer.pager
        val a = viewer.itemAt(position)
        val b = viewer.itemAt(position + 1)
        // 章节过渡页（PagerTransitionHolder）没有「一整张纸」的语义，保持普通滑动。
        if (a !is ReaderPage || b !is ReaderPage) return null

        val holderA = findHolder(a) ?: return null
        val holderB = findHolder(b) ?: return null
        val distA = kotlin.math.abs(holderA.left - scrollX)
        val distB = kotlin.math.abs(holderB.left - scrollX)
        val pinned = if (distA <= distB) holderA else holderB
        val sheet = if (pinned === holderA) holderB else holderA

        // 新页还没画出来就退回普通滑动 —— 否则会把一张空白页盖到屏幕上。
        if (!sheet.isImageReady) return null

        sheet.alpha = 0f
        if (!loggedThisScroll) {
            loggedThisScroll = true
            DiagLog.d(
                KOMIHA_FLIP_TAG,
                "cover start pinnedLeft=${pinned.item.first.index} " +
                    "sheetLeft=${sheet.item.first.index} sheetX=${sheet.left - scrollX}",
            )
        }
        return Session(pinnedView = pinned, sheetView = sheet).also { session = it }
    }

    private fun isEnabled(): Boolean {
        // 瞬间跳页（恢复进度 / 旋转 / 滑杆拖拽）不接管：那几个场景根本没有滚动过程。
        if (scrollState == ViewPager.SCROLL_STATE_IDLE) return false
        // 只有横向的 L2R / R2L 页漫才翻书；竖向翻页没有「纸」的语义。
        if (viewer is VerticalPagerViewer) return false
        if (viewer !is L2RPagerViewer && viewer !is R2LPagerViewer) return false
        // 页码滑杆连续跳页时不接管（跟着手指刷几十页会很吵）。
        if (viewer.activity.isScrollingThroughPages) return false
        return viewer.config.pageFlipActive
    }

    /**
     * 按页找 holder。必须同时比 `item.first` 与 `item.second`：双页模式下 holder 的
     * `item` 是 `page to extraPage` 这一对整个 Pair。
     */
    private fun findHolder(page: ReaderPage): PagerPageHolder? {
        val pager = viewer.pager
        for (i in 0 until pager.childCount) {
            val child = pager.getChildAt(i) as? PagerPageHolder ?: continue
            if (child.item.first == page || child.item.second == page) return child
        }
        return null
    }
}
