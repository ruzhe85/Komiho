package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sin

/**
 * Komiho (2026-10-27): 「翻书 2」的绘制层 —— 带**卷筒**的翻页。
 *
 * 与 [PageFlipView.drawFold]（平面对折，折痕是一条硬边）的区别只有一处，但正是观感的全部：
 * 纸在折线附近**绕一根圆柱卷过去**，而不是在一条线上直接镜像。
 *
 * ## 几何
 *
 * 取折线为圆柱的切线位置，半径 [CURL_RADIUS]×页宽。设某点 P 到折线的有符号距离为 d
 * （正 = 书脊侧），则：
 *
 * ```
 * d' = R·sin(d/R)    当 |d| ≤ πR/2   —— 卷筒带内，纸面被压向折线
 * d' = d             其余           —— 平放
 * ```
 *
 * 也就是「按弧长展开、按正弦投影」，R→0 时退化成原来的镜像对折。正面与背面用同一个
 * 变换，背面再对折线做一次镜像 —— 这样纸在折线两侧连续，卷筒是同一个圆。
 *
 * ## 为什么用 drawBitmapMesh 而不是自己逐像素
 *
 * 变形只沿折线法线一维发生，用 32×8 的网格近似已经看不出折线（三角形级的插值误差远小于
 * 一个像素的明暗变化）；这样不需要 GL/WebGPU，纯 Canvas，E-Ink 与低端机都能走。
 *
 * 输入是**快照**（屏幕尺寸），所以动画期间不会反映翻页中途的增强结果 —— 与「翻书」用实时
 * View 不同。这是换取形变的代价，两者各自一个档位，由用户选。
 */
internal class PageCurlView(context: Context) : View(context) {

    /** 当前页快照（屏幕尺寸）。null = 空闲。 */
    var front: Bitmap? = null

    /** 下一页快照；null 时用 [front] 的镜像兜底（观感等同「同页透印」）。 */
    var back: Bitmap? = null

    /** 0 = 整页平放，1 = 完全翻走。 */
    var progress: Float = 0f

    /** true = 书脊在左（自由边在右）。 */
    var spineOnLeft: Boolean = true

    /** 手指落点（0..1，相对高度），决定折线倾角。 */
    var grabFraction: Float = 0.5f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val shadePaint = Paint()
    private val castPaint = Paint()
    private var castGradient: LinearGradient? = null
    private var castReach = 0f

    /** 网格顶点缓冲（复用，逐帧只覆盖数值）。 */
    private var vertices = FloatArray(0)

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
        castReach = w * CAST_FRACTION
        castGradient = LinearGradient(
            0f,
            0f,
            0f,
            -castReach,
            CAST_COLOR,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        castPaint.shader = castGradient
    }

    override fun onDraw(canvas: Canvas) {
        val frontBitmap = front ?: return
        if (frontBitmap.isRecycled) return
        val p = progress.coerceIn(0f, 1f)
        if (p >= MAX_PROGRESS) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val geom = geometry(p, w, h)
        val radius = CURL_RADIUS * w

        // ⓪ 投影：卷起的纸压在被揭开那页上（先画，后面画的纸会盖住不该显示的部分）。
        drawCastShadow(canvas, geom, h, p)

        // ① 正面：书脊侧（含卷筒带）。
        drawWarped(canvas, frontBitmap, geom, radius, mirror = false, side = SIDE_SPINE)

        // ② 反面：自由侧的镜像 —— 有下一页快照就用它，否则用当前页（透印观感）。
        val backBitmap = back?.takeIf { !it.isRecycled } ?: frontBitmap
        drawWarped(canvas, backBitmap, geom, radius, mirror = true, side = SIDE_FREE)

        // ③ 卷筒明暗：折线处一条窄暗带 + 卷筒外沿一条高光，把「圆弧」读出来。
        drawRollShading(canvas, geom, radius, w, h, p)
    }

    // region 几何

    /** 折线的位置与朝向（屏幕坐标）。 */
    private class Geom(
        /** 折线上一点。 */
        val ax: Float,
        val ay: Float,
        /** 指向书脊侧的单位法线。 */
        val nx: Float,
        val ny: Float,
        /** 沿折线的单位方向。 */
        val ux: Float,
        val uy: Float,
        /** 折线倾角（度）。 */
        val deg: Float,
    )

    private fun geometry(p: Float, w: Float, h: Float): Geom {
        val freeX = if (spineOnLeft) w else 0f
        val spineX = if (spineOnLeft) 0f else w
        // 折线中点线性推进（与 ViewPager 的 0.5 落位判据一致，不能加缓动）。
        val cx = freeX + (spineX - freeX) * p
        val gy = grabFraction.coerceIn(0.12f, 0.88f) * h

        // 从抓点出发的倾角：抓上方 → 折线向一侧偏，抓中间 → 竖直。
        val env = sin(PI.toFloat() * min(p / TILT_FADE_END, 1f))
        val ahead = if (spineOnLeft) 1f else -1f
        val drift = ahead * (0.5f - grabFraction) * 2f * TILT_MAX * w * env
        val topX = cx - drift * (gy / h)
        val botX = cx + drift * ((h - gy) / h)

        val dx = botX - topX
        val len = kotlin.math.hypot(dx, h).coerceAtLeast(1f)
        val ux = dx / len
        val uy = h / len
        // 法线指向书脊侧（与 PageFlipView 同一约定）。
        val nx = if (spineOnLeft) -uy else uy
        val ny = if (spineOnLeft) ux else -ux
        return Geom(topX, 0f, nx, ny, ux, uy, atan2(uy, ux) * 180f / PI.toFloat())
    }

    /**
     * 卷筒变换：把「到折线的距离」按弧长→正弦映射压缩。
     *
     * `d' = R·sin(d/R)`（|d| ≤ πR/2），其余保持原样；R→0 时恒等，退化成平面对折。
     */
    private fun warpOffset(d: Float, radius: Float): Float {
        if (radius <= 0.5f) return d
        val limit = (PI / 2 * radius).toFloat()
        if (abs(d) >= limit) return d
        return radius * sin(d / radius)
    }

    // endregion

    // region 绘制

    private fun drawWarped(
        canvas: Canvas,
        bitmap: Bitmap,
        geom: Geom,
        radius: Float,
        mirror: Boolean,
        side: Int,
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cols = MESH_COLS
        val rows = MESH_ROWS
        val need = (cols + 1) * (rows + 1) * 2
        if (vertices.size != need) vertices = FloatArray(need)

        // 顶点位置 = 纹理坐标经卷筒变换（可选镜像）后的屏幕坐标。
        var k = 0
        for (j in 0..rows) {
            val ty = h * j / rows
            for (i in 0..cols) {
                val tx = w * i / cols
                var px = tx
                var py = ty
                val d0 = (px - geom.ax) * geom.nx + (py - geom.ay) * geom.ny
                val signed = if (mirror) -d0 else d0
                val delta = warpOffset(signed, radius) - signed
                px += delta * geom.nx
                py += delta * geom.ny
                if (mirror) {
                    // 关于折线镜像：P' = P - 2·(P-A)·n·n
                    val dist = (px - geom.ax) * geom.nx + (py - geom.ay) * geom.ny
                    px -= 2f * dist * geom.nx
                    py -= 2f * dist * geom.ny
                }
                vertices[k++] = px
                vertices[k++] = py
            }
        }

        // 网格映射的是整张位图；不属于本侧的那一半会被卷筒变换拉成条纹，
        // 所以画之前按折线把画布裁成半平面（另一侧交给另一张网格）。
        val ext = (w + h) * 2f
        val sidePath = Path()
        if (side == SIDE_SPINE) {
            sidePath.moveTo(geom.ax + geom.ux * ext, geom.ay + geom.uy * ext)
            sidePath.lineTo(geom.ax - geom.ux * ext, geom.ay - geom.uy * ext)
            sidePath.lineTo(
                geom.ax - geom.ux * ext + geom.nx * ext,
                geom.ay - geom.uy * ext + geom.ny * ext,
            )
            sidePath.lineTo(
                geom.ax + geom.ux * ext + geom.nx * ext,
                geom.ay + geom.uy * ext + geom.ny * ext,
            )
        } else {
            sidePath.moveTo(geom.ax + geom.ux * ext, geom.ay + geom.uy * ext)
            sidePath.lineTo(geom.ax - geom.ux * ext, geom.ay - geom.uy * ext)
            sidePath.lineTo(
                geom.ax - geom.ux * ext - geom.nx * ext,
                geom.ay - geom.uy * ext - geom.ny * ext,
            )
            sidePath.lineTo(
                geom.ax + geom.ux * ext - geom.nx * ext,
                geom.ay + geom.uy * ext - geom.ny * ext,
            )
        }
        sidePath.close()

        val save = canvas.save()
        canvas.clipPath(sidePath)
        canvas.drawBitmapMesh(bitmap, cols, rows, vertices, 0, null, 0, bitmapPaint)
        canvas.restoreToCount(save)
    }

    private fun drawCastShadow(canvas: Canvas, geom: Geom, h: Float, p: Float) {
        if (castReach <= 0f) return
        val fade = min(1f, p / CAST_FADE_IN) * min(1f, (1f - p) / CAST_FADE_OUT)
        if (fade <= 0.01f) return
        castPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
        val sc = canvas.save()
        canvas.translate(geom.ax, geom.ay)
        canvas.rotate(geom.deg)
        if (!spineOnLeft) canvas.rotate(180f)
        canvas.drawRect(-width.toFloat() * 2f, -castReach, width.toFloat() * 2f, 0f, castPaint)
        canvas.restoreToCount(sc)
    }

    private fun drawRollShading(canvas: Canvas, geom: Geom, radius: Float, w: Float, h: Float, p: Float) {
        if (radius <= 0.5f) return
        val fade = min(1f, p / SHADE_FADE_IN) * min(1f, (1f - p) / SHADE_FADE_OUT)
        if (fade <= 0.01f) return
        shadePaint.alpha = (255 * fade).toInt().coerceIn(0, 255)

        // 折线两侧各一条渐变，本地系里 x 轴即折线方向、y 轴为法线方向。
        val sc = canvas.save()
        canvas.translate(geom.ax, geom.ay)
        canvas.rotate(geom.deg)
        val reach = radius * 2.2f
        val ext = (w + h) * 2f
        val gradient = LinearGradient(
            -reach,
            0f,
            reach,
            0f,
            intArrayOf(Color.TRANSPARENT, SHADE_COLOR, SHADE_CORE, SHADE_COLOR, Color.TRANSPARENT),
            floatArrayOf(0f, 0.30f, 0.5f, 0.70f, 1f),
            Shader.TileMode.CLAMP,
        )
        shadePaint.shader = gradient
        canvas.drawRect(-reach, -ext, reach, ext, shadePaint)
        shadePaint.shader = null
        canvas.restoreToCount(sc)
    }

    // endregion

    private companion object {
        /** 卷筒半径（占页宽）—— 与 `TransitionCurl.RADIUS` 同量级；太小像折纸，太大像气球。 */
        const val CURL_RADIUS = 0.085f

        /** 网格密度：法线方向 32 段足够（形变只沿它发生），折线方向 8 段。 */
        const val MESH_COLS = 32
        const val MESH_ROWS = 8

        const val TILT_MAX = 0.35f
        const val TILT_FADE_END = 0.75f

        const val CAST_FRACTION = 0.10f
        const val CAST_COLOR = 0x4D000000
        const val CAST_FADE_IN = 0.06f
        const val CAST_FADE_OUT = 0.18f

        const val SHADE_COLOR = 0x33000000
        const val SHADE_CORE = 0x66000000
        const val SHADE_FADE_IN = 0.04f
        const val SHADE_FADE_OUT = 0.12f

        const val MAX_PROGRESS = 0.999f

        const val SIDE_SPINE = 0
        const val SIDE_FREE = 1
    }
}
