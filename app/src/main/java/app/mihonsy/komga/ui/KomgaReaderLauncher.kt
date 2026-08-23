package app.mihonsy.komga.ui

import android.content.Context
import android.content.Intent
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaDbBridge
import app.mihonsy.komga.data.download.KomgaDownloadStore
import app.mihonsy.komga.source.KomgaSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
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
     * Opens [bookId] in the native reader. Must be called from a coroutine.
     * Throws on failure so callers can surface a toast.
     *
     * 离线优先：若本书已下载（[KomgaDownloadStore.isDownloaded]），则完全不
     * 触碰网络——直接从本地 DB 反查 chapter（url = komga://book/{id}）→ manga
     * 并启动阅读器。这样断网时也能读已下载 CBZ，而非卡在联网解析阶段报
     * "无法连接服务器"。
     */
    suspend fun open(context: Context, client: KomgaApiClient, bookId: String) {
        val bookUrl = KomgaSource.BOOK_URL_PREFIX + bookId

        // 离线优先：已下载则纯本地启动，跳过所有网络请求
        if (KomgaDownloadStore(context).isDownloaded(bookId)) {
            val chapterRepository: ChapterRepository = Injekt.get()
            val getManga: GetManga = Injekt.get()
            val chapter = chapterRepository.getChapterByUrl(bookUrl).firstOrNull()
                ?: throw IllegalStateException("章节未同步(本地)")
            val manga = getManga.await(chapter.mangaId)
                ?: throw IllegalStateException("漫画未同步(本地)")
            context.startActivity(
                Intent(context, ReaderActivity::class.java)
                    .putExtra("manga", manga.id)
                    .putExtra("chapter", chapter.id),
            )
            return
        }

        // 未下载：走原联网流程解析 book -> series -> manga/chapters
        val book = client.getBook(bookId)
        val seriesId = book.seriesId ?: throw IllegalStateException("书缺少 seriesId")
        val series = client.getSeriesDetail(seriesId)
        val manga = KomgaDbBridge.ensureManga(client, series.id, series.name)
        // Auto reading mode from Komga series metadata (LTR/RTL/VERTICAL→webtoon)
        KomgaDbBridge.applyReadingMode(manga, series.metadata.readingDirection)
        val chapters = KomgaDbBridge.ensureChapters(client, series.id, manga.id)
        val chapter = chapters.firstOrNull { it.url == bookUrl }
            ?: throw IllegalStateException("章节未同步")
        context.startActivity(
            Intent(context, ReaderActivity::class.java)
                .putExtra("manga", manga.id)
                .putExtra("chapter", chapter.id),
        )
    }
}
