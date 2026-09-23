package app.mihonsy.komga.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeriesDto(
    val id: String,
    val name: String,
    val url: String? = null,
    val libraryId: String? = null,
    val oneshot: Boolean = false,
    val deleted: Boolean = false,
    @SerialName("booksCount") val booksCount: Int = 0,
    @SerialName("booksReadCount") val booksReadCount: Int = 0,
    @SerialName("booksUnreadCount") val booksUnreadCount: Int = 0,
    @SerialName("booksInProgressCount") val booksInProgressCount: Int = 0,
    val metadata: SeriesMetadataDto = SeriesMetadataDto(),
)

@Serializable
data class SeriesMetadataDto(
    val title: String? = null,
    val titleSort: String? = null,
    val status: String? = null,
    val summary: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    @SerialName("readingDirection") val readingDirection: String? = null,
    @SerialName("ageRating") val ageRating: Int? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val authors: List<AuthorDto> = emptyList(),
)

@Serializable
data class AuthorDto(
    val name: String = "",
    val role: String? = null,
)

/**
 * Komiho: Komga 的 `genres` + `tags` 合并后的标签列表（去空、去重，genres 在前）。
 *
 * 用途：`Manga.mangaType()` 靠标签里的 `webtoon` / `long strip` 识别条漫，这是「打开前就知道
 * 该用哪种阅读模式」的首选信号（比按图片比例探测更早、更准）。**源详情与 DB 桥接两处写入必须
 * 用同一个口径**，否则两条入口对同一本书判断不一致。
 */
fun SeriesMetadataDto.combinedTags(): List<String> =
    (genres + tags).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

@Serializable
data class GenreDto(
    val name: String = "",
    val id: String = "",
)
