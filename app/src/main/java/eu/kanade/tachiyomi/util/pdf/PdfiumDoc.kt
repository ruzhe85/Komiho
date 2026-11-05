package eu.kanade.tachiyomi.util.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.diagnostic.DiagLog
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Komiho (2026-11-04): 自带 PDFium（`io.legere:pdfiumandroid`）的文档封装。
 *
 * 为什么换掉系统的 `android.graphics.pdf.PdfRenderer`：
 * - 它对损坏的 xref 一律抛 `SecurityException`，我们只能当成「需要密码」→ 反复弹框；
 * - 页树/对象解析不出就只能整本渲染，页数还会对不上；
 * - 它既不暴露「取内嵌图原始字节」，也不暴露对象/矩阵信息。
 *
 * PDFium 本体对畸形 PDF 的容忍度高得多（Chrome 用的同一套），打开成功与否就是唯一判据：
 * 能打开 = 页数可信；打不开 = 要么真加密（IOException 里带 password 字样），要么文件损坏。
 *
 * 注意：这里**不做**直通（提取内嵌 JPEG）—— 那是 [PdfiumExtractor] 的活（同一份
 * libpdfium.so 的对象 API，见 cpp/pdfium_jni.cpp），[PdfParser] 只作它的兜底。
 * 直通判否的页一律走本类的渲染。
 */
class PdfiumDoc private constructor(
    private val doc: PdfDocument,
    private val pfd: ParcelFileDescriptor,
) : Closeable {

    /** 关闭标志：关过之后所有调用直接返回 null/0，绝不把已销毁的句柄交给 PDFium。 */
    private var closed = false

    val pageCount: Int
        get() = synchronized(PdfiumLock.mutex) {
            if (closed) 0 else runCatching { doc.getPageCount() }.getOrDefault(0)
        }

    /** 页面尺寸（PostScript 点，1/72 英寸）；取不到返回 null。 */
    fun sizePoints(index: Int): Pair<Int, Int>? = synchronized(PdfiumLock.mutex) {
        if (closed) return@synchronized null
        runCatching {
            val page = doc.openPage(index) ?: return@runCatching null
            try {
                page.getPageWidthPoint() to page.getPageHeightPoint()
            } finally {
                runCatching { page.close() }
            }
        }.getOrNull()
    }

    /**
     * 渲染一页到 ARGB_8888 位图。
     *
     * - 白底：`pageBackgroundColor` 填白，透明区（软掩膜）合成到白，和 PDF 查看器一致；
     * - `canvasColor = 0` 表示不额外填充画布（页面已铺满整张位图）；
     * - 失败（越界/渲染异常）返回 null，调用方回落错误页。
     */
    fun renderPage(index: Int, width: Int, height: Int): Bitmap? = synchronized(PdfiumLock.mutex) {
        if (closed || width <= 0 || height <= 0) return@synchronized null
        val bmp = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return@synchronized null
        val ok = runCatching {
            val page = doc.openPage(index) ?: return@runCatching false
            try {
                page.renderPageBitmap(
                    bmp,
                    0,
                    0,
                    width,
                    height,
                    renderAnnot = false,
                    canvasColor = 0,
                    pageBackgroundColor = Color.WHITE,
                )
                true
            } finally {
                runCatching { page.close() }
            }
        }.onFailure { DiagLog.w(TAG, "render page=$index ${width}x$height 失败: ${it.message}") }
            .getOrDefault(false)
        if (!ok) {
            runCatching { bmp.recycle() }
            return@synchronized null
        }
        bmp
    }

    override fun close() {
        // 与渲染/尺寸查询互斥：等正在跑的 PDFium 调用结束再销毁句柄，
        // 否则就是「PdfiumDoc 已 close、后台线程还在 LoadPage」的 use-after-free。
        synchronized(PdfiumLock.mutex) {
            if (!closed) {
                closed = true
                runCatching { doc.close() }
                runCatching { pfd.close() }
            }
        }
    }

    companion object {
        private const val TAG = "KomihoPdfium"

        /** 全局唯一的 PDFium 入口（native 库只加载一次）。 */
        private val core: PdfiumCore by lazy { PdfiumCore(null) }

        /**
         * 打开文档。密码错误/需要密码时抛 [IOException]，其它失败（文件损坏等）同样抛 IOException。
         *
         * 调用方按 `message` 是否包含 `password` 区分「要密码」与「打不开」，不要去猜
         * SecurityException —— 那是系统 PdfRenderer 的行为，PDFium 不这么报。
         */
        @Throws(IOException::class)
        fun open(path: String, password: String? = null): PdfiumDoc {
            val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                val doc = synchronized(PdfiumLock.mutex) { core.newDocument(pfd, password) }
                val wrapper = PdfiumDoc(doc, pfd)
                // 先构造再读页数：pageCount 自带 runCatching，不会因为这一行把 doc 漏掉没关。
                DiagLog.d(TAG, "open ok path=$path password=${password != null} pages=${wrapper.pageCount}")
                wrapper
            } catch (e: IOException) {
                runCatching { pfd.close() }
                throw e
            } catch (e: Throwable) {
                runCatching { pfd.close() }
                throw IOException(e.message, e)
            }
        }
    }
}
