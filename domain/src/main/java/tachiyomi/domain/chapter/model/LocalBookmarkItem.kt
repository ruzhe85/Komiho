package tachiyomi.domain.chapter.model

// SY --> Komiho: 本地模式书签 tab 的一项：某来源下已加书签的章节（章节级书签）。
// 关联所属 manga，供列表展示（系列名 + 章节名）与点击续读。
data class LocalBookmarkItem(
    val chapterId: Long,
    val mangaId: Long,
    val chapterName: String,
    val chapterNumber: Double,
    val chapterUrl: String,
    val mangaTitle: String,
    val mangaUrl: String,
)
// SY <--
