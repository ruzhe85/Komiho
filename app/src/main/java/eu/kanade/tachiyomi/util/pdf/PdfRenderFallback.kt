package eu.kanade.tachiyomi.util.pdf

import android.graphics.Bitmap
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

    private const val MAX_LONG_SIDE = 2200

    /** 渲染指定页为 Bitmap（按 maxLongSide 等比缩放，避免超大 PDF 爆内存）。 */
    fun renderPageBitmap(path: String, pageIndex: Int, maxLongSide: Int = MAX_LONG_SIDE): Bitmap? {
        val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
            val page = renderer.openPage(pageIndex)
            val (tw, th) = fit(page.width, page.height, maxLongSide)
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
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

    private fun fit(w: Int, h: Int, maxLong: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return maxLong to maxLong
        val long = maxOf(w, h)
        if (long <= maxLong) return w to h
        val scale = maxLong.toFloat() / long
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }
}
