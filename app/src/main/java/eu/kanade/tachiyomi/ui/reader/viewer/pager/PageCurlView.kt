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
 * Komiho (2026-10-28): 「翻书 2」—— 独立实现的卷曲动画，完整移植 `GarrettKK/Foldchiyomi`
 * 的 `TransitionCurl`（WebGPU/WGSL，Apache-2.0）。
 *
 * ## 与「翻书」的本质区别
 *
 * 「翻书」是**平面对折**（纸沿一条线镜像，R=0）。这里是**一张纸绕圆柱卷过去**：
 *
 *  - 折轴由「抓点 → 自由边原位」的中垂线决定，再沿法线退回半周长（πR/2），角正好在手指下；
 *  - 屏幕点到折轴的有符号距离 d 决定它属于哪一层：
 *      d > R        纸已掀开 → 看**下一页**（underlay）
 *      d<0          纸面这一侧 → 正面平放 / 已落下的**纸背**（flap，关于折轴镜像）
 *      0 ≤ d ≤ R    卷筒带 → 正面或背面在圆柱上（采样坐标按 asin(d/R) 反推）
 *  - 纸背内容：跨页时是**下一跨页关于脊线的镜像**；单页时是本页透印。
 *
 * 关键点：`underlay` 必须由着色器自己画（下一页 / 脊线另一侧 / 按进度混合），
 * 不能依赖"透明露出下面的 View"——否则纸没盖住的区域会一直显示未变化的正面，
 * 看起来就像"只有一条线划过"。
 *
 * RuntimeShader 需要 Android 13；低版本 [isSupported] 为 false，整档交给原生滑动。
 */
@SuppressLint("NewApi") // RuntimeShader 是 API 33+，低版本由 isSupported 拦住。
internal class PageCurlView(context: Context) : View(context) {

    /** 当前页快照（屏幕尺寸）。null = 空闲。 */
    var front: Bitmap? = null

    /** 下一页快照；跨页时作为纸背（书里翻过去露出的是下一个跨页）。 */
    var back: Bitmap? = null

    /** 0 = 整页平放，1 = 完全翻走。 */
    var progress: Float = 0f

    /** true = 脊线在左（L2R 前进方向）；false 时整体镜像（日漫 R2L）。 */
    var spineOnLeft: Boolean = true

    /** 手指落点（0..1，相对高度）—— 决定折轴倾角与抓角吸附。 */
    var grabFraction: Float = 0.5f

    /** true = 双页跨页（一张合并图 = 两页并排）。 */
    var dualPage: Boolean = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var shader: RuntimeShader? = null
    private var frontShader: BitmapShader? = null
    private var backShader: BitmapShader? = null
    private var shaderFront: Bitmap? = null
    private var shaderBack: Bitmap? = null
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

    override fun onDraw(canvas: Canvas) {
        val frontBitmap = front ?: return
        if (frontBitmap.isRecycled) return
        val p = progress.coerceIn(0f, 1f)
        if (p <= MIN_PROGRESS || p >= MAX_PROGRESS) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val shader = ensureShader() ?: run {
            // 着色器建不起来时不能让屏幕空着：平铺画出这一页，落位后照常换页。
            paint.shader = null
            canvas.drawBitmap(frontBitmap, 0f, 0f, paint)
            return
        }
        if (shaderFront !== frontBitmap || shaderBack !== back) {
            frontShader = BitmapShader(frontBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setInputShader(TEX_A, frontShader!!)
            val b = back?.takeIf { !it.isRecycled }
            if (b != null) {
                backShader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader.setInputShader(TEX_B, backShader!!)
            } else {
                shader.setInputShader(TEX_B, frontShader!!)
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
        shader.setFloatUniform("uT", p)
        shader.setFloatUniform("uSheet", frame.sx1, frame.sy1, frame.sx2, frame.sy2)
        shader.setFloatUniform("uSpine", frame.spineX)
        shader.setFloatUniform("uDual", if (dualPage) 1f else 0f)
        shader.setFloatUniform("uMirror", if (spineOnLeft) 0f else 1f)

        paint.shader = shader
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    // region 几何（对应 TransitionCurl.curl + sheet）

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

        // leaf：翻动的那一张。
        //  - 跨页：脊线到自由边的那半张（脊线固定在 0.5）；
        //  - 单页：整页。
        val spineX = if (dualPage) SPINE else 0f
        val sx1 = spineX
        val sy1 = 0f
        val sx2 = 1f
        val sy2 = aspect
        val width = (sx2 - sx1).coerceAtLeast(0.3f)

        // 行程：自由边从原位走到脊线 = 一个 leaf 宽（他们的 travel = 2*width 是往返量，
        // 这里 turnProgress 已经把它归一化到 0..1，所以直接用 width）。
        val travel = 2f * width
        val pace = min(1f, 1f / travel)
        val eased = pace * t + (1f - pace) * t * t
        val px = sx2 - travel * eased

        val height = sy2 - sy1
        var grab = grabFraction.coerceIn(0f, 1f) * aspect
        // 抓角吸附（CORNER_SNAP = 0.14，与上游一致）。
        if (grab - sy1 < CORNER_SNAP * height) grab = sy1
        if (sy2 - grab < CORNER_SNAP * height) grab = sy2
        val gy = grab

        // 点击翻页：角自然抬起再落下（AUTO_LIFT），这是"掀起"而非"对折"的来源。
        val dy = -AUTO_LIFT * height * sin(PI * t).toFloat()

        val p0x = sx2
        val p0y = gy
        var cx = px
        var cy = gy + dy
        // 铰链不动：角始终在到两端可达范围内（上游 repeat(3) 松弛）。
        repeat(3) {
            for (hy in floatArrayOf(sy1, sy2)) {
                val reach = hypot(p0x - sx1, p0y - hy)
                val vx = cx - sx1
                val vy = cy - hy
                val l = hypot(vx, vy)
                if (l > reach && l > 0f) {
                    cx = sx1 + vx * reach / l
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
        val radius = max(
            min(RADIUS * max(width, 0.3f), dist * 0.2f) * (1f - smoothstep((t - 0.8f) / 0.2f)),
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
            shaderFailed = true
            eu.kanade.tachiyomi.diagnostic.DiagLog.w(
                "PageCurl",
                "RuntimeShader 构建失败，退回平铺绘制：${e.javaClass.simpleName}: ${e.message}",
            )
        }.getOrNull()
    }

    companion object {
        /** RuntimeShader（AGSL）需要 Android 13。低版本这一档不接管。 */
        val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        private const val TEX_A = "texA"
        private const val TEX_B = "texB"

        /** 跨页时的脊线位置（度量坐标；一张合并跨页图的中缝）。 */
        private const val SPINE = 0.5f

        /** 卷筒半径上限（占 leaf 宽）—— 上游同值。 */
        private const val RADIUS = 0.085f

        /** 抓角吸附阈值（占纸高）。 */
        private const val CORNER_SNAP = 0.14f

        /** 点击翻页时角的抬起高度（占纸高）。 */
        private const val AUTO_LIFT = 0.16f

        private const val MIN_PROGRESS = 0.001f
        private const val MAX_PROGRESS = 0.999f

        private const val AGSL = """
uniform shader texA;
uniform shader texB;

uniform float2 uSize;
uniform float uAspect;
uniform float2 uAxis;
uniform float2 uNormal;
uniform float uRadius;
uniform float uLift;
uniform float uT;
uniform float4 uSheet;
uniform float uSpine;
uniform float uDual;
uniform float uMirror;

const float PI = 3.14159265;

float2 uvOf(float2 m) {
    float x = (uMirror > 0.5) ? (1.0 - m.x) : m.x;
    return float2(x, m.y / uAspect);
}

float4 sampleA(float2 m) { return texA.eval(uvOf(m) * uSize); }
float4 sampleB(float2 m) { return texB.eval(uvOf(m) * uSize); }

float sheetDistance(float2 p) {
    float2 c = (uSheet.xy + uSheet.zw) * 0.5;
    float2 halfSize = (uSheet.zw - uSheet.xy) * 0.5;
    float2 q = abs(p - c) - halfSize;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0);
}

bool inSheet(float2 p) { return sheetDistance(p) <= 0.0; }

// 纸背：跨页时是下一跨页关于脊线的镜像；单页时是本页透印。
float4 backColor(float2 src) {
    if (uDual > 0.5) {
        return sampleB(float2(2.0 * uSpine - src.x, src.y));
    }
    float4 through = sampleA(src);
    return float4(mix(through.rgb, float3(0.98, 0.98, 0.969), 0.45), 1.0);
}

// 纸下面这一层：被揭开处看下一页；跨页时脊线另一侧先是本页另一半、末段过渡到下一跨页；
// 其余按进度在 A→B 之间混合。**必须自己画**，不能返回透明让下面的 View 兜底。
float4 underlay(float2 q, float2 p) {
    if (uDual > 0.5 && q.x < uSpine) {
        return mix(sampleA(p), sampleB(p), smoothstep(0.85, 1.0, uT));
    }
    if (inSheet(p)) { return sampleB(p); }
    return mix(sampleA(p), sampleB(p), uT);
}

float4 shadeOver(float4 c, float a) {
    return float4(c.rgb * (1.0 - a), c.a + a * (1.0 - c.a));
}

float4 shade(float2 fragCoord) {
    float2 q = float2(fragCoord.x / uSize.x, fragCoord.y / uSize.y);
    if (uMirror > 0.5) { q.x = 1.0 - q.x; }
    float2 p = float2(q.x, q.y * uAspect);

    float r = uRadius;
    float d = dot(p - uAxis, uNormal);

    int layer = 0;
    float2 src = p;
    float theta = 0.0;

    if (d < 0.0) {
        // 纸面侧：先看折轴对面镜像点是否仍落在纸内（= 已经翻过来的背面），否则正面平放。
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

    float4 base;
    if (layer == 2) {
        float lit = 0.62 + 0.38 * cos(theta);
        float4 c = sampleA(src);
        base = float4(c.rgb * lit * crease, 1.0);
    } else if (layer == 3 || layer == 4) {
        float lit = 0.62 + 0.38 * max(-cos(theta), 0.0);
        float g = (theta - 0.72 * PI) / 0.12;
        float gloss = 0.1 * uLift * exp(-g * g);
        float4 c = backColor(src);
        base = float4(min(c.rgb * lit * crease + gloss, float3(1.0, 1.0, 1.0)), 1.0);
    } else if (layer == 1) {
        float4 c = sampleA(p);
        base = float4(c.rgb * crease, 1.0);
    } else {
        base = underlay(q, p);
    }

    // 纸背的落影（贴着折过来的那一块边缘）
    float shadow = 0.0;
    if (d < 0.0) {
        float edge = sheetDistance(p + uNormal * (PI * r - 2.0 * d));
        shadow = 0.32 * uLift * (1.0 - smoothstep(0.0, 0.035, edge));
    }
    // 卷筒在被揭开那页上投的影（随距离衰减，两端收在纸的上下边）
    if (layer == 0 && d > 0.0) {
        float2 foot = p - uNormal * d + uNormal * (0.5 * PI * r);
        float ends = 1.0 - smoothstep(0.0, 0.03, sheetDistance(foot));
        float thrown = 0.5 * uLift * ends * exp(-max(d - 0.5 * r, 0.0) / (0.02 + 0.6 * r));
        shadow = 1.0 - (1.0 - shadow) * (1.0 - thrown);
    }

    return shadeOver(base, shadow);
}

half4 main(float2 fragCoord) {
    return half4(shade(fragCoord));
}
"""
    }
}
