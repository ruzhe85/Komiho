package app.mihonsy.komga.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LibraryDto(
    val id: String,
    val name: String,
    val root: String? = null,
    @SerialName("import_regex") val importRegex: String? = null,
    val url: String? = null,
)
