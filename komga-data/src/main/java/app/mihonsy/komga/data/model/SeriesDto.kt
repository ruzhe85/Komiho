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

@Serializable
data class GenreDto(
    val name: String = "",
    val id: String = "",
)
