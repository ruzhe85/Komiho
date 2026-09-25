package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.util.Log
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.pdf.PdfParser
import eu.kanade.tachiyomi.util.pdf.PdfRenderFallback
import eu.kanade.tachiyomi.util.pdf.remotePdfSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.min
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

        // 先尝试自写解析（提取内嵌图字节流 + 增强）；失败则整本走系统渲染兜底。
        try {
            val p = PdfParser(path)
            if (p.parseOk && p.isEncrypted()) {
                throw IllegalStateException("PDF 已加密，当前版本不支持解密，请先解除加密后重试")
            }
            if (p.parseOk && p.pageCount > 0) {
                parser = p
                renderOnly = false
                Log.d(TAG, "PDF extract mode, pages=${p.pageCount}")
            } else {
                renderOnly = true
            }
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message?.contains("加密") == true) throw e
            Log.w(TAG, "PDF parse failed, fallback to render: ${e.message}")
            renderOnly = true
        }

        val pages = if (renderOnly) {
            val n = PdfRenderFallback.getPageCount(path)
            Log.d(TAG, "PDF render-only mode, pages=$n")
            if (n <= 0) throw Exception("PDF 无法解析（可能已损坏或已加密）")
            List(n) { i ->
                ReaderPage(i).apply {
                    stream = { openStream(i) }
                    status = Page.State.Ready
                    // 系统渲染兜底产出的位图无需再做增强（避免无谓 2x 放大）。
                    skipEnhance = true
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
        // 兜底：系统渲染
        val rendered = PdfRenderFallback.renderPage(path, index)
            ?: throw IllegalStateException("PDF 第 ${index + 1} 页无法渲染（可能已损坏或加密）")
        return ByteArrayInputStream(rendered)
    }

    override fun recycle() {
        super.recycle()
    }

    companion object {
        private const val TAG = "KomihoPdfLoader"
    }
}
