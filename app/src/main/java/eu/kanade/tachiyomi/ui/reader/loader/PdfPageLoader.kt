package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.pdf.PdfParser
import eu.kanade.tachiyomi.util.pdf.PdfRenderFallback
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 本地 PDF 页加载器（方案 A：自写提取 + 系统渲染兜底）。
 *
 * 每页交付「编码字节流」给既有解码+增强管线：
 *  - DCTDecode(JPEG) 内嵌图：直通原始字节，Lanczos3 / AI / NPU 增强无损生效（网点细节保住）。
 *  - 其他（CCITT/JPX/Flate/矢量/无内嵌图）：[PdfRenderFallback] 渲染成 PNG 字节流，增强仍可用。
 *
 * 骨架范围：先支持「未加密 + DCT 直通 + 渲染兜底」；加密(R2–R4)/软件 Flate 解码留待补齐步。
 */
internal class PdfPageLoader(
    private val file: UniFile,
    private val context: Context,
) : PageLoader() {

    private val path: String = file.filePath
        ?: throw IllegalArgumentException("PDF 不是本地文件，无法随机访问：${file.uri}")

    private val parser = PdfParser(path)

    override var isLocal: Boolean = true

    init {
        if (parser.isEncrypted()) {
            // 骨架阶段：加密 PDF 明确报错，不静默失败（解密留待补齐步）。
            throw IllegalStateException("PDF 已加密，当前版本不支持解密，请先解除加密后重试")
        }
    }

    override suspend fun getPages(): List<ReaderPage> {
        val count = parser.pageCount
        if (count == 0) {
            throw Exception("PDF 没有任何页面")
        }
        return List(count) { i ->
            ReaderPage(i).apply {
                stream = { openStream(i) }
                status = Page.State.Ready
            }
        }
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
