package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * Komiho: 页漫「覆盖翻页」的绘制层。
 *
 * ## 效果
 *
 * **当前页原地不动，下一页从一侧滑上来把它盖住**，交界处有一道压在当前页上的投影。
 * 这正是「覆盖」与「滑动」的区别：滑动是两页一起走，覆盖只有新页在动。
 *
 * ```
 *   当前页（钉住不动） │ 投影 │ 下一页（按原生偏移滑入）
 * ```
 *
 * ## 为什么要自己画新页
 *
 * 新页由本层绘制、并把它原本的 View 隐藏，而不是交给 `ViewPager`：
 * `ViewPager` 的子 View 绘制顺序取决于下标（`mCurItem` 不一定最后画），后退翻页时
 * 新页下标更小、会被钉住的当前页盖住 —— 那样看到的就不是覆盖而是「新页被挡住」。
 * 本层最后绘制，层级永远正确，两个方向表现一致。
 *
 * ## 成本
 *
 * 每帧 = 1 次页面合成（无裁剪、无变换，除平移外） + 1 个渐变矩形；全部预分配，
 * `onDraw` 内零分配，不触达解码/增强。
 */
internal class PageFlipView(context: Context) : View(context) {

    /**
     * 正在盖上来那一页（真实页 View，即目标页）。null = 空闲。
     *
     * 只作引用、**不缓存绘制结果**：内容随时可能是增强刚出的新图。
     */
    var sheetView: View? = null

    /** 覆盖进度：0 = 还没盖上来（当前页完整），1 = 完全盖住（目标页完整）。 */
    var progress: Float = 0f

    /** 新页从哪一侧盖上来：true = 从右往左，false = 从左往右。 */
    var scrollForward: Boolean = true

    /** 投在下方当前页上的阴影。 */
    private val shadowPaint = Paint()

    private var shadowW = 0f

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
        // 渐变坐标固定在本地系，逐帧靠 canvas 的 translate/scale 挪到交界上，
        // 因此拖动时不需要重新分配 Shader。
        shadowPaint.shader = LinearGradient(
            0f,
            0f,
            shadowW,
            0f,
            intArrayOf(SHADOW_COLOR, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** 清空状态（会话结束 / 视图销毁）。只解除引用，不持有任何绘制产物。 */
    fun reset() {
        if (sheetView == null && progress == 0f) return
        sheetView = null
        progress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // 这里会把**别人的** View 画到自己的画布上，属于跨 View 绘制（与 DragShadowBuilder
        // 同一套机制）。万一某个页面在这种画法下抛异常，异常会落进绘制遍历 → 阅读器崩，
        // 所以兜住：失败就退化成不画（页面照常可见）。
        try {
            drawCover(canvas)
        } catch (e: Throwable) {
            if (!drawFailed) {
                drawFailed = true
                eu.kanade.tachiyomi.diagnostic.DiagLog.w(
                    "PageFlip",
                    "draw failed, skip cover: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
            sheetView = null
            progress = 0f
            onDrawFailed?.invoke()
        }
    }

    private fun drawCover(canvas: Canvas) {
        val sheet = sheetView ?: return
        val p = progress
        if (p <= MIN_PROGRESS) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 新页在屏幕上的横向位置：它按 ViewPager 的原生偏移滑入。
        val sheetLeft = if (scrollForward) (1f - p) * w else -(1f - p) * w

        // 新页的前沿（也就是投下阴影的那条边），以及阴影方向（指向下方当前页）。
        val edgeX = if (scrollForward) sheetLeft else sheetLeft + w
        val shadowDir = if (scrollForward) -1f else 1f

        // ① 投影：压在当前页上，贴着新页前沿。起点附近淡入，避免刚起手就闪一道暗带。
        val fade = (p / START_FADE).coerceAtMost(1f)
        shadowPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
        val sc1 = canvas.save()
        canvas.translate(edgeX, 0f)
        canvas.scale(shadowDir, 1f)
        canvas.drawRect(0f, 0f, shadowW, h, shadowPaint)
        canvas.restoreToCount(sc1)

        // ② 新页：按原生偏移画在最上层，盖住当前页。
        val sc2 = canvas.save()
        canvas.translate(sheetLeft, 0f)
        sheet.draw(canvas)
        canvas.restoreToCount(sc2)
    }

    internal companion object {
        /** 低于此进度不绘制（新页还在屏幕外）。 */
        const val MIN_PROGRESS = 0.002f

        /** 投影宽度（占页宽比例）。 */
        const val SHADOW_FRACTION = 0.045f

        /** 投影淡入区间（占进度比例）。 */
        const val START_FADE = 0.05f

        /** 投影峰值（约 30% 黑）。 */
        const val SHADOW_COLOR = 0x4D000000
    }
}
