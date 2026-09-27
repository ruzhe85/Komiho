package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

/**
 * 进度条缩略图：复用章节 [PageLoader] 加载某一页，子采样解码为小尺寸 JPEG 后交给 Coil。
 *
 * 用于 Komga 之外的来源（本地 / SMB / WebDAV / 远程 HttpSource 等）——这些来源没有服务端
 * 缩图接口，本取图器自行做「两次 decode」子采样（与 LocalCoverFetcher.decodeSampled 同策略）：
 * 先 inJustDecodeBounds 探尺寸 → 算 inSampleSize（目标 ~THUMB_PX）→ 再按采样比解码，
 * 保证**绝不先解整张原图再缩小**（原图常 1000–3000px，整解会卡顿、GC 抖动）。
 *
 * 另设进程内 LRU（按 章节+页码 唯一 key），重复拖动经过同一页直接命中、不再重解/重拉。
 *
 * [ReaderPage.stream] 每次调用返回新 InputStream，故可安全重开两次；为避免 SMB/WebDAV
 * 双次拉网，先把流整段读入内存一次，再两次解码（开销仅在拖动首生成时发生）。
 */
class ReaderPageThumbnailFetcher(
    private val pageLoader: PageLoader,
    private val readerPage: ReaderPage,
    private val cacheKey: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // 进程内 LRU 命中：直接回放已压缩的小图字节，零解码、零拉取。
        ThumbnailCache[cacheKey]?.let { bytes ->
            return bytesResult(bytes)
        }

        // 触发加载（fire-and-forget）：
        // - 本地 / SMB / WebDAV 的页在 getPages() 阶段已是 Ready，loadPage 是 no-op，立即返回；
        // - 远程 HttpSource 的页初始为 Queue，loadPage 会入队异步加载；
        // 注意 HttpPageLoader.loadPage 对「已 Ready」页会挂起（其 continuation 不在本处 resume），
        // 故只用 detached scope fire-and-forget，真正等待交给下方 statusFlow，绝不 await loadPage 本身。
        val trigger = if (readerPage.status !is Page.State.Ready) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { pageLoader.loadPage(readerPage) }
            }
        } else {
            null
        }
        try {
            // 等到终态（已就绪则立即返回，不会挂起）。
            readerPage.statusFlow.first { it is Page.State.Ready || it is Page.State.Error }
        } finally {
            trigger?.cancel()
        }

        if (readerPage.status !is Page.State.Ready) {
            throw IOException("page ${readerPage.index} failed to load for thumbnail")
        }

        val input = readerPage.stream?.invoke()
            ?: throw IOException("page ${readerPage.index} stream unavailable for thumbnail")
        // 先把整段读入内存（避免 SMB/WebDAV 双次拉网），再子采样解码。
        val raw = input.use { it.readBytes() }
        val bitmap = decodeSampled({ ByteArrayInputStream(raw) }, THUMB_PX)
            ?: throw IOException("page ${readerPage.index} decode failed for thumbnail")
        val bytes = runCatching {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }.getOrNull().also { bitmap.recycle() }
            ?: throw IOException("page ${readerPage.index} compress failed for thumbnail")

        // 存入进程内 LRU 供重复拖动命中。
        ThumbnailCache.put(cacheKey, bytes)

        return bytesResult(bytes)
    }

    /** 把已压缩的小图字节包成 SourceFetchResult（标注 MEMORY，避免 Coil 落盘缓存）。 */
    private fun bytesResult(bytes: ByteArray): SourceFetchResult {
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(bytes),
                fileSystem = options.fileSystem,
            ),
            mimeType = "image/jpeg",
            dataSource = DataSource.MEMORY,
        )
    }

    /** 两次 decode：先读边界算 inSampleSize，再采样解码；open 提供可重开的输入流。 */
    private fun decodeSampled(open: () -> InputStream?, maxPx: Int): Bitmap? {
        open()?.use { first ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(first, null, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            val sample = max(1, max(opts.outWidth, opts.outHeight) / maxPx)
            open()?.use { second ->
                val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
                return BitmapFactory.decodeStream(second, null, opts2)
            }
        }
        return null
    }

    class Factory : Fetcher.Factory<ReaderPageThumbnailRequest> {
        override fun create(
            data: ReaderPageThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return ReaderPageThumbnailFetcher(data.pageLoader, data.readerPage, data.key, options)
        }
    }
}

/**
 * 进程内缩略图 LRU：key = 章节+页码（[ReaderPageThumbnailRequest.key]），value = 压缩后的小图字节。
 * 上限 ~8MB：单张缩图（THUMB_PX=480, q85）约 30–80KB，可容纳上百张；LRU 淘汰最旧。
 */
private object ThumbnailCache {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val cache = object : LruCache<String, ByteArray>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    operator fun get(key: String): ByteArray? = cache.get(key)
    fun put(key: String, bytes: ByteArray) = cache.put(key, bytes)
}

/**
 * 进度条缩略图的 Coil 模型：携带章节 [PageLoader] 与目标 [ReaderPage]。
 * [key] 用于进程内 LRU 与 Coil 内存缓存键（按 章节+页码 唯一），避免跨章同名页串图。
 */
data class ReaderPageThumbnailRequest(
    val key: String,
    val pageLoader: PageLoader,
    val readerPage: ReaderPage,
)

// SY --> Komiho: 进度气泡缩略图子采样相关常量
private const val THUMB_PX = 480
private const val JPEG_QUALITY = 85
// SY <--
