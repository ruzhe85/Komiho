package tachiyomi.domain.history.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.model.LocalHistoryItem

interface HistoryRepository {

    fun getHistory(query: String): Flow<List<HistoryWithRelations>>

    // SY --> Komiho: 本地模式历史 tab —— 按来源过滤最近阅读。
    fun getHistoryBySource(sourceId: Long): Flow<List<HistoryWithRelations>>

    // 文件级历史：返回每一条历史记录，包含章节名/URL/进度，用于历史 tab 条目。
    fun getHistoryBySourceDetailed(sourceId: Long): Flow<List<LocalHistoryItem>>
    // SY <--

    suspend fun getLastHistory(): HistoryWithRelations?

    suspend fun getTotalReadDuration(): Long

    suspend fun getHistoryByMangaId(mangaId: Long): List<History>

    suspend fun resetHistory(historyId: Long)

    suspend fun resetHistoryByMangaId(mangaId: Long)

    suspend fun deleteAllHistory(): Boolean

    suspend fun upsertHistory(historyUpdate: HistoryUpdate)

    // SY -->
    suspend fun upsertAllHistory(historyUpdate: List<HistoryUpdate>)
    // SY <--
}
