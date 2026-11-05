package eu.kanade.tachiyomi.util.pdf

/**
 * Komiho (2026-11-05): libpdfium 的**进程级串行锁**。
 *
 * PDFium 的 public API 不是线程安全的 —— 不只是「同一个 FPDF_DOCUMENT 不能并发」，
 * 全局状态（字体缓存、内存分配、图像解码缓存）也不允许跨 document 并发。我们进程里有
 * 两个入口会进 libpdfium：
 *  - [PdfiumDoc]（`io.legere:pdfiumandroid`，渲染）；
 *  - [PdfiumExtractor]（自己 dlopen 的同一份 libpdfium.so，抽取内嵌 JPEG）。
 *
 * 两个入口各持一把锁是**不够**的：阅读器预取线程在渲染第 N 页、Coil 线程同时在
 * 另一页做 probe（LoadPage）—— 这正是 2026-11-05 22:56:48 那次 SIGTRAP（CRASH_NATIVE）
 * 的现场（崩溃前 168ms 刚好是 `ReaderChapter: Recycling chapter`，回收关闭文档与
 * 后台渲染撞在一起）。
 *
 * 所以所有 libpdfium 调用统一走这一把锁。代价是渲染与抽取串行；probe 每页只有几毫秒，
 * 而 PDFium 本来也不允许这两件事并行。
 */
internal object PdfiumLock {
    val mutex = Any()
}
