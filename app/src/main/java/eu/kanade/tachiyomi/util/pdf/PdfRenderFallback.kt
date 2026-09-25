package eu.kanade.tachiyomi.util.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 系统 PdfRenderer 兜底：当某页无法走「内嵌图字节直通」（CCITT/JPX/Flate/矢量/无内嵌图）
 * 时，用系统 PDFium 渲染成 Bitmap 再编码为 PNG 字节流交给既有解码+增强管线。
 *
 * 注意：PdfRenderer 不支持加密 PDF，加密文件应在 [PdfParser] 阶段拦截，不会走到这里。
 */
object PdfRenderFallback {

    /** 阅读器兜底渲染的目标长边：低分辨率矢量/文本页（约 595×842 @72DPI）上采样到此值保证清晰。 */
    private const val TARGET_LONG_SIDE = 2000

    /** 渲染指定页为 Bitmap：
     *  - 先填白底，避免矢量/文本 PDF 无色块区域透出（原先透明 → 底色消失）。
     *  - 按 [maxLongSide] 等比缩放：小页面（低 DPI 矢量/文本）上采样到该值保证清晰；
     *    大页面下采样到该值控制内存。封面缩略可传更小值（见 LocalCoverFetcher）。
     */
    fun renderPageBitmap(path: String, pageIndex: Int, maxLongSide: Int = TARGET_LONG_SIDE): Bitmap? {
        val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
            val page = renderer.openPage(pageIndex)
            val (tw, th) = fit(page.width, page.height, maxLongSide)
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            return bmp
        } finally {
            renderer.close()
            pfd.close()
        }
    }

    /** 渲染指定页为 PNG 字节流（供既有解码+增强管线消费）。 */
    fun renderPage(path: String, pageIndex: Int): ByteArray? {
        val bmp = renderPageBitmap(path, pageIndex) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** 读取 PDF 页数（渲染兜底模式用）。失败返回 0。 */
    fun getPageCount(path: String): Int {
        return try {
            val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            try {
                renderer.pageCount
            } finally {
                renderer.close()
                pfd.close()
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getPageCount failed: ${e.message}")
            0
        }
    }

    private const val TAG = "KomihoPdfRender"

    /** 按目标长边等比缩放：小则上采样（矢量/文本更清晰），大则下采样（控制内存/封面缩略）。 */
    private fun fit(w: Int, h: Int, target: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return target to target
        val long = maxOf(w, h)
        val scale = target.toFloat() / long
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }
}
