package tachiyomi.domain.chapter.model

import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY

data class Chapter(
    val id: Long,
    val mangaId: Long,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val bookmarkPage: Long,
    val dateFetch: Long,
    val sourceOrder: Long,
    val url: String,
    val name: String,
    val dateUpload: Long,
    val chapterNumber: Double,
    val scanlator: String?,
    val lastModifiedAt: Long,
    val version: Long,
    val memo: JsonObject,
    /**
     * Komiho (2026-11-06): 文件身份 `v1:<size>:<mtimeSec>`（见 app 层 `FileIdentity`）。
     *
     * 与 [url] 的区别：url 是路径，文件移动/改名就失效；content_key 只由大小+修改时间组成，
     * 移动、改名都不变，所以「文件浏览器 ↔ 历史 ↔ 章节进度」能靠它重新对上。
     * 老数据为 null（首次打开该文件时补写）。
     */
    val contentKey: String? = null,
    /** 总页数（0 = 未知）。文件浏览器显示「62/166」的分母。 */
    val totalPages: Int = 0,
) {
    val isRecognizedNumber: Boolean
        get() = chapterNumber >= 0f

    fun copyFrom(other: Chapter): Chapter {
        return copy(
            name = other.name,
            url = other.url,
            dateUpload = other.dateUpload,
            chapterNumber = other.chapterNumber,
            scanlator = other.scanlator?.ifBlank { null },
        )
    }

    companion object {
        fun create() = Chapter(
            id = -1,
            mangaId = -1,
            read = false,
            bookmark = false,
            lastPageRead = 0,
            bookmarkPage = 0,
            dateFetch = 0,
            sourceOrder = 0,
            url = "",
            name = "",
            dateUpload = -1,
            chapterNumber = -1.0,
            scanlator = null,
            lastModifiedAt = 0,
            version = 1,
            memo = JsonObject.EMPTY,
        )
    }
}
