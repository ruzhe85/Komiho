package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.util.Log
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.pdf.PdfPasswordException
import eu.kanade.tachiyomi.util.pdf.PdfPasswordHolder
import eu.kanade.tachiyomi.util.pdf.PdfParser
import eu.kanade.tachiyomi.util.pdf.PdfRenderFallback
import eu.kanade.tachiyomi.util.pdf.remotePdfSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.min
import android.os.Build
import mihon.core.common.archive.RandomAccessSource
import tachiyomi.core.common.util.lang.withIOContext

/**
 * 本地 / 远程 PDF 页加载器（方案 A：自写提取 + 系统渲染兜底）。
 *
 * 每页交付「编码字节流」给既有解码+增强管线：
 *  - DCTDecode(JPEG) 内嵌图：直通原始字节，Lanczos3 / AI / NPU 增强无损生效（网点细节保住）。
 *  - 其他（CCITT/JPX/Flate/矢量/无内嵌图/解析失败）：[PdfRenderFallback] 渲染成 PNG 字节流。
 *
 * 健壮性（修复「打开 PDF 退回文件列表」）：
 *  - 不依赖 [UniFile.filePath]：Android 11+ 本地源常走 SAF，filePath 为 null 或直开 EACCES；
 *    此时整本经 [UniFile.openInputStream] 落到缓存再当本地文件解析。
 *  - 自写解析抛异常 / 0 页 / 加密识别失败 → 自动降级为「整本系统渲染」，保证任何 PDF 都能打开。
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
    private var parser: PdfParser? = null
    private var renderOnly = false
    /** 本次打开需带密码渲染时使用的密码（加密 PDF 走系统渲染兜底）。 */
    private var currentPassword: String? = null

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
        Log.d(TAG, "PDF open: path=$path")

        // 超大 PDF（>200MB）不整文件载入内存解析（避免 OOM），直接走系统渲染兜底；
        // 仍用运行时探测判定加密，保证加密 PDF 弹密码框。
        val fileSize = File(path).length()
        val probe = if (fileSize <= MAX_PARSER_FILE_BYTES) runCatching { PdfParser(path) }.getOrNull() else null
        // 探测加密状态（不消耗密码尝试）。
        // 解析器偶发漏判加密时，用系统渲染器运行时兜底探测：无密码构造加密 PDF 必抛 SecurityException。
        var encrypted = probe?.parseOk == true && probe.isEncrypted()
        if (!encrypted) {
            encrypted = runCatching { PdfRenderFallback.isEncryptedPdf(path) }.getOrDefault(false)
        }

        var effectivePassword: String? = null

        if (encrypted) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                // 低于 Android 15 无 setPassword 入口，无法渲染加密 PDF。
                throw PdfPasswordException(unsupported = true)
            }
            val pw = PdfPasswordHolder.current
            if (pw == null) {
                // 缺密码 → 弹框输入。
                throw PdfPasswordException()
            }
            // 校验密码：错误则抛 wrongPassword。
            val n = runCatching { PdfRenderFallback.getPageCount(path, pw) }
                .getOrElse { throw PdfPasswordException(wrongPassword = true) }
            if (n <= 0) throw PdfPasswordException(wrongPassword = true)
            effectivePassword = pw
            renderOnly = true
            Log.d(TAG, "PDF encrypted, render-only with password, pages=$n")
        } else if (probe != null && probe.parseOk && probe.pageCount > 0) {
            parser = probe
            renderOnly = false
            Log.d(TAG, "PDF extract mode, pages=${probe.pageCount}")
        } else {
            renderOnly = true
            Log.d(TAG, "PDF parse incomplete / 超大文件, fallback to render-only")
        }

        currentPassword = effectivePassword

        val pages = if (renderOnly) {
            val n = PdfRenderFallback.getPageCount(path, currentPassword)
            Log.d(TAG, "PDF render-only mode, pages=$n")
            if (n <= 0) throw Exception("PDF 无法解析（可能已损坏或已加密）")
            List(n) { i ->
                ReaderPage(i).apply {
                    stream = { openStream(i) }
                    status = Page.State.Ready
                    skipEnhance = false
                }
            }
        } else {
            List(parser!!.pageCount) { i ->
                ReaderPage(i).apply {
                    stream = { openStream(i) }
                    status = Page.State.Ready
                }
            }
        }
        pages
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
            var offset = 0L
            val size = source.size
            while (offset < size) {
                val len = min(1 shl 20, (size - offset).toInt())
                val buf = source.read(offset, len)
                if (buf.isEmpty()) break
                fos.write(buf)
                offset += buf.size
            }
        }
        if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    private fun openStream(index: Int): InputStream {
        check(!isRecycled) { "PDF 页面读取时加载器已被回收——重载即恢复" }
        if (!renderOnly) {
            try {
                val img = parser!!.bestImageForPage(index)
                if (img != null && img.filter == "DCTDecode") {
                    val raw = parser!!.rawStreamBytes(img.objNum)
                    if (raw != null && raw.isNotEmpty()) {
                        return ByteArrayInputStream(raw)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PDF extract page ${index + 1} failed, render fallback: ${e.message}")
            }
        }
        // 兜底：系统渲染（含加密带密码；密码错误时系统抛 SecurityException → 转 wrongPassword）
        val rendered = try {
            PdfRenderFallback.renderPage(path, index, currentPassword)
        } catch (e: SecurityException) {
            if (currentPassword != null) throw PdfPasswordException(wrongPassword = true)
            throw e
        } ?: throw IllegalStateException("PDF 第 ${index + 1} 页无法渲染（可能已损坏或加密）")
        return ByteArrayInputStream(rendered)
    }

    override fun recycle() {
        super.recycle()
    }

    companion object {
        private const val TAG = "KomihoPdfLoader"

        /** 解析器整文件载入内存的阈值：超过则跳过内存解析、直接走系统渲染兜底，避免大文件 OOM。 */
        private const val MAX_PARSER_FILE_BYTES = 200L * 1024 * 1024 // 200MB
    }
}
