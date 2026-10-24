package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import eu.kanade.tachiyomi.ui.reader.viewer.PaperPageTexture
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Komiho: 页漫「真实翻书」的绘制层。
 *
 * ## 效果
 *
 * **新的一页从一侧盖上来，把当前页压下去**，全程内容清晰：
 *
 * ```
 *   当前页 │阴影│折边│          新页（真实页 View，清晰）
 * ```
 *
 * - 新页就是目标页的**活 View**，直接画到本层（硬件画布）上，不是截图，所以永远是
 *   增强后的最新图、全分辨率；
 * - 当前页保持不动（就是下面那个真实页 View），随新页推进被逐步遮住；
 * - 折边：新页前沿压住一条**空白纸**（纸的背面），配合折痕阴影，是「像纸」的关键一笔。
 *
 * ## 为什么是这个模型
 *
 * 曾经做过「当前页沿折线卷走、露出下一页」，但那张纸只能做到半透明的镜像页，屏幕上
 * 会出现一大块看不清主体的重影；而且前置条件是「当前页先折叠」，与「下一页面盖上来」
 * 的直觉相反。现在改成新页着陆覆盖：只有一块纸、内容清晰、每帧一次裁剪绘制。
 *
 * ## 成本约束（本文件最容易写坏的地方）
 *
 * - [onDraw] 内**零分配**：Path / Paint / Shader 全部是成员，逐帧只 `rewind()`；
 * - 每帧 = 1 次带裁剪的整页合成 + 1 个纸色四边形 + 1 个渐变矩形；
 * - 不做 `setShadowLayer` / `saveLayer` / `Path.op` / 镜像重绘 —— 离屏缓冲与几何布尔
 *   运算正是同类实现在低端机上掉帧的主因；纸化档位的纸纹是唯二的例外（复用页面同一张）。
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
    var coverFromRight: Boolean = true

    /**
     * 手指落点（0..1，相对屏幕高度），让前沿略微倾斜：抓上方则上半部先盖到。
     * 0.5 退化为竖直前沿。
     */
    var grabFraction: Float = 0.5f

    /**
     * 纸色，由控制器按当前背景档位给出：
     * - 普通档位 = `pageCanvasColor`；
     * - 纸化档位（羊皮纸 / 牛皮纸）= `PaperStyle.whiteColor`。
     *
     * 两处用途：新页内容的**不透明衬底**（页 View 的 letterbox 是透明的，不铺底会漏出
     * 下面那一页），以及折边那条空白纸。
     */
    var backTint: Int = Color.WHITE

    /** 纸色。衬底与折边共用。 */
    private val sheetBgPaint = Paint()
    private val shadowPaint = Paint()

    /** 已盖住的区域（书脊侧到前沿）。 */
    private val coveredPath = Path()

    /** 前沿到折进去的那条线之间的一条带 —— 纸的背面。 */
    private val foldPath = Path()

    private var shadowRadius = 0f

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
        shadowRadius = SHADOW_DP * resources.displayMetrics.density
        shadowPaint.shader = LinearGradient(
            0f,
            -shadowRadius,
            0f,
            shadowRadius,
            intArrayOf(Color.TRANSPARENT, SHADOW_COLOR, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
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
        // 所以兜住：失败就退化成不画纸（页面照常可见）。
        try {
            drawFlip(canvas)
        } catch (e: Throwable) {
            if (!drawFailed) {
                drawFailed = true
                eu.kanade.tachiyomi.diagnostic.DiagLog.w(
                    "PageFlip",
                    "draw failed, skip flip: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
            sheetView = null
            progress = 0f
            onDrawFailed?.invoke()
        }
    }

    private fun drawFlip(canvas: Canvas) {
        val sheet = sheetView ?: return
        val p = progress
        // 两端不画：0 = 还没开始，1 = 已经盖满（交给真实页 View，避免多一层合成）。
        if (p <= MIN_PROGRESS || p >= MAX_PROGRESS) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 前沿：从进入侧向另一侧推进，与 ViewPager 的滚动偏移 1:1（跟手）。
        val travelled = p * w
        val edgeX = if (coverFromRight) w - travelled else travelled

        // 前沿按手指落点倾一点：抓上半部则上半部先盖到，0.5 时竖直线。
        val gy = grabFraction.coerceIn(0.12f, 0.88f) * h
        val env = sin(PI.toFloat() * min(p / TILT_FADE_END, 1f))
        val sign = if (coverFromRight) 1f else -1f
        val drift = (0.5f - grabFraction) * 2f * TILT_MAX * w * env
        val topX = edgeX - sign * drift * (gy / h)
        val botX = edgeX + sign * drift * ((h - gy) / h)

        val dx = botX - topX
        val lineLen = hypot(dx, h)
        if (lineLen < 1f) return
        val ux = dx / lineLen
        val uy = h / lineLen

        // 前沿向「已覆盖侧」延伸的单位法线。
        val nx = if (coverFromRight) uy else -uy
        val ny = if (coverFromRight) -ux else ux
        val ext = (w + h) * 2f

        // ① 已覆盖区域：新页（真实页 View）。
        coveredPath.rewind()
        coveredPath.moveTo(topX, 0f)
        coveredPath.lineTo(botX, h)
        coveredPath.lineTo(botX + nx * ext, h + ny * ext)
        coveredPath.lineTo(topX + nx * ext, ny * ext)
        coveredPath.close()

        sheetBgPaint.color = backTint
        sheetBgPaint.alpha = 255

        val sc1 = canvas.save()
        canvas.clipPath(coveredPath)
        canvas.drawRect(0f, 0f, w, h, sheetBgPaint)
        sheet.draw(canvas)
        canvas.restoreToCount(sc1)

        // ② 折边：前沿往里 FLAP_FRACTION 的一条带，是压住的那张纸的**背面**（空白纸）。
        //    两端淡出：起步时它还没出现，盖满时它必须收干，否则最后一帧会留一道白条。
        val edgeFade = min(1f, min(p, 1f - p) / EDGE_FADE)
        val flapW = FLAP_FRACTION * w * edgeFade
        if (flapW > 0.5f) {
            // 折线沿两端各延长 ext，保证倾斜时不会在屏幕上下沿留下缺口。
            val ax = topX - ux * ext
            val ay = -uy * ext
            val bx = botX + ux * ext
            val by = h + uy * ext
            foldPath.rewind()
            foldPath.moveTo(ax, ay)
            foldPath.lineTo(bx, by)
            foldPath.lineTo(bx - nx * flapW, by - ny * flapW)
            foldPath.lineTo(ax - nx * flapW, ay - ny * flapW)
            foldPath.close()

            val sc2 = canvas.save()
            canvas.clipPath(foldPath)
            canvas.drawRect(0f, 0f, w, h, sheetBgPaint)
            // 纸化档位：折边也乘同一张纸纹，颗粒与色值才和页面一致。非纸档位直接返回。
            PaperPageTexture.drawOver(canvas, width, height)
            canvas.restoreToCount(sc2)
        }

        // ③ 折痕阴影：沿前沿对称衰减，正好压住「当前页 → 折边」这条交界，是让它像纸的关键。
        if (shadowPaint.shader != null && edgeFade > 0f) {
            shadowPaint.alpha = (255 * edgeFade).toInt().coerceIn(0, 255)
            val deg = atan2(uy, ux) * 180f / PI.toFloat()
            val sc3 = canvas.save()
            canvas.translate(topX, 0f)
            canvas.rotate(deg)
            canvas.drawRect(-ext, -ext, ext, ext, shadowPaint)
            canvas.restoreToCount(sc3)
        }
    }

    internal companion object {
        /** 低于此进度不绘制（此时目标页还没露出来，交给真实页 View）。 */
        const val MIN_PROGRESS = 0.002f

        /** 高于此进度不绘制（此时已经盖满，交给真实页 View）。 */
        const val MAX_PROGRESS = 0.998f

        /** 前沿倾斜的衰减终点：超过这个进度前沿必须已经竖直，否则贴近边缘时会撕裂。 */
        const val TILT_FADE_END = 0.6f

        /** 前沿两端相对中点的最大水平张开（占页宽比例）。 */
        const val TILT_MAX = 0.18f

        /** 折边宽度（占页宽比例）。 */
        const val FLAP_FRACTION = 0.05f

        /** 折边与阴影在起点 / 终点的淡出区间（占进度比例）。 */
        const val EDGE_FADE = 0.12f

        /** 折痕阴影半径（dp）。 */
        const val SHADOW_DP = 18f

        /** 折痕阴影峰值色（约 24% 黑）。 */
        const val SHADOW_COLOR = 0x3D000000
    }
}
