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
    @SerialName("bookIds") val bookIds: List<String> = emptyList(),
    val url: String? = null,
    @SerialName("filtered") val filtered: Boolean = false,
) {
    /**
     * 阅读列表里的本数。
     *
     * Komga 的 `ReadListDto` **没有** `booksCount` 字段（只有 `bookIds`）——Web 端显示的
     * 数量同样是前端用 `bookIds.length` 算的。这里若声明成可反序列化字段，服务端不返回
     * 就会一直落默认值 0（表现：列表永远「0 本」，Web 端却正常）。故改为派生属性。
     */
    val booksCount: Int get() = bookIds.size
}

/** Komga collection (收藏). Matches the keiyoushi extension's CollectionDto. */
@Serializable
data class CollectionDto(
    val id: String,
    val name: String,
    @SerialName("ordered") val ordered: Boolean = false,
    @SerialName("seriesIds") val seriesIds: List<String> = emptyList(),
    @SerialName("createdDate") val createdDate: String? = null,
    @SerialName("lastModifiedDate") val lastModifiedDate: String? = null,
    @SerialName("filtered") val filtered: Boolean = false,
)
