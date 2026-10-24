package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.diagnostic.DiagLog
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlin.math.max

/** Komiho 诊断 TAG：翻书动画（快照耗时 / 降级原因）。 */
private const val KOMIHA_FLIP_TAG = "PageFlip"

/**
 * Komiho: 页漫「真实翻书」动画的会话控制。
 *
 * ## 分工
 *
 * 翻页判定与滚动仍然完全交给 `ViewPager`（速度、阈值、边界、取消回弹都不碰），
 * 本类只做三件事：
 *
 * 1. 在用户开始翻页的那一刻给**被翻走的那一页**拍一张半分辨率快照；
 * 2. 每收到一次 `onPageScrolled` 就把进度换算成折线姿态交给 [PageFlipView]，
 *    同时把 `ViewPager` 自己的横向平移**抵消掉**（页面原地不动，只有纸在动）；
 * 3. 会话结束时复位（页 View 透明度、位移、叠加层）。
 *
 * ## 两张「纸」的来源
 *
 * - **前进**（`currentItem` → +1）：纸 = 当前页。它在屏幕上、必然已加载，快照不会空。
 * - **后退**（`currentItem` → -1）：纸 = 即将进入的那一页（进度 1 → 0）。物理上这是
 *   同一张叶子从书脊处翻回来落地 —— 起点纸是翻走的（不可见），终点正好盖满屏幕，
 *   与活 View 无缝交接。代价是它可能还没解码完，所以 [isPageReady] 不过就直接不播动画。
 *
 * 两种方向共用同一套折线几何（折线从自由边扫向书脊），只有「快照取自哪一页」与进度
 * 走向相反。这样**永远只需要一张快照**，也不需要下一页的纹理。
 *
 * ## 与图像增强的关系
 *
 * 本类**不发起任何解码 / 增强 / 网络请求**：快照只是把页 View 已在内存里的 tile
 * 画一次到 0.5× 位图上（实测 3–5ms）。每帧只有一次网格无关的裁剪绘制，且全部零分配。
 * 唯一的共享资源是那 3–5ms 的 CPU blit，发生在翻页起点，不与增强的推理窗口重叠。
 */
internal class PageFlipController(private val viewer: PagerViewer) {

    /** 叠在 pager 之上的绘制层。由 [PagerViewer] 放进容器。 */
    val overlay = PageFlipView(viewer.activity)

    /** 单张快照位图，跨翻页复用；尺寸变化（旋转）才重建。 */
    private var sheetBitmap: Bitmap? = null

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

    /**
     * 快照失败（例如页面含 HARDWARE 位图，无法画进软件 Canvas）后的降级原因。
     * 一旦写入就**永久**退回普通滑动，避免每翻一页都抛一次异常。
     */
    private var disabledReason: String? = null

    /** 手指落点（0..1，相对 pager 高度），由 [Pager] 的 DOWN 回调写入。 */
    private var grabFraction = 0.5f

    private class Session(
        /** 阅读方向是否在前进（= 页码在往后走）。R2L 下 item 下标是递减的，见 [startSession]。 */
        val advancing: Boolean,
        /** 本会话对应的 `onPageScrolled.position`（用于识别拖过半反向的情况）。 */
        val expectedPosition: Int,
        /** 纸面取自哪个 holder（前进 = 出发页，后退 = 目标页）。 */
        val sheetView: PagerPageHolder,
        /** 参与本次翻页的另一页；只需要把它的位移一起抵消。 */
        val otherView: PagerPageHolder?,
    ) {
        /** 纸当前是否已把页 View 藏起来（低于隐藏阈值时先不藏，避免第一帧画质突变）。 */
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
        // 改成了目标页（populate 里赋值），所以 rolling 期间的 currentItem 与 position 的
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
        applyProgress(if (current.advancing) offset else 1f - offset)
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
        current.otherView?.translationX = 0f
        resetTranslations()
        overlay.reset()
    }

    /** 释放快照位图（退出阅读器 / 切换阅读模式）。 */
    fun release() {
        end()
        sheetBitmap?.recycle()
        sheetBitmap = null
        disabledReason = null
        attempted = false
    }

    // endregion

    // region 会话启动

    private fun startSession(position: Int, offset: Float, scrollForward: Boolean) {
        if (disabledReason != null) return
        // 只对真正在滚动的翻页开动画：瞬间跳页（恢复进度 / 旋转 / 滑杆）不该播。
        if (scrollState == ViewPager.SCROLL_STATE_IDLE) return
        // 只有横向的 L2R / R2L 页漫才翻书；竖向翻页没有「纸」的语义。
        if (viewer is VerticalPagerViewer) return
        if (viewer !is L2RPagerViewer && viewer !is R2LPagerViewer) return
        if (!viewer.config.pageFlipActive) return
        // 页码滑杆连续跳页时不播（跟着手指刷几十页会很吵）。
        if (viewer.activity.isScrollingThroughPages) return

        // `position` 是「最左侧那一页」的下标：scrollX 增大时它就是出发页，
        // 减小时出发页是 position + 1。这一步只跟几何有关，与阅读方向无关。
        val fromItem = if (scrollForward) position else position + 1
        val toItem = if (scrollForward) fromItem + 1 else fromItem - 1
        val expectedPosition = if (scrollForward) fromItem else fromItem - 1

        // 阅读方向：R2L 的 joinedItems 是反序的，**下一页 = 下标减一**。
        // 翻书的视觉必须跟阅读方向一致（前进 = 当前页翻走），不能直接用 scrollX 的方向。
        val advancing = if (viewer is R2LPagerViewer) !scrollForward else scrollForward
        val sheetPos = if (advancing) fromItem else toItem
        val otherPos = if (advancing) toItem else fromItem

        val sheetItem = viewer.itemAt(sheetPos) ?: return
        val otherItem = viewer.itemAt(otherPos) ?: return
        // 章节过渡页（PagerTransitionHolder）没有「一整张纸」的语义，直接走原有滑动。
        // 插入页（拆开宽图后的右半张）本身是 ReaderPage，且已被合并进所属跨页，不在此列。
        if (sheetItem !is ReaderPage || otherItem !is ReaderPage) return

        val sheetView = findHolder(sheetItem) ?: return
        // 纸必须先就绪：后退时它是「还没进场的页」，前进时也可能正赶上首图还在加载
        // （此时拍到的只有底色和进度环，翻起来就是一张白纸）。没就绪就退回普通滑动。
        // 双页跨页要**两页都就绪** —— holder 的合并渲染本来就等两页 settle（见
        // `PagerPageHolder.awaitPairSettled`），只判 first 会把「半张跨页」拍成快照。
        if (!isSheetReady(sheetView)) return

        val startedAt = SystemClock.uptimeMillis()
        val bitmap = capture(sheetView) ?: return
        val cost = SystemClock.uptimeMillis() - startedAt
        DiagLog.d(
            KOMIHA_FLIP_TAG,
            "start advancing=$advancing page=${sheetItem.index} " +
                "size=${bitmap.width}x${bitmap.height} capture=${cost}ms " +
                "grab=${(grabFraction * 100).toInt()}% spineLeft=${viewer !is R2LPagerViewer}",
        )
        if (cost > SLOW_CAPTURE_MS) {
            // 只记录不降级：一次 15ms 的 blit 对 60fps 也只有一帧，值得保留动画。
            DiagLog.w(KOMIHA_FLIP_TAG, "slow snapshot ${cost}ms (page=${sheetItem.index})")
        }

        overlay.sheet = bitmap
        overlay.spineOnLeft = viewer !is R2LPagerViewer
        overlay.grabFraction = grabbedFractionForOverlay()
        overlay.backTint = viewer.config.pageCanvasColor

        session = Session(
            advancing = advancing,
            expectedPosition = expectedPosition,
            sheetView = sheetView,
            otherView = findHolder(otherItem),
        )
        applyProgress(if (advancing) offset else 1f - offset)
    }

    /** 抓点只在使用边距内生效；接近顶部/底部时夹一下，避免折线过陡。 */
    private fun grabbedFractionForOverlay(): Float = grabFraction.coerceIn(0.12f, 0.88f)

    /**
     * 按页找 holder。必须同时比 `item.first` 与 `item.second`：双页模式下 holder 的
     * `item` 是 `page to extraPage` 这一对整个 Pair，只比 `first` 会漏掉「右半页」，
     * 只比整个 Pair 又会在 `itemAt()` 只返回单页时永远不等。
     */
    private fun findHolder(page: ReaderPage): PagerPageHolder? {
        val pager = viewer.pager
        for (i in 0 until pager.childCount) {
            val child = pager.getChildAt(i) as? PagerPageHolder ?: continue
            if (child.item.first == page || child.item.second == page) return child
        }
        return null
    }

    /** 页面内容是否已经落到屏上（[Page.State.Ready]）。 */
    private fun isPageReady(page: ReaderPage): Boolean =
        page.statusFlow.value is Page.State.Ready

    /**
     * 纸面是否已经可以拍快照。
     *
     * 双页模式下 holder 的 `item` 是 `page to extraPage`，**两页都要就绪**：合并渲染
     * 会先等两页 settle，只判第一页的话，回翻时很容易拍到「左半页有了、右半页还是
     * 空白/进度环」的中间态 —— 那张快照会在整段动画里以半张跨页的形式展示。
     */
    private fun isSheetReady(holder: PagerPageHolder): Boolean {
        val (first, second) = holder.item
        return isPageReady(first) && (second == null || isPageReady(second))
    }

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

    // region 快照

    /**
     * 把 [holder] 当前的显示内容画进复用位图（屏幕的 [SHEET_SCALE] 倍）。
     *
     * 用 `View.draw()` 而不是 `PixelCopy` / Coil 重新解码：内容已经在显存/内存里，
     * 这一遍只是把它 blit 到一张小位图上，不触发任何解码、不与增强抢引擎。
     */
    private fun capture(holder: View): Bitmap? {
        val pager = viewer.pager
        val w = holder.width.takeIf { it > 0 } ?: pager.width
        val h = holder.height.takeIf { it > 0 } ?: pager.height
        if (w <= 0 || h <= 0) return null
        val tw = max(1, (w * SHEET_SCALE).toInt())
        val th = max(1, (h * SHEET_SCALE).toInt())
        return try {
            var cached = sheetBitmap
            if (cached != null && (cached.width != tw || cached.height != th || cached.isRecycled)) {
                cached.recycle()
                cached = null
            }
            val target = cached ?: Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                .also { sheetBitmap = it }
            val canvas = Canvas(target)
            // 先铺页面底色：纸的边缘不该是透明像素（否则叠加时会透出下一层）。
            canvas.drawColor(viewer.config.pageCanvasColor)
            canvas.scale(tw / w.toFloat(), th / h.toFloat())
            holder.draw(canvas)
            target
        } catch (e: Throwable) {
            // 典型原因：页内含 Config.HARDWARE 位图，软件 Canvas 画不了。
            // 一旦发生就永久退回普通滑动，绝不让它每页抛一次。
            disabledReason = "${e.javaClass.simpleName}: ${e.message}"
            DiagLog.w(KOMIHA_FLIP_TAG, "capture failed, fallback to slide — $disabledReason")
            sheetBitmap?.recycle()
            sheetBitmap = null
            null
        }
    }

    // endregion

    private companion object {
        /**
         * 快照相对屏幕的缩放：0.5 ⇒ 面积与 blit 成本都是 1/4。
         * 翻页全程纸都在运动，双线性放大带来的柔化在运动中是察觉不到的。
         */
        const val SHEET_SCALE = 0.5f

        /** 低于此偏移不启动会话（此时整页还在原位，开始翻没有意义）。 */
        const val START_OFFSET = 0.002f

        /** 超过这个耗时的快照会在日志里标红（用于实机验收）。 */
        const val SLOW_CAPTURE_MS = 16L
    }
}
