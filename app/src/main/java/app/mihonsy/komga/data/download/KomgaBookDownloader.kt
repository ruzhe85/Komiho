package app.mihonsy.komga.data.download

import android.content.Context
import android.util.Log
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaDbBridge
import app.mihonsy.komga.data.model.BookDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
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
    fun downloadBook(
        bookId: String,
        seriesName: String,
        seriesId: String = "",
        bookName: String = "",
        number: Int = 0,
        coverUrl: String? = null,
    ): Flow<KomgaDownloadEvent> = callbackFlow {
        store.markQueued(bookId)
        send(KomgaDownloadEvent.Queued(bookId))

        val safeSeries = sanitize(seriesName)
        val dir = File(context.getExternalFilesDir(null), "komga/$safeSeries")
        dir.mkdirs()
        // 文件名用补零序号前缀（001_002…），离线可读；后缀带 bookId 防重。
        val fileName = if (number > 0) "${String.format("%03d", number)}_$bookId.cbz" else "$bookId.cbz"
        val target = File(dir, fileName)
        // 单本下载也顺手缓存系列封面（离线显示真实封面）。
        if (!seriesId.isBlank() && coverUrl != null) {
            runCatching {
                val coverDir = File(context.getExternalFilesDir(null), "komga/_covers")
                coverDir.mkdirs()
                val coverFile = File(coverDir, "$seriesId.jpg")
                if (!coverFile.exists()) {
                    val bytes = client.downloadBytes(coverUrl)
                    if (bytes.isNotEmpty()) coverFile.writeBytes(bytes)
                }
            }.onFailure { Log.w("KomgaDL", "单本封面缓存失败：${it.message}") }
        }

        try {
            client.getBookFile(bookId) { resp ->
                Log.d("KomgaDL", "resp code=${resp.code} type=${resp.body?.contentType()} len=${resp.body?.contentLength()} url=${resp.request.url}")
                val body = resp.body ?: run {
                    Log.e("KomgaDL", "body null, code=${resp.code}")
                    send(KomgaDownloadEvent.Error(bookId, "响应体为空(code=${resp.code})"))
                    return@getBookFile
                }
                // 注意：本项目 OkHttp 配置下 resp.body.source()/byteStream() 会返回空流
                // （见 KomgaApiClient.downloadBytes 的同款修复注释：source()?.buffer()?.readByteArray()
                // 会返回空，必须用 bytes()）。此前的 byteStream() 底层仍是 source()，等同于空流 →
                // 循环立刻 EOF → 秒"完成"但 0 字节。这里改用 body.bytes()（downloadBytes 验证可用），
                // 拿到完整字节数组后分块写入文件并回传进度。
                val data = body.bytes()
                val total = data.size.toLong()
                var done = 0L
                target.outputStream().use { out ->
                    val bufSize = 64 * 1024
                    var offset = 0
                    var lastEmit = 0L
                    while (offset < data.size) {
                        val n = minOf(bufSize, data.size - offset)
                        out.write(data, offset, n)
                        offset += n
                        done = offset.toLong()
                        if (done - lastEmit >= 512 * 1024L || done >= total) {
                            send(KomgaDownloadEvent.Progress(bookId, total, done))
                            lastEmit = done
                        }
                    }
                }
                Log.d("KomgaDL", "written bytes=$done path=${target.absolutePath} exists=${target.exists()} size=${target.length()}")
                // 防呆：0 字节绝不标 Done，避免"秒完成实际没下载"的假象
                if (done == 0L || !target.exists() || target.length() == 0L) {
                    if (target.exists()) target.delete()
                    store.remove(bookId)
                    Log.e("KomgaDL", "下载结果为空，标 Error (code=${resp.code}, type=${body.contentType()})")
                    send(KomgaDownloadEvent.Error(bookId, "下载结果为空(code=${resp.code})"))
                    return@getBookFile
                }
                store.markCompleted(bookId, target.absolutePath, seriesName, seriesId, bookName, number)
                // 同步 series 进 DB，保证离线打开可反查（下载期联网，成本低）。
                syncSeriesToDb(seriesId, seriesName)
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

    /**
     * 下载期间（本就联网）把 series 同步进本地 DB，使离线打开能反查 chapter/manga。
     * 仅 seriesId 非空时执行；失败不阻断下载（离线打开失败属于次要问题）。
     */
    private suspend fun syncSeriesToDb(seriesId: String, seriesName: String) {
        if (seriesId.isBlank()) return
        runCatching {
            val manga = KomgaDbBridge.ensureManga(client, seriesId, seriesName)
            KomgaDbBridge.ensureChapters(client, seriesId, manga.id)
        }.onFailure { Log.w("KomgaDL", "series 同步 DB 失败：${it.message}") }
    }

    /**
     * 下载整个系列：先存系列封面到本地，再按顺序逐本下载（串行）。
     * 汇总进度通过 [KomgaDownloadEvent.SeriesProgress] 上报。
     * [books] 必须已按系列内顺序排好（调用方负责）。
     */
    fun downloadSeries(
        seriesId: String,
        seriesName: String,
        coverUrl: String,
        books: List<BookDto>,
    ): Flow<KomgaDownloadEvent> = callbackFlow {
        val safeSeries = sanitize(seriesName)
        val coverDir = File(context.getExternalFilesDir(null), "komga/_covers")
        coverDir.mkdirs()
        val coverFile = File(coverDir, "$seriesId.jpg")
        // 下载并缓存系列封面（失败不影响书本下载）。
        runCatching {
            val bytes = client.downloadBytes(coverUrl)
            if (bytes.isNotEmpty()) coverFile.writeBytes(bytes)
        }.onFailure { Log.w("KomgaDL", "封面下载失败：${it.message}") }
        store.putSeriesMeta(
            KomgaSeriesMeta(
                seriesId = seriesId,
                seriesName = seriesName,
                coverPath = if (coverFile.exists()) coverFile.absolutePath else "",
                totalBooks = books.size,
            ),
        )
        var done = 0
        books.forEach { book ->
            downloadBook(
                book.id,
                seriesName,
                seriesId,
                bookName = book.name,
                number = book.number ?: 0,
                coverUrl = coverUrl,
            ).collect { ev ->
                when (ev) {
                    is KomgaDownloadEvent.Completed -> {
                        done++
                        send(KomgaDownloadEvent.SeriesProgress(seriesId, done, books.size))
                    }
                    is KomgaDownloadEvent.Error -> send(KomgaDownloadEvent.SeriesProgress(seriesId, done, books.size, lastError = ev.message))
                    else -> send(ev) // Queued / Progress 透传
                }
            }
        }
        send(KomgaDownloadEvent.SeriesDone(seriesId, done, books.size))
        close()
    }
}

sealed interface KomgaDownloadEvent {
    val bookId: String

    data class Queued(override val bookId: String) : KomgaDownloadEvent
    data class Progress(override val bookId: String, val total: Long, val done: Long) : KomgaDownloadEvent
    data class Completed(override val bookId: String, val path: String = "") : KomgaDownloadEvent
    data class Error(override val bookId: String, val message: String) : KomgaDownloadEvent
    data class Canceled(override val bookId: String) : KomgaDownloadEvent
    // 系列级汇总进度
    data class SeriesProgress(
        val seriesId: String,
        val done: Int,
        val total: Int,
        val lastError: String? = null,
    ) : KomgaDownloadEvent {
        override val bookId: String get() = seriesId
    }
    data class SeriesDone(
        val seriesId: String,
        val done: Int,
        val total: Int,
    ) : KomgaDownloadEvent {
        override val bookId: String get() = seriesId
    }
}

/** UI 层使用的下载状态（由 downloadBook Flow + KomgaDownloadStore 推算）。 */
enum class DownloadUiState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, ERROR }
