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

/**
 * Komiho: 页漫翻页动画的绘制层（覆盖 / 翻书两种模式共用）。
 *
 * ## 两种模式
 *
 * - **覆盖**（[drawCover]）：当前页原地不动，下一页从一侧滑上来盖住它；
 * - **翻书**（[drawFold]）：当前页沿折线从自由边卷向书脊；**折线处是一条被横向压扁的
 *   当前页内容**（筒面正对观者），下一页在被揭开的那片区域里完整显示。
 *
 * 平移（两页并排滑动）不需要本层做任何事 —— ViewPager 自己会画。
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
 * 翻书：每帧 2 次页面合成（下层 + 正面，正面带裁剪）+ 1 块纸背 + 1 个渐变矩形。
 * 全部预分配、零分配。
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
     * 被折走的那一页（当前页，由本层绘制）。非 null 时进入翻书模式。
     *
     * 只画这一页：下面那张「被揭开」的下一页仍然是可见的，由 `ViewPager` 自己绘制。
     * 如果这里再画一遍，纸化档位的纸纹（MULTIPLY）会乘两次 —— 纸色明显变深。
     */
    var foldView: View? = null

    /** 折线进度：0 = 尚未折起，1 = 已折到书脊。 */
    var foldProgress: Float = 0f

    /** 折线从哪一侧的自由边开始（true = 右侧）。 */
    var foldFromRight: Boolean = true

    // endregion

    /** 页面底色（`pageCanvasColor`），纸档隔离层里当页面之下的衬底用。 */
    var canvasColor: Int = Color.WHITE

    /** 投在下方当前页上的阴影 / 折痕阴影。 */
    private val shadowPaint = Paint()

    /** 纸档隔离层的衬底画笔。 */
    private val backdropPaint = Paint()

    /** 卷边筒面的明暗（沿卷边带宽方向的渐变）。 */
    private val rollPaint = Paint()

    /** 斜折线的正面裁剪路径（预分配，逐帧 rewind 复用）。 */
    private val frontPath = Path()

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
        // 卷边筒面的明暗渐变：坐标固定在 [0, bandW]，绘制时平移过去（逐帧零分配）。
        val bandW = w * ROLL_FRACTION
        rollPaint.shader = LinearGradient(
            0f,
            0f,
            bandW,
            0f,
            intArrayOf(ROLL_DARK, ROLL_MID, ROLL_HIGHLIGHT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
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
     * 翻书：当前页沿折线卷走。
     *
     * ```
     *   下一页（静止、清晰） │ 卷边（压扁的当前页） │ 正面剩余部分
     *                        ↑ 折线
     * ```
     *
     * 折线从**自由边**出发向书脊推进；卷边宽度固定（屏幕占比），里面的内容取自折线内侧
     * 一小段并横向压缩 + 镜像 —— 这就是「纸卷起来」的观感来源。p=1 时正面收缩到书脊一条线。
     */
    private fun drawFold(canvas: Canvas) {
        val page = foldView ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val p = foldProgress.coerceIn(0f, 1f)

        // ① 下层（被揭开的下一页）由 `ViewPager` 绘制 —— 它已被钉在 0 且可见。
        //    这里不能重画：纸纹是 MULTIPLY，画两遍会乘两次，纸档会明显偏深。

        // ② 折线：倾斜的。上端推进得比下端快，所以残余会先在上边收窄，
        //    最后落成**下角的一块楔形** —— 这正是参考里「揭起一角」的形态。
        val shift = if (foldFromRight) 1f else -1f      // 自由边在右 ⇒ 向 -x 推进
        val edge = if (foldFromRight) w else 0f
        // 上端比下端推进得快（TOP > BOTTOM），残余因此先在上边收窄、最后落成下角楔形。
        val topX = (edge - shift * w * (p * FOLD_TILT_TOP)).coerceIn(0f, w)
        val botX = (edge - shift * w * (p * FOLD_TILT_BOTTOM)).coerceIn(0f, w)

        // ③ 正面：折线靠书脊一侧的剩余部分（四边形，倾斜后自然成楔形）。
        frontPath.rewind()
        if (foldFromRight) {
            frontPath.moveTo(0f, 0f)
            frontPath.lineTo(topX, 0f)
            frontPath.lineTo(botX, h)
            frontPath.lineTo(0f, h)
        } else {
            frontPath.moveTo(topX, 0f)
            frontPath.lineTo(w, 0f)
            frontPath.lineTo(w, h)
            frontPath.lineTo(botX, h)
        }
        frontPath.close()
        if (p < 1f) {
            val sc1 = canvas.save()
            canvas.clipPath(frontPath)
            drawPage(canvas, page)
            canvas.restoreToCount(sc1)
        }

        // ④ 卷边：折线中点附近一条**被压扁的当前页**（筒面）。
        //    折线虽然是斜的，但倾角只有十几度，这里仍按竖带对齐 —— 省掉一次带旋转的
        //    整页重放（那是唯一的昂贵绘制），观感上看不出差别。
        if (p > 0f && p < 1f) {
            val bandW = w * ROLL_FRACTION
            val srcW = bandW / ROLL_SQUEEZE
            val k = bandW / srcW
            val midX = (topX + botX) / 2f
            val bandLeft = if (foldFromRight) midX else midX - bandW
            val bandRight = bandLeft + bandW
            val sc2 = canvas.save()
            canvas.clipRect(bandLeft, 0f, bandRight, h)
            // dest(x) = mid + (mid − x) · k：把源条带压进卷边、并左右镜像。
            canvas.translate(midX, 0f)
            canvas.scale(-k, 1f)
            canvas.translate(-midX, 0f)
            drawPage(canvas, page)
            canvas.restoreToCount(sc2)

            // 筒面明暗（shader 在 onSizeChanged 里预建，逐帧零分配）。
            val sc3 = canvas.save()
            canvas.clipRect(bandLeft, 0f, bandRight, h)
            canvas.translate(bandLeft, 0f)
            canvas.drawRect(0f, 0f, bandW, h, rollPaint)
            canvas.restoreToCount(sc3)

            // ⑤ 投影：落在被揭开的下一页上，从卷边外侧向外衰减。
            shadowPaint.alpha = FOLD_SHADOW_ALPHA
            val sc4 = canvas.save()
            canvas.translate(if (foldFromRight) bandRight else bandLeft, 0f)
            canvas.scale(if (foldFromRight) 1f else -1f, 1f)
            canvas.drawRect(0f, 0f, shadowW, h, shadowPaint)
            canvas.restoreToCount(sc4)
        }
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

        /** 折痕阴影不透明度（固定，不随进度淡入 —— 折起本身就意味着有厚度）。 */
        const val FOLD_SHADOW_ALPHA = 0x66

        /**
         * 斜折线上端的推进比例（相对页宽）。
         *
         * > 1 表示会越过整页宽 —— 上端先到书脊，残余便只剩下半部分，形成参考里那种
         * 「左下角一块楔形」。
         */
        const val FOLD_TILT_TOP = 1.18f

        /** 斜折线下端的推进比例。比上端小 ⇒ 折线倾斜。 */
        const val FOLD_TILT_BOTTOM = 1.0f

        /** 卷边宽度（占页宽比例）。太窄看不出卷，太宽会像一根粗黑条。 */
        const val ROLL_FRACTION = 0.035f

        /** 卷边压缩比：源条带宽度 = 卷边宽 / 该值。0.35 ≈ 压到 1/3，接近圆柱被看扁。 */
        const val ROLL_SQUEEZE = 0.35f

        /** 筒面近折线一侧（暗）。 */
        const val ROLL_DARK = 0x66000000

        /** 筒面中段（半明半暗）。 */
        const val ROLL_MID = 0x1A000000

        /** 筒面外缘（留一道亮边，让卷边有圆感）。 */
        const val ROLL_HIGHLIGHT = 0x22FFFFFF
    }
}
