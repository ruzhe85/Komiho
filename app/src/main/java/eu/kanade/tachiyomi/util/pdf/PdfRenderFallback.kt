package eu.kanade.tachiyomi.util.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.diagnostic.DiagLog
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * 系统 PdfRenderer 兜底：当某页无法走「内嵌图字节直通」（CCITT/JPX/Flate/矢量/无内嵌图）
 * 时，用系统 PDFium 渲染成 Bitmap 再编码为 PNG 字节流交给既有解码+增强管线。
 *
 * 加密 PDF：Android 15（API 35）起 PdfRenderer 支持 [android.graphics.pdf.LoadParams]
 * 带密码加载；[password] 非 null 且系统版本足够时使用密码构造，否则走无密码构造。
 * 密码错误时系统抛 SecurityException，由调用方（[PdfPageLoader]）转成 [PdfPasswordException]。
 */
object PdfRenderFallback {

    /** 阅读器兜底渲染的目标长边：低分辨率矢量/文本页（约 595×842 @72DPI）上采样到此值保证清晰。 */
    private const val TARGET_LONG_SIDE = 2000

    /** 长条页判定（高宽比）：与解码器 [eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder] 的条漫口径一致。 */
    private const val TALL_ASPECT_RATIO = 2.5f

    /** 长条页渲染宽度目标：宽度是条漫页的显示关键维度（1440 覆盖主流屏宽且不放大过度）。 */
    private const val TARGET_TALL_WIDTH = 1440

    /** 长条页输出像素上限（≈96MB ARGB_8888）：超预算才按面积回缩，避免单页 OOM。 */
    private const val MAX_TALL_PIXELS = 24_000_000.0

    /** 长条页输出长边上限：PDFium/位图分配对大尺寸有上限，极端窄长页不能无限拉高。 */
    private const val MAX_TALL_LONG_SIDE = 24_000

    /** 渲染指定页为 Bitmap：
     *  - 先填白底，避免矢量/文本 PDF 无色块区域透出（原先透明 → 底色消失）。
     *  - 按 [maxLongSide] 等比缩放：小页面（低 DPI 矢量/文本）上采样到该值保证清晰；
     *    大页面下采样到该值控制内存。封面缩略可传更小值（见 LocalCoverFetcher）。
     *  - [password] 非空时（API≥35）以密码加载加密 PDF。
     */
    fun renderPageBitmap(
        path: String,
        pageIndex: Int,
        maxLongSide: Int = TARGET_LONG_SIDE,
        password: String? = null,
    ): Bitmap? {
        return ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            renderBitmap(pfd, pageIndex, maxLongSide, password, tallWidthDriven = false)
        }
    }

    /**
     * Komiho (2026-10-01): PFD 重载 —— SAF 模式的本地 PDF 拿不到真实文件路径
     * （`UniFile.filePath` 为 null），封面/页渲染改用 contentResolver 直开的描述符。
     */
    fun renderPageBitmap(
        pfd: ParcelFileDescriptor,
        pageIndex: Int,
        maxLongSide: Int = TARGET_LONG_SIDE,
        password: String? = null,
    ): Bitmap? = renderBitmap(pfd, pageIndex, maxLongSide, password, tallWidthDriven = false)

    private fun renderBitmap(
        pfd: ParcelFileDescriptor,
        pageIndex: Int,
        maxLongSide: Int,
        password: String?,
        tallWidthDriven: Boolean,
    ): Bitmap? {
        val renderer = openRenderer(pfd, password)
        try {
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
            val page = renderer.openPage(pageIndex)
            val (tw, th) = fit(page.width, page.height, maxLongSide, tallWidthDriven)
            DiagLog.d(TAG, "render page=$pageIndex page=${page.width}x${page.height} -> ${tw}x$th")
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            return bmp
        } finally {
            renderer.close()
        }
    }

    /*
     * Komiho (2026-11-03): 复用 PdfRenderer —— 每取一页都 new 一个 PdfRenderer 等于让 PDFium 把
     * 整份文档重新解析一遍（扫描本每页多花几十到上百毫秒，翻页明显发涩，和「秒开」的阅读器
     * 差距就在这）。这里按「路径 + 密码」缓存一个实例，只在换文件/换密码时重建；
     * 由 [PdfPageLoader.recycle] 调 [releaseCachedRenderer] 释放。
     *
     * PdfRenderer 非线程安全：整段渲染都在同一把锁里，天然把并发翻页的渲染排队。
     */
    private val rendererLock = Any()
    private var cachedPath: String? = null
    private var cachedPassword: String? = null
    private var cachedPfd: ParcelFileDescriptor? = null
    private var cachedRenderer: PdfRenderer? = null

    /** 释放缓存里的渲染器（阅读器回收章节时调用；重复调用安全）。 */
    fun releaseCachedRenderer() = synchronized(rendererLock) { closeCachedLocked() }

    private fun closeCachedLocked() {
        runCatching { cachedRenderer?.close() }
        runCatching { cachedPfd?.close() }
        cachedRenderer = null
        cachedPfd = null
        cachedPath = null
        cachedPassword = null
    }

    private fun <T> withCachedRenderer(path: String, password: String?, block: (PdfRenderer) -> T?): T? =
        synchronized(rendererLock) {
            if (cachedRenderer == null || cachedPath != path || cachedPassword != password) {
                closeCachedLocked()
                val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = try {
                    openRenderer(pfd, password)
                } catch (e: Throwable) {
                    runCatching { pfd.close() }
                    throw e
                }
                cachedPfd = pfd
                cachedRenderer = renderer
                cachedPath = path
                cachedPassword = password
            }
            val renderer = cachedRenderer ?: return null
            block(renderer)
        }

    /** 渲染指定页为图片字节流（供既有解码+增强管线消费）。 */
    fun renderPage(path: String, pageIndex: Int, password: String? = null): ByteArray? {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val bytes = withCachedRenderer(path, password) { renderer ->
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withCachedRenderer null
            val page = renderer.openPage(pageIndex)
            try {
                val (tw, th) = fit(page.width, page.height, TARGET_LONG_SIDE, tallWidthDriven = true)
                val renderStart = android.os.SystemClock.elapsedRealtime()
                val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                try {
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val renderMs = android.os.SystemClock.elapsedRealtime() - renderStart
                    val encodeStart = android.os.SystemClock.elapsedRealtime()
                    val encoded = encodePage(bmp)
                    eu.kanade.tachiyomi.diagnostic.DiagLog.d(
                        TAG,
                        "render page=$pageIndex ${tw}x$th page=${page.width}x${page.height} " +
                            "render=${renderMs}ms encode=${android.os.SystemClock.elapsedRealtime() - encodeStart}ms " +
                            "bytes=${encoded.size} total=${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
                    )
                    encoded
                } finally {
                    bmp.recycle()
                }
            } finally {
                page.close()
            }
        }
        return bytes
    }

    /**
     * 页面像素 → 图片字节。
     *
     * API 30+ 用 **WebP 无损** 而不是 PNG：两者都是无损，但 Skia 的 WebP 编码器比 libpng 快
     * 一大截，而这一层编码正是「渲染兜底页」除 PDFium 之外的第二个大头（3MP 扫描页 PNG
     * 动辄几百毫秒/页）。低版本没有 WebP 无损常量，继续 PNG。
     */
    private fun encodePage(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(1 shl 20)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
        } else {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return out.toByteArray()
    }

    /** 读取 PDF 页数（渲染兜底模式用）。
     *  - [password] 为空：失败静默返回 0（供未加密探测）。
     *  - [password] 非空：失败（含密码错误 SecurityException）重新抛出，由上层转 [PdfPasswordException]。
     */
    fun getPageCount(path: String, password: String? = null): Int {
        return try {
            // Komiho: 走同一个缓存实例 —— 打开一本 PDF 会连着探加密、读页数，再逐页渲染；
            // 以前这三次各 new 一个 PdfRenderer（= PDFium 整份解析三遍），是「打开慢」的一半。
            withCachedRenderer(path, password) { it.pageCount } ?: 0
        } catch (e: Exception) {
            if (password != null) throw e
            DiagLog.w(TAG, "getPageCount failed: ${e.message}")
            0
        }
    }

    /** Komiho (2026-10-01): PFD 重载 —— SAF 模式拿不到真实路径时由 contentResolver 直开。 */
    fun getPageCount(pfd: ParcelFileDescriptor, password: String? = null): Int {
        return try {
            val renderer = openRenderer(pfd, password)
            try {
                renderer.pageCount
            } finally {
                renderer.close()
            }
        } catch (e: Exception) {
            if (password != null) throw e
            DiagLog.w(TAG, "getPageCount failed: ${e.message}")
            0
        }
    }

    /**
     * 运行时加密探测：以无密码方式构造系统 PdfRenderer。
     * 加密 PDF 在缺少密码时必然抛 SecurityException，借此可靠判定加密，
     * 作为手写解析器 /Encrypt 探测的兜底（解析器偶发漏判时仍能弹出密码框，而非误报「无法解析」）。
     */
    fun isEncryptedPdf(path: String): Boolean {
        return try {
            // Komiho: 复用缓存实例。探测成功时顺手留下这个 renderer —— 紧接着的 getPageCount /
            // 首页渲染都能直接用它，不必再让 PDFium 解析一遍。
            withCachedRenderer(path, null) { true } ?: false
        } catch (e: SecurityException) {
            // Komiho: 把 PDFium 的原话记下来 —— 「明明没密码却要密码」时就看这一行：
            // 真正的加密文档报 password required/incorrect，结构损坏的文档报的往往是别的东西。
            DiagLog.w(TAG, "PDFium requires password: ${e.message}")
            true
        }
    }

    /**
     * 按是否带密码与系统版本选择 PdfRenderer 构造方式。
     * 仅在 API≥35 且 [password] 非空时走带密码构造；否则回退无密码构造
     * （加密文档会抛 SecurityException，由上层识别）。
     */
    private fun openRenderer(pfd: ParcelFileDescriptor, password: String?): PdfRenderer {
        if (password != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // 完全限定名避免低版本设备加载 LoadParams 类（运行时仅在此分支执行到）。
            val params = android.graphics.pdf.LoadParams.Builder().setPassword(password).build()
            return PdfRenderer(pfd, params)
        }
        return PdfRenderer(pfd)
    }

    private const val TAG = "KomihoPdfRender"

    /** 按目标长边等比缩放：小则上采样（矢量/文本更清晰），大则下采样（控制内存/封面缩略）。 */
    private fun fit(w: Int, h: Int, target: Int, tallWidthDriven: Boolean = false): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return target to target
        // 长条页（如 1440×15050 的条漫单页）：按长边等比缩会把宽度一起压到 2000/10.45 ≈ 191px，
        // 交回阅读器再按屏宽放大就是一片糊。这类页宽度才是显示关键维度（与解码器 isTallStrip 同口径），
        // 改按宽度定尺度；只有总面积超过预算时才按面积回缩。
        if (tallWidthDriven && h.toFloat() / w.toFloat() > TALL_ASPECT_RATIO) {
            var scale = TARGET_TALL_WIDTH.toFloat() / w.toFloat()
            scale = minOf(scale, MAX_TALL_LONG_SIDE.toFloat() / maxOf(w, h).toFloat())
            val pixels = w.toDouble() * h.toDouble() * scale * scale
            if (pixels > MAX_TALL_PIXELS) {
                scale *= sqrt(MAX_TALL_PIXELS / pixels).toFloat()
            }
            return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
        }
        val long = maxOf(w, h)
        val scale = target.toFloat() / long
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }
}
