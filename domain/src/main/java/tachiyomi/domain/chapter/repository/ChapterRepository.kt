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

    /**
     * Komiho (2026-11-07): 文件浏览器的进度查询 —— **订阅式**，key 与 url 两条通道一次查完，
     * chapters 表一变就重新发射。
     *
     * 通道 1：content_key（size+mtime）—— 文件移动/改名后仍然有效，优先；
     * 通道 2：章节 URL —— 兜底。从历史 / 书签进入阅读不经过「打开文件」入口，
     * 这类章节的 content_key 可能为空，只认 key 会让列表显示不出进度。
     *
     * 用它替代「退出阅读器后 delay 一下再查」：阅读器写 last_page_read 是异步的，
     * 猜时间必然偶发读到旧值（表现为「第一次退出只有进入章有进度」）。订阅表变化后
     * 不存在时序假设，也覆盖「切后台再回来」等不经过退出的路径。
     *
     * 调用方对空集合传 `listOf("")` 占位（`IN ()` 是非法 SQL，而空串匹配不到真实 key/url）。
     */
    fun getChapterProgressFlow(contentKeys: List<String>, urls: List<String>): Flow<List<ChapterProgress>>

    suspend fun updateChapterContentKey(chapterId: Long, contentKey: String)

    suspend fun updateChapterTotalPages(chapterId: Long, totalPages: Int)
}
