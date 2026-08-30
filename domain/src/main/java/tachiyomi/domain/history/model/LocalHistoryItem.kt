package tachiyomi.domain.history.model

import java.util.Date

// SY --> Komiho: 本地模式历史 tab 的一项（文件级）。
// 与 HistoryWithRelations 不同：不按 manga 去重，返回每一条历史记录；
// 包含章节名、章节 URL、最后阅读页、上传/修改时间，供列表展示完整进度。
data class LocalHistoryItem(
    val id: Long,
    val mangaId: Long,
    val chapterId: Long,
    val mangaTitle: String,
    val thumbnailUrl: String?,
    val chapterName: String,
    val chapterNumber: Double,
    val lastPageRead: Long,
    val chapterUrl: String,
    val dateUpload: Long,
    val readAt: Date?,
    val readDuration: Long,
)
// SY <--
