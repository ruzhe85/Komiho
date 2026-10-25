package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import eu.kanade.tachiyomi.ui.reader.viewer.PaperPageTexture

/**
 * Komiho: 页漫翻页动画的绘制层（覆盖 / 翻书两种模式共用）。
 *
 * ## 覆盖
 *
 * [drawCover]：当前页原地不动，下一页从一侧滑上来盖住它。
 *
 * ## 翻书
 *
 * [drawFold]：**掀起的那一页正面始终朝向观者**，随着翻动被**压向书脊**
 * （纸面转到接近垂直，投影宽度趋近于零），被揭开的下一页从自由边一侧露出来。
 * 交界处是一道暗带 + 落在下一页上的投影 —— 这是「纸」而不是「卷轴」的关键：
 *
 * ```
 *   下一页（已揭开） │ 暗带 │ 掀起的那页（越翻越窄）
 *                        ↑ 折线
 * ```
 *
 * 参考录制（SVID_20261025_141038）逐帧看下来就是这一种：没有空白纸背，
 * 也没有一条压缩带扫过，而是整页朝书脊方向挤窄。
 *
 * ## 纸化档位的隔离层（色差修正）
 *
 * 纸档（羊皮纸/牛皮纸）下，页面会在 `dispatchDraw` 里用 `saveLayer` + **MULTIPLY** 贴一层
 * 纸纹。MULTIPLY 的对象是「画布上该位置已有的像素」：静态渲染时页面自己是不透明的，
 * 纸纹乘的就是页面本身；而本层把页面画到叠加层时，页面之外的透明区域下面是**另一张页**，
 * 乘出来的纸色就偏了 —— 表现为翻页过程中出现色差。
 *
 * 所以纸档下先把这一遍画进**隔离层**，并铺上页面底色（与静态时页面之下那层一致），
 * 让纸纹只跟页面自身相乘。非纸档没有这一步，零额外开销。
 *
 * ## 成本
 *
 * 覆盖：每帧 1 次页面合成 + 1 个渐变矩形。
 * 翻书：每帧 1 次页面合成（带一次横向缩放）+ 2 个渐变矩形。
 * 渐变全部在 `onSizeChanged` 预建，逐帧零分配。
 */
internal class PageFlipView(context: Context) : View(context) {

    // region 覆盖模式

    /**
     * 正在盖上来那一页（真实页 View，即目标页）。null = 空闲。
     *
     * 只作引用、**不缓存绘制结果**：内容随时可能是增强刚出的新图。
     */
    var sheetView: View? = null

    /**
     * 新页在屏幕上的左边界（像素）。为正是从右侧滑入，为负是从左侧滑入；
     * 方向完全由此值决定，两个方向走同一段代码。
     */
    var sheetOffsetX: Float = 0f

    /** 覆盖进度（仅用于投影淡入）。 */
    var progress: Float = 0f

    /**
     * 是否绘制交界投影。
     *
     * 覆盖翻页需要它（新页压在旧页之上）；平移是两页并排滑动、没有层级关系，
     * 画一道投影反而会像「多了一条黑边」。
     */
    var drawShadow: Boolean = true

    // endregion

    // region 翻书模式

    /**
     * 被掀起的那一页（当前页，由本层绘制）。非 null 时进入翻书模式。
     *
     * 只画这一页：下面那张「被揭开」的下一页仍然是可见的，由 `ViewPager` 自己绘制。
     * 如果这里再画一遍，纸化档位的纸纹（MULTIPLY）会乘两次 —— 纸色明显变深。
     */
    var foldView: View? = null

    /** 折线进度：0 = 尚未翻起，1 = 已完全贴到书脊。 */
    var foldProgress: Float = 0f

    /** 折线从哪一侧的自由边开始（true = 右侧）。书脊在相反的一侧。 */
    var foldFromRight: Boolean = true

    // endregion

    /** 页面底色（`pageCanvasColor`），纸档隔离层里当页面之下的衬底用。 */
    var canvasColor: Int = Color.WHITE

    /** 投在被揭开页面上的阴影。 */
    private val shadowPaint = Paint()

    /** 掀起那一页自由边附近的暗带（纸面转侧后反射变暗）。 */
    private val edgePaint = Paint()

    /** 纸档隔离层的衬底画笔。 */
    private val backdropPaint = Paint()

    private var shadowW = 0f

    /** 自由边暗带宽度。 */
    private var edgeW = 0f

    /** 跨 View 绘制失败的记录标记（只记一次日志，之后静默跳过）。 */
    private var drawFailed = false

    /** 绘制失败时回调（控制器据此收尾，把页 View 的透明度与位移复原）。 */
    var onDrawFailed: (() -> Unit)? = null

    init {
        // 纯绘制层：不拦截触摸（点击/拖动必须继续落到下面的 Pager 上）。
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shadowW = w * SHADOW_FRACTION
        edgeW = w * EDGE_FRACTION
        // 两条渐变都固定在本地坐标系（从左往右衰减），绘制时靠 translate/scale 贴到边上，
        // 因此每帧不需要重新分配 Shader。
        shadowPaint.shader = LinearGradient(
            0f,
            0f,
            shadowW,
            0f,
            intArrayOf(SHADOW_COLOR, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        edgePaint.shader = LinearGradient(
            0f,
            0f,
            edgeW,
            0f,
            intArrayOf(EDGE_DARK, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** 清空状态（会话结束 / 视图销毁）。只解除引用，不持有任何绘制产物。 */
    fun reset() {
        if (sheetView == null && foldView == null) return
        sheetView = null
        foldView = null
        progress = 0f
        foldProgress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // 这里会把**别人的** View 画到自己的画布上，属于跨 View 绘制（与 DragShadowBuilder
        // 同一套机制）。万一某个页面在这种画法下抛异常，异常会落进绘制遍历 → 阅读器崩，
        // 所以兜住：失败就退化成不画（页面照常可见）。
        try {
            if (foldView != null) drawFold(canvas) else drawCover(canvas)
        } catch (e: Throwable) {
            if (!drawFailed) {
                drawFailed = true
                eu.kanade.tachiyomi.diagnostic.DiagLog.w(
                    "PageFlip",
                    "draw failed, skip animation: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
            reset()
            onDrawFailed?.invoke()
        }
    }

    // region 覆盖

    private fun drawCover(canvas: Canvas) {
        val sheet = sheetView ?: return
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return

        val left = sheetOffsetX
        // 新页从右侧滑入时，它的前沿是左边缘；从左侧滑入时前沿是右边缘。
        val fromRight = left >= 0f
        val edgeX = if (fromRight) left else left + w
        // 投影落在下方当前页上，也就是新页前沿的外侧。
        val shadowDir = if (fromRight) -1f else 1f

        // ① 投影：起点附近淡入，避免刚起手就闪一道暗带。平移模式不画（见 [drawShadow]）。
        if (drawShadow) {
            val fade = (progress / START_FADE).coerceAtMost(1f)
            shadowPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            val sc1 = canvas.save()
            canvas.translate(edgeX, 0f)
            canvas.scale(shadowDir, 1f)
            canvas.drawRect(0f, 0f, shadowW, h, shadowPaint)
            canvas.restoreToCount(sc1)
        }

        // ② 新页：按真实屏幕位置画在最上层，盖住当前页。
        val sc2 = canvas.save()
        canvas.translate(left, 0f)
        drawPage(canvas, sheet)
        canvas.restoreToCount(sc2)
    }

    // endregion

    // region 翻书

    /**
     * 翻书：掀起的那一页以书脊为锚点横向压缩，被揭开的下一页从自由边一侧露出。
     *
     * `keep = 1 - p` 就是「投影在屏幕上的宽度」——p=0 时是整页，p=1 时收成书脊上的一条线，
     * 与真实纸张转到侧面时的表现一致。
     */
    private fun drawFold(canvas: Canvas) {
        val page = foldView ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val p = foldProgress.coerceIn(0f, 1f)
        val keep = 1f - p
        if (keep <= 0.002f) return

        // ① 下面那张（被揭开的下一页）由 `ViewPager` 绘制 —— 它已被钉在 0 且可见。
        //    这里不能重画：纸纹是 MULTIPLY，画两遍会乘两次，纸档会明显偏深。

        // ② 掀起的那一页：以**书脊**为锚点压缩。自由边在右 ⇒ 书脊在左（x=0）。
        //    再叠一点横向剪切（≈3°），折线就不是竖直的了 —— 参考录制里那道边是斜的。
        val spineX = if (foldFromRight) 0f else w
        val sc1 = canvas.save()
        canvas.scale(keep, 1f, spineX, 0f)
        canvas.skew(FOLD_SKEW * (if (foldFromRight) 1f else -1f), 0f)
        drawPage(canvas, page)
        canvas.restoreToCount(sc1)

        // ③ 自由边（压缩后那张纸的边缘）：纸面正转到接近垂直，反射变暗。
        val edgeX = if (foldFromRight) w * keep else w - w * keep
        edgePaint.alpha = EDGE_ALPHA
        val sc2 = canvas.save()
        canvas.translate(edgeX, 0f)
        // 暗带始终朝纸面内侧衰减：右自由边 → 向左；左自由边 → 向右。
        canvas.scale(if (foldFromRight) -1f else 1f, 1f)
        canvas.drawRect(0f, 0f, edgeW, h, edgePaint)
        canvas.restoreToCount(sc2)

        // ④ 投影：落在被揭开的下一页上，从自由边向外衰减。
        shadowPaint.alpha = FOLD_SHADOW_ALPHA
        val sc3 = canvas.save()
        canvas.translate(edgeX, 0f)
        canvas.scale(if (foldFromRight) 1f else -1f, 1f)
        canvas.drawRect(0f, 0f, shadowW, h, shadowPaint)
        canvas.restoreToCount(sc3)
    }

    // endregion

    /**
     * 把一个页 View 画到当前画布的原点。
     *
     * 纸档下先铺一层页面底色再画 —— 否则页面留白处的纸纹会乘到下面那张页上（色差）。
     */
    private fun drawPage(canvas: Canvas, view: View) {
        if (PaperPageTexture.enabled) {
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            backdropPaint.color = canvasColor
            backdropPaint.alpha = 255
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)
            view.draw(canvas)
            canvas.restoreToCount(layer)
        } else {
            view.draw(canvas)
        }
    }

    internal companion object {
        /** 投影宽度（占页宽比例）。 */
        const val SHADOW_FRACTION = 0.045f

        /** 投影淡入区间（占进度比例）。 */
        const val START_FADE = 0.05f

        /** 投影峰值（约 30% 黑）。 */
        const val SHADOW_COLOR = 0x4D000000

        /** 折痕阴影不透明度（固定，不随进度淡入 —— 翻起本身就意味着有厚度）。 */
        const val FOLD_SHADOW_ALPHA = 0x66

        /** 自由边暗带宽度（占页宽比例）。纸面转到侧面时那一条的宽度。 */
        const val EDGE_FRACTION = 0.16f

        /** 自由边暗带不透明度。 */
        const val EDGE_ALPHA = 0x8C

        /** 暗带峰值（约 55% 黑，靠近边缘）。 */
        const val EDGE_DARK = 0x8C000000

        /**
         * 折线的横向剪切量（x' = x + k·y）。
         *
         * 0.035 ≈ 3°：竖边在页底会偏移约 3.5% 页宽，看起来像手工翻页留下的斜边，
         * 又不至于让内容明显歪掉。参考录制的倾角大约就是这个量级。
         */
        const val FOLD_SKEW = 0.035f
    }
}
