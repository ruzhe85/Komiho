package app.mihonsy.komga.data

import app.mihonsy.komga.source.KomgaSource
import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho V2 (R-1): writes Komga data into Mihon's local DB so the native
 * MihonSY reader can consume it (reader resolves manga/chapter by DB ids).
 *
 * The DB is only a cache/adaptation layer — Komga remains the source of truth.
 */
object KomgaDbBridge {

    private val mangaRepository: MangaRepository by lazy { Injekt.get() }
    private val chapterRepository: ChapterRepository by lazy { Injekt.get() }

    /** Returns the DB manga for a Komga series, inserting it on first visit. */
    suspend fun ensureManga(client: KomgaApiClient, seriesId: String, seriesName: String): Manga {
        val url = KomgaSource.SERIES_URL_PREFIX + seriesId
        mangaRepository.getMangaByUrlAndSourceId(url, KomgaSource.ID)?.let { return it }
        return mangaRepository.insertNetworkManga(
            listOf(
                Manga(
                    id = 0,
                    source = KomgaSource.ID,
                    favorite = false,
                    lastUpdate = 0,
                    nextUpdate = 0,
                    fetchInterval = -1,
                    dateAdded = System.currentTimeMillis(),
                    viewerFlags = 0,
                    chapterFlags = 0,
                    coverLastModified = 0,
                    url = url,
                    ogTitle = seriesName,
                    ogArtist = null,
                    ogAuthor = null,
                    ogThumbnailUrl = null,
                    ogDescription = null,
                    ogGenre = null,
                    ogStatus = 0,
                    updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                    initialized = true,
                    lastModifiedAt = 0,
                    favoriteModifiedAt = null,
                    version = 1,
                    notes = "",
                    memo = JsonObject.EMPTY,
                ),
            ),
        ).first()
    }

    /**
     * Inserts all books of a series as chapters of [mangaId] (incremental —
     * already-present books are skipped). Returns the full chapter list.
     */
    suspend fun ensureChapters(
        client: KomgaApiClient,
        seriesId: String,
        mangaId: Long,
    ): List<Chapter> {
        val books = client.getSeriesBooks(seriesId, size = 500).content
        val existing = chapterRepository.getChapterByMangaId(mangaId).map { it.url }.toSet()
        val toAdd = books
            .filter { (KomgaSource.BOOK_URL_PREFIX + it.id) !in existing }
            .sortedBy { it.number ?: Int.MAX_VALUE }
            .mapIndexed { i, book ->
                Chapter.create().copy(
                    mangaId = mangaId,
                    url = KomgaSource.BOOK_URL_PREFIX + book.id,
                    name = book.name,
                    chapterNumber = (book.number ?: i + 1).toDouble(),
                    dateFetch = System.currentTimeMillis(),
                )
            }
        if (toAdd.isNotEmpty()) {
            chapterRepository.addAll(toAdd)
        }
        return chapterRepository.getChapterByMangaId(mangaId)
    }
}
