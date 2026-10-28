package app.mihonsy.komga.ui

import android.content.Context
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaDbBridge
import app.mihonsy.komga.data.download.KomgaDownloadStore
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.source.KomgaSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho V2: opens a Komga book in the native MihonSY reader.
 *
 * All v1 UI (Main / Series / Readlist / SectionList) used to start the
 * self-written KomgaReaderActivity with a bare bookId. In V2 we resolve the
 * book -> series -> manga/chapters into the local DB (KomgaDbBridge) and
 * launch the native MihonSY ReaderActivity instead.
 */
object KomgaReaderLauncher {

    /**
     * 已下载的书补查服务器进度的上限。超时就按本地进度启动 —— 离线优先的打开路径
     * 不能因为这一次补查变慢（断网时连接失败可能拖几秒）。
     */
    private const val PROGRESS_PROBE_TIMEOUT_MS = 1_500L

    /**
     * Opens [bookId] in the native reader. Must be called from a coroutine.
     * Throws on failure so callers can surface a toast.
     *
     * 离线优先：若本书已下载（[KomgaDownloadStore.isDownloaded]），则完全不
     * 触碰网络——直接从本地 DB 反查 chapter（url = komga://book/{id}）→ manga
     * 并启动阅读器。这样断网时也能读已下载 CBZ，而非卡在联网解析阶段报
     * "无法连接服务器"。
     *
     * 起始页：Komga 服务器上的 readProgress 才是唯一的跨端真源（Web 端 / 其它设备读过的
     * 进度本机 DB 里没有）。以前这里只带 chapter id 启动，阅读器回退到本地的
     * `last_page_read`（=0），于是「章节列表里明明 13%，点进去却从第 1 页开始」，
     * 而且第一页落库后还会把服务器进度覆盖成 0。现在按 [resumePageOf] 算出的页号显式
     * 传给阅读器，并回写本地。
     */
    suspend fun open(context: Context, client: KomgaApiClient, bookId: String) {
        val bookUrl = KomgaSource.BOOK_URL_PREFIX + bookId
        val updateChapter: UpdateChapter = Injekt.get()

        // 离线优先：已下载则纯本地启动，跳过所有网络请求。
        // 阅读方向不在这里解析 —— 它在同步/下载期就已由 ensureManga 落库（见 [KomgaDbBridge.ensureManga]），
        // 离线读到的就是那个值。
        if (KomgaDownloadStore(context).isDownloaded(bookId)) {
            val chapterRepository: ChapterRepository = Injekt.get()
            val getManga: GetManga = Injekt.get()
            val chapter = chapterRepository.getChapterByUrl(bookUrl).firstOrNull()
                ?: throw IllegalStateException("章节未同步(本地)")
            val manga = getManga.await(chapter.mangaId)
                ?: throw IllegalStateException("漫画未同步(本地)")
            val localPage = chapter.lastPageRead.toInt()
            // 本地零进度（换机 / 重装 / 只在 Web 端读过）时补查一次服务器进度；限时、失败静默。
            val page = resumePageOf(
                book = if (localPage == 0) probeBook(client, bookId) else null,
                localPage = localPage,
            )
            persistPage(updateChapter, chapter.id, localPage, page)
            startReader(context, manga.id, chapter.id, page, manga.url.removePrefix(KomgaSource.SERIES_URL_PREFIX))
            return
        }

        // 未下载：走原联网流程解析 book -> series -> manga/chapters
        val book = client.getBook(bookId)
        val seriesId = book.seriesId ?: throw IllegalStateException("书缺少 seriesId")
        val series = client.getSeriesDetail(seriesId)
        // 交给 ensureManga 一次性完成：阅读方向 → 阅读模式（LTR/RTL/VERTICAL/WEBTOON），
        // genres + tags → ogGenre（标签里的 webtoon/long strip 让阅读器**打开前**就定好条漫模式）。
        // 放这里而不是各调用点，是为了保证下载 / 首页卡片 / 打开阅读这三条入口口径一致。
        val manga = KomgaDbBridge.ensureManga(client, series.id, series.name, series.metadata)
        val chapters = KomgaDbBridge.ensureChapters(client, series.id, manga.id)
        val chapter = chapters.firstOrNull { it.url == bookUrl }
            ?: throw IllegalStateException("章节未同步")
        val localPage = chapter.lastPageRead.toInt()
        val page = resumePageOf(book, localPage)
        persistPage(updateChapter, chapter.id, localPage, page)
        startReader(context, manga.id, chapter.id, page, seriesId)
    }

    /**
     * 服务器进度换算成阅读器起始页（0-based）。
     *
     * 返回 null = 不干预起始页（沿用旧行为：`page ?: last_page_read` 交给 ChapterLoader
     * 的「已读章节从第一页」守卫）。只在服务器**有未读完进度**时返回页号，理由：
     * 已读完的书点开时保持从第一页看起，是 Mihon 侧一贯的行为，不该被本次修复改掉。
     *
     * 取 `max(本地, 服务器)`：本机离线读得比服务器记录的更靠后时（还没来得及上报）不倒退回服务器页。
     */
    private fun resumePageOf(book: BookDto?, localPage: Int): Int? {
        val progress = book?.readProgress ?: return null
        if (progress.completed || progress.page <= 0) return null
        val pagesCount = book.media.pagesCount
        val serverPage = (progress.page - 1)
            .coerceAtLeast(0)
            .let { if (pagesCount > 0) it.coerceAtMost(pagesCount - 1) else it }
        return maxOf(localPage, serverPage)
    }

    /** 限时补查书本详情（只为拿 readProgress）；拿不到就当没有，按本地进度走。 */
    private suspend fun probeBook(client: KomgaApiClient, bookId: String): BookDto? = runCatching {
        withTimeoutOrNull(PROGRESS_PROBE_TIMEOUT_MS) { client.getBook(bookId) }
    }.getOrNull()

    /** 把服务器进度落到本地 chapter，退出阅读器后章节列表 / 历史 / 续读口径一致。 */
    private suspend fun persistPage(
        updateChapter: UpdateChapter,
        chapterId: Long,
        localPage: Int,
        page: Int?,
    ) {
        if (page != null && page > localPage) {
            updateChapter.await(ChapterUpdate(id = chapterId, lastPageRead = page.toLong()))
        }
    }

    private fun startReader(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        page: Int?,
        seriesId: String,
    ) {
        context.startActivity(
            ReaderActivity.newIntent(context, mangaId, chapterId, page)
                // Komiho: 退出章节时回该系列详情页
                .putExtra(ReaderActivity.EXTRA_KOMGA_SERIES_ID, seriesId),
        )
    }
}
