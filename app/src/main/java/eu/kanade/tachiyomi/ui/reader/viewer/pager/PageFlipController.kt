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
 * 3. 每帧把进度交给叠加层，由它在交界处画一道压在当前页上的投影。
 *
 * 翻页的判定、速度、阈值、取消回弹仍然完全归 `ViewPager`，这里不碰。
 *
 * ## 方向
 *
 * 方向由 `scrollX` 的位移方向判定（`ViewPager.currentItem` 在滚动一开始就已经是目标页，
 * 用它推方向会把前进判成后退），并在本轮滚动内**锁定**：用户拖到一半回拖时 `delta`
 * 会反号，而交界位置是按 `offset` 算的，方向一翻新页就会从另一侧跳进来。
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

    /**
     * 上一帧的 `scrollX`，只用来判定本次滚动的方向。
     */
    private var lastScrollX = 0

    /** 当前滚动状态。只在真正滚动的帧里接管（瞬间跳页没有滚动过程）。 */
    private var scrollState = ViewPager.SCROLL_STATE_IDLE

    /**
     * 本轮滚动的方向锁定值，以及锁定时 `onPageScrolled` 报出的 position。
     * 只有 `position` 变了（拖过起点、进到另一页）才重新判定。
     */
    private var lockedForward = true
    private var lockedPosition = -1

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
        val scrollX = viewer.pager.scrollX
        val delta = scrollX - lastScrollX
        lastScrollX = scrollX

        if (offset <= PageFlipView.MIN_PROGRESS || !isEnabled()) {
            end()
            lockedPosition = -1
            return
        }
        if (lockedPosition != position) {
            // 首帧 scrollX 可能还没动，方向未知就先等一帧。
            if (delta == 0) return
            lockedPosition = position
            lockedForward = delta > 0
        }

        val scrollForward = lockedForward
        // `position` 是「最左侧那一页」的下标：
        // 前进时它自己就是当前页、下一页在它右边；后退时它就是新页、当前页在它右边。
        val leavingItem = viewer.itemAt(if (scrollForward) position else position + 1)
        val enteringItem = viewer.itemAt(if (scrollForward) position + 1 else position)
        // 章节过渡页（PagerTransitionHolder）没有「一整张纸」的语义，保持普通滑动。
        if (leavingItem !is ReaderPage || enteringItem !is ReaderPage) {
            end()
            return
        }
        val leaving = findHolder(leavingItem)
        val entering = findHolder(enteringItem)
        if (leaving == null || entering == null || !entering.isImageReady) {
            end()
            return
        }

        val current = session
        if (current == null || current.pinnedView !== leaving || current.sheetView !== entering) {
            end()
            session = Session(pinnedView = leaving, sheetView = entering)
            entering.alpha = 0f
            if (!loggedThisScroll) {
                loggedThisScroll = true
                DiagLog.d(
                    KOMIHA_FLIP_TAG,
                    "cover start page=${enteringItem.index} forward=$scrollForward",
                )
            }
        }

        val w = viewer.pager.width.toFloat()
        // 钉住当前页：抵消 ViewPager 给它的位移，让它原地不动。
        leaving.translationX = if (scrollForward) offset * w else -offset * w
        overlay.sheetView = entering
        overlay.scrollForward = scrollForward
        overlay.progress = offset
        overlay.invalidate()
    }

    /** 滚动状态变化：一旦停稳就复位。 */
    fun onScrollStateChanged(state: Int) {
        scrollState = state
        lastScrollX = viewer.pager.scrollX
        if (state == ViewPager.SCROLL_STATE_IDLE) {
            loggedThisScroll = false
            lockedPosition = -1
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
        lastScrollX = 0
        lockedPosition = -1
        scrollState = ViewPager.SCROLL_STATE_IDLE
    }

    // endregion

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
