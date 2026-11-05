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
 * 直通前提（宁严不宽，判错会显示错页）：**本页只有一张图** + 原始流是完整 JPEG +
 * JPEG 宽高比与页面比例吻合（排掉跨页图被 /Clip 切一半的情况）。多图拼页、水印页、
 * JPX/JBIG2/Flate、CMYK 一律返回 null，由调用方回落 [PdfiumDoc.renderPage]。
 *
 * ⚠️ native 侧日志进不了应用内导出（那边只写 logcat），所以统计值全部经 IntArray
 * 回传、在这里用 [DiagLog] 记录：`selftest[..]` / `probe page=..` / `raw page=..`。
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
        if (!PdfiumGuard.available()) return null
        val startedAt = android.os.SystemClock.uptimeMillis()
        PdfiumGuard.arm()
        val ptr = synchronized(PdfiumLock.mutex) {
            runCatching { nativeOpen(path, password) }.getOrDefault(0L)
        }
        PdfiumGuard.disarm()
        val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
        if (ptr == 0L) {
            DiagLog.d(TAG, "PDFium 抽取句柄不可用（${elapsed}ms），回落解析器/渲染")
            return null
        }
        // 自检摘要（对象数/图数/页面尺寸）—— 打开阶段就能看出对象 API 是否正常。
        PdfiumGuard.arm()
        val selftest = synchronized(PdfiumLock.mutex) {
            runCatching { nativeSelfTest(ptr) }.getOrNull()
        }
        PdfiumGuard.disarm()
        DiagLog.d(
            TAG,
            "PDFium 抽取句柄就绪 耗时=${elapsed}ms selftest[${selftest ?: "?"}] path=$path",
        )
        return Handle(ptr)
    }

    /** [stats] = [对象数, 图对象数, 0]；返回本页是否「单图页」。 */
    fun probe(handle: Long, index: Int, stats: IntArray): Boolean =
        handle != 0L && synchronized(PdfiumLock.mutex) {
            runCatching { nativeProbe(handle, index, stats) }.getOrDefault(false)
        }

    /** [info] = [对象数, 图对象数, JPEG 分量数]；返回原始 JPEG 字节（不合格为 null）。 */
    fun rawJpeg(handle: Long, index: Int, info: IntArray): ByteArray? =
        if (handle == 0L) {
            null
        } else {
            synchronized(PdfiumLock.mutex) {
                runCatching { nativeRawJpeg(handle, index, info) }.getOrNull()
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

        /**
         * 本句柄是否已经成功跑过一次 native 调用。
         *
         * 熔断只需要覆盖「第一次真正进 PDFium」：崩溃几乎都发生在首个 probe，而且
         * 每页都写两次 SharedPreferences（commit + fsync）代价太大。首次正常返回后
         * 就不再写盘。
         */
        private var armedOnce = false

        private inline fun <T> guard(block: () -> T): T {
            if (armedOnce) return block()
            PdfiumGuard.arm()
            return try {
                block()
            } finally {
                // 走不到这里 = 进程死在 native 调用里 → 下次启动熔断。
                PdfiumGuard.disarm()
                armedOnce = true
            }
        }

        /** 这一页是否是「单图页」（判据与 [rawJpeg] 的 native 侧完全一致）。 */
        @Synchronized
        fun canPassthrough(index: Int): Boolean {
            val handle = ptr
            if (handle == 0L) return false
            val stats = IntArray(3)
            val ok = guard { PdfiumExtractor.probe(handle, index, stats) }
            DiagLog.d(
                TAG,
                "probe page=${index + 1} objs=${stats[0]} imgs=${stats[1]} " +
                    "single=${if (ok) 1 else 0}",
            )
            return ok
        }

        /** 这一页内嵌 JPEG 的**原始字节**；不是「单图页 / 纯 JPEG / 比例吻合」返回 null。 */
        @Synchronized
        fun rawJpeg(index: Int): ByteArray? {
            val handle = ptr
            if (handle == 0L) return null
            val info = IntArray(3)
            val bytes = guard { PdfiumExtractor.rawJpeg(handle, index, info) }
            DiagLog.d(
                TAG,
                "raw page=${index + 1} objs=${info[0]} imgs=${info[1]} " +
                    "comps=${info[2]} len=${bytes?.size ?: 0} ok=${if (bytes != null) 1 else 0}",
            )
            return bytes
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

    private external fun nativeSelfTest(handle: Long): String?

    private external fun nativeClose(handle: Long)

    private external fun nativeProbe(handle: Long, index: Int, stats: IntArray): Boolean

    private external fun nativeRawJpeg(handle: Long, index: Int, info: IntArray): ByteArray?
}
