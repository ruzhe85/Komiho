package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.diagnostic.DiagLog
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderTheme

/** Komiho 诊断 TAG：翻书动画。 */
private const val KOMIHA_FLIP_TAG = "PageFlip"

/**
 * 点击翻页的时长。参考录制是 42.42fps，逐帧量下来一次换页约 8~9 帧（≈190~210ms），
 * 取 220ms 与之对齐；曲线用「先加速后减速」，峰值速度只有平均值的 1.57 倍，快而不窜。
 */
private const val TAP_FLIP_MS = 220L

/**
 * Komiho: 页漫「覆盖翻页」的驱动。
 *
 * ## 它做三件事
 *
 * 1. **钉住当前页**：把正在露出的那一页的 `translationX` 抵消掉，让它停在原位不动
 *    （这就是「覆盖」与「滑动」的分界 —— 滑动是两页一起走）；
 * 2. **藏起新页**：它改由 [PageFlipView] 画在最上层，保证两个方向的层级都正确；
 * 3. 每帧把新页的**屏幕位置**交给叠加层，由它画新页并在交界处压一道投影。
 *
 * 翻页的判定、速度、阈值、取消回弹仍然完全归 `ViewPager`，这里不碰。
 *
 * ## 两个方向完全对称
 *
 * 全程**不判断「前进 / 后退」**：每帧直接比两页的 `left - scrollX`（它们在屏幕上的真实
 * 位置）—— 贴在视口原点的那一页是当前页（钉住），另一页是新页（按它自己的位置绘制）。
 * 曾经靠 `scrollX` 的位移符号推方向，点击翻页时会判反：当前页被推向错误一侧、新页也从
 * 错误一侧进来，收尾时两页「炸开」。
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

    /** 点击翻页动画（见 [startTapFlip]）。 */
    private var tapAnimator: ValueAnimator? = null

    init {
        // 跨 View 绘制失败（极少数机型/页面）→ 立刻收尾，把页 View 复原，
        // 用户看到的就是一次普通滑动，而不是「页不见了」。
        overlay.onDrawFailed = { end() }
    }

    // region 对外入口

    /**
     * 点击 / 按键翻页：由叠加层自己播完，最后一帧一次性落到目标页。
     *
     * ## 为什么不用 `setCurrentItem(…, true)` 或「假拖动」
     *
     * - `setCurrentItem(…, true)` 用 ViewPager 写死的 viscous-fluid 曲线，前 100ms 走完
     *   大半行程，观感很「窜」；而这个 fork 没有暴露 `setScroller`，换不了曲线。
     * - 「假拖动」（`beginFakeDrag` + `fakeDragBy`）看起来能自定义曲线，但它绕过了手势的
     *   位移累积：首帧真正滚动的像素与我们给的值并不严格相等。叠加层是按**真实 `scrollX`**
     *   钉住当前页的，于是第一帧就被拽回一点 —— 表现就是「横向抖一下，然后才开始动」。
     *
     * 现在整段动画里 ViewPager **一动不动**（`currentItem` 也不变），叠加层把新页从一侧
     * 推到 0；结束后 `setCurrentItem(target, false)` 一次性落位。因为叠加层早已把新页画满
     * 全屏，这一下切换在视觉上不可见，也就不存在任何「拽回」。
     */
    fun startTapFlip(pages: Int) {
        // 先把上一次还没播完的落位（连点两次时 currentItem 会因此变化，target 必须在那之后算）。
        abortTapFlip()

        val pager = viewer.pager
        val target = pager.currentItem + pages
        if (target < 0 || target >= viewer.adapter.count) return

        val prepared = prepareSession(pages)
        if (!prepared) {
            // 没有可用的「纸」（章节过渡页 / 页未就绪）：交回 ViewPager 的默认动画。
            pager.setCurrentItem(target, true)
            return
        }
        // 新页的起点就是它在布局里的真实位置（±页宽），方向不用额外判断。
        val startOffset = overlay.sheetOffsetX
        tapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TAP_FLIP_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                overlay.sheetOffsetX = startOffset * (1f - t)
                overlay.progress = t
                overlay.invalidate()
            }
            doOnEnd {
                tapAnimator = null
                commitTapFlip(target)
            }
            start()
        }
    }

    /** 动画途中用户触摸 → 立即落位，把控制权交回手势。 */
    fun abortTapFlip() {
        tapAnimator?.cancel() // cancel 也会走 doOnEnd → commitTapFlip
    }

    private fun commitTapFlip(target: Int) {
        val current = session
        session = null
        overlay.reset()
        // 先落位再复原：落位后新页就是 currentItem，恢复 alpha 不会闪。
        viewer.pager.setCurrentItem(target, false)
        current?.pinnedView?.translationX = 0f
        current?.sheetView?.alpha = 1f
    }

    /**
     * 预置会话：钉住当前页、藏起新页。
     *
     * 必须在动画开始**之前**做：钉住是按 `left - scrollX` 算的，静止状态下它就是 0，
     * 所以没有任何位移跳变。
     *
     * @return 是否成功备好一张可用的「纸」。
     */
    private fun prepareSession(pages: Int): Boolean {
        if (!isSupported()) return false
        val pager = viewer.pager
        val leavingItem = viewer.itemAt(pager.currentItem)
        val enteringItem = viewer.itemAt(pager.currentItem + pages)
        if (leavingItem !is ReaderPage || enteringItem !is ReaderPage) return false
        val leaving = findHolder(leavingItem) ?: return false
        val entering = findHolder(enteringItem) ?: return false

        end()
        entering.alpha = 0f
        val prepared = Session(pinnedView = leaving, sheetView = entering)
        session = prepared
        applyFrame(prepared, pager.scrollX, 0f)
        return true
    }

    /** `ViewPager` 的滚动回调，唯一的驱动源：拖动与 `setCurrentItem(…, true)` 都走这里。 */
    fun onPageScrolled(position: Int, offset: Float) {
        if (!isSupported() || scrollState == ViewPager.SCROLL_STATE_IDLE) {
            end()
            return
        }

        val pager = viewer.pager
        val scrollX = pager.scrollX
        val current = session ?: startSession(position, scrollX) ?: return
        applyFrame(current, scrollX, offset)
    }

    private fun applyFrame(current: Session, scrollX: Int, offset: Float) {
        // 钉住当前页：让它相对视口停在 0（抵消 ViewPager 给它的位移）。
        current.pinnedView.translationX = -(current.pinnedView.left - scrollX).toFloat()
        // 新页按它自己的真实屏幕位置绘制，方向由位置本身决定，不需要额外判断。
        overlay.sheetView = current.sheetView
        overlay.sheetOffsetX = (current.sheetView.left - scrollX).toFloat()
        // 纸档的衬底要用纸色（不是纯白）：静态时页面之外的空白处是纸纹底，
        // 纸色是它的均值，乘上同一张纸纹后两者才对得上。
        overlay.canvasColor = ReaderTheme.paperStyleOf(viewer.config.theme)?.whiteColor
            ?: viewer.config.pageCanvasColor
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

    /**
     * 立即结束并复位所有副作用（幂等）。
     *
     * 若点击动画正在进行，这里是**放弃**它（不跳页）；主动落位走 [commitTapFlip]。
     */
    fun end() {
        // 先摘监听再取消：否则 cancel 会走 doOnEnd → commitTapFlip，在收尾途中又去跳页。
        tapAnimator?.also {
            it.removeAllListeners()
            it.cancel()
        }
        tapAnimator = null
        val current = session ?: return
        session = null
        current.pinnedView.translationX = 0f
        current.sheetView.alpha = 1f
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

        // 新页藏起来，改由叠加层画在最上层 —— 否则后退翻页时它会被钉住的当前页盖住。
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

    /**
     * 这个 viewer 是否支持覆盖翻页（与「此刻是否在滚动」无关 —— 点击翻页需要在滚动
     * 开始之前就预置会话，见 [startTapFlip]）。
     */
    private fun isSupported(): Boolean {
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
