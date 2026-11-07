package tachiyomi.domain.chapter.model

/**
 * Komiho (2026-11-06): 文件浏览器列表用的轻量进度投影。
 *
 * 不取整条 [Chapter]（列表里几百个文件，没必要搬 memo/JSON 之类的大字段），
 * 只按 `content_key` 批量取展示需要的四列。
 */
data class ChapterProgress(
    val contentKey: String?,
    /**
     * 章节 URL（本地绝对路径 / WebDAV 章节 URL / SMB 章节 URL）。
     * 只有「按 URL 兜底查询」这条通道会填；按 content_key 查询时为 null。
     */
    val url: String? = null,
    /** 0-based 页码（展示时 +1）。 */
    val lastPageRead: Long,
    /** 总页数，0 = 未知（不显示进度）。 */
    val totalPages: Int,
    /** 章节是否已读完 —— 比按百分比判更可靠（最后一页未必触发完成标记）。 */
    val read: Boolean,
)
