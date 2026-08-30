package tachiyomi.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.chapter.ChapterMapper.mapChapter
import tachiyomi.data.subscribeToList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.LocalBookmarkItem
import tachiyomi.domain.chapter.repository.ChapterRepository

class ChapterRepositoryImpl(
    private val database: Database,
) : ChapterRepository {

    override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
        return try {
            database.transactionWithResult {
                chapters.map { chapter ->
                    val chapterId = database.chaptersQueries.insertReturningId(
                        chapter.mangaId,
                        chapter.url,
                        chapter.name,
                        chapter.scanlator,
                        chapter.read,
                        chapter.bookmark,
                        chapter.lastPageRead,
                        chapter.chapterNumber,
                        chapter.sourceOrder,
                        chapter.dateFetch,
                        chapter.dateUpload,
                        chapter.version,
                        chapter.memo,
                    )
                        .awaitAsOne()
                    chapter.copy(id = chapterId)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun update(chapterUpdate: ChapterUpdate) {
        partialUpdate(chapterUpdate)
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
        partialUpdate(*chapterUpdates.toTypedArray())
    }

    private suspend fun partialUpdate(vararg chapterUpdates: ChapterUpdate) {
        database.transaction {
            chapterUpdates.forEach { chapterUpdate ->
                database.chaptersQueries.update(
                    mangaId = chapterUpdate.mangaId,
                    url = chapterUpdate.url,
                    name = chapterUpdate.name,
                    scanlator = chapterUpdate.scanlator,
                    read = chapterUpdate.read,
                    bookmark = chapterUpdate.bookmark,
                    lastPageRead = chapterUpdate.lastPageRead,
                    chapterNumber = chapterUpdate.chapterNumber,
                    sourceOrder = chapterUpdate.sourceOrder,
                    dateFetch = chapterUpdate.dateFetch,
                    dateUpload = chapterUpdate.dateUpload,
                    chapterId = chapterUpdate.id,
                    version = chapterUpdate.version,
                    isSyncing = 0,
                    memo = chapterUpdate.memo?.let(MemoColumnAdapter::encode),
                )
            }
        }
    }

    override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
        try {
            database.chaptersQueries.removeChaptersWithIds(chapterIds)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
        return database.chaptersQueries
            .getChaptersByMangaId(mangaId, applyScanlatorFilter.toLong(), ::mapChapter)
            .awaitAsList()
    }

    override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> {
        return database.chaptersQueries
            .getScanlatorsByMangaId(mangaId) { it.orEmpty() }
            .awaitAsList()
    }

    override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> {
        return database.chaptersQueries
            .getScanlatorsByMangaId(mangaId) { it.orEmpty() }
            .subscribeToList()
    }

    override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> {
        return database.chaptersQueries
            .getBookmarkedChaptersByMangaId(mangaId, ::mapChapter)
            .awaitAsList()
    }

    // SY --> Komiho: 本地模式书签 tab —— 取某来源下所有已加书签的章节。
    override suspend fun getBookmarkedChaptersBySource(sourceId: Long): List<LocalBookmarkItem> {
        return database.chaptersQueries
            .bookmarkedChaptersBySource(sourceId) { chapterId, mangaId, chapterName, chapterNumber, chapterUrl, lastPageRead, dateUpload, mangaTitle, mangaUrl, thumbnailUrl ->
                LocalBookmarkItem(
                    chapterId = chapterId,
                    mangaId = mangaId,
                    chapterName = chapterName,
                    chapterNumber = chapterNumber,
                    chapterUrl = chapterUrl,
                    lastPageRead = lastPageRead,
                    dateUpload = dateUpload,
                    mangaTitle = mangaTitle,
                    mangaUrl = mangaUrl,
                    thumbnailUrl = thumbnailUrl,
                )
            }
            .awaitAsList()
    }
    // SY <--

    override suspend fun getChapterById(id: Long): Chapter? {
        return database.chaptersQueries
            .getChapterById(id, ::mapChapter)
            .awaitAsOneOrNull()
    }

    override suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean): Flow<List<Chapter>> {
        return database.chaptersQueries
            .getChaptersByMangaId(mangaId, applyScanlatorFilter.toLong(), ::mapChapter)
            .subscribeToList()
    }

    override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? {
        return database.chaptersQueries
            .getChapterByUrlAndMangaId(url, mangaId, ::mapChapter)
            .awaitAsOneOrNull()
    }

    // SY -->
    override suspend fun getChapterByUrl(url: String): List<Chapter> {
        return database.chaptersQueries
            .getChapterByUrl(url, ::mapChapter)
            .awaitAsList()
    }

    override suspend fun getMergedChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
        return database.chaptersQueries
            .getMergedChaptersByMangaId(
                mangaId,
                applyScanlatorFilter.toLong(),
                ::mapChapter,
            )
            .awaitAsList()
    }

    override suspend fun getMergedChapterByMangaIdAsFlow(
        mangaId: Long,
        applyScanlatorFilter: Boolean,
    ): Flow<List<Chapter>> {
        return database.chaptersQueries
            .getMergedChaptersByMangaId(
                mangaId,
                applyScanlatorFilter.toLong(),
                ::mapChapter,
            )
            .subscribeToList()
    }

    override suspend fun getScanlatorsByMergeId(mangaId: Long): List<String> {
        return database.chaptersQueries
            .getScanlatorsByMergeId(mangaId) { it.orEmpty() }
            .awaitAsList()
    }

    override fun getScanlatorsByMergeIdAsFlow(mangaId: Long): Flow<List<String>> {
        return database.chaptersQueries
            .getScanlatorsByMergeId(mangaId) { it.orEmpty() }
            .subscribeToList()
    }
    // SY <--
}
