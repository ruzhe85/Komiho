package app.mihonsy.komga.data.download

import android.content.Context
import app.mihonsy.komga.data.KomgaApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.Buffer
import okio.sink
import java.io.File

/**
 * Komga 单本下载器（MVP）。
 *
 * 参考 Komelia BookDownloadService，但用 Komga 模型、不引入离线元数据库：
 * KomihoV2 联网为主，下载 = 预缓存整本 CBZ 以便离线阅读。
 *
 * 下载单元 = Book（本）；下载方式 = GET /books/{id}/file 整本 CBZ 流式落盘。
 */
class KomgaBookDownloader(
    private val context: Context,
    private val client: KomgaApiClient,
    private val store: KomgaDownloadStore,
) {
    fun downloadBook(bookId: String, seriesName: String): Flow<KomgaDownloadEvent> = flow {
        emit(KomgaDownloadEvent.Queued(bookId))
        store.markQueued(bookId)

        val safeSeries = sanitize(seriesName)
        val dir = File(context.getExternalFilesDir(null), "komga/$safeSeries")
        dir.mkdirs()
        val target = File(dir, "$bookId.cbz")

        try {
            client.getBookFile(bookId) { resp ->
                val total = resp.headers["Content-Length"]?.toLongOrNull() ?: 0L
                target.sink().use { sink ->
                    val src = resp.body?.source() ?: return@getBookFile
                    val buf = Buffer()
                    var done = 0L
                    var lastEmit = 0L
                    while (true) {
                        val r = src.read(buf, 64 * 1024L)
                        if (r == -1L) break
                        sink.write(buf, buf.size)
                        done += r
                        if (done - lastEmit >= 512 * 1024L || (total > 0L && done >= total)) {
                            emit(KomgaDownloadEvent.Progress(bookId, total, done))
                            lastEmit = done
                        }
                    }
                }
                store.markCompleted(bookId, target.absolutePath)
                emit(KomgaDownloadEvent.Completed(bookId, target.absolutePath))
            }
        } catch (e: Exception) {
            if (target.exists()) target.delete()
            store.remove(bookId)
            emit(KomgaDownloadEvent.Error(bookId, e.message ?: e.toString()))
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "series" }
}

sealed interface KomgaDownloadEvent {
    val bookId: String

    data class Queued(override val bookId: String) : KomgaDownloadEvent
    data class Progress(override val bookId: String, val total: Long, val done: Long) : KomgaDownloadEvent
    data class Completed(override val bookId: String, val path: String = "") : KomgaDownloadEvent
    data class Error(override val bookId: String, val message: String) : KomgaDownloadEvent
    data class Canceled(override val bookId: String) : KomgaDownloadEvent
}

/** UI 层使用的下载状态（由 downloadBook Flow + KomgaDownloadStore 推算）。 */
enum class DownloadUiState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, ERROR }
