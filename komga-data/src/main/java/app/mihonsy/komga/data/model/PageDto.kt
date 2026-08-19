package app.mihonsy.komga.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PageDto(
    val number: Int = 0,
    @SerialName("fileName") val fileName: String? = null,
    @SerialName("mediaType") val mediaType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
)

@Serializable
data class ReadingListDto(
    val id: String,
    val name: String,
    @SerialName("booksCount") val booksCount: Int = 0,
    @SerialName("bookIds") val bookIds: List<String> = emptyList(),
    val url: String? = null,
    @SerialName("filtered") val filtered: Boolean = false,
)
