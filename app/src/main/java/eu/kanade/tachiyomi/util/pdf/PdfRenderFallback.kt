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
 * 时，用系统 PDFium 栅格化。
 *
 * 输出有两种形态：
 * - [renderBitmapForDisplay]：直接给位图，显示层不再编码/解码（阅读器主路径，最省）；
 * - [renderPage]：编码成 WebP 无损（API 30+）/ JPEG（≥10MP 大页）/ PNG（低版本）字节流，
 *   供「另存图片」或仍有字节需求的调用方使用。
 *
 * 加密 PDF：**阅读器已改走自带 PDFium**（[PdfiumDoc]，minSdk 起都支持密码），本类目前只剩
 * 「封面缩略图」与「页数探测」两条路径还在用系统 PdfRenderer —— 那条要到 Android 15
 * （API 35）的 [android.graphics.pdf.LoadParams] 才支持带密码。所以低版本上加密 PDF 是
 * 「正文可读、封面为空」，与阅读无关。密码错误时系统抛 SecurityException，由调用方转成
 * [PdfPasswordException]。
 */
object PdfRenderFallback {

    /** 阅读器兜底渲染的目标长边：低分辨率矢量/文本页（约 595×842 @72DPI）上采样到此值保证清晰。 */
    private const val TARGET_LONG_SIDE = 2000

    /** 长条页判定（高宽比）：与解码器 [eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder] 的条漫口径一致。 */
    private const val TALL_ASPECT_RATIO = 2.5f

    /** 长条页渲染宽度目标：宽度是条漫页的显示关键维度（1440 覆盖主流屏宽且不放大过度）。 */
    private const val TARGET_TALL_WIDTH = 1440

    /** 长条页输出像素的**硬**上限（24MP ≈ 96MB ARGB）；实际预算按可用堆动态给，见 [tallPixelBudget]。 */
    private const val MAX_TALL_PIXELS = 24_000_000.0

    /** 长条页输出像素的**下限**：再省也不能低于 6MP，否则 1440 宽的长条会被压到 640 宽。 */
    private const val MIN_TALL_PIXELS = 6_000_000.0

    /**
     * 长条页的输出预算（像素）。
     *
     * Komiho (2026-11-04): 不再固定 12MP 一刀切（那是拿画质换稳定），改成**按当前可用堆**算：
     * 只占用可用堆的 1/8（4 字节/像素），上限 24MP。堆宽裕时 1440×15050 原尺寸渲染，
     * 堆紧张时自动降档；真分配失败还有 [renderWithOomRetry] 减半兜底。
     */
    private fun tallPixelBudget(): Double {
        val freeBytes = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }
        val byHeap = (freeBytes / 8).toDouble()
        return byHeap.coerceIn(MIN_TALL_PIXELS, MAX_TALL_PIXELS)
    }

    /** 长条页输出长边上限：PDFium/位图分配对大尺寸有上限，极端窄长页不能无限拉高。 */
    private const val MAX_TALL_LONG_SIDE = 24_000

    /** JPEG 质量：PDF 渲染页是栅格化的二次产物，q95 肉眼无可辨差异。 */
    private const val JPEG_QUALITY = 95

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
            try {
                val (tw, th) = fit(page.width, page.height, maxLongSide, tallWidthDriven)
                DiagLog.d(TAG, "render page=$pageIndex page=${page.width}x${page.height} -> ${tw}x$th")
                return renderWithOomRetry(page, tw, th)
            } finally {
                page.close()
            }
        } finally {
            renderer.close()
        }
    }

    /**
     * 分配 + 渲染，OOM 时把尺寸减半重试一次。
     *
     * 单页位图是 4 字节/像素：1522×2000 只有 12MB，但长条页（1440×15050）是 86MB；
     * 再叠加预取与解析器持有的整份 PDF 字节，堆很容易打满。宁可这一页糊一半，
     * 也不能让进程被系统干掉（用户看到的就是「闪退 / OOM」）。
     */
    private fun renderWithOomRetry(page: PdfRenderer.Page, targetW: Int, targetH: Int): Bitmap? {
        var w = targetW.coerceAtLeast(1)
        var h = targetH.coerceAtLeast(1)
        repeat(2) { attempt ->
            var bmp: Bitmap? = null
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                DiagLog.d(TAG, "render draw -> ${w}x$h (try=${attempt + 1})")
                return bmp
            } catch (e: OutOfMemoryError) {
                // 半成品位图要立刻还回去，否则重试时堆更紧，必然再次失败。
                runCatching { if (bmp != null && !bmp.isRecycled) bmp.recycle() }
                DiagLog.w(TAG, "render OOM at ${w}x$h, retry half size")
                w = (w / 2).coerceAtLeast(1)
                h = (h / 2).coerceAtLeast(1)
            }
        }
        return null
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

    /**
     * 按阅读器的显示策略给出一页的目标像素尺寸：竖长页按宽度、普通页按长边。
     *
     * 供自带 PDFium（[PdfiumDoc]/`PdfPageLoader`）复用同一套尺寸口径，避免两套引擎下
     * 长条页一会儿 1440 宽、一会儿 191 宽。
     */
    fun displaySize(pageWidth: Int, pageHeight: Int): Pair<Int, Int> =
        fit(pageWidth, pageHeight, TARGET_LONG_SIDE, tallWidthDriven = true)

    /** 位图 → 显示用字节流（编码后立即回收位图）。仅「需要字节」的路径使用（如增强开启时）。 */
    fun encodeForDisplay(bitmap: Bitmap): ByteArray = try {
        encodePage(bitmap)
    } finally {
        runCatching { bitmap.recycle() }
    }

    /**
     * Komiho (2026-11-04): 直接渲染成位图交给显示层（不编码、不解码）。
     *
     * 与 [renderPage] 完全相同的尺寸策略（竖长页按宽度 / 普通页按长边）与白底，只是省掉
     * 「位图 → WebP/JPEG 字节」这一步 —— 实测每页 250–470ms，是渲染路线里最大的一块；
     * 省下的字节随后也不用再解码一次（~40ms/页）。整段仍在缓存渲染器的锁内。
     */
    fun renderBitmapForDisplay(path: String, pageIndex: Int, password: String? = null): Bitmap? =
        withCachedRenderer(path, password) { renderer ->
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withCachedRenderer null
            val page = renderer.openPage(pageIndex)
            try {
                val (tw, th) = fit(page.width, page.height, TARGET_LONG_SIDE, tallWidthDriven = true)
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val bmp = renderWithOomRetry(page, tw, th)
                if (bmp != null) {
                    eu.kanade.tachiyomi.diagnostic.DiagLog.d(
                        TAG,
                        "render(bitmap) page=$pageIndex ${bmp.width}x${bmp.height} " +
                            "page=${page.width}x${page.height} " +
                            "耗时=${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
                    )
                }
                bmp
            } finally {
                page.close()
            }
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
                val bmp = renderWithOomRetry(page, tw, th)
                    ?: return@withCachedRenderer null
                try {
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
     * 动辄几百毫秒/页）。
     *
     * Komiho (2026-11-06): 统一改成 **JPEG q95**，不再按像素量分流。
     *  - 这类页是 PDFium 栅格化的二次产物，q95 肉眼无可辨差异；
     *  - libjpeg-turbo 有 SIMD 优化：3MP 页约 40~80ms，而无损 WebP 要 250~470ms
     *    （21MP 长条页实测 4.9s），有损 WebP q95 也要 100~250ms；
     *  - 下游还要再解码一次（喂 Coil / 双页合并），JPEG 解码同样更快；
     *  - 渲染页一律白底不透明，不受 JPEG 无 alpha 的限制。
     */
    private fun encodePage(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(1 shl 20)
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
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
    /**
     * Komiho (2026-11-04): 用「空密码 + [LoadParams]」再试一次能否打开。
     *
     * 现场：xref 损坏的 PDF 用裸 [PdfRenderer] 打开会抛 SecurityException（Android 把它归成
     * 「需要密码」），但改走 Android 15 的 LoadParams 路径就能正常打开 —— 这类文件其实一个密码
     * 都没有，不该让用户去猜。返回 true 表示「可读，且应走带密码参数的那条构造」。
     */
    fun isReadableWithEmptyPassword(path: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        return try {
            withCachedRenderer(path, "") { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

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
            val budget = tallPixelBudget()
            val pixels = w.toDouble() * h.toDouble() * scale * scale
            if (pixels > budget) {
                scale *= sqrt(budget / pixels).toFloat()
            }
            return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
        }
        val long = maxOf(w, h)
        val scale = target.toFloat() / long
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }
}
