package eu.kanade.tachiyomi.ui.reader.loader

import androidx.annotation.CallSuper
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    /**
     * Whether this loader has been already recycled.
     */
    var isRecycled = false
        private set

    abstract var isLocal: Boolean

    /**
     * 非流化缓存进度回调（值域 [0f, 1f]）。
     *
     * 仅在「整本下载」路径（远程 PDF 整本落盘、WebDAV rar/7z 强制回退、WebDAV 不支持 Range
     * 时的整本下载）由加载器周期性调用。流式来源（Komga / 散图目录 / CBZ Range 直读）不调用，
     * 此时阅读器保持转圈。由 [ChapterLoader] 在加载章节时挂接到 ViewModel 的进度通道。
     */
    var progressReporter: ((Float) -> Unit)? = null

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /**
     * Loads the page. May also preload other pages.
     * Progress of the page loading should be followed via [page.statusFlow].
     * [loadPage] is not currently guaranteed to complete, so it should be launched asynchronously.
     */
    open suspend fun loadPage(page: ReaderPage) {}

    /**
     * Retries the given [page] in case it failed to load. This method only makes sense when an
     * online source is used.
     */
    open fun retryPage(page: ReaderPage) {}

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    open fun recycle() {
        isRecycled = true
    }
}
