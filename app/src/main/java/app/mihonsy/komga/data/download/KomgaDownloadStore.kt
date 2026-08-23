package app.mihonsy.komga.data.download

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import java.io.File
import kotlinx.serialization.json.Json

@Serializable
data class KomgaDownloadEntry(
    val status: String, // queued | completed | error
    val path: String = "",
    val total: Long = 0L,
    val seriesName: String = "",
    val seriesId: String = "",
    val bookName: String = "",   // 下载时写入，避免离线反查 DB
    val number: Int = 0,         // Komga book 序号，用于文件名补零与排序
    val completedAt: Long = 0L, // 下载完成时间戳(ms)，用于排序
)

/** 系列级下载元信息（封面路径 + 总数），与 book entry 分开存。 */
@Serializable
data class KomgaSeriesMeta(
    val seriesId: String = "",
    val seriesName: String = "",
    val coverPath: String = "",
    val totalBooks: Int = 0,
)

/**
 * 已下载书籍的本地索引（持久化）。
 *
 * 只持久化终态/可恢复态（queued / completed / error）+ 本地路径；
 * 下载进度（Progress）不写 SP，避免高频 IO。
 * MVP 用 SharedPreferences（与 Mihon DownloadStore 一致）。
 */
class KomgaDownloadStore(private val appContext: Context) {
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("komga_downloads", Context.MODE_PRIVATE)
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
    fun markCompleted(
        bookId: String,
        path: String,
        seriesName: String,
        seriesId: String = "",
        bookName: String = "",
        number: Int = 0,
    ) = put(
        bookId,
        KomgaDownloadEntry(
            "completed",
            path,
            seriesName = seriesName,
            seriesId = seriesId,
            bookName = bookName,
            number = number,
            completedAt = System.currentTimeMillis(),
        ),
    )
    fun markError(bookId: String, message: String) = put(bookId, KomgaDownloadEntry("error", message))
    fun remove(bookId: String) = prefs.edit { remove(bookId) }

    // ---------- 系列级元信息（封面 + 总数）----------

    fun getSeriesMeta(seriesId: String): KomgaSeriesMeta? {
        val raw = prefs.getString("series_meta:$seriesId", null) ?: return null
        return runCatching { json.decodeFromString<KomgaSeriesMeta>(raw) }.getOrNull()
    }

    fun putSeriesMeta(meta: KomgaSeriesMeta) =
        prefs.edit { putString("series_meta:${meta.seriesId}", json.encodeToString(meta)) }

    /** 删整个系列：删全部 book entry + series meta + 本地封面文件。 */
    fun deleteSeries(seriesId: String) {
        prefs.all.forEach { (k, v) ->
            if (v is String && k != "series_meta:$seriesId") {
                runCatching { json.decodeFromString<KomgaDownloadEntry>(v) }
                    .getOrNull()
                    ?.takeIf { it.seriesId == seriesId && it.status == "completed" }
                    ?.let { entry ->
                        entry.path.let { p -> runCatching { File(p).delete() } }
                        prefs.edit { remove(k) }
                    }
            }
        }
        val meta = getSeriesMeta(seriesId)
        meta?.coverPath?.let { runCatching { File(it).delete() } }
        runCatching { File(appContext.getExternalFilesDir(null), "komga/${sanitizeSeries(meta?.seriesName ?: seriesId)}").deleteRecursively() }
        runCatching { File(appContext.getExternalFilesDir(null), "komga/_covers/$seriesId.jpg").delete() }
        prefs.edit { remove("series_meta:$seriesId") }
    }

    /** 删 SP 索引 + 本地 CBZ 文件。若该 seriesId 下已无其他已下载书，连带清理系列目录与封面。返回是否成功删除文件。 */
    fun deleteWithFile(bookId: String): Boolean {
        val entry = getStatus(bookId)
        val fileDeleted = entry?.path?.let { p -> runCatching { File(p).delete() }.getOrDefault(false) } ?: true
        remove(bookId)
        // 若系列已无其它已下载书，清理系列目录与共享封面（封面是系列级，不能在有其它书时删）。
        val sid = entry?.seriesId
        if (!sid.isNullOrBlank()) {
            val remaining = allDownloaded().values.any { it.seriesId == sid }
            if (!remaining) {
                val safeSeries = sanitizeSeries(entry.seriesName)
                runCatching { File(appContext.getExternalFilesDir(null), "komga/$safeSeries").deleteRecursively() }
                runCatching { File(appContext.getExternalFilesDir(null), "komga/_covers/$sid.jpg").delete() }
                prefs.edit { remove("series_meta:$sid") }
            }
        }
        return fileDeleted
    }

    private fun sanitizeSeries(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "series" }

    private fun put(bookId: String, entry: KomgaDownloadEntry) {
        prefs.edit { putString(bookId, json.encodeToString(entry)) }
    }
}
