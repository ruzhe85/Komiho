package app.mihonsy.komga.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BookDto(
    val id: String,
    val name: String,
    val number: Int? = null,
    val url: String? = null,
    val seriesId: String? = null,
    @SerialName("seriesTitle") val seriesTitle: String? = null,
    val libraryId: String? = null,
    val deleted: Boolean = false,
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    val media: MediaDto = MediaDto(),
    val metadata: BookMetadataDto = BookMetadataDto(),
    @SerialName("readProgress") val readProgress: ReadProgressDto? = null,
)

@Serializable
data class MediaDto(
    @SerialName("mediaType") val mediaType: String? = null,
    @SerialName("pagesCount") val pagesCount: Int = 0,
    val status: String? = null,
    @SerialName("mediaProfile") val mediaProfile: String? = null,
)

@Serializable
data class BookMetadataDto(
    val title: String? = null,
    val number: String? = null,
    val summary: String? = null,
    val isbn: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
    val tags: List<String> = emptyList(),
    val authors: List<AuthorDto> = emptyList(),
)

@Serializable
data class ReadProgressDto(
    val page: Int = 0,
    val completed: Boolean = false,
    @SerialName("readDate") val readDate: String? = null,
    @SerialName("deviceId") val deviceId: String? = null,
    @SerialName("deviceName") val deviceName: String? = null,
    @SerialName("lastModified") val lastModified: String? = null,
)

@Serializable
data class ReadProgressUpdateDto(
    val page: Int,
    val completed: Boolean = false,
)
