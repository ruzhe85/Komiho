package eu.kanade.tachiyomi.util.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

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
        val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = openRenderer(pfd, password)
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
    fun renderPage(path: String, pageIndex: Int, password: String? = null): ByteArray? {
        val bmp = renderPageBitmap(path, pageIndex, password = password) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** 读取 PDF 页数（渲染兜底模式用）。
     *  - [password] 为空：失败静默返回 0（供未加密探测）。
     *  - [password] 非空：失败（含密码错误 SecurityException）重新抛出，由上层转 [PdfPasswordException]。
     */
    fun getPageCount(path: String, password: String? = null): Int {
        return try {
            val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = openRenderer(pfd, password)
            try {
                renderer.pageCount
            } finally {
                renderer.close()
                pfd.close()
            }
        } catch (e: Exception) {
            if (password != null) throw e
            android.util.Log.w(TAG, "getPageCount failed: ${e.message}")
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
            val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfRenderer(pfd).close()
            } finally {
                pfd.close()
            }
            false
        } catch (e: SecurityException) {
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
    private fun fit(w: Int, h: Int, target: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return target to target
        val long = maxOf(w, h)
        val scale = target.toFloat() / long
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }
}
