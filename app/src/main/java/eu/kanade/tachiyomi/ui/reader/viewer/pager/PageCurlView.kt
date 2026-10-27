package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Komiho (2026-10-28): 「翻书 2」—— **独立实现的卷曲动画**，逐像素移植自
 * `GarrettKK/Foldchiyomi` 的 `TransitionCurl`（WebGPU/WGSL，Apache-2.0）。
 *
 * ## 为什么不能沿用「翻书」的思路
 *
 * 现有「翻书」(`PageFlipView.drawFold`) 是**平面对折**：折线一侧留正面、另一侧画关于折线的
 * 镜像。数学上等价于卷筒半径 R=0 —— 纸在折线处对折，没有厚度、没有受光、没有背面落下。
 * 在那个骨架上无论加多少阴影，观感都还是"对折"，这就是把参数调来调去也看不出区别的原因。
 *
 * 这里换成他们的模型：
 *
 *  1. **纸只有一张，绕一根圆柱卷过去**。抓点 `c` 与它在自由边上的原位 `p0` 连线，
 *     取中垂线作为**折轴**，再沿法线退回半个周长（πR/2）—— 这样「角」正好落在手指处；
 *     半径 R 随行程收敛（`min(RADIUS·width, dist·0.2)`，末段再压到 0），所以起手是斜折、
 *     收尾自然摊平。
 *  2. **折轴左右两侧是同一张纸的正、反两面**：屏幕点到折轴的有符号距离 d 决定它落在
 *     「正面平放」「正面在卷筒上」「反面在卷筒上」「反面平放」哪一层；采样点按弧长
 *     `asin(d/R)` 反推回纸面坐标，所以内容是被**压扁**而不是镜像 —— 卷筒的透视由此而来。
 *  3. **受光**：正面 `0.62+0.38·cos θ`，反面 `0.62+0.38·max(-cos θ,0)` 且在高光角
 *     (θ≈0.72π) 加一条 gloss；折线附近按 `exp` 压暗（crease）。
 *
 * ## 为什么是 RuntimeShader
 *
 * 上面这些是**逐像素**的分层判断（同一屏幕点可能取正面纹理、反面纹理，或什么都不取），
 * `drawBitmapMesh` 无法表达"每个顶点用自己的 UV"，硬做只能近似成网格条纹。AGSL
 * （Android 13+ 的 `RuntimeShader`）可以几乎逐行照搬他们的 WGSL。
 *
 * Android 12 及以下没有 RuntimeShader —— 那一档直接不接管（[isSupported] 为 false），
 * 阅读器退回 ViewPager 的原生滑动，不会出现半成品动画。
 */
@SuppressLint("NewApi") // RuntimeShader 是 API 33+；低版本由 isSupported 拦在门外，见类注释。
internal class PageCurlView(context: Context) : View(context) {

    /** 当前页快照（屏幕尺寸）。null = 空闲。 */
    var front: Bitmap? = null

    /** 下一页快照；双页跨页时作为"纸背"（书里翻过去露出的是下一个跨页）。 */
    var back: Bitmap? = null

    /** 0 = 整页平放，1 = 完全翻走。 */
    var progress: Float = 0f

    /** true = 书脊在左（自由边在右）。 */
    var spineOnLeft: Boolean = true

    /** 手指落点（0..1，相对高度）—— 决定折轴倾角与"抓的是角还是边中点"。 */
    var grabFraction: Float = 0.5f

    /** true = 双页跨页（背面取下一跨页，而不是本页透印）。 */
    var dualPage: Boolean = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var shader: RuntimeShader? = null
    private var frontShader: BitmapShader? = null
    private var backShader: BitmapShader? = null
    private var shaderFront: Bitmap? = null
    private var shaderBack: Bitmap? = null

    /** 着色器编译失败后不再重试（每帧重试只会刷日志）。 */
    private var shaderFailed = false

    fun reset() {
        val dirty = front != null || back != null
        front = null
        back = null
        progress = 0f
        if (dirty) invalidate()
    }

    /** 释放快照位图（会话结束 / 退出阅读器）。 */
    fun releaseBitmaps() {
        val f = front
        val b = back
        front = null
        back = null
        progress = 0f
        f?.takeIf { !it.isRecycled }?.recycle()
        b?.takeIf { it !== f && !it.isRecycled }?.recycle()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onDraw(canvas: Canvas) {
        val frontBitmap = front ?: return
        if (frontBitmap.isRecycled) return
        val p = progress.coerceIn(0f, 1f)
        if (p <= MIN_PROGRESS || p >= MAX_PROGRESS) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 着色器建不起来（AGSL 编译失败等）时不能让屏幕空着：直接把纸平铺画出来，
        // 至少这一页还在，翻页落位后照常换页。
        val shader = ensureShader() ?: run {
            paint.shader = null
            canvas.drawBitmap(frontBitmap, 0f, 0f, paint)
            return
        }
        // 位图换了就重建采样器（BitmapShader 与位图内容绑定）。
        if (shaderFront !== frontBitmap || shaderBack !== back) {
            frontShader = BitmapShader(frontBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setInputShader(UNIFORM_TEX_A, frontShader!!)
            val b = back?.takeIf { !it.isRecycled }
            if (b != null) {
                backShader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader.setInputShader(UNIFORM_TEX_B, backShader!!)
            } else {
                // 没有下一页快照：用当前页兜底（观感等同单页透印）。
                shader.setInputShader(UNIFORM_TEX_B, frontShader!!)
            }
            shaderFront = frontBitmap
            shaderBack = back
        }

        val frame = computeFrame(p, w, h) ?: return

        val aspect = h / w
        shader.setFloatUniform("uSize", w, h)
        shader.setFloatUniform("uAspect", aspect)
        shader.setFloatUniform("uAxis", frame.axisX, frame.axisY)
        shader.setFloatUniform("uNormal", frame.nx, frame.ny)
        shader.setFloatUniform("uRadius", frame.radius)
        shader.setFloatUniform("uLift", frame.lift)
        shader.setFloatUniform("uSheet", frame.sx1, frame.sy1, frame.sx2, frame.sy2)
        shader.setFloatUniform("uSpine", frame.spineX)
        shader.setFloatUniform("uDual", if (dualPage) 1f else 0f)

        paint.shader = shader
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    // region 几何（对应 TransitionCurl.curl）

    /** 一帧的卷曲参数，全部在「度量坐标」：x ∈ [0,1]，y ∈ [0,aspect]。 */
    private class Frame(
        val axisX: Float,
        val axisY: Float,
        val nx: Float,
        val ny: Float,
        val radius: Float,
        val lift: Float,
        val sx1: Float,
        val sy1: Float,
        val sx2: Float,
        val sy2: Float,
        val spineX: Float,
    )

    private fun computeFrame(t: Float, w: Float, h: Float): Frame? {
        val aspect = h / w
        // 书脊侧 / 自由侧（单位坐标）。书脊在右时把整个几何镜像，采样时用 1-x 处理。
        val hingeX = if (spineOnLeft) 0f else 1f
        val freeX = 1f - hingeX

        // 正在翻的那张纸：单页就是整屏；双页时是跨页的一半（脊线到自由边）。
        val sx1 = 0f
        val sx2 = 1f
        val sy1 = 0f
        val sy2 = aspect
        val spineX = if (dualPage) 0.5f else hingeX
        val width = abs(sx2 - sx1).coerceAtLeast(0.3f)

        // 行程：翻过去等价于自由边跨过两张纸宽（与他们的 travel = 2*width 一致）。
        val travel = 2f * width
        val pace = min(1f, 1f / travel)
        val eased = pace * t + (1f - pace) * t * t
        val px = freeX + (hingeX - freeX) * eased

        val height = sy2 - sy1
        var grab = grabFraction.coerceIn(0f, 1f) * aspect
        // 抓角吸附（他们的 CORNER_SNAP=0.14）：靠近上下边时视为抓住了角。
        if (grab - sy1 < CORNER_SNAP * height) grab = sy1
        if (sy2 - grab < CORNER_SNAP * height) grab = sy2
        val gy = grab

        // 点击翻页（auto）：角自然抬起再落下，这是「掀起来」而不是「对折」的关键。
        val dy = -AUTO_LIFT * height * sin(PI * t).toFloat()

        val p0x = freeX
        val p0y = gy
        var cx = px
        var cy = gy + dy
        // 铰链不动：角始终在它到两端可达范围内（他们的 repeat(3) 松弛）。
        repeat(3) {
            for (hy in floatArrayOf(sy1, sy2)) {
                val reach = hypot(p0x - hingeX, p0y - hy)
                val vx = cx - hingeX
                val vy = cy - hy
                val l = hypot(vx, vy)
                if (l > reach && l > 0f) {
                    cx = hingeX + vx * reach / l
                    cy = hy + vy * reach / l
                }
            }
        }

        val lift = smoothstep(t / 0.05f) * (1f - smoothstep((t - 0.9f) / 0.1f))
        val dx = p0x - cx
        val dyc = p0y - cy
        val dist = hypot(dx, dyc)
        if (dist < 1e-5f) return null
        val nx = dx / dist
        val ny = dyc / dist
        // 卷筒半径：中段最粗（RADIUS×纸宽），起手受行程限制，收尾压到 0 自然摊平。
        val radius = max(
            min(RADIUS * width, dist * 0.2f) * (1f - smoothstep((t - 0.8f) / 0.2f)),
            1e-4f,
        )
        val mx = (p0x + cx) * 0.5f
        val my = (p0y + cy) * 0.5f
        val back = PI.toFloat() * radius * 0.5f
        return Frame(
            axisX = mx - nx * back,
            axisY = my - ny * back,
            nx = nx,
            ny = ny,
            radius = radius,
            lift = lift,
            sx1 = sx1,
            sy1 = sy1,
            sx2 = sx2,
            sy2 = sy2,
            spineX = spineX,
        )
    }

    private fun smoothstep(v: Float): Float {
        val e = v.coerceIn(0f, 1f)
        return e * e * (3f - 2f * e)
    }

    // endregion

    private fun ensureShader(): RuntimeShader? {
        if (!isSupported) return null
        shader?.let { return it }
        if (width <= 0 || height <= 0) return null
        if (shaderFailed) return null
        return runCatching {
            RuntimeShader(AGSL).also {
                shader = it
                eu.kanade.tachiyomi.diagnostic.DiagLog.d("PageCurl", "RuntimeShader 编译成功")
            }
        }.onFailure { e ->
            // AGSL 是**运行期**编译的，写错不会在构建时报出来 —— 必须留一条可导出的日志。
            shaderFailed = true
            eu.kanade.tachiyomi.diagnostic.DiagLog.w(
                "PageCurl",
                "RuntimeShader 构建失败，退回平铺绘制：${e.javaClass.simpleName}: ${e.message}",
            )
        }.getOrNull()
    }

    companion object {
        /** RuntimeShader（AGSL）需要 Android 13。低版本直接不接管这一档动画。 */
        val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        private const val UNIFORM_TEX_A = "texA"
        private const val UNIFORM_TEX_B = "texB"

        /** 卷筒半径上限（占纸宽）—— 与 `TransitionCurl.RADIUS` 同值。 */
        private const val RADIUS = 0.085f

        /** 抓角吸附阈值（占纸高）。 */
        private const val CORNER_SNAP = 0.14f

        /** 点击翻页时角的抬起高度（占纸高）。 */
        private const val AUTO_LIFT = 0.16f

        private const val MIN_PROGRESS = 0.001f
        private const val MAX_PROGRESS = 0.999f

        /**
         * 逐像素移植自 `TransitionCurl` 的片段着色器。
         *
         * 与他们的差异只有一处：`layer == 0`（纸没盖住的地方）这里直接返回透明，
         * 让下面 ViewPager 已经摆好的下一页露出来；他们因为自绘整个 surface，得多画一层
         * underlay。
         */
        private const val AGSL = """
uniform shader texA;
uniform shader texB;

uniform float2 uSize;      // 画布像素尺寸
uniform float uAspect;     // 高/宽
uniform float2 uAxis;      // 折轴上一点（度量坐标）
uniform float2 uNormal;    // 折轴法线（指向抬起的一侧）
uniform float uRadius;
uniform float uLift;
uniform float4 uSheet;     // x1,y1,x2,y2（度量坐标）
uniform float uSpine;      // 书脊 x（度量坐标），双页时的镜像轴
uniform float uDual;       // 1 = 跨页

const float PI = 3.14159265;

float4 sampleA(float2 m) {
    return texA.eval(float2(m.x, m.y / uAspect) * uSize);
}

float4 sampleB(float2 m) {
    return texB.eval(float2(m.x, m.y / uAspect) * uSize);
}

bool inSheet(float2 p) {
    return p.x >= uSheet.x && p.x <= uSheet.z && p.y >= uSheet.y && p.y <= uSheet.w;
}

// 纸背：跨页时是下一跨页关于书脊的镜像；单页时是本页透印压白。
float4 backColor(float2 src) {
    if (uDual > 0.5) {
        return sampleB(float2(2.0 * uSpine - src.x, src.y));
    }
    float4 through = sampleA(src);
    return float4(mix(through.rgb, float3(0.98, 0.98, 0.969), 0.86), 1.0);
}

float4 shade(float2 fragCoord) {
    float2 p = float2(fragCoord.x / uSize.x, (fragCoord.y / uSize.y) * uAspect);
    float r = uRadius;
    float d = dot(p - uAxis, uNormal);

    int layer = 0;
    float2 src = p;
    float theta = 0.0;

    if (d < 0.0) {
        // 自由侧：先看是不是翻过去压在正面上的那一半（背面），否则仍是正面平放。
        float2 flap = p + uNormal * (PI * r - 2.0 * d);
        if (inSheet(flap)) {
            layer = 4; src = flap; theta = PI;
        } else if (inSheet(p)) {
            layer = 1;
        }
    } else if (d <= r) {
        // 卷筒带：同一屏幕点可能先遇到背面（upper）再遇到正面（lower）。
        float a1 = asin(clamp(d / r, 0.0, 1.0));
        float2 upper = p + uNormal * (r * (PI - a1) - d);
        float2 lower = p + uNormal * (r * a1 - d);
        if (inSheet(upper)) {
            layer = 3; src = upper; theta = PI - a1;
        } else if (inSheet(lower)) {
            layer = 2; src = lower; theta = a1;
        }
    }

    float crease = 1.0 - 0.14 * uLift * exp(min(d, 0.0) / (0.5 * r + 0.01));

    if (layer == 2) {
        float lit = 0.62 + 0.38 * cos(theta);
        float4 c = sampleA(src);
        return float4(c.rgb * lit * crease, c.a);
    }
    if (layer == 3 || layer == 4) {
        float lit = 0.62 + 0.38 * max(-cos(theta), 0.0);
        float g = (theta - 0.72 * PI) / 0.12;
        float gloss = 0.1 * uLift * exp(-g * g);
        float4 c = backColor(src);
        return float4(min(c.rgb * lit * crease + gloss, float3(1.0, 1.0, 1.0)), c.a);
    }
    if (layer == 1) {
        float4 c = sampleA(p);
        return float4(c.rgb * crease, c.a);
    }
    // 纸没盖住：透明 → 露出下面那一页。
    return float4(0.0);
}

// AGSL 的入口必须是 half4 main(float2)；内部统一用 float4 算完再转换。
half4 main(float2 fragCoord) {
    return half4(shade(fragCoord));
}
"""
    }
}

