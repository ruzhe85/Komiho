package eu.kanade.tachiyomi.ui.reader.viewer.pager

import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.diagnostic.DiagLog
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderTheme

/** Komiho 诊断 TAG：翻书动画。 */
private const val KOMIHA_FLIP_TAG = "PageFlip"

/**
 * Komiho: 页漫「真实翻书」动画的会话控制。
 *
 * ## 分工
 *
 * 翻页判定与滚动仍然完全交给 `ViewPager`（速度、阈值、边界、取消回弹都不碰），
 * 本类只做三件事：
 *
 * 1. 在用户开始翻页的那一刻选定「哪一页盖上来」，交给 [PageFlipView]；
 * 2. 每收到一次 `onPageScrolled` 就把进度交过去，同时把 `ViewPager` 自己的横向平移
 *    **抵消掉**（两页都停在原位，屏幕上只有那张纸在推进）；
 * 3. 会话结束时复位（页 View 透明度、位移、叠加层）。
 *
 * ## 盖上来的是「目标页」
 *
 * 两个方向都是**目标页从进入侧压住当前页**：当前页原地不动、保持不变暗，目标页逐步
 * 露出。方向只决定从哪一侧进入（与 ViewPager 自身的滚动方向一致，跟手）。
 *
 * 当前页**不需要隐身**，它本来就是屏上那一层；只有目标页要藏起来由叠加层按进度裁剪重画。
 *
 * ## 与图像增强的关系
 *
 * 本类**不发起任何解码 / 增强 / 网络请求，也不做任何位图快照**：纸面就是目标页的活 View，
 * 由 [PageFlipView] 每帧裁剪合成一次。增强管线在翻页期间照常工作，两者只在 GPU 上有一次
 * 整屏合成的交叠，量级远小于一次推理。
 *
 * 代价是目标页必须**已经渲染**（见 [isSheetReady]）：它通常在预载窗口里、已解码好；若还
 * 没出来，本次翻页就退回普通滑动 —— 与没有这个动画时的表现一致。
 */
internal class PageFlipController(private val viewer: PagerViewer) {

    /** 叠在 pager 之上的绘制层。由 [PagerViewer] 放进容器。 */
    val overlay = PageFlipView(viewer.activity)

    private var session: Session? = null

    /** 本次滚动已经尝试过启动（失败就不再逐帧重试）。 */
    private var attempted = false

    /**
     * 上一帧的 `scrollX`。只用来判定本次滚动的方向（见 [onPageScrolled]）——
     * `ViewPager.currentItem` 在滚动一开始就已经是目标页，推不出方向。
     */
    private var lastScrollX = 0

    /** 当前滚动状态。只在真正由手指/惯性驱动的滚动里开动画（瞬间跳页不会走到这里）。 */
    private var scrollState = ViewPager.SCROLL_STATE_IDLE

    /** 手指落点（0..1，相对 pager 高度），由 [Pager] 的 DOWN 回调写入。 */
    private var grabFraction = 0.5f

    init {
        // 跨 View 绘制失败（极少数机型/页面）→ 立刻收尾，把页 View 复原，
        // 用户看到的就是一次普通换页，而不是「页不见了」。
        overlay.onDrawFailed = { end() }
    }

    private class Session(
        /** 本会话对应的 `onPageScrolled.position`（用于识别拖过半反向的情况）。 */
        val expectedPosition: Int,
        /** 盖上来那一页（目标页）的 holder —— 由叠加层负责绘制，原 View 需要隐身。 */
        val sheetView: PagerPageHolder,
        /** 被盖住的那一页；只需要把它的位移一起抵消。 */
        val otherView: PagerPageHolder?,
    ) {
        /** 页 View 是否已被叠加层接管（低于接管阈值时先不管）。 */
        var hidden: Boolean = false
    }

    // region 对外入口

    /** 手势按下点：决定了折线倾角（抓上/抓下/抓中间）。 */
    fun onTouchDown(y: Float) {
        val h = viewer.pager.height
        if (h > 0) grabFraction = (y / h).coerceIn(0f, 1f)
    }

    /**
     * `ViewPager` 的滚动回调。这是唯一的驱动源：拖动与 `setCurrentItem(…, true)`
     * 都会走到这里，因此不需要自己实现动画与速度判定。
     */
    fun onPageScrolled(position: Int, offset: Float) {
        // 方向只能由 scrollX 的位移方向判定：ViewPager 在滚动**一开始**就把 currentItem
        // 改成了目标页（populate 里赋值），所以滚动期间的 currentItem 与 position 的
        // 先后关系恰好相反，拿它推方向会把前进判成后退。
        val scrollX = viewer.pager.scrollX
        val delta = scrollX - lastScrollX
        lastScrollX = scrollX

        val current = session
        if (current == null) {
            if (attempted || offset <= START_OFFSET || delta == 0) return
            attempted = true
            startSession(position, offset, scrollForward = delta > 0)
            return
        }
        // 用户拖过半又反向、或适配器在这些帧里换过页：直接收尾，让下一帧重新判定。
        if (position != current.expectedPosition) {
            end()
            return
        }
        applyProgress(offset)
    }

    /** 滚动状态变化：一旦停稳就复位。 */
    fun onScrollStateChanged(state: Int) {
        scrollState = state
        // DRAGGING / SETTLING 起始时 scrollX 还在原位：这里对齐基准，
        // 免得首个位移用的是上一次滚动残留的数值。
        lastScrollX = viewer.pager.scrollX
        if (state == ViewPager.SCROLL_STATE_IDLE) {
            end()
            attempted = false
        }
    }

    /** 立即结束会话并复位所有副作用（幂等）。 */
    fun end() {
        val current = session ?: return
        session = null
        current.sheetView.alpha = 1f
        resetTranslations()
        overlay.reset()
    }

    /** 释放（退出阅读器 / 切换阅读模式）。 */
    fun release() {
        end()
        attempted = false
    }

    // endregion

    // region 会话启动

    private fun startSession(position: Int, offset: Float, scrollForward: Boolean) {
        // 只对真正在滚动的翻页开动画：瞬间跳页（恢复进度 / 旋转 / 滑杆）不该播。
        if (scrollState == ViewPager.SCROLL_STATE_IDLE) return
        // 只有横向的 L2R / R2L 页漫才翻书；竖向翻页没有「纸」的语义。
        if (viewer is VerticalPagerViewer) return
        if (viewer !is L2RPagerViewer && viewer !is R2LPagerViewer) return
        if (!viewer.config.pageFlipActive) return
        // 页码滑杆连续跳页时不播（跟着手指刷几十页会很吵）。
        if (viewer.activity.isScrollingThroughPages) return

        // `position` 是「最左侧那一页」的下标：scrollX 增大时它就是出发页，
        // 减小时出发页是 position + 1。
        val fromItem = if (scrollForward) position else position + 1
        val toItem = if (scrollForward) fromItem + 1 else fromItem - 1
        val expectedPosition = if (scrollForward) fromItem else fromItem - 1

        // 盖上来的一律是**目标页**：两个方向都是「新页从进入侧压住当前页」。
        // 方向只决定从哪一侧进入 —— 与 ViewPager 自身的滚动方向一致（跟手）。
        val sheetItem = viewer.itemAt(toItem) ?: return
        val otherItem = viewer.itemAt(fromItem) ?: return
        // 章节过渡页（PagerTransitionHolder）没有「一整张纸」的语义，直接走原有滑动。
        // 插入页（拆开宽图后的右半张）本身是 ReaderPage，且已被合并进所属跨页，不在此列。
        if (sheetItem !is ReaderPage || otherItem !is ReaderPage) return

        val sheetView = findHolder(sheetItem) ?: return
        // 纸面必须已经**渲染出图**：`Page.State.Ready` 只代表数据到了，位图解码（尤其带
        // 增强时 0.5~3s）与 SSIV 初始化都可能还没完成，此时画出来就是一张空白纸。
        if (!isSheetReady(sheetView)) return

        val otherView = findHolder(otherItem)
        DiagLog.d(
            KOMIHA_FLIP_TAG,
            "start coverFromRight=$scrollForward page=${sheetItem.index} " +
                "grab=${(grabFraction * 100).toInt()}%",
        )

        overlay.sheetView = sheetView
        overlay.coverFromRight = scrollForward
        overlay.grabFraction = grabbedFractionForOverlay()
        // 纸色必须跟背景档位一致：纸化档位（羊皮纸/牛皮纸）的 `pageCanvasColor` 是**白**，
        // 直接用会在翻动时露出一块纯白 —— 它们的实际纸色在 `PaperStyle.whiteColor` 里。
        overlay.backTint = ReaderTheme.paperStyleOf(viewer.config.theme)?.whiteColor
            ?: viewer.config.pageCanvasColor

        session = Session(
            expectedPosition = expectedPosition,
            sheetView = sheetView,
            otherView = otherView,
        )
        applyProgress(offset)
    }

    /** 抓点只在安全边距内生效；接近顶部/底部时夹一下，避免折线过陡。 */
    private fun grabbedFractionForOverlay(): Float = grabFraction.coerceIn(0.12f, 0.88f)

    /**
     * 按页找 holder。必须同时比 `item.first` 与 `item.second`：双页模式下 holder 的
     * `item` 是 `page to extraPage` 这一对整个 Pair，只比整个 Pair 会在 `itemAt()`
     * 只返回单页时永远不等。
     */
    private fun findHolder(page: ReaderPage): PagerPageHolder? {
        val pager = viewer.pager
        for (i in 0 until pager.childCount) {
            val child = pager.getChildAt(i) as? PagerPageHolder ?: continue
            if (child.item.first == page || child.item.second == page) return child
        }
        return null
    }

    /**
     * 纸面是否已经可以画。
     *
     * 两道门缺一不可：
     * - 双页跨页的**两页都要 Ready** —— 合并渲染本来就等两页 settle（见
     *   `PagerPageHolder.awaitPairSettled`），只判 first 会拿到「半张跨页」；
     * - 页 View 本身要**已渲染**（`ReaderPageImageView.isImageReady`）—— Ready 之后
     *   还有 Coil 解码 / 增强 / SSIV 初始化，那段时间画出来是空白。
     */
    private fun isSheetReady(holder: PagerPageHolder): Boolean {
        val (first, second) = holder.item
        val dataReady = isPageReady(first) && (second == null || isPageReady(second))
        return dataReady && holder.isImageReady
    }

    /** 页面数据是否已经落到本地（[Page.State.Ready]）。 */
    private fun isPageReady(page: ReaderPage): Boolean =
        page.statusFlow.value is Page.State.Ready

    // endregion

    // region 每帧

    private fun applyProgress(progress: Float) {
        val current = session ?: return
        val pager = viewer.pager
        val w = pager.width.toFloat()
        if (w <= 0f) return
        val p = progress.coerceIn(0f, 1f)

        // 接管点与叠加层的 MIN_PROGRESS 是同一个值：低于它两边都不动手（保持原生滑移的
        // 头几像素），高于它才「藏真实页 + 抵消 ViewPager 位移 + 由叠加层接管画面」。
        val active = p > PageFlipView.MIN_PROGRESS

        // 抵消 ViewPager 自身的横向平移：让参战的两页原地不动，屏幕上只有纸在动。
        // 只处理这两页 —— 若对所有离屏页一视同仁，offscreen>0 的页会全部叠到屏幕中央。
        val scroll = pager.scrollX
        for (i in 0 until pager.childCount) {
            val child = pager.getChildAt(i)
            val joined = active && (child === current.sheetView || child === current.otherView)
            child.translationX = if (joined) {
                -((child.left - scroll) / w) * w
            } else {
                0f
            }
        }

        // 目标页由叠加层按进度裁剪重画，原 View 必须隐身 —— 否则它会整屏盖住当前页。
        if (active != current.hidden) {
            current.hidden = active
            current.sheetView.alpha = if (active) 0f else 1f
        }

        overlay.progress = p
        overlay.invalidate()
    }

    private fun resetTranslations() {
        val pager = viewer.pager
        for (i in 0 until pager.childCount) {
            pager.getChildAt(i).translationX = 0f
        }
    }

    // endregion

    private companion object {
        /** 低于此偏移不启动会话（此时整页还在原位，开始翻没有意义）。 */
        const val START_OFFSET = 0.002f
    }
}
