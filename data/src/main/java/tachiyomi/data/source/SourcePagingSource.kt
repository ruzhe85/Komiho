package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceSearchPagingSource(
    source: Source,
    private val query: String,
    private val filters: FilterList,
) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source!!.getSearchManga(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source!!.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source!!.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    protected open val source: Source?,
    protected val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : SourcePagingSource() {

    private val seenManga = hashSetOf<String>()

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(
        params: LoadParams<Long>,
    ): LoadResult<Long, /*SY --> */ Manga/*SY <-- */> {
        val page = params.key ?: 1

        return try {
            val mangasPage = withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.mangas.isNotEmpty() }
                    ?: throw NoResultsException()
            }

            // SY -->
            getPageLoadResult(params, mangasPage)
            // SY <--
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    // SY -->
    open suspend fun getPageLoadResult(
        params: LoadParams<Long>,
        mangasPage: MangasPage,
    ): LoadResult.Page<Long, /*SY --> */ Manga/*SY <-- */> {
        val page = params.key ?: 1

        // SY -->
        val manga = mangasPage.mangas.map { sManga -> sManga.toDomainManga(source!!.id) }
            .filter { seenManga.add(it.url) }
            .let { manga -> manga.zip(networkToLocalManga(manga)).map { it.second } }
        // SY <--

        return LoadResult.Page(
            data = manga,
            prevKey = null,
            nextKey = if (mangasPage.hasNextPage) page + 1 else null,
        )
    }
    // SY <--

    override fun getRefreshKey(
        state: PagingState<Long, /*SY --> */ Manga/*SY <-- */>,
    ): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}

class NoResultsException : Exception()
