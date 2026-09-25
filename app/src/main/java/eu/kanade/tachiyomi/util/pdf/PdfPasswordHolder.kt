package eu.kanade.tachiyomi.util.pdf

/**
 * 当前正在阅读的加密 PDF 密码（session 级）。
 * 打开加密 PDF 时由 [PdfPageLoader] 读取；用户在密码框输入后由
 * [eu.kanade.tachiyomi.ui.reader.ReaderViewModel.submitPdfPassword] 写入；
 * 关闭阅读器时应清空（见 ReaderViewModel 的清理逻辑）。
 */
object PdfPasswordHolder {
    var current: String? = null
}
