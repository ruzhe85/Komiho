package eu.kanade.tachiyomi.data.coil

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
import okio.buffer
import okio.source
import java.io.IOException

/**
 * 进度条缩略图：复用章节 [PageLoader] 加载某一页，返回其图像流。
 *
 * 用于 Komga 之外的来源（本地 / SMB / WebDAV / 远程 HttpSource 等）——这些来源没有服务端
 * 缩图接口，直接用页面原图（缩显示）当作缩略图。
 *
 * 关键约束：[ReaderPage.stream] 是「每次调用返回新 InputStream」的 lambda，因此这里取一份
 * 独立流交给 Coil，不会消耗 reader 自身渲染用的流（reader 在渲染该页时会再次调用 stream()）。
 */
class ReaderPageThumbnailFetcher(
    private val pageLoader: PageLoader,
    private val readerPage: ReaderPage,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
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
        val source = input.source().buffer()
        return SourceFetchResult(
            source = ImageSource(
                source = source,
                fileSystem = options.fileSystem,
            ),
            mimeType = "image/*",
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory : Fetcher.Factory<ReaderPageThumbnailRequest> {
        override fun create(
            data: ReaderPageThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return ReaderPageThumbnailFetcher(data.pageLoader, data.readerPage, options)
        }
    }
}

/**
 * 进度条缩略图的 Coil 模型：携带章节 [PageLoader] 与目标 [ReaderPage]。
 * [key] 用于 Coil 内存缓存键（按 章节+页码 唯一），避免跨章同名页串图。
 */
data class ReaderPageThumbnailRequest(
    val key: String,
    val pageLoader: PageLoader,
    val readerPage: ReaderPage,
)
