package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Komiho: 页漫「真实翻书」的绘制层。
 *
 * ## 它画什么
 *
 * 只画**正在翻动的那一张纸**，不画任何页面 View：
 *
 * 1. 纸面正面 —— 折线靠书脊一侧的剩余平放部分（[sheet] 裁剪绘制）；
 * 2. 纸面背面 —— 折过去的那一块。几何上等于「页矩形 关于折线 的镜像」与「平放半平面」
 *    在页面范围内的交集，内容就是同一张快照的镜像，再压一层纸色（[backTint]）。
 *    这样处理的好处：**不需要第二张位图**，也不需要目标页的纹理 —— 与真实纸张
 *    「背面是白纸、只能透出一点背面油墨」一致；
 * 3. 折痕阴影 —— 沿折线对称衰减的渐变，压在前后两面之上，是「像纸」的关键一笔。
 *
 * 目标页（翻过去后露出的那一页）始终是**活 View**，所以永远是增强后的最新图；
 * 本层只负责把「纸」盖在上面。
 *
 * ## 成本约束（本文件最容易写坏的地方）
 *
 * - [onDraw] 内**零分配**：Path / RectF / Paint / Shader 全部是成员，逐帧只 `rewind()`；
 * - 每帧固定 2 次 `drawBitmap`（正/背）+ 1 次 `drawRect`（背面纸色）+ 1 次渐变矩形（阴影）；
 * - 快照是屏幕的 [SHEET_SCALE] 倍（见 [PageFlipController]），采样时只做一次双线性缩放；
 * - 不做 `setShadowLayer` / `saveLayer` / `Path.op` —— 这三样在绘制期都会触发离屏缓冲或
 *   几何布尔运算，是同类实现（含参考库）在低端机上掉帧的主因。
 *
 * ## 方向
 *
 * 折线**永远从自由边扫向书脊**：[spineOnLeft] 为 true 时书脊在左（左→右阅读），
 * false 时书脊在右（日漫右→左）。翻页方向不同只改变 [PageFlipController] 里
 * 「快照取自哪一页」与 progress 的走向，几何本身不变。
 */
internal class PageFlipView(context: Context) : View(context) {

    /** 正在翻动那一页的半分辨率快照。null = 没有可画的纸（空闲状态）。 */
    var sheet: Bitmap? = null

    /**
     * 翻动进度：0 = 整页平放（快照盖满屏幕），1 = 完全翻走（屏幕上一点纸都不剩）。
     * 由 `ViewPager` 的滚动偏移直接驱动，因此拖动与点击翻页共用同一套姿态。
     */
    var progress: Float = 0f

    /** true = 书脊在左（L2R）；false = 书脊在右（R2L / 日漫）。 */
    var spineOnLeft: Boolean = true

    /**
     * 手指落点（0..1，相对屏幕高度），决定折线倾角：抓上方则折线绕上方旋转，
     * 抓中间（0.5）退化为竖直折线。翻页动画的「捏住一角」手感全靠它。
     */
    var grabFraction: Float = 0.5f

    /** 背面纸色（跟随阅读器页面底色），见 [PageFlipController] 的取色。 */
    var backTint: Int = Color.WHITE

    private val sheetPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val tintPaint = Paint()
    private val shadowPaint = Paint()
    private val sheetDst = RectF()
    private val frontPath = Path()
    private val mirroredPath = Path()
    private var shadowRadius = 0f

    init {
        // 纯绘制层：不拦截触摸（点击/拖动必须继续落到下面的 Pager 上）。
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 阴影视口只在尺寸变化时重建：渐变坐标固定在本地系 ±radius，逐帧靠 canvas
        // 的 translate+rotate 把它挪到折线上，因此拖动时不需要重新分配 Shader。
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

    /** 清空状态（会话结束 / 视图销毁）。位图本身由控制器复用，这里只解除引用。 */
    fun reset() {
        if (sheet == null && progress == 0f) return
        sheet = null
        progress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = sheet ?: return
        val p = progress
        // 两端什么都不画：0 = 页面还是原样（真实页 View 就位），1 = 纸已经翻走。
        if (p <= MIN_PROGRESS || p >= MAX_PROGRESS) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || bmp.isRecycled) return

        val freeX = if (spineOnLeft) w else 0f
        val spineX = if (spineOnLeft) 0f else w

        // 折线中点沿「自由边 → 书脊」推进。两段式：前 34% 走完 62% 的行程，
        // 与参考实现（pagecurl 的 keyframes：450ms / 中间姿态在 150ms）比例一致。
        val t = if (p < MID_PROGRESS) {
            p / MID_PROGRESS * 0.62f
        } else {
            0.62f + (p - MID_PROGRESS) / (1f - MID_PROGRESS) * 0.38f
        }
        val cx = freeX + (spineX - freeX) * t

        // 折线绕手指落点旋转，两端相对中点水平张开；张开量在两端收敛为 0
        // （p→0 必须竖直贴住自由边，否则第一帧就会凭空出现一大块折角）。
        val gy = grabFraction.coerceIn(0.12f, 0.88f) * h
        val env = sin(PI.toFloat() * min(p / TILT_FADE_END, 1f))
        val ahead = if (spineOnLeft) 1f else -1f
        val drift = ahead * (0.5f - grabFraction) * 2f * TILT_MAX * w * env
        val topX = cx - drift * (gy / h)
        val botX = cx + drift * ((h - gy) / h)

        val dx = botX - topX
        val lineLen = hypot(dx, h)
        if (lineLen < 1f) return
        val ux = dx / lineLen
        val uy = h / lineLen

        // 指向「平放侧」（书脊一侧）的单位法线。
        val nx = if (spineOnLeft) -uy else uy
        val ny = if (spineOnLeft) ux else -ux
        val ext = (w + h) * 2f

        frontPath.rewind()
        frontPath.moveTo(topX, 0f)
        frontPath.lineTo(botX, h)
        frontPath.lineTo(botX + nx * ext, h + ny * ext)
        frontPath.lineTo(topX + nx * ext, ny * ext)
        frontPath.close()

        buildMirroredRect(topX, ux, uy, w, h)

        sheetDst.set(0f, 0f, w, h)
        val deg = atan2(uy, ux) * 180f / PI.toFloat()

        // ① 正面：折线靠书脊一侧的剩余平放部分。
        val sc1 = canvas.save()
        canvas.clipPath(frontPath)
        canvas.drawBitmap(bmp, null, sheetDst, sheetPaint)
        canvas.restoreToCount(sc1)

        // ② 背面：平放半平面 ∩ 镜像后的页矩形。裁好之后把画布镜像过去再画同一张快照。
        val sc2 = canvas.save()
        canvas.clipPath(frontPath)
        canvas.clipPath(mirroredPath)
        canvas.translate(topX, 0f)
        canvas.rotate(deg)
        canvas.scale(1f, -1f)
        canvas.rotate(-deg)
        canvas.translate(-topX, 0f)
        canvas.drawBitmap(bmp, null, sheetDst, sheetPaint)
        tintPaint.color = backTint
        tintPaint.alpha = BACK_TINT_ALPHA
        canvas.drawRect(sheetDst, tintPaint)
        canvas.restoreToCount(sc2)

        // ③ 折痕阴影：本地系里折线就是 x 轴，渐变沿 y 向对称衰减。
        // 两端各淡出一段：阴影在折线贴到屏幕边缘时会突然消失，不淡出就是一次可见的闪跳。
        if (shadowPaint.shader != null) {
            val fade = min(1f, p / SHADOW_FADE_IN) * min(1f, (1f - p) / SHADOW_FADE_OUT)
            shadowPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            val sc3 = canvas.save()
            canvas.translate(topX, 0f)
            canvas.rotate(deg)
            canvas.drawRect(-ext, -ext, ext, ext, shadowPaint)
            canvas.restoreToCount(sc3)
        }
    }

    /**
     * 生成「页矩形关于折线镜像」后的四边形。
     *
     * 背面之所以还要裁它：折过去的那一块在几何上只可能来自页面本身，镜像矩形之外
     * 对应的是「纸外」，不裁就会画出越出页面边缘的残影。
     */
    private fun buildMirroredRect(topX: Float, ux: Float, uy: Float, w: Float, h: Float) {
        mirroredPath.rewind()
        for (i in 0..3) {
            val px = if (i == 0 || i == 3) 0f else w
            val py = if (i < 2) 0f else h
            val vx = px - topX
            val vy = py
            val dot = vx * ux + vy * uy
            val rx = 2f * (topX + ux * dot) - px
            val ry = 2f * (uy * dot) - py
            if (i == 0) mirroredPath.moveTo(rx, ry) else mirroredPath.lineTo(rx, ry)
        }
        mirroredPath.close()
    }

    internal companion object {
        /**
         * 低于此进度不绘制：此时纸还贴着原样，交给真实页 View。
         *
         * **与 `PageFlipController` 的接管阈值必须是同一个值** —— 低于它两边都不动手
         * （既不藏真实页、也不抵消 ViewPager 的位移），高于它叠加层与真实页同时交接，
         * 屏幕上不会出现「快照与真实页同时可见」或「谁也没画」的中间态。
         */
        const val MIN_PROGRESS = 0.002f

        /** 高于此进度不绘制：纸已经翻出屏幕。 */
        const val MAX_PROGRESS = 0.998f

        /** 折线行程的分段点（对应参考实现 450ms 里的 150ms 中间姿态）。 */
        const val MID_PROGRESS = 0.34f

        /** 折线倾角的衰减终点：超过这个进度折线必须已经竖直，否则贴近书脊时会撕裂。 */
        const val TILT_FADE_END = 0.75f

        /** 折线两端相对中点的最大水平张开（占页宽比例）。 */
        const val TILT_MAX = 0.35f

        /** 背面纸色的不透明度：留一点镜像内容透出来（模拟背面油墨透印）。 */
        const val BACK_TINT_ALPHA = 217

        /** 折痕阴影半径（dp）。 */
        const val SHADOW_DP = 18f

        /** 阴影在起点 / 终点附近的淡出区间（占进度比例）。 */
        const val SHADOW_FADE_IN = 0.06f
        const val SHADOW_FADE_OUT = 0.12f

        /** 折痕阴影峰值色（约 24% 黑）。 */
        const val SHADOW_COLOR = 0x3D000000
    }
}
