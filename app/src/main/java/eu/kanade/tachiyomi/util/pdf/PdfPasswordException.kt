package eu.kanade.tachiyomi.util.pdf

/**
 * PDF 加密相关异常：
 *  - 缺密码（[wrongPassword]=false, [unsupported]=false）：需弹出密码输入框；
 *  - 密码错误（[wrongPassword]=true）：弹框并显示「密码错误」；
 *  - 系统版本不支持（[unsupported]=true）：弹框告知需 Android 15+。
 *
 * 由 [PdfPageLoader] 抛出，reader 框架捕获后弹出对应对话框。
 */
class PdfPasswordException(
    val wrongPassword: Boolean = false,
    val unsupported: Boolean = false,
) : Exception(
    when {
        unsupported -> "Encrypted PDF not supported on this Android version (requires Android 15+)"
        wrongPassword -> "PDF password incorrect"
        else -> "PDF requires password"
    },
)
