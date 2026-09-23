package app.mihonsy.komga.data

import app.mihonsy.komga.source.KomgaSource
import app.mihonsy.komga.data.model.SeriesMetadataDto
import app.mihonsy.komga.data.model.combinedTags
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
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

    /**
     * Returns the DB manga for a Komga series, inserting it on first visit.
     *
     * [metadata] 是 Komga series metadata，用来一次带出两样东西：
     *  - 阅读方向 → 顺带落库（见 [applyReadingMode]）。**必须在同步期写**：已下载的书走离线优先的
     *    打开路径（不联网、拿不到 metadata），只能靠这里写过的值，否则会退回「按图片比例自动判定」。
     *  - `genres + tags` → 落进 ogGenre。`Manga.mangaType()` 靠标签里的 webtoon / long strip 判条漫，
     *    而这条判断在**打开阅读器之前**就生效（优先于按比例探测）。
     */
    suspend fun ensureManga(
        client: KomgaApiClient,
        seriesId: String,
        seriesName: String,
        metadata: SeriesMetadataDto? = null,
    ): Manga {
        val url = KomgaSource.SERIES_URL_PREFIX + seriesId
        // Komga 系列缩略图 URL：聚合页 Komga 卡片封面、历史行的封面都依赖它
        // （history 查询 join mangas.thumbnail_url = ogThumbnailUrl）。
        val thumb = client.seriesThumbnailUrl(seriesId)
        val genres = metadata?.combinedTags().orEmpty()
        val existing = mangaRepository.getMangaByUrlAndSourceId(url, KomgaSource.ID)
        val manga = if (existing != null) {
            // 补全：早期插入的记录既没有封面也没有标签（ogThumbnailUrl / ogGenre 为 null）。
            // 标签必须补 —— 缺了就只能退回按图片比例探测（晚一两页才切模式）。
            val needThumb = existing.thumbnailUrl.isNullOrBlank()
            val needGenre = genres.isNotEmpty() && existing.ogGenre.isNullOrEmpty()
            if (needThumb || needGenre) {
                mangaRepository.update(
                    MangaUpdate(
                        id = existing.id,
                        thumbnailUrl = thumb.takeIf { needThumb },
                        genre = genres.takeIf { needGenre },
                    ),
                )
                mangaRepository.getMangaByUrlAndSourceId(url, KomgaSource.ID) ?: existing
            } else {
                existing
            }
        } else {
            mangaRepository.insertNetworkManga(
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
                        ogThumbnailUrl = thumb,
                        ogDescription = null,
                        ogGenre = genres.ifEmpty { null },
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
        metadata?.readingDirection?.let { applyReadingMode(manga, it) }
        return manga
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

    /**
     * Applies the Komga series reading direction to the manga's per-manga
     * reading mode, so the native reader opens with the matching mode —
     * no aspect-ratio auto-detection needed (that path only kicks in as a
     * fallback and switches mode a page or two late).
     *
     * Komga ReadingDirection values: LEFT_TO_RIGHT / RIGHT_TO_LEFT / VERTICAL / WEBTOON.
     * Only applies when the user hasn't set a custom per-manga mode yet
     * (viewerFlags ReadingMode bits == 0).
     */
    suspend fun applyReadingMode(manga: Manga, readingDirection: String?) {
        val mode = when (readingDirection) {
            "LEFT_TO_RIGHT" -> ReadingMode.LEFT_TO_RIGHT
            "RIGHT_TO_LEFT" -> ReadingMode.RIGHT_TO_LEFT
            "VERTICAL", "WEBTOON" -> ReadingMode.WEBTOON // Komga vertical/webtoon = continuous scroll
            else -> return
        }
        val currentMode = manga.viewerFlags and ReadingMode.MASK.toLong()
        if (currentMode == 0L) {
            mangaRepository.update(MangaUpdate(id = manga.id, viewerFlags = mode.flagValue.toLong()))
        }
    }
}
