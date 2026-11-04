package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.diagnostic.DiagLog
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.pdf.PdfPasswordException
import eu.kanade.tachiyomi.util.pdf.PdfPasswordHolder
import eu.kanade.tachiyomi.util.pdf.PdfParser
import eu.kanade.tachiyomi.util.pdf.PdfRenderFallback
import eu.kanade.tachiyomi.util.pdf.PdfiumDoc
import eu.kanade.tachiyomi.util.pdf.remotePdfSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import mihon.core.common.archive.RandomAccessSource
import tachiyomi.core.common.util.lang.withIOContext

/**
 * 本地 / 远程 PDF 页加载器。
 *
 * Komiho (2026-11-04): 引擎换成**自带 PDFium**（[PdfiumDoc]，`io.legere:pdfiumandroid`）。
 *
 * 之前用系统的 `android.graphics.pdf.PdfRenderer`，它对畸形 PDF 一律抛 `SecurityException`，
 * 我们只能当成「需要密码」反复弹框，页数也会对不上。现在：
 *  - **页数 / 能否打开**：只信 PDFium。打得开就一定有正确页数；打不开才分「要密码 / 文件损坏」。
 *  - **直通**：仍由自写的 [PdfParser] 负责（取 `/DCTDecode` 原始字节）。它只是**可选加速**：
 *    解析成功且页数和 PDFium 一致才逐页尝试，任何一页不成即就地渲染，绝不影响打开。
 *  - **渲染**：PDFium 直接写 ARGB_8888 位图；图像增强开着时走字节路径（AI 正常），
 *    关着时直接给位图（省掉「编码成 WebP/JPEG → 再解码」的往返）。
 *
 * 健壮性（修复「打开 PDF 退回文件列表」）：
 *  - 不依赖 [UniFile.filePath]：Android 11+ 本地源常走 SAF，filePath 为 null 或直开 EACCES；
 *    此时整本经 [UniFile.openInputStream] 落到缓存再当本地文件解析。
 *  - 只有 PDFium 打不开才会报错（加密 → 弹密码框；损坏 → 错误页）。
 *
 * 来源：
 *  - 本地：`PdfPageLoader(file, context)`。
 *  - 远程（WebDAV/SMB）：`PdfPageLoader(remoteUrl, context)` —— 首次取页时整本落本地缓存。
 */
internal class PdfPageLoader private constructor(
    private val context: Context,
    private val localFile: UniFile?,
    private val remoteUrl: String?,
) : PageLoader() {

    private lateinit var path: String

    /** PDFium 文档句柄（页数与渲染的唯一来源）。 */
    private var doc: PdfiumDoc? = null

    /** 本次打开实际使用的密码（空串表示「权限型加密、无用户密码」）。 */
    private var currentPassword: String? = null

    /**
     * 直通用的解析器：**惰性且可选**。
     *
     * 只有「解析成功 + 页数与 PDFium 一致」才启用；否则返回 null，全部走渲染。
     * 这样即使我们的解析器又踩到某个畸形结构，也只是少一层加速，不影响打开。
     */
    private var passthrough: PdfParser? = null
    private var passthroughResolved = false

    /** 取直通解析器（首次调用时才解析；失败返回 null，之后不再重试）。 */
    private fun parserOrNull(): PdfParser? {
        if (!passthroughResolved) {
            passthroughResolved = true
            passthrough = openPassthroughParser()
        }
        return passthrough
    }

    /** 本地 PDF：传 UniFile。 */
    constructor(file: UniFile, context: Context) : this(context, file, null)

    /** 远程 PDF（WebDAV/SMB）：传章节 url，首次取页时整本落本地缓存再复用本地解析。 */
    constructor(remoteUrl: String, context: Context) : this(context, null, remoteUrl)

    override var isLocal: Boolean = remoteUrl == null

    init {
        if (localFile != null && remoteUrl != null) {
            error("PdfPageLoader 不能同时持本地文件与远程 url")
        }
    }

    override suspend fun getPages(): List<ReaderPage> = withIOContext {
        path = resolvePath()
        DiagLog.d(TAG, "PDF open: path=$path impl=$IMPL_MARK")

        val openStartedAt = android.os.SystemClock.elapsedRealtime()
        val opened = openDocument()
        val pageCount = opened.pageCount
        if (pageCount <= 0) {
            // 打开了却读不出页数：关掉句柄再报错，别留 fd。
            runCatching { opened.close() }
            throw Exception("PDF 无法解析（可能已损坏或已加密）")
        }
        doc = opened

        val pages = List(pageCount) { i ->
            ReaderPage(i).apply {
                stream = { openStream(i) }
                status = Page.State.Ready
                preRenderedBitmap = preRenderedProvider(i)
            }
        }

        DiagLog.d(
            TAG,
            "PDF open done: pages=$pageCount 耗时=${android.os.SystemClock.elapsedRealtime() - openStartedAt}ms",
        )
        pages
    }

    /**
     * 用 PDFium 打开文档。
     *
     * 打开成功即视为「可读」——不再有「PDFium 说加密但我们不信」这种拉扯：
     * PDFium 的 IOException 里带 `password` 才是真要密码，其它情况一律按文件损坏处理。
     */
    private fun openDocument(): PdfiumDoc {
        val password = currentPassword ?: PdfPasswordHolder.current
        val result = runCatching { PdfiumDoc.open(path, password) }
        result.getOrNull()?.let {
            currentPassword = password
            return it
        }

        val error = result.exceptionOrNull()
        val passwordIssue = (error as? IOException)
            ?.message
            ?.contains("password", ignoreCase = true) == true
        if (!passwordIssue) {
            // 带 throwable 的重载是 e()，且它要求非空 —— 这里 error 可空，分开写。
            val message = "PDF open failed: ${error?.message}"
            if (error != null) DiagLog.e(TAG, message, error) else DiagLog.w(TAG, message)
            throw Exception("PDF 打开失败：${error?.message ?: "未知错误"}")
        }
        // 需要密码：没给过 → 弹框；给过还失败 → 密码错误。
        throw if (password == null) {
            DiagLog.w(TAG, "PDF requires password")
            PdfPasswordException()
        } else {
            DiagLog.w(TAG, "PDF password incorrect")
            PdfPasswordException(wrongPassword = true)
        }
    }

    /** 尝试启用直通解析器；不可用返回 null（调用方全部走渲染）。 */
    private fun openPassthroughParser(): PdfParser? {
        val document = doc ?: return null
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val parsed = runCatching { PdfParser(path) }.getOrNull() ?: return null
        val usable = parsed.parseOk && parsed.pageCount == document.pageCount
        DiagLog.d(
            TAG,
            "PDF passthrough parser: ok=${parsed.parseOk} pages=${parsed.pageCount}/${document.pageCount} " +
                "usable=$usable 耗时=${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
        )
        if (!usable) {
            runCatching { parsed.close() }
            return null
        }
        return parsed
    }

    /**
     * 页面「是否已经有位图」。
     *
     * **能直通的页不提供位图**：这类页交回 `stream()`（内嵌 JPEG 原字节），继续走 Coil ——
     * 原图画质、AI 增强、双页拆分/旋转全都和以前一样，一点不打折。
     *
     * 只有「本来就要渲染」的页（无内嵌 JPEG / 解析器不可用）才给位图，省掉
     * 「渲染 → 编码成 WebP/JPEG → 再解码」的往返；增强与 E-Ink 由
     * [eu.kanade.tachiyomi.util.PdfRenderPostProcess] 直接在这张位图上做。
     *
     * 注意：这里返回的是**惰性 lambda**，判据在真正准备这一页时才求值，
     * 不会因为要判断直通而拖慢打开（解析器也只是首次用到时才建）。
     */
    private fun preRenderedProvider(index: Int): () -> Bitmap? = {
        if (canPassthrough(index)) null else renderPage(index)
    }

    /** 这一页能否走内嵌 JPEG 直通（判据与 [openStream] 完全一致，只读流头 3 字节）。 */
    private fun canPassthrough(index: Int): Boolean {
        val parser = parserOrNull() ?: return false
        return runCatching {
            val img = parser.bestImageForPage(index) ?: return@runCatching false
            if (img.filter != "DCTDecode") return@runCatching false
            val head = parser.rawStreamHead(img.objNum, 3) ?: return@runCatching false
            head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        }.getOrDefault(false)
    }

    /** 按与阅读器一致的尺寸策略渲染一页（竖长页按宽度、普通页按长边）。 */
    private fun renderPage(index: Int): Bitmap? {
        val document = doc ?: return null
        val (pw, ph) = document.sizePoints(index) ?: (595 to 842)
        val (tw, th) = PdfRenderFallback.displaySize(pw, ph)
        return document.renderPage(index, tw, th)
    }

    private fun openStream(index: Int): InputStream {
        check(!isRecycled) { "PDF 页面读取时加载器已被回收——重载即恢复" }

        // ① 直通：内嵌 JPEG 原字节（解一次码，和读 zip 等价）。
        val parser = parserOrNull()
        if (parser != null) {
            val raw = runCatching {
                val img = parser.bestImageForPage(index)
                if (img != null && img.filter == "DCTDecode") parser.rawStreamBytes(img.objNum) else null
            }.getOrNull()
            if (raw != null && raw.size > 2 &&
                raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()
            ) {
                DiagLog.d(TAG, "page ${index + 1}: DCT passthrough (${raw.size}B)")
                return ByteArrayInputStream(raw)
            }
        }

        // ② 渲染兜底：位图直通拿不到（渲染失败/被回收）时才走字节路径，保证不白屏。
        val bitmap = renderPage(index)
            ?: throw IllegalStateException("PDF 第 ${index + 1} 页无法渲染（可能已损坏）")
        val bytes = PdfRenderFallback.encodeForDisplay(bitmap)
        return ByteArrayInputStream(bytes)
    }

    private suspend fun resolvePath(): String = withIOContext {
        if (localFile != null) {
            // 优先用真实文件路径（最快）；SAF / 无 filePath / 直开会 EACCES 时整本落缓存。
            val fp = localFile.filePath
            if (fp != null && runCatching { java.io.RandomAccessFile(fp, "r").use { it.length() } }.isSuccess) {
                return@withIOContext fp
            }
            val cache = localCacheFile(localFile.uri.toString())
            if (!cache.exists() || cache.length() != localFile.length()) {
                localFile.openInputStream().use { input ->
                    FileOutputStream(cache).use { input.copyTo(it) }
                }
            }
            return@withIOContext cache.absolutePath
        }
        // 远程：整本下载到 cache 后当本地文件处理（渲染兜底也需本地文件）。
        val source = remotePdfSource(remoteUrl!!, context)
        try {
            val cacheFile = remoteCacheFile(remoteUrl!!)
            if (!cacheFile.exists() || cacheFile.length() != source.size) {
                downloadTo(source, cacheFile)
            }
            cacheFile.absolutePath
        } finally {
            source.close()
        }
    }

    private fun localCacheFile(key: String): File {
        val dir = File(context.cacheDir, "remote_pdf")
        dir.mkdirs()
        val name = "pdf_local_${key.hashCode().toLong().and(0xffffffffL).toString(16)}"
        return File(dir, name)
    }

    private fun remoteCacheFile(url: String): File {
        val dir = File(context.cacheDir, "remote_pdf")
        dir.mkdirs()
        val name = "pdf_${url.hashCode().toLong().and(0xffffffffL).toString(16)}"
        return File(dir, name)
    }

    private fun downloadTo(source: RandomAccessSource, out: File) {
        val tmp = File(out.parentFile, out.name + ".part")
        FileOutputStream(tmp).use { fos ->
            val size = source.size
            var offset = 0L
            // SY --> Komiho: 远程整本下载带进度（size 已知时上报百分比，否则仅保持转圈）。
            if (size > 0L) progressReporter?.invoke(0f)
            while (offset < size) {
                val len = minOf(1 shl 20, (size - offset).toInt())
                val buf = source.read(offset, len)
                if (buf.isEmpty()) break
                fos.write(buf)
                offset += buf.size
                if (size > 0L) progressReporter?.invoke((offset.toFloat() / size).coerceIn(0f, 1f))
            }
            if (size > 0L) progressReporter?.invoke(1f)
        }
        if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        // 注意：这里不能碰 parserOrNull() —— 那会在回收时触发一次整本解析。只关已存在的。
        runCatching { passthrough?.close() }
        passthrough = null
        runCatching { doc?.close() }
        doc = null
        super.recycle()
    }

    companion object {
        private const val TAG = "KomihoPdfLoader"

        /** 实现标记：出现在 `PDF open` 日志里，用来判断装到的包是否包含本版逻辑。 */
        private const val IMPL_MARK = "20261105-pdfium"
    }
}
