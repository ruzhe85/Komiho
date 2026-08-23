package app.mihonsy.komga.data.download

import android.content.Context
import app.mihonsy.komga.data.KomgaApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
 *
 * 用 callbackFlow 而非 flow{}：进度 emit 发生在 getBookFile 的 Dispatchers.IO 内部，
 * 若用 flow{} 直接 emit 会触发 "Flow invariant is violated"（emit 与 collect 上下文不一致）。
 * callbackFlow 的 send 跨上下文安全，由 channel 负责上下文切换。
 */
class KomgaBookDownloader(
    private val context: Context,
    private val client: KomgaApiClient,
    private val store: KomgaDownloadStore,
) {
    fun downloadBook(bookId: String, seriesName: String): Flow<KomgaDownloadEvent> = callbackFlow {
        store.markQueued(bookId)
        send(KomgaDownloadEvent.Queued(bookId))

        val safeSeries = sanitize(seriesName)
        val dir = File(context.getExternalFilesDir(null), "komga/$safeSeries")
        dir.mkdirs()
        val target = File(dir, "$bookId.cbz")

        try {
            client.getBookFile(bookId) { resp ->
                val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: 0L
                target.sink().use { sink ->
                    val src = resp.body?.source() ?: return@getBookFile
                    val buf = Buffer()
                    var done = 0L
                    var lastEmit = 0L
                    while (true) {
                        val r = src.read(buf, 64 * 1024L)
                        if (r == -1L) break
                        sink.write(buf, r)
                        done += r
                        if (done - lastEmit >= 512 * 1024L || (total > 0L && done >= total)) {
                            send(KomgaDownloadEvent.Progress(bookId, total, done))
                            lastEmit = done
                        }
                    }
                }
                store.markCompleted(bookId, target.absolutePath)
                send(KomgaDownloadEvent.Completed(bookId, target.absolutePath))
            }
        } catch (e: Exception) {
            // 取消不应被当作下载失败（否则会误删残文件 + 报 Error）
            if (e is CancellationException) throw e
            if (target.exists()) target.delete()
            store.remove(bookId)
            send(KomgaDownloadEvent.Error(bookId, e.message ?: e.toString()))
        }
        close()
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
