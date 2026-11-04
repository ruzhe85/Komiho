package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    // SY -->
    /** Value to check if this page is used to as if it was too wide */
    var shiftedPage: Boolean = false,
    /** Value to check if a page is can be doubled up, but can't because the next page is too wide */
    var isolatedPage: Boolean = false,
    // SY <--
    var stream: (() -> InputStream)? = null,

) : Page(index, url, imageUrl, null), ReaderItem {

    open lateinit var chapter: ReaderChapter

    /** Value to check if a page is too wide to be doubled up */
    var fullPage: Boolean = false
        set(value) {
            field = value
            if (value) shiftedPage = false
        }

    // Komiho: 标记该页由系统渲染兜底产出位图（如 PDF 渲染页），无需再做图像增强（避免无谓 2x 放大）。
    var skipEnhance: Boolean = false

    /**
     * Komiho (2026-11-04): 已渲染好的位图提供者 —— PDF 渲染兜底页用。
     *
     * 非空时 holder / 预热直接拿这张位图显示，**跳过「栅格化 → 编码成 WebP/JPEG → 再解码」的往返**
     * （实测编码 250–470ms/页 + 解码 ~40ms/页）。返回 null（渲染失败）时调用方回落原字节路径。
     */
    var preRenderedBitmap: (() -> android.graphics.Bitmap?)? = null
}
