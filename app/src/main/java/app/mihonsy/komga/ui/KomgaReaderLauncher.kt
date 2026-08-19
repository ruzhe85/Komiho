package app.mihonsy.komga.ui

import android.content.Context
import android.content.Intent
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaDbBridge
import app.mihonsy.komga.source.KomgaSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity

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
     */
    suspend fun open(context: Context, client: KomgaApiClient, bookId: String) {
        val book = client.getBook(bookId)
        val seriesId = book.seriesId ?: throw IllegalStateException("书缺少 seriesId")
        val series = client.getSeriesDetail(seriesId)
        val manga = KomgaDbBridge.ensureManga(client, series.id, series.name)
        val chapters = KomgaDbBridge.ensureChapters(client, series.id, manga.id)
        val chapter = chapters.firstOrNull {
            it.url == KomgaSource.BOOK_URL_PREFIX + book.id
        } ?: throw IllegalStateException("章节未同步")
        context.startActivity(
            Intent(context, ReaderActivity::class.java)
                .putExtra("manga", manga.id)
                .putExtra("chapter", chapter.id),
        )
    }
}
