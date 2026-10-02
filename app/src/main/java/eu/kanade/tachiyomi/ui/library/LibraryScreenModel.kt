package eu.kanade.tachiyomi.ui.library

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMapNotNull
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.core.util.fastFilterNot
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import mihon.core.common.utils.mutate
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class LibraryScreenModel(
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val setCustomMangaInfo: SetCustomMangaInfo = Injekt.get(),
) : StateScreenModel<LibraryScreenModel.State>(State()) {

    init {
        mutableState.update { state ->
            state.copy(activeCategoryIndex = libraryPreferences.lastUsedCategory.get())
        }
        screenModelScope.launchIO {
            combine(
                combine(
                    state.map { it.searchQuery }.distinctUntilChanged().debounce(0.25.seconds),
                    getCategories.subscribe(),
                    getFavoritesFlow(),
                    ::Triple,
                ),
                // SY -->
                combine(
                    state.map { it.groupType }.distinctUntilChanged(),
                    libraryPreferences.sortingMode.changes(),
                    ::Pair,
                ),
                // SY <--
                getLibraryItemPreferencesFlow(),
            ) { (searchQuery, categories, favorites), /* SY --> */ (groupType, sortingMode)/* <-- SY */, itemPreferences ->
                val showSystemCategory = favorites.any { it.libraryManga.categories.contains(0) }
                val filteredFavorites = favorites
                    .applyFilters(itemPreferences)
                    .let {
                        if (searchQuery == null) {
                            it
                        } else {
                            // SY -->
                            // it.filter { m -> m.matches(searchQuery) } }
                            filterLibrary(it, searchQuery)
                            // SY <--
                        }
                    }

                LibraryData(
                    isInitialized = true,
                    showSystemCategory = showSystemCategory,
                    categories = categories,
                    favorites = filteredFavorites,
                )
            }
                .distinctUntilChanged()
                .collectLatest { libraryData ->
                    mutableState.update { state ->
                        state.copy(libraryData = libraryData)
                    }
                }
        }

        screenModelScope.launchIO {
            state
                .dropWhile { !it.libraryData.isInitialized }
                .map {
                    Pair(
                        it.libraryData,
                        // SY -->
                        it.groupType,
                        // SY <--
                    )
                }
                .distinctUntilChanged()
                .map { (data, groupType) ->
                    data.favorites
                        .applyGrouping(
                            data.categories,
                            data.showSystemCategory,
                            // SY -->
                            groupType,
                            // SY <--
                        )
                        .applySort(
                            data.favoritesById,
                            // SY -->
                            libraryPreferences.sortingMode.get().takeIf { groupType != LibraryGroup.BY_DEFAULT },
                            // SY <--
                        )
                        .let {
                            it.ifEmpty {
                                mapOf(
                                    Category(
                                        0,
                                        preferences.context.stringResource(MR.strings.default_category),
                                        0,
                                        0,
                                    ) to emptyList(),
                                )
                            }
                        }
                }
                .collectLatest {
                    mutableState.update { state ->
                        state.copy(
                            isLoading = false,
                            groupedFavorites = it,
                        )
                    }
                }
        }

        combine(
            libraryPreferences.categoryTabs.changes(),
            libraryPreferences.categoryNumberOfItems.changes(),
            libraryPreferences.showContinueReadingButton.changes(),
        ) { a, b, c -> arrayOf(a, b, c) }
            .onEach { (showCategoryTabs, showMangaCount, showMangaContinueButton) ->
                mutableState.update { state ->
                    state.copy(
                        showCategoryTabs = showCategoryTabs,
                        showMangaCount = showMangaCount,
                        showMangaContinueButton = showMangaContinueButton,
                    )
                }
            }
            .launchIn(screenModelScope)

        getLibraryItemPreferencesFlow().map { prefs ->
            listOf(
                prefs.filterDownloaded,
                prefs.filterUnread,
                prefs.filterStarted,
                prefs.filterBookmarked,
                prefs.filterCompleted,
                prefs.filterIntervalCustom,
            )
                .any { it != TriState.DISABLED }
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(screenModelScope)

        // SY -->
        libraryPreferences.groupLibraryBy.changes()
            .onEach {
                mutableState.update { state ->
                    state.copy(groupType = it)
                }
            }
            .launchIn(screenModelScope)
        // SY <--
    }

    private fun List<LibraryItem>.applyFilters(
        preferences: ItemPreferences,
    ): List<LibraryItem> {
        val downloadedOnly = preferences.globalFilterDownloaded
        val skipOutsideReleasePeriod = preferences.skipOutsideReleasePeriod
        val filterDownloaded = if (downloadedOnly) TriState.ENABLED_IS else preferences.filterDownloaded
        val filterUnread = preferences.filterUnread
        val filterStarted = preferences.filterStarted
        val filterBookmarked = preferences.filterBookmarked
        val filterCompleted = preferences.filterCompleted
        val filterIntervalCustom = preferences.filterIntervalCustom

        val filterFnDownloaded: (LibraryItem) -> Boolean = {
            applyFilter(filterDownloaded) { it.isLocal || it.downloadCount > 0 }
        }

        val filterFnUnread: (LibraryItem) -> Boolean = {
            applyFilter(filterUnread) { it.libraryManga.unreadCount > 0 }
        }

        val filterFnStarted: (LibraryItem) -> Boolean = {
            applyFilter(filterStarted) { it.libraryManga.hasStarted }
        }

        val filterFnBookmarked: (LibraryItem) -> Boolean = {
            applyFilter(filterBookmarked) { it.libraryManga.hasBookmarks }
        }

        val filterFnCompleted: (LibraryItem) -> Boolean = {
            applyFilter(filterCompleted) { it.libraryManga.manga.status.toInt() == SManga.COMPLETED }
        }

        val filterFnIntervalCustom: (LibraryItem) -> Boolean = {
            if (skipOutsideReleasePeriod) {
                applyFilter(filterIntervalCustom) { it.libraryManga.manga.fetchInterval < 0 }
            } else {
                true
            }
        }

        // SY -->

        return fastFilter {
            filterFnDownloaded(it) &&
                filterFnUnread(it) &&
                filterFnStarted(it) &&
                filterFnBookmarked(it) &&
                filterFnCompleted(it) &&
                filterFnIntervalCustom(it)
        }
    }

    private fun List<LibraryItem>.applyGrouping(
        categories: List<Category>,
        showSystemCategory: Boolean,
        // SY -->
        groupType: Int,
        // <-- SY
    ): Map<Category, List</* LibraryItem */ Long>> {
        // SY -->
        when (groupType) {
            LibraryGroup.BY_DEFAULT -> {
                // SY <--
                val groupCache = mutableMapOf</* Category.id */ Long, MutableList</* LibraryItem */ Long>>()
                forEach { item ->
                    item.libraryManga.categories.forEach { categoryId ->
                        groupCache.getOrPut(categoryId) { mutableListOf() }.add(item.id)
                    }
                }

                return categories.filter { showSystemCategory || !it.isSystemCategory }
                    .associateWith { groupCache[it.id]?.toList().orEmpty() }
            }
            // SY -->
            LibraryGroup.UNGROUPED -> {
                return mapOf(
                    Category(
                        0,
                        preferences.context.stringResource(SYMR.strings.ungrouped),
                        0,
                        0,
                    ) to
                        map { it.id },
                )
            }

            else -> {
                return getGroupedMangaItems(
                    groupType = groupType,
                )
            }
        }
        // SY <--
    }

    private fun Map<Category, List</* LibraryItem */ Long>>.applySort(
        favoritesById: Map<Long, LibraryItem>,
        // SY -->
        groupSort: LibrarySort? = null,
        // SY <--
    ): Map<Category, List</* LibraryItem */ Long>> {
        // SY -->
        val listOfTags by lazy {
            libraryPreferences.sortTagsForLibrary.get()
                .asSequence()
                .mapNotNull {
                    val list = it.split("|")
                    (list.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to
                        (list.getOrNull(1) ?: return@mapNotNull null)
                }
                .sortedBy { it.first }
                .map { it.second }
                .toList()
        }
        // SY <--

        val sortAlphabetically: (LibraryItem, LibraryItem) -> Int = { manga1, manga2 ->
            val title1 = manga1.libraryManga.manga.title.lowercase()
            val title2 = manga2.libraryManga.manga.title.lowercase()
            title1.compareToWithCollator(title2)
        }

        fun LibrarySort.comparator(): Comparator<LibraryItem> = Comparator { manga1, manga2 ->
            // SY -->
            val sort = groupSort ?: this
            // SY <--
            when (sort.type) {
                LibrarySort.Type.Alphabetical -> {
                    sortAlphabetically(manga1, manga2)
                }

                LibrarySort.Type.LastRead -> {
                    manga1.libraryManga.lastRead.compareTo(manga2.libraryManga.lastRead)
                }

                LibrarySort.Type.LastUpdate -> {
                    manga1.libraryManga.manga.lastUpdate.compareTo(manga2.libraryManga.manga.lastUpdate)
                }

                LibrarySort.Type.UnreadCount -> when {
                    // Ensure unread content comes first
                    manga1.libraryManga.unreadCount == manga2.libraryManga.unreadCount -> 0
                    manga1.libraryManga.unreadCount == 0L -> if (sort.isAscending) 1 else -1
                    manga2.libraryManga.unreadCount == 0L -> if (sort.isAscending) -1 else 1
                    else -> manga1.libraryManga.unreadCount.compareTo(manga2.libraryManga.unreadCount)
                }

                LibrarySort.Type.TotalChapters -> {
                    manga1.libraryManga.totalChapters.compareTo(manga2.libraryManga.totalChapters)
                }

                LibrarySort.Type.LatestChapter -> {
                    manga1.libraryManga.latestUpload.compareTo(manga2.libraryManga.latestUpload)
                }

                LibrarySort.Type.ChapterFetchDate -> {
                    manga1.libraryManga.chapterFetchedAt.compareTo(manga2.libraryManga.chapterFetchedAt)
                }

                LibrarySort.Type.DateAdded -> {
                    manga1.libraryManga.manga.dateAdded.compareTo(manga2.libraryManga.manga.dateAdded)
                }

                LibrarySort.Type.TrackerMean -> {
                    // Trackers are no longer supported; fall back to alphabetical order
                    sortAlphabetically(manga1, manga2)
                }

                LibrarySort.Type.Random -> {
                    error("Why Are We Still Here? Just To Suffer?")
                }
                // SY -->
                LibrarySort.Type.TagList -> {
                    val manga1IndexOfTag = listOfTags.indexOfFirst {
                        manga1.libraryManga.manga.genre?.contains(it) ?: false
                    }
                    val manga2IndexOfTag = listOfTags.indexOfFirst {
                        manga2.libraryManga.manga.genre?.contains(it) ?: false
                    }
                    manga1IndexOfTag.compareTo(manga2IndexOfTag)
                }
                // SY <--
            }
        }

        return mapValues { (key, value) ->
            // SY -->
            val sort = groupSort ?: key.sort
            if (sort.type == LibrarySort.Type.Random) {
                // SY <--
                return@mapValues value.shuffled(Random(libraryPreferences.randomSortSeed.get()))
            }

            val manga = value.mapNotNull { favoritesById[it] }

            // SY -->
            val comparator = sort.comparator()
                // SY <--
                .let { if (/* SY --> */ sort.isAscending /* SY <-- */) it else it.reversed() }
                .thenComparator(sortAlphabetically)

            manga.sortedWith(comparator).map { it.id }
        }
    }

    private fun getLibraryItemPreferencesFlow(): Flow<ItemPreferences> {
        return combine(
            libraryPreferences.downloadBadge.changes(),
            libraryPreferences.unreadBadge.changes(),
            libraryPreferences.localBadge.changes(),
            libraryPreferences.languageBadge.changes(),
            libraryPreferences.autoUpdateMangaRestrictions.changes(),

            preferences.downloadedOnly.changes(),
            libraryPreferences.filterDownloaded.changes(),
            libraryPreferences.filterUnread.changes(),
            libraryPreferences.filterStarted.changes(),
            libraryPreferences.filterBookmarked.changes(),
            libraryPreferences.filterCompleted.changes(),
            libraryPreferences.filterIntervalCustom.changes(),
        ) {
            ItemPreferences(
                downloadBadge = it[0] as Boolean,
                unreadBadge = it[1] as Boolean,
                localBadge = it[2] as Boolean,
                languageBadge = it[3] as Boolean,
                skipOutsideReleasePeriod = LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in (it[4] as Set<*>),
                globalFilterDownloaded = it[5] as Boolean,
                filterDownloaded = it[6] as TriState,
                filterUnread = it[7] as TriState,
                filterStarted = it[8] as TriState,
                filterBookmarked = it[9] as TriState,
                filterCompleted = it[10] as TriState,
                filterIntervalCustom = it[11] as TriState,
            )
        }
    }

    private fun getFavoritesFlow(): Flow<List<LibraryItem>> {
        return combine(
            getLibraryManga.subscribe(),
            getLibraryItemPreferencesFlow(),
            downloadCache.changes,
        ) { libraryManga, preferences, _ ->
            libraryManga.map { manga ->
                // SY -->
                val downloadCount = downloadManager.getDownloadCount(manga.manga)
                // SY <--
                LibraryItem(
                    libraryManga = manga,
                    // SY -->
                    downloadCount = downloadCount,
                    // SY <--
                    unreadCount = manga.unreadCount,
                    isLocal = manga.manga.isLocal(),
                    badges = LibraryItem.Badges(
                        downloadCount = if (preferences.downloadBadge) {
                            // SY -->
                            downloadCount
                            // SY <--
                        } else {
                            0
                        },
                        unreadCount = if (preferences.unreadBadge) {
                            manga.unreadCount
                        } else {
                            0
                        },
                        isLocal = if (preferences.localBadge) {
                            manga.manga.isLocal()
                        } else {
                            false
                        },
                        sourceLanguage = if (preferences.languageBadge) {
                            sourceManager.getOrStub(manga.manga.source).lang
                        } else {
                            ""
                        },
                    ),
                )
            }
        }
    }

    /**
     * Returns the common categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getCommonCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        return mangas
            .map { getCategories.await(it.id).toSet() }
            .reduce { set1, set2 -> set1.intersect(set2) }
    }

    suspend fun getNextUnreadChapter(manga: Manga): Chapter? {
        return getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
            .getNextUnread(manga, downloadManager)
    }

    /**
     * Returns the mix (non-common) categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getMixCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        val mangaCategories = mangas.map { getCategories.await(it.id).toSet() }
        val common = mangaCategories.reduce { set1, set2 -> set1.intersect(set2) }
        return mangaCategories.flatten().distinct().subtract(common)
    }

    /**
     * Queues the amount specified of unread chapters from the list of selected manga
     */
    fun performDownloadAction(action: DownloadAction) {
        when (action) {
            DownloadAction.NEXT_1_CHAPTER -> downloadNextChapters(1)
            DownloadAction.NEXT_5_CHAPTERS -> downloadNextChapters(5)
            DownloadAction.NEXT_10_CHAPTERS -> downloadNextChapters(10)
            DownloadAction.NEXT_25_CHAPTERS -> downloadNextChapters(25)
            DownloadAction.UNREAD_CHAPTERS -> downloadNextChapters(null)
            DownloadAction.BOOKMARKED_CHAPTERS -> downloadBookmarkedChapters()
        }
        clearSelection()
    }

    private fun downloadNextChapters(amount: Int?) {
        val mangas = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                val chapters = getNextChapters.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                // SY -->
                                manga.ogTitle,
                                // SY <--
                                manga.source,
                            )
                    }
                    .let { if (amount != null) it.take(amount) else it }

                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    private fun downloadBookmarkedChapters() {
        val mangas = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                val chapters = getBookmarkedChaptersByMangaId.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                // SY -->
                                manga.ogTitle,
                                // SY <--
                                manga.source,
                            )
                    }
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }
    fun resetInfo() {
        state.value.selectedManga.fastForEach { manga ->
            val mangaInfo = CustomMangaInfo(
                id = manga.id,
                title = null,
                author = null,
                artist = null,
                thumbnailUrl = null,
                description = null,
                genre = null,
                status = null,
            )

            setCustomMangaInfo.set(mangaInfo)
        }
        clearSelection()
    }
// SY <--

    /**
     * Marks mangas' chapters read status.
     */
    fun markReadSelection(read: Boolean) {
        val selection = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            selection.forEach { manga ->
                setReadStatus.await(
                    manga = manga,
                    read = read,
                )
            }
        }
        clearSelection()
    }

    /**
     * Remove the selected manga.
     *
     * @param mangas the list of manga to delete.
     * @param deleteFromLibrary whether to delete manga from library.
     * @param deleteChapters whether to delete downloaded chapters.
     */
    fun removeMangas(mangas: List<Manga>, deleteFromLibrary: Boolean, deleteChapters: Boolean) {
        screenModelScope.launchNonCancellable {
            if (deleteFromLibrary) {
                val toDelete = mangas.map {
                    it.removeCovers(coverCache)
                    MangaUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateManga.awaitAll(toDelete)
            }

            if (deleteChapters) {
                mangas.forEach { manga ->
                    val source = sourceManager.get(manga.source) as? HttpSource
                    if (source != null) {
                        downloadManager.deleteManga(manga, source)
                    }
                }
            }
        }
    }

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    fun setMangaCategories(mangaList: List<Manga>, addCategories: List<Long>, removeCategories: List<Long>) {
        screenModelScope.launchNonCancellable {
            mangaList.forEach { manga ->
                val categoryIds = getCategories.await(manga.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                setMangaCategories.await(manga.id, categoryIds)
            }
        }
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return libraryPreferences.displayMode.asState(screenModelScope)
    }

    fun getColumnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(screenModelScope)
    }

    fun getRandomLibraryItemForCurrentCategory(): LibraryItem? {
        val state = state.value
        return state.getItemsForCategoryId(state.activeCategory?.id).randomOrNull()
    }

    fun showSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.SettingsSheet) }
    }

    // SY -->
    private suspend fun filterLibrary(
        unfiltered: List<LibraryItem>,
        query: String?,
    ): List<LibraryItem> {
        val q = query?.trim() ?: return unfiltered
        if (q.isEmpty()) return unfiltered
        if (q.startsWith("id:", true)) {
            val id = q.substringAfter("id:").toLongOrNull()
            return unfiltered.filter { it.libraryManga.manga.id == id }
        }
        val sourceIdString = q.toLongOrNull()?.toString()
        return unfiltered.filter { item ->
            val manga = item.libraryManga.manga
            val source = sourceManager.get(manga.source)
            manga.title.contains(q, true) ||
                (manga.author?.contains(q, true) == true) ||
                (manga.artist?.contains(q, true) == true) ||
                (manga.description?.contains(q, true) == true) ||
                (source?.name?.contains(q, true) == true) ||
                (sourceIdString != null && manga.source.toString() == sourceIdString) ||
                (manga.genre.orEmpty().fastAny { it.contains(q, true) })
        }
    }
// SY <--

    private var lastSelectionCategory: Long? = null

    fun clearSelection() {
        lastSelectionCategory = null
        mutableState.update { it.copy(selection = setOf()) }
    }

    fun toggleSelection(category: Category, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { set ->
                if (!set.remove(manga.id)) set.add(manga.id)
            }
            lastSelectionCategory = category.id.takeIf { newSelection.isNotEmpty() }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Selects all mangas between and including the given manga and the last pressed manga from the
     * same category as the given manga
     */
    fun toggleRangeSelection(category: Category, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val lastSelected = list.lastOrNull()
                if (lastSelectionCategory != category.id) {
                    list.add(manga.id)
                    return@mutate
                }

                val items = state.getItemsForCategoryId(category.id).fastMap { it.id }
                val lastMangaIndex = items.indexOf(lastSelected)
                val curMangaIndex = items.indexOf(manga.id)

                val selectionRange = when {
                    lastMangaIndex < curMangaIndex -> lastMangaIndex..curMangaIndex
                    curMangaIndex < lastMangaIndex -> curMangaIndex..lastMangaIndex
                    // We shouldn't reach this point
                    else -> return@mutate
                }
                selectionRange.mapNotNull { items[it] }.let(list::addAll)
            }
            lastSelectionCategory = category.id
            state.copy(selection = newSelection)
        }
    }

    fun selectAll() {
        lastSelectionCategory = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                state.getItemsForCategoryId(state.activeCategory?.id).map { it.id }.let(list::addAll)
            }
            state.copy(selection = newSelection)
        }
    }

    fun invertSelection() {
        lastSelectionCategory = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val itemIds = state.getItemsForCategoryId(state.activeCategory?.id).fastMap { it.id }
                val (toRemove, toAdd) = itemIds.partition { it in list }
                list.removeAll(toRemove.toSet())
                list.addAll(toAdd)
            }
            state.copy(selection = newSelection)
        }
    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun updateActiveCategoryIndex(index: Int) {
        val newIndex = mutableState.updateAndGet { state ->
            state.copy(activeCategoryIndex = index)
        }
            .coercedActiveCategoryIndex

        libraryPreferences.lastUsedCategory.set(newIndex)
    }

    fun openChangeCategoryDialog() {
        screenModelScope.launchIO {
            // Create a copy of selected manga
            val mangaList = state.value.selectedManga

            // Hide the default category because it has a different behavior than the ones from db.
            // SY -->
            val categories = state.value.libraryData.categories.filter { it.id != 0L }
            // SY <--

            // Get indexes of the common categories to preselect.
            val common = getCommonCategories(mangaList)
            // Get indexes of the mix categories to preselect.
            val mix = getMixCategories(mangaList)
            val preselected = categories
                .map {
                    when (it) {
                        in common -> CheckboxState.State.Checked(it)
                        in mix -> CheckboxState.TriState.Exclude(it)
                        else -> CheckboxState.State.None(it)
                    }
                }

            mutableState.update { it.copy(dialog = Dialog.ChangeCategory(mangaList, preselected)) }
        }
    }

    fun openDeleteMangaDialog() {
        mutableState.update { it.copy(dialog = Dialog.DeleteManga(state.value.selectedManga)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed interface Dialog {
        data object SettingsSheet : Dialog
        data class ChangeCategory(
            val manga: List<Manga>,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog

        data class DeleteManga(val manga: List<Manga>) : Dialog
    }

// SY -->

    /** Returns first unread chapter of a manga */
    suspend fun getFirstUnread(manga: Manga): Chapter? {
        return getNextChapters.await(manga.id).firstOrNull()
    }

    private fun List<LibraryItem>.getGroupedMangaItems(
        groupType: Int,
    ): Map<Category, List</* LibraryItem */ Long>> {
        val context = preferences.context
        return when (groupType) {
            LibraryGroup.BY_TRACK_STATUS -> {
                // Trackers are no longer supported; treat stale grouping preference as ungrouped
                return mapOf(
                    Category(
                        0,
                        preferences.context.stringResource(SYMR.strings.ungrouped),
                        0,
                        0,
                    ) to
                        map { it.id },
                )
            }

            LibraryGroup.BY_SOURCE -> {
                val sources: List<Long>
                groupBy { item ->
                    item.libraryManga.manga.source
                }.also {
                    sources = it.keys
                        .map {
                            sourceManager.getOrStub(it)
                        }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id.toString() } })
                        .map { it.id }
                }.mapKeys {
                    Category(
                        id = it.key,
                        name = if (it.key == LocalSource.ID) {
                            context.stringResource(MR.strings.local_source)
                        } else {
                            val source = sourceManager.getOrStub(it.key)
                            source.name.ifBlank { source.id.toString() }
                        },
                        order = sources.indexOf(it.key).takeUnless { it == -1 }?.toLong() ?: Long.MAX_VALUE,
                        flags = 0,
                    )
                }
            }

            LibraryGroup.BY_STATUS -> {
                groupBy { item ->
                    item.libraryManga.manga.status
                }.mapKeys {
                    Category(
                        id = it.key + 1,
                        name = when (it.key) {
                            SManga.ONGOING.toLong() -> context.stringResource(MR.strings.ongoing)
                            SManga.LICENSED.toLong() -> context.stringResource(MR.strings.licensed)
                            SManga.CANCELLED.toLong() -> context.stringResource(MR.strings.cancelled)
                            SManga.ON_HIATUS.toLong() -> context.stringResource(MR.strings.on_hiatus)
                            SManga.PUBLISHING_FINISHED.toLong() -> context.stringResource(MR.strings.publishing_finished)
                            SManga.COMPLETED.toLong() -> context.stringResource(MR.strings.completed)
                            else -> context.stringResource(MR.strings.unknown)
                        },
                        order = when (it.key) {
                            SManga.ONGOING.toLong() -> 1
                            SManga.LICENSED.toLong() -> 2
                            SManga.CANCELLED.toLong() -> 3
                            SManga.ON_HIATUS.toLong() -> 4
                            SManga.PUBLISHING_FINISHED.toLong() -> 5
                            SManga.COMPLETED.toLong() -> 6
                            else -> 7
                        },
                        flags = 0,
                    )
                }
            }

            else -> emptyMap()
        }.toSortedMap(compareBy { it.order })
            .mapValues { (_, libraryItem) -> libraryItem.fastMap { it.id } }
    }

// SY <--
    @Immutable
    private data class ItemPreferences(
        val downloadBadge: Boolean,
        val unreadBadge: Boolean,
        val localBadge: Boolean,
        val languageBadge: Boolean,
        val skipOutsideReleasePeriod: Boolean,

        val globalFilterDownloaded: Boolean,
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterCompleted: TriState,
        val filterIntervalCustom: TriState,
    )

    @Immutable
    data class LibraryData(
        val isInitialized: Boolean = false,
        val showSystemCategory: Boolean = false,
        val categories: List<Category> = emptyList(),
        val favorites: List<LibraryItem> = emptyList(),
    ) {
        val favoritesById by lazy { favorites.associateBy { it.id } }
    }

    @Immutable
    data class State(
        val isInitialized: Boolean = false,
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val selection: Set</* Manga */ Long> = setOf(),
        val hasActiveFilters: Boolean = false,
        val showCategoryTabs: Boolean = false,
        val showMangaCount: Boolean = false,
        val showMangaContinueButton: Boolean = false,
        val dialog: Dialog? = null,
        val libraryData: LibraryData = LibraryData(),
        private val activeCategoryIndex: Int = 0,
        private val groupedFavorites: Map<Category, List</* LibraryItem */ Long>> = emptyMap(),
        val groupType: Int = LibraryGroup.BY_DEFAULT,
        // SY <--
    ) {
        val displayedCategories: List<Category> = groupedFavorites.keys.toList()

        val coercedActiveCategoryIndex = activeCategoryIndex.coerceIn(
            minimumValue = 0,
            maximumValue = displayedCategories.lastIndex.coerceAtLeast(0),
        )

        val activeCategory: Category? = displayedCategories.getOrNull(coercedActiveCategoryIndex)

        val isLibraryEmpty = libraryData.favorites.isEmpty()

        val selectionMode = selection.isNotEmpty()

        val selectedManga by lazy { selection.mapNotNull { libraryData.favoritesById[it]?.libraryManga?.manga } }

        // SY -->
        val showResetInfo: Boolean by lazy {
            selectedManga.fastAny { manga ->
                manga.title != manga.ogTitle ||
                    manga.author != manga.ogAuthor ||
                    manga.artist != manga.ogArtist ||
                    manga.thumbnailUrl != manga.ogThumbnailUrl ||
                    manga.description != manga.ogDescription ||
                    manga.genre != manga.ogGenre ||
                    manga.status != manga.ogStatus
            }
        }
        // SY <--

        fun getItemsForCategoryId(categoryId: Long?): List<LibraryItem> {
            if (categoryId == null) return emptyList()
            val category = displayedCategories.find { it.id == categoryId } ?: return emptyList()
            return getItemsForCategory(category)
        }

        fun getItemsForCategory(category: Category): List<LibraryItem> {
            return groupedFavorites[category].orEmpty().mapNotNull { libraryData.favoritesById[it] }
        }

        fun getItemCountForCategory(category: Category): Int? {
            return if (showMangaCount || !searchQuery.isNullOrEmpty()) groupedFavorites[category]?.size else null
        }

        fun getToolbarTitle(
            defaultTitle: String,
            defaultCategoryTitle: String,
            page: Int,
        ): LibraryToolbarTitle {
            val category = displayedCategories.getOrNull(page) ?: return LibraryToolbarTitle(defaultTitle)
            val categoryName = category.let {
                if (it.isSystemCategory) defaultCategoryTitle else it.name
            }
            val title = if (showCategoryTabs) defaultTitle else categoryName
            val count = when {
                !showMangaCount -> null
                !showCategoryTabs -> getItemCountForCategory(category)
                // Whole library count
                else -> libraryData.favorites.size
            }
            return LibraryToolbarTitle(title, count)
        }
    }
}
