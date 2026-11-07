package tachiyomi.domain.chapter.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterProgress
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.LocalBookmarkItem

interface ChapterRepository {

    suspend fun addAll(chapters: List<Chapter>): List<Chapter>

    suspend fun update(chapterUpdate: ChapterUpdate)

    suspend fun updateAll(chapterUpdates: List<ChapterUpdate>)

    suspend fun removeChaptersWithIds(chapterIds: List<Long>)

    suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter>

    suspend fun getScanlatorsByMangaId(mangaId: Long): List<String>

    fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>>

    suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter>

    // SY --> Komiho: 本地模式书签 tab —— 取某来源下所有已加书签的章节。
    suspend fun getBookmarkedChaptersBySource(sourceId: Long): List<LocalBookmarkItem>
    // SY <--

    suspend fun getChapterById(id: Long): Chapter?

    suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean = false): Flow<List<Chapter>>

    suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter?

    // SY -->
    suspend fun getChapterByUrl(url: String): List<Chapter>
    // SY <--

    // Komiho (2026-11-06): 文件身份（v1:size:mtime）。文件移动/改名后 url 失效，
    // 但 content_key 不变 —— 文件浏览器与历史都靠它把同一条章节找回来。
    suspend fun getChapterByContentKey(contentKey: String): Chapter?

    /** 文件浏览器列表：按一批 content_key 批量取进度（不含大字段）。 */
    suspend fun getChapterProgressByContentKeys(contentKeys: List<String>): List<ChapterProgress>

    /**
     * 文件浏览器列表的**兜底通道**：按一批章节 URL 批量取进度。
     * 从历史 / 书签进入阅读不会经过打开入口，这类章节的 content_key 可能为空，
     * 只按 key 查会显示不出进度。返回项的 [ChapterProgress.url] 为对应章节 URL。
     */
    suspend fun getChapterProgressByUrls(urls: List<String>): List<ChapterProgress>

    suspend fun updateChapterContentKey(chapterId: Long, contentKey: String)

    suspend fun updateChapterTotalPages(chapterId: Long, totalPages: Int)
}
