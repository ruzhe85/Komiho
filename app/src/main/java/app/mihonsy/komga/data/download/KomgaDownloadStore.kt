package app.mihonsy.komga.data.download

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class KomgaDownloadEntry(
    val status: String, // queued | completed | error
    val path: String = "",
    val total: Long = 0L,
)

/**
 * 已下载书籍的本地索引（持久化）。
 *
 * 只持久化终态/可恢复态（queued / completed / error）+ 本地路径；
 * 下载进度（Progress）不写 SP，避免高频 IO。
 * MVP 用 SharedPreferences（与 Mihon DownloadStore 一致）。
 */
class KomgaDownloadStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("komga_downloads", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getStatus(bookId: String): KomgaDownloadEntry? {
        val raw = prefs.getString(bookId, null) ?: return null
        return runCatching { json.decodeFromString<KomgaDownloadEntry>(raw) }.getOrNull()
    }

    fun isDownloaded(bookId: String): Boolean = getStatus(bookId)?.status == "completed"

    fun getPath(bookId: String): String? =
        getStatus(bookId)?.takeIf { it.status == "completed" }?.path

    fun allDownloaded(): Map<String, KomgaDownloadEntry> {
        val out = mutableMapOf<String, KomgaDownloadEntry>()
        prefs.all.forEach { (k, v) ->
            if (v is String) {
                runCatching { json.decodeFromString<KomgaDownloadEntry>(v) }
                    .getOrNull()
                    ?.let { if (it.status == "completed") out[k] = it }
            }
        }
        return out
    }

    fun markQueued(bookId: String) = put(bookId, KomgaDownloadEntry("queued"))
    fun markCompleted(bookId: String, path: String) = put(bookId, KomgaDownloadEntry("completed", path))
    fun markError(bookId: String, message: String) = put(bookId, KomgaDownloadEntry("error", message))
    fun remove(bookId: String) = prefs.edit { remove(bookId) }

    private fun put(bookId: String, entry: KomgaDownloadEntry) {
        prefs.edit { putString(bookId, json.encodeToString(entry)) }
    }
}
