package app.mihonsy.komga.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PageableDto<T>(
    val content: List<T> = emptyList(),
    @kotlinx.serialization.SerialName("totalElements") val totalElements: Long = 0,
    @kotlinx.serialization.SerialName("totalPages") val totalPages: Int = 0,
    @kotlinx.serialization.SerialName("number") val number: Int = 0,
    @kotlinx.serialization.SerialName("size") val size: Int = 0,
)
