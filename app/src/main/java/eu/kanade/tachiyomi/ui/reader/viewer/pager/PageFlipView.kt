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
 * Komiho: 页漫「覆盖翻页」的绘制层。
 *
 * ## 效果
 *
 * **当前页原地不动，下一页从一侧滑上来把它盖住**，交界处有一道压在当前页上的投影。
 * 这正是「覆盖」与「滑动」的区别：滑动是两页一起走，覆盖只有新页在动。
 *
 * ```
 *   当前页（钉住不动） │ 投影 │ 下一页（按它自己的屏幕位置绘制）
 * ```
 *
 * ## 只认坐标，不认方向
 *
 * 本层不接收「前进 / 后退」，只接收 [sheetOffsetX]（新页在屏幕上的左边界，可以为负）。
 * 它为正说明新页从右侧滑入，为负说明从左滑入 —— 一切由几何决定，两个方向走同一段代码。
 *
 * ## 为什么自己画新页
 *
 * `ViewPager` 的子 View 按 index 绘制，后退翻页时新页下标更小、会被钉住的当前页盖住。
 * 本层最后绘制，层级永远正确。
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
 * 每帧 = 1 次页面合成 + 1 个渐变矩形；纸档多一个隔离层。全部预分配、零分配。
 */
internal class PageFlipView(context: Context) : View(context) {

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

    /** 页面底色（`pageCanvasColor`），纸档隔离层里当页面之下的衬底用。 */
    var canvasColor: Int = Color.WHITE

    /** 投在下方当前页上的阴影。 */
    private val shadowPaint = Paint()

    /** 纸档隔离层的衬底画笔。 */
    private val backdropPaint = Paint()

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
        if (sheetView == null) return
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
            onDrawFailed?.invoke()
        }
    }

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
        if (PaperPageTexture.enabled) {
            // 纸档隔离层：见类注释。先铺底色再画页面，纸纹只跟页面自身相乘。
            val layer = canvas.saveLayer(0f, 0f, w, h, null)
            backdropPaint.color = canvasColor
            backdropPaint.alpha = 255
            canvas.drawRect(0f, 0f, w, h, backdropPaint)
            sheet.draw(canvas)
            canvas.restoreToCount(layer)
        } else {
            sheet.draw(canvas)
        }
        canvas.restoreToCount(sc2)
    }

    internal companion object {
        /** 投影宽度（占页宽比例）。 */
        const val SHADOW_FRACTION = 0.045f

        /** 投影淡入区间（占进度比例）。 */
        const val START_FADE = 0.05f

        /** 投影峰值（约 30% 黑）。 */
        const val SHADOW_COLOR = 0x4D000000
    }
}
