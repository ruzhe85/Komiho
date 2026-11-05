package eu.kanade.tachiyomi.util.pdf

import eu.kanade.tachiyomi.diagnostic.DiagLog
import java.io.Closeable

/**
 * Komiho (2026-11-05): PDF 内嵌 JPEG 直通（PDFium 对象 API）。
 *
 * 与 [PdfParser] 的分工：这里**不解析 PDF 结构**，直接用 PDFium 自己的页对象树 —
 * `FPDF_LoadPage → FPDFPage_GetObject → FPDFImageObj_GetImageDataRaw`，拿到 PDF 里
 * 嵌入的原始压缩流。所以自写解析器解不开的畸形/残缺页树（典型：205MB 那本 xref 只剩
 * 869 个对象、页树只走得出 3 页）在这里照样能逐页抽。
 *
 * 只抽「铺满整页的 JPEG」：多图拼页、水印页、JPX/JBIG2/Flate/矢量页一律返回 null，
 * 由调用方回落 [PdfiumDoc.renderPage]。原生侧实现见 `app/src/main/cpp/pdfium_jni.cpp`。
 *
 * 全部失败路径都静默降级（返回 null/false），绝不影响打开与阅读。
 */
internal object PdfiumExtractor {

    private const val TAG = "KomihoPdfiumX"

    /** 原生库装载失败（非 arm64 构建、裁剪安装包等）时整个直通能力关闭。 */
    private val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("komiho-pdfium-jni") }
            .onFailure { DiagLog.w(TAG, "komiho-pdfium-jni 装载失败，直通关闭: ${it.message}") }
            .isSuccess
    }

    /** 打开**独立的** PDFium 文档句柄（不与 legere 的渲染句柄共享，互不影响）。 */
    fun open(path: String, password: String?): Handle? {
        if (!loaded) return null
        val startedAt = android.os.SystemClock.uptimeMillis()
        val ptr = synchronized(PdfiumLock.mutex) {
            runCatching { nativeOpen(path, password) }.getOrDefault(0L)
        }
        val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
        if (ptr == 0L) {
            DiagLog.d(TAG, "PDFium 抽取句柄不可用（${elapsed}ms），回落解析器/渲染")
            return null
        }
        DiagLog.d(TAG, "PDFium 抽取句柄就绪 耗时=${elapsed}ms path=$path")
        return Handle(ptr)
    }

    fun probe(handle: Long, index: Int): Boolean =
        handle != 0L && synchronized(PdfiumLock.mutex) {
            runCatching { nativeProbe(handle, index) }.getOrDefault(false)
        }

    fun rawJpeg(handle: Long, index: Int): ByteArray? =
        if (handle == 0L) {
            null
        } else {
            synchronized(PdfiumLock.mutex) {
                runCatching { nativeRawJpeg(handle, index) }.getOrNull()
            }
        }

    fun close(handle: Long) {
        if (handle != 0L) {
            synchronized(PdfiumLock.mutex) { runCatching { nativeClose(handle) } }
        }
    }

    /**
     * 生命周期包装：关闭时把原生句柄还回去（重复 close 安全）。
     *
     * 所有调用与 [close] 互斥：阅读器回收章节时 Coil 可能还在后台取下一页，
     * 一旦 native 句柄已经销毁而另一线程还在用它，就是 use-after-free。
     */
    class Handle internal constructor(private var ptr: Long) : Closeable {

        /** 这一页是否是「铺满整页的 JPEG」（轻量探测：只读原始流的前 2 字节）。 */
        @Synchronized
        fun canPassthrough(index: Int): Boolean {
            val handle = ptr
            return handle != 0L && PdfiumExtractor.probe(handle, index)
        }

        /** 这一页内嵌 JPEG 的**原始字节**；不是整页 JPEG 返回 null。 */
        @Synchronized
        fun rawJpeg(index: Int): ByteArray? {
            val handle = ptr
            return if (handle == 0L) null else PdfiumExtractor.rawJpeg(handle, index)
        }

        @Synchronized
        override fun close() {
            val handle = ptr
            ptr = 0L
            PdfiumExtractor.close(handle)
        }
    }

    // ---- JNI（见 app/src/main/cpp/pdfium_jni.cpp）------------------------------

    private external fun nativeOpen(path: String, password: String?): Long

    private external fun nativeClose(handle: Long)

    private external fun nativeProbe(handle: Long, index: Int): Boolean

    private external fun nativeRawJpeg(handle: Long, index: Int): ByteArray?
}
