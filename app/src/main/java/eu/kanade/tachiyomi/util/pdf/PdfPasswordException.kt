package eu.kanade.tachiyomi.util.pdf

/**
 * PDF 加密相关异常：
 *  - 缺密码（[wrongPassword]=false）：需弹出密码输入框；
 *  - 密码错误（[wrongPassword]=true）：弹框并显示「密码错误」。
 *
 * Komiho (2026-11-06): 不再有「系统版本不支持」这一支 —— 阅读器改用自带 PDFium 打开
 * （`FPDF_LoadDocument(path, password)`），加密 PDF 在 minSdk 以上都能读，
 * 旧版那句「需要 Android 15+」只对系统 PdfRenderer 成立，已经删掉。
 *
 * 由 [PdfPageLoader] 抛出，reader 框架捕获后弹出对应对话框。
 */
class PdfPasswordException(
    val wrongPassword: Boolean = false,
) : Exception(
    if (wrongPassword) "PDF password incorrect" else "PDF requires password",
)
