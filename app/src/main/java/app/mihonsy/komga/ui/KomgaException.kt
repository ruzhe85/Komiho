package app.mihonsy.komga.ui

/**
 * 轻量领域异常：用于在 runCatching 块内主动抛出、并由 onFailure 捕获显示
 * 给用户（例如创建空阅读列表时提示 no_books_to_add）。
 */
class KomgaException(message: String) : Exception(message)
