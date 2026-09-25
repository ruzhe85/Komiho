package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
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
 *  - 其他（CCITT/JPX/Flate/矢量/无内嵌图）：[PdfRenderFallback] 渲染成 PNG 字节流，增强仍可用。
 *
 * 来源：
 *  - 本地：`PdfPageLoader(file, context)` —— 直接读本地文件路径。
 *  - 远程（WebDAV/SMB）：`PdfPageLoader(remoteUrl, context)` —— 首次取页时整本落本地缓存，
 *    之后完全复用本地解析路径（渲染兜底也需本地文件）。
 *
 * 骨架范围：先支持「未加密 + DCT 直通 + 渲染兜底」；加密(R2–R4)/软件 Flate 解码留待补齐步。
 */
internal class PdfPageLoader private constructor(
    private val context: Context,
    private val localFile: UniFile?,
    private val remoteUrl: String?,
) : PageLoader() {

    private lateinit var path: String
    private lateinit var parser: PdfParser

    /** 本地 PDF：传 UniFile（必须是本地路径）。 */
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
        parser = PdfParser(path)
        if (parser.isEncrypted()) {
            // 骨架阶段：加密 PDF 明确报错，不静默失败（解密留待补齐步）。
            throw IllegalStateException("PDF 已加密，当前版本不支持解密，请先解除加密后重试")
        }
        val count = parser.pageCount
        if (count == 0) {
            throw Exception("PDF 没有任何页面")
        }
        List(count) { i ->
            ReaderPage(i).apply {
                stream = { openStream(i) }
                status = Page.State.Ready
            }
        }
    }

    private suspend fun resolvePath(): String = withIOContext {
        if (localFile != null) {
            return@withIOContext localFile.filePath
                ?: throw IllegalArgumentException("PDF 不是本地文件，无法随机访问：${localFile.uri}")
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
        val img = parser.bestImageForPage(index)
        if (img != null && img.filter == "DCTDecode") {
            val raw = parser.rawStreamBytes(img.objNum)
            if (raw != null && raw.isNotEmpty()) {
                return ByteArrayInputStream(raw)
            }
        }
        // 兜底：系统渲染
        val rendered = PdfRenderFallback.renderPage(path, index)
            ?: throw IllegalStateException("PDF 第 ${index + 1} 页无法渲染（可能已损坏或加密）")
        return ByteArrayInputStream(rendered)
    }

    override fun recycle() {
        super.recycle()
        // PdfParser / PdfRenderFallback 各自无长生命周期句柄，无需额外释放。
    }
}
