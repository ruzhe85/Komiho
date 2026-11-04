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
        return lastPathSegment(series.url) ?: series.name
    }

    /** 书籍显示名：开=标题（回退文件名），关=原始文件名。 */
    fun book(book: BookDto): String {
        if (showTitles) {
            return book.metadata.title?.takeIf { it.isNotBlank() } ?: book.name
        }
        // 关掉标题时用原始文件名；若服务端给的是带路径的名字，只保留最后一段。
        return lastPathSegment(book.name) ?: book.name
    }

    /**
     * 从 Komga 给的路径里取最后一段（原始文件夹 / 文件名），失败返回 null。
     *
     * Komiho (2026-11-04): **必须先解码再切分**。Komga 的 `url` 是 URL 编码过的路径，斜杠常被编成
     * `%2F`（`/data/Comics%2F%E6%9C%80...`），先前先 `substringAfterLast('/')` 就切不开，
     * 解码后整条路径原样显示出来——就是「原始文件夹名把路径也显示了」。
     * 同时兼容 Windows 风格的反斜杠（Komga 跑在 Windows 上时可能出现）。
     */
    private fun lastPathSegment(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val decoded = runCatching { Uri.decode(text) }.getOrDefault(text)
        val segment = decoded
            .trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        return segment.takeIf { it.isNotBlank() }
    }
}
