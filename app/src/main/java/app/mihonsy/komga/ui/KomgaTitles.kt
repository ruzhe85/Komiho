package app.mihonsy.komga.ui

import android.content.Context
import android.net.Uri
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.SeriesDto

/**
 * Komiho (2026-11-04): 系列 / 书籍「显示标题」的统一取值口。
 *
 * 设置-Komga 里有一个开关「显示系列标题 / 书籍标题」（默认开）：
 * - 开：显示 Komga 的标题（`metadata.title`，取不到回退 `name`），也就是现在的行为；
 * - 关：显示**原始文件夹名 / 原始文件名**，便于对照磁盘上的实际目录。
 *
 * 实现上刻意做成「进程内缓存 + 非 composable 函数」：
 * 标题在列表/卡片/点击回调里到处用，做成 composable 会逼着每个调用点都插一个 `val`，
 * 改动面反而更大；这里由 App.onCreate 初始化、开关切换时同步刷新，读到的是内存值。
 *
 * 只影响显示，不改 DB 里的漫画名与阅读记录（`KomgaDbBridge` 仍用原始 DTO 的 name）。
 */
object KomgaTitles {

    @Volatile
    private var showTitles: Boolean = true

    /** App 启动时调用一次，把磁盘上的偏好读进内存。 */
    fun init(context: Context) {
        showTitles = runCatching {
            KomgaPreferences(context.applicationContext).showTitles
        }.getOrDefault(true)
    }

    /** 设置页切换开关时调用：写偏好 + 立即刷新内存值。 */
    fun update(context: Context, value: Boolean) {
        showTitles = value
        runCatching { KomgaPreferences(context.applicationContext).showTitles = value }
    }

    fun current(): Boolean = showTitles

    /** 系列显示名：开=标题，关=原始文件夹名（`url` 末段，URL 解码）。 */
    fun series(series: SeriesDto): String {
        if (showTitles) {
            return series.metadata.title?.takeIf { it.isNotBlank() } ?: series.name
        }
        return originalName(series.url) ?: series.name
    }

    /** 书籍显示名：开=标题（回退文件名），关=原始文件名。 */
    fun book(book: BookDto): String {
        if (showTitles) {
            return book.metadata.title?.takeIf { it.isNotBlank() } ?: book.name
        }
        return book.name
    }

    /** 从 Komga 的 url 里取最后一段（原始文件夹/文件名），失败返回 null。 */
    private fun originalName(url: String?): String? {
        val segment = url?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Uri.decode(segment) }.getOrDefault(segment)
    }
}
