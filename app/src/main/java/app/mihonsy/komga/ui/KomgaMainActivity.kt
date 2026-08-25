package app.mihonsy.komga.ui

import android.content.Intent
import android.content.res.Configuration
import coil3.ImageLoader
import android.os.Bundle
import android.widget.Toast
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import eu.kanade.presentation.components.TabbedDialog
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaConnection
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.download.KomgaDownloadStore
import java.io.File
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.CollectionDto
import app.mihonsy.komga.data.model.LibraryDto
import app.mihonsy.komga.data.model.ReadingListDto
import app.mihonsy.komga.data.model.SeriesDto
import app.mihonsy.komga.data.model.AuthorDto
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.tachiyomi.R
import eu.kanade.presentation.more.settings.widget.AppThemeModePreferenceWidget
import eu.kanade.presentation.more.settings.widget.AppThemePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.util.LocalBackPress
import androidx.appcompat.app.AppCompatDelegate
import androidx.annotation.StringRes
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource as composeStringResource
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.launch
import uy.kohesive.injekt.api.get

// 划动选择命中检测已移除（长按圈选废弃）。


/**
 * Komiho M3: main activity with bottom navigation.
 *
 * 5 tabs mirroring Komga Web semantics:
 *   Home       — latest series / overview
 *   Library    — library chips + series grid (previous KomgaHome content)
 *   Readlists  — GET /api/v1/readlists
 *   Search     — GET /api/v1/series?search=
 *   Settings   — server connection settings + basic info
 *
 * All data is fetched live from the Komga server (Komga is the single
 * source of truth; nothing is cached locally).
 */
class KomgaMainActivity : KomgaBaseActivity() {
    // Incremented on every onResume so the tabs reload data when returning
    // from the reader (read progress / unread counts change while reading).
    private val refreshSignal = MutableStateFlow(0)
    // Filter passed from the Series detail page (tap a tag/author → jump here
    // and filter the Library by it, Komga WebUI parity). Emitted both on the
    // initial onCreate and on subsequent onNewIntent (CLEAR_TOP+SINGLE_TOP
    // reuses this instance, so onCreate won't run again).
    private val filterSignal = MutableStateFlow<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = intent?.getStringExtra("filterType")
        val v = intent?.getStringExtra("filterValue")
        if (!t.isNullOrBlank() && !v.isNullOrBlank()) filterSignal.value = t to v
        setContent { KomihoTheme { KomgaMainScreen(refreshSignal, filterSignal) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        val t = intent.getStringExtra("filterType")
        val v = intent.getStringExtra("filterValue")
        if (!t.isNullOrBlank() && !v.isNullOrBlank()) filterSignal.value = t to v
    }

    override fun onResume() {
        super.onResume()
        refreshSignal.update { it + 1 }
    }
}

private enum class MainTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    Home(R.string.tab_home, Icons.Filled.Home),
    Library(R.string.tab_library, Icons.Filled.Book),
    Lists(R.string.tab_lists, Icons.AutoMirrored.Filled.List),
    Downloads(R.string.tab_downloads, Icons.Filled.Download),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
    ;

    @Composable
    fun labelText(): String = composeStringResource(labelRes)
}

@Composable
private fun KomgaMainScreen(
    refreshSignal: MutableStateFlow<Int>,
    filterSignal: MutableStateFlow<Pair<String, String>?>,
) {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val client = remember { KomgaApiClient(prefs.connection()) }

    // Filter coming from the Series detail page (tap tag/genre/author → filter Library).
    // Driven reactively by filterSignal so taps arriving via onNewIntent (when this
    // Activity instance is reused) actually apply. (Previously captured once with
    // remember{} and never updated → opening a tag/author showed the whole library.)
    val activeLibraryFilter by filterSignal.collectAsState()
    val libraryFilterType = activeLibraryFilter?.first
    val libraryFilterValue = activeLibraryFilter?.second

    // Tab state is preserved across configuration changes by the Activity's
    // configChanges flag (orientation). rememberSaveable additionally keeps
    // the current tab across activity.recreate() — theme/language switches
    // in Settings would otherwise bounce back to the Home tab.
    var currentTab by rememberSaveable { mutableIntStateOf(MainTab.Home.ordinal) }
    // Refresh counter: bumped by tab re-tap and by Activity onResume (returning
    // from the reader). Passed to tabs to trigger data reload.
    val refreshTick by refreshSignal.collectAsState()
    // M3.12: search collapsed to an icon in the title row; expands the field.
    var searchOpen by remember { mutableStateOf(false) }

    // U3+: shelf display mode lives in the top-level so the TopAppBar toggle
    // can drive every page (KomgaMainScreen's tabs + child activities that
    // also read prefs.libraryDisplayMode).
    var displayMode by remember { mutableStateOf(LibraryDisplayMode.fromPref(prefs.libraryDisplayMode)) }
    // Library shelf sort + read-status filter lifted here so the single
    // toolbar "display options" button can drive them in one composite menu.
    var librarySortMode by remember { mutableStateOf(LibrarySort.fromPref(prefs.librarySort)) }
    var libraryReadFilter by remember { mutableStateOf(ReadFilter.All) }
    var shelfMenuOpen by remember { mutableStateOf(false) }
    // Home tab display options (layout / columns / per-section limit / display mode).
    var homeMenuOpen by remember { mutableStateOf(false) }
    var homeLayout by remember { mutableStateOf(prefs.homeSectionLayout) }
    var homeGridColumns by remember { mutableStateOf(prefs.homeGridColumns) }
    var homeSectionLimit by remember { mutableStateOf(prefs.homeSectionLimit) }
    var homeDisplayMode by remember { mutableStateOf(prefs.homeDisplayMode) }
    // Bumped whenever any home option changes so HomeTab re-reads prefs and refreshes.
    var homeRefresh by remember { mutableStateOf(0) }
    // Library tab also gets per-orientation column counts (read-only from
    // prefs; the old columns slider was folded into the toolbar menu).
    val configuration = LocalConfiguration.current
    val isLandscape = remember(configuration) {
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    var portraitColumns by remember { mutableStateOf(prefs.libraryPortraitColumns) }
    var landscapeColumns by remember { mutableStateOf(prefs.libraryLandscapeColumns) }
    val columns = if (isLandscape) landscapeColumns else portraitColumns

    // Library selection — owned at the top level. The Library tab's top bar
    // shows a dropdown (LibrarySelector) listing all libraries; picking one
    // switches the active library in place (no dialog, no extra screen).
    var libraries by remember { mutableStateOf<List<LibraryDto>>(emptyList()) }
    var selectedLibraryId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!prefs.hasConnection()) {
            context.startActivity(Intent(context, KomgaConnectActivity::class.java))
        } else {
            runCatching { client.getLibraries() }
                .onSuccess { libs ->
                    libraries = libs
                    if (selectedLibraryId == null) {
                        selectedLibraryId = libs.firstOrNull()?.id
                    }
                }
        }
    }
    // A filter handed in from the Series detail page means "open Library filtered".
    // Driven by filterSignal so both the initial onCreate and subsequent
    // onNewIntent (CLEAR_TOP+SINGLE_TOP reuses this instance) are covered. The
    // actual filter values are already derived reactively from filterSignal above;
    // here we just jump to the Library tab when a filter arrives.
    val filterPair by filterSignal.collectAsState()
    LaunchedEffect(filterPair) {
        if (!filterPair?.first.isNullOrBlank() && !filterPair?.second.isNullOrBlank()) {
            currentTab = MainTab.Library.ordinal
        }
    }
    // Close the search field when switching tabs (it is not shown on Settings).
    LaunchedEffect(currentTab) {
        searchOpen = false
    }

    // 统一返回手势处理：KomgaMainActivity 通常是 task 根 Activity，内部 4 个 tab +
    // 选库/搜索/菜单态都靠本地状态切换（无返回栈），必须拦截返回手势
    // 避免退出程序。但当本实例是被 Series 详情页调起（点 tag/作者 → 过滤库）时，
    // 它不是 task 根——Series 页在它下面，此时绝不能拦截本地 UI 态以外的返回，
    // 只拦截搜索框/菜单等本地 UI 态，其余交还系统 → 返回手势回 Series 页。
    // 选库现已改为顶栏下拉（LibrarySelector），不再有「选库对话框」态，
    // 因此 Library tab 上按返回直接回 Home（与 Lists/Downloads 一致）。
    // 优先级（仅根实例）：搜索框/菜单打开→关闭；非 Home tab→回 Home；
    // Home 根→交还系统默认（退出程序）。
    val isRootActivity = (context as? android.app.Activity)?.isTaskRoot ?: true
    // Library selection now lives in the top-bar dropdown, so the only
    // "back" affordance needed is: close search/menu, or (root only) drop a
    // non-Home tab back to Home. A selected library no longer traps back.
    val interceptBack = searchOpen || shelfMenuOpen ||
        (isRootActivity && currentTab != MainTab.Home.ordinal)
    BackHandler(enabled = interceptBack) {
        when {
            searchOpen -> searchOpen = false
            shelfMenuOpen -> shelfMenuOpen = false
            currentTab != MainTab.Home.ordinal -> currentTab = MainTab.Home.ordinal
        }
    }

    Scaffold(
        topBar = {
            val currentTabEnum = MainTab.entries[currentTab]
            TopAppBar(
                title = {
                    if (currentTabEnum == MainTab.Library) {
                        LibrarySelector(
                            libraries = libraries,
                            selectedLibraryId = selectedLibraryId,
                            onSelect = { selectedLibraryId = it },
                        )
                    } else {
                        Text(MainTab.entries[currentTab].labelText())
                    }
                },
                actions = {
                    // Single "display options" button on the Library shelf:
                    // opens one composite menu (display mode · sort · filter),
                    // replacing the old separate filter funnel + display dialog.
                    if (currentTabEnum == MainTab.Library) {
                        Box {
                            IconButton(onClick = { shelfMenuOpen = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = composeStringResource(R.string.cd_display_options),
                                )
                            }
                            ShelfOptionsMenu(
                                expanded = shelfMenuOpen,
                                onDismiss = { shelfMenuOpen = false },
                                displayMode = displayMode,
                                onDisplayModeChange = {
                                    displayMode = it
                                    prefs.libraryDisplayMode = it.prefValue
                                },
                                columns = columns,
                                onColumnChange = { newColumns ->
                                    if (isLandscape) {
                                        landscapeColumns = newColumns
                                        prefs.libraryLandscapeColumns = newColumns
                                    } else {
                                        portraitColumns = newColumns
                                        prefs.libraryPortraitColumns = newColumns
                                    }
                                },
                                sort = librarySortMode,
                                onSortModeChange = {
                                    librarySortMode = it
                                    prefs.librarySort = it.toPref()
                                },
                                readFilter = libraryReadFilter,
                                onReadFilterChange = { libraryReadFilter = it },
                            )
                        }
                    }
                    if (currentTabEnum == MainTab.Home) {
                        Box {
                            IconButton(onClick = { homeMenuOpen = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = composeStringResource(R.string.home_display_options),
                                )
                            }
                            HomeOptionsMenu(
                                expanded = homeMenuOpen,
                                onDismiss = { homeMenuOpen = false },
                                layout = homeLayout,
                                onLayoutChange = {
                                    homeLayout = it
                                    prefs.homeSectionLayout = it
                                    homeRefresh++
                                },
                                columns = homeGridColumns,
                                onColumnsChange = {
                                    homeGridColumns = it
                                    prefs.homeGridColumns = it
                                    homeRefresh++
                                },
                                displayMode = homeDisplayMode,
                                onDisplayModeChange = {
                                    homeDisplayMode = it
                                    prefs.homeDisplayMode = it
                                    homeRefresh++
                                },
                                sectionLimit = homeSectionLimit,
                                onSectionLimitChange = {
                                    homeSectionLimit = it
                                    prefs.homeSectionLimit = it
                                    homeRefresh++
                                },
                            )
                        }
                    }
                    if (currentTabEnum != MainTab.Settings && currentTabEnum != MainTab.Downloads) {
                        androidx.compose.material3.IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(
                                imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = if (searchOpen) composeStringResource(R.string.cd_close_search) else composeStringResource(R.string.cd_search),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Icon-only tabs: a compact bar — the default 80dp NavigationBar
            // leaves large empty areas above/below the icons.
            NavigationBar(modifier = Modifier.height(60.dp)) {
                MainTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = {
                        if (tab == MainTab.Library) {
                            // Library selection is now in the top-bar dropdown;
                            // tapping the icon just focuses the Library tab
                            // (refresh if already there, like the other tabs).
                            if (currentTab == index) refreshSignal.update { it + 1 } else currentTab = index
                        } else {
                                // 点击 tab（含重复点击当前 tab）触发刷新。
                                if (currentTab == index) refreshSignal.update { it + 1 } else currentTab = index
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.labelText()) },
                        label = null,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Search field expands below the title row when the icon is tapped.
            if (searchOpen) {
                EmbeddedSearch(
                    client,
                    displayMode = displayMode,
                    columns = columns,
                    libraryScope = if (currentTab == MainTab.Library.ordinal) selectedLibraryId else null,
                ) { seriesId ->
                    context.startActivity(Intent(context, KomgaSeriesActivity::class.java).putExtra("seriesId", seriesId))
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (MainTab.entries[currentTab]) {
                    MainTab.Home -> HomeTab(
                        client = client,
                        refreshTick = refreshTick,
                        homeRefresh = homeRefresh,
                    ) { seriesId ->
                        context.startActivity(Intent(context, KomgaSeriesActivity::class.java).putExtra("seriesId", seriesId))
                    }
                    MainTab.Library -> LibraryTab(
                        client = client,
                        selectedLibraryId = selectedLibraryId,
                        displayMode = displayMode,
                        columns = columns,
                        refreshTick = refreshTick,
                        sort = librarySortMode,
                        onSortModeChange = { librarySortMode = it },
                        readFilter = libraryReadFilter,
                        onReadFilterChange = { libraryReadFilter = it },
                        filterType = libraryFilterType,
                        filterValue = libraryFilterValue,
                        onFilterClear = {
                            // 根实例（正常库页）：清除筛选、停留库页。
                            // 非根实例（被 Series 详情页调起的过滤层）：✕ 等同关闭该层，回到系列页。
                            val act = context as? android.app.Activity
                            if (act != null && !act.isTaskRoot) {
                                act.finish()
                            } else {
                                filterSignal.value = null
                            }
                        },
                    ) { seriesId ->
                        context.startActivity(Intent(context, KomgaSeriesActivity::class.java).putExtra("seriesId", seriesId))
                    }
                    MainTab.Lists -> ListsTab(
                        client,
                        onReadlistClick = { rlId, rlName ->
                            context.startActivity(
                                Intent(context, KomgaReadlistActivity::class.java)
                                    .putExtra("readlistId", rlId)
                                    .putExtra("readlistName", rlName),
                            )
                        },
                        onCollectionClick = { cId, cName ->
                            context.startActivity(
                                Intent(context, KomgaCollectionActivity::class.java)
                                    .putExtra("collectionId", cId)
                                    .putExtra("collectionName", cName),
                            )
                        },
                    )
                    MainTab.Downloads -> DownloadsTab(
                        client = client,
                        onBookClick = { bookId ->
                            runCatching { KomgaReaderLauncher.open(context, client, bookId) }
                                .onFailure {
                                    android.widget.Toast.makeText(
                                        context, "打开阅读器失败：${it.message}", android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                        },
                    )
                    MainTab.Settings -> SettingsTab(context)
                }
            }
        }
    }

    // 选库已改为顶栏 LibrarySelector 下拉（见下方 LibrarySelector 组合函数），
    // 不再使用 AlertDialog，返回键也不再被困在「选库」态。

}

// ---------- Home tab ----------

@Composable
private fun HomeTab(
    client: KomgaApiClient,
    refreshTick: Int,
    homeRefresh: Int,
    onSeriesClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    // Read display options directly from prefs every recomposition so changes made
    // in the toolbar "显示" menu take effect immediately (no manual refresh needed).
    val homeLayout = prefs.homeSectionLayout
    val homeGridColumns = prefs.homeGridColumns
    val homeSectionLimit = prefs.homeSectionLimit
    val homeDisplayMode = prefs.homeDisplayMode
    val itemLimit = homeSectionLimit

    // Continue reading = Komga's /books/ondeck (book-level, matches web UI).
    var inProgress by remember { mutableStateOf<List<BookDto>>(emptyList()) }
    var recentSeries by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var addedSeries by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var addedBooks by remember { mutableStateOf<List<BookDto>>(emptyList()) }
    var readBooks by remember { mutableStateOf<List<BookDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadAll() {
        loading = true
        runCatching {
            // 继续阅读 (Keep Reading, web dashboard): in-progress books sorted by
            // read progress timestamp — NOT /books/ondeck (that is the web's
            // "On Deck / 导航" which the user does not want).
            val p = client.getBooks(
                readStatus = "IN_PROGRESS",
                sort = "readProgress.readDate,desc",
                size = itemLimit,
            ).content
            // 最近更新系列 (Recently Updated Series): official /series/updated.
            val u = client.getUpdatedSeries(size = itemLimit)
            val a = client.getSeries(sort = "createdDate,desc", size = itemLimit).content
            val ab = client.getBooks(sort = "createdDate,desc", size = itemLimit).content
            // 最近阅读书籍 (Recently Read Books): finished books by read date.
            val rb = client.getBooks(
                readStatus = "READ",
                sort = "readProgress.readDate,desc",
                size = itemLimit,
            ).content
            HomeData(p, u, a, ab, rb)
        }.onSuccess {
            inProgress = it.inProgress
            recentSeries = it.recentSeries
            addedSeries = it.addedSeries
            addedBooks = it.addedBooks
            readBooks = it.readBooks
            error = null
        }.onFailure {
            error = context.getString(R.string.load_failed, it.message)
        }
        loading = false
    }

    LaunchedEffect(Unit, refreshTick, homeRefresh) { loadAll() }

    // Search is hosted at the screen level (title-row icon), not per tab.
    Box(Modifier.fillMaxSize()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { scope.launch { loadAll() } }) { Text(composeStringResource(R.string.retry)) }
                    }
                }
                inProgress.isEmpty() && recentSeries.isEmpty() && addedSeries.isEmpty() &&
                    addedBooks.isEmpty() && readBooks.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(composeStringResource(R.string.no_series))
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // M3.18: render sections in the user-configured order,
                    // skipping hidden ones (those not in homeSectionOrder).
                    val order = prefs.homeSectionOrder
                        .split(',')
                        .mapNotNull { name -> runCatching { HomeSection.valueOf(name) }.getOrNull() }
                    val data = HomeData(inProgress, recentSeries, addedSeries, addedBooks, readBooks)

                    // Quick layout toggle removed from body — now lives in the toolbar options menu.
                    order.forEach { section ->
                        when (section) {
                            HomeSection.ContinueReading -> {
                                if (data.inProgress.isNotEmpty()) {
                                    item {
                                        HomeSectionHeader(section.labelText(), onClick = {
                                            context.startActivity(
                                                Intent(context, KomgaSectionListActivity::class.java)
                                                    .putExtra("section", section.name),
                                            )
                                        })
                                    }
                                    item {
                                        // Continue-reading books open the reader directly.
                                        HomeContinueReadingRow(client, data.inProgress, layout = homeLayout, columns = homeGridColumns, mode = homeDisplayMode) { bookId, bookName ->
                                            scope.launch {
                                                runCatching { KomgaReaderLauncher.open(context, client, bookId) }
                                                    .onFailure {
                                                        android.widget.Toast.makeText(
                                                            context, context.getString(R.string.open_reader_failed, it.message), android.widget.Toast.LENGTH_LONG,
                                                        ).show()
                                                    }
                                            }
                                        }
                                    }
                                }
                            }
                            HomeSection.RecentlyAddedBooks -> {
                                if (data.addedBooks.isNotEmpty()) {
                                    item {
                                        HomeSectionHeader(section.labelText(), onClick = {
                                            context.startActivity(
                                                Intent(context, KomgaSectionListActivity::class.java)
                                                    .putExtra("section", section.name),
                                            )
                                        })
                                    }
                                    item {
                                        HomeBookRow(client, data.addedBooks, layout = homeLayout, columns = homeGridColumns, mode = homeDisplayMode) { bookId, bookName ->
                                            scope.launch {
                                                runCatching { KomgaReaderLauncher.open(context, client, bookId) }
                                                    .onFailure {
                                                        android.widget.Toast.makeText(
                                                            context, context.getString(R.string.open_reader_failed, it.message), android.widget.Toast.LENGTH_LONG,
                                                        ).show()
                                                    }
                                            }
                                        }
                                    }
                                }
                            }
                            HomeSection.RecentlyAddedSeries -> {
                                if (data.addedSeries.isNotEmpty()) {
                                    item {
                                        HomeSectionHeader(section.labelText(), onClick = {
                                            context.startActivity(
                                                Intent(context, KomgaSectionListActivity::class.java)
                                                    .putExtra("section", section.name),
                                            )
                                        })
                                    }
                                    item {
                                        HomeSeriesRow(client, data.addedSeries, showProgress = false, layout = homeLayout, columns = homeGridColumns, mode = homeDisplayMode) { onSeriesClick(it) }
                                    }
                                }
                            }
                            HomeSection.RecentlyUpdatedSeries -> {
                                if (data.recentSeries.isNotEmpty()) {
                                    item {
                                        HomeSectionHeader(section.labelText(), onClick = {
                                            context.startActivity(
                                                Intent(context, KomgaSectionListActivity::class.java)
                                                    .putExtra("section", section.name),
                                            )
                                        })
                                    }
                                    item {
                                        HomeSeriesRow(client, data.recentSeries, showProgress = false, layout = homeLayout, columns = homeGridColumns, mode = homeDisplayMode) { onSeriesClick(it) }
                                    }
                                }
                            }
                            HomeSection.RecentlyReadBooks -> {
                                if (data.readBooks.isNotEmpty()) {
                                    item {
                                        HomeSectionHeader(section.labelText(), onClick = {
                                            context.startActivity(
                                                Intent(context, KomgaSectionListActivity::class.java)
                                                    .putExtra("section", section.name),
                                            )
                                        })
                                    }
                                    item {
                                        HomeBookRow(client,  data.readBooks, layout = homeLayout, columns = homeGridColumns, mode = homeDisplayMode) { bookId, bookName ->
                                            scope.launch {
                                                runCatching { KomgaReaderLauncher.open(context, client, bookId) }
                                                    .onFailure {
                                                        android.widget.Toast.makeText(
                                                            context, context.getString(R.string.open_reader_failed, it.message), android.widget.Toast.LENGTH_LONG,
                                                        ).show()
                                                    }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}

/** Bundle of the five Home sections loaded in one pass. */
private data class HomeData(
    val inProgress: List<BookDto>,
    val recentSeries: List<SeriesDto>,
    val addedSeries: List<SeriesDto>,
    val addedBooks: List<BookDto>,
    val readBooks: List<BookDto>,
)
/**
 * Home tab sections. Also used by KomgaSectionListActivity ("全部" full lists).
 * The display type tells the list activity whether to show series or books.
 */
enum class HomeSection(@StringRes val labelRes: Int, val isSeries: Boolean) {
    // Continue reading = Komga's /books/ondeck (book-level, same as the web UI),
    // NOT series?read_status=IN_PROGRESS — the two never matched each other.
    ContinueReading(R.string.home_section_continue, false),
    RecentlyAddedBooks(R.string.home_section_added_books, false),
    RecentlyAddedSeries(R.string.home_section_added_series, true),
    RecentlyUpdatedSeries(R.string.home_section_updated_series, true),
    RecentlyReadBooks(R.string.home_section_read_books, false),
    ;

    @Composable
    fun labelText(): String = composeStringResource(labelRes)
}

/** Horizontal scrollable row of book covers (used on the Home tab). */
@Composable
private fun HomeBookRow(
    client: KomgaApiClient,
    books: List<BookDto>,
    layout: String = "CAROUSEL",
    columns: Int = 0,
    mode: String = "COMFORTABLE_GRID",
    onBookClick: (String,  String) -> Unit,
) {
    when (mode) {
        "LIST" -> HomeListColumn {
            books.forEach { b ->
                HomeBookListItem(client, b, onClick = { onBookClick(b.id, b.metadata.title ?: b.name) })
            }
        }
        else -> {
            val cardModifier = if (mode == "COMPACT_GRID") Modifier.width(80.dp) else Modifier.width(100.dp)
            if (layout == "GRID") {
                GridWrap(
                    columns = columns,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    minCellWidth = if (mode == "COMPACT_GRID") 80.dp else 100.dp,
                ) { cellModifier ->
                    books.forEach { b ->
                        HomeBookCard(client, b, cellModifier, compact = mode == "COMPACT_GRID", onClick = { onBookClick(b.id, b.metadata.title ?: b.name) })
                    }
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(books.size) { i ->
                        val b = books[i]
                        HomeBookCard(client, b, cardModifier, compact = mode == "COMPACT_GRID", onClick = { onBookClick(b.id, b.metadata.title ?: b.name) })
                    }
                }
            }
        }
    }
}

/**
 * Flexible grid wrapper for Home sections.
 * - [columns] >= 1: fixed number of equal-width columns computed from available width.
 * - [columns] <= 0 (auto): adaptive column count derived from [minCellWidth]
 *   (equivalent to GridCells.Adaptive), so phones land at a sensible ~3-4 columns
 *   instead of collapsing to a single full-width column.
 * No nested scrolling — safe inside a LazyColumn.
 */
@Composable
private fun GridWrap(
    columns: Int,
    modifier: Modifier = Modifier,
    minCellWidth: Dp = 100.dp,
    content: @Composable (cellModifier: Modifier) -> Unit,
) {
    val gap = 12.dp
    BoxWithConstraints(modifier = modifier) {
        val resolvedColumns = if (columns <= 0) {
            max(1, ((maxWidth + gap) / (minCellWidth + gap)).toInt())
        } else {
            columns
        }
        // 用整数像素精确划分，避免浮点 cellWidth 在大列数宽屏下累积误差
        // 导致每行右侧留空。roundToPx/toDp 在部分 Compose 版本不是
        // BoxWithConstraintsScope 的可用扩展，这里改用 Density 比例手工换算
        // （Dp.value / Dp(Float) / LocalDensity.density 均为稳定公开 API）。
        val density = LocalDensity.current.density
        val gapPx = (gap.value * density).roundToInt()
        val cellPx = ((maxWidth.value * density).roundToInt() - gapPx * (resolvedColumns - 1)) / resolvedColumns
        val cellWidth = Dp(cellPx / density)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            maxItemsInEachRow = resolvedColumns,
        ) {
            content(Modifier.width(cellWidth))
        }
    }
}

@Composable
private fun HomeBookCard(client: KomgaApiClient, b: BookDto, modifier: Modifier = Modifier.width(100.dp), compact: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            KomgaCover(
                client = client,
                url = client.bookThumbnailUrl(b.id),
                modifier = Modifier.fillMaxSize(),
            )
            // Compact grid: overlay 书名 + 章节名 inside the cover.
            if (compact) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                                startY = 0f,
                                endY = 48f,
                            ),
                        )
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                ) {
                    Column {
                        b.seriesTitle?.let {
                            Text(
                                text = it,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = b.metadata.title ?: b.name,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = b.metadata.title ?: b.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            b.seriesTitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** LIST-mode item: horizontal row with a small thumbnail + title/subtitle. */
@Composable
private fun HomeBookListItem(client: KomgaApiClient, b: BookDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .aspectRatio(3f / 4f),
        ) {
            KomgaCover(
                client = client,
                url = client.bookThumbnailUrl(b.id),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = b.metadata.title ?: b.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            b.seriesTitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Horizontal row of continue-reading books with a thin progress bar. */
@Composable
private fun HomeContinueReadingRow(
    client: KomgaApiClient,
    books: List<BookDto>,
    layout: String = "CAROUSEL",
    columns: Int = 0,
    mode: String = "COMFORTABLE_GRID",
    onBookClick: (String, String) -> Unit,
) {
    when (mode) {
        "LIST" -> HomeListColumn {
            books.forEach { b ->
                HomeContinueReadingListItem(client, b, onClick = { onBookClick(b.id, b.metadata.title ?: b.name) })
            }
        }
        else -> {
            val cardModifier = if (mode == "COMPACT_GRID") Modifier.width(80.dp) else Modifier.width(100.dp)
            if (layout == "GRID") {
                GridWrap(
                    columns = columns,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    minCellWidth = if (mode == "COMPACT_GRID") 80.dp else 100.dp,
                ) { cellModifier ->
                    books.forEach { b ->
                        HomeContinueReadingCard(client, b, cellModifier, compact = mode == "COMPACT_GRID", onClick = { onBookClick(b.id, b.metadata.title ?: b.name) })
                    }
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(books.size) { i ->
                        val b = books[i]
                        HomeContinueReadingCard(client, b, cardModifier, compact = mode == "COMPACT_GRID", onClick = { onBookClick(b.id, b.metadata.title ?: b.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeContinueReadingCard(client: KomgaApiClient, b: BookDto, modifier: Modifier = Modifier.width(100.dp), compact: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            KomgaCover(
                client = client,
                url = client.bookThumbnailUrl(b.id),
                modifier = Modifier.fillMaxSize(),
            )
            // Compact grid: overlay 书名 + 章节名 inside the cover.
            if (compact) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                                startY = 0f,
                                endY = 48f,
                            ),
                        )
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                ) {
                    Column {
                        b.seriesTitle?.let {
                            Text(
                                text = it,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = b.metadata.title ?: b.name,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        val rp = b.readProgress
        if (rp != null && b.media.pagesCount > 0) {
            LinearProgressIndicator(
                progress = { (rp.page.toFloat() / b.media.pagesCount).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
                    .height(3.dp),
            )
        }
        if (!compact) {
            Text(
                text = b.metadata.title ?: b.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            b.seriesTitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** LIST-mode item for continue-reading (thumbnail + title + thin progress). */
@Composable
private fun HomeContinueReadingListItem(client: KomgaApiClient, b: BookDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .aspectRatio(3f / 4f),
        ) {
            KomgaCover(
                client = client,
                url = client.bookThumbnailUrl(b.id),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            b.seriesTitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = b.metadata.title ?: b.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val rp = b.readProgress
            if (rp != null && b.media.pagesCount > 0) {
                LinearProgressIndicator(
                    progress = { (rp.page.toFloat() / b.media.pagesCount).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(3.dp),
                )
            }
        }
    }
}

/** Vertical list container for LIST display mode (safe inside LazyColumn). */
@Composable
private fun HomeListColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        content()
    }
}

@Composable
private fun HomeSectionHeader(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = composeStringResource(R.string.section_all),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

/** Horizontal scrollable row of series covers (used on the Home tab). */
@Composable
private fun HomeSeriesRow(
    client: KomgaApiClient,
    series: List<SeriesDto>,
    showProgress: Boolean,
    layout:  String = "CAROUSEL",
    columns: Int = 0,
    mode: String = "COMFORTABLE_GRID",
    onSeriesClick: (String) -> Unit,
) {
    when (mode) {
        "LIST" -> HomeListColumn {
            series.forEach { s ->
                HomeSeriesListItem(client, s, showProgress, onClick = { onSeriesClick(s.id) })
            }
        }
        else -> {
            val cardModifier = if (mode == "COMPACT_GRID") Modifier.width(80.dp) else Modifier.width(100.dp)
            if (layout == "GRID") {
                GridWrap(
                    columns = columns,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    minCellWidth = if (mode == "COMPACT_GRID") 80.dp else 100.dp,
                ) { cellModifier ->
                    series.forEach { s ->
                        HomeSeriesCard(client, s, showProgress, cellModifier, compact = mode == "COMPACT_GRID", onClick = { onSeriesClick(s.id) })
                    }
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(series.size) { i ->
                        val s = series[i]
                        HomeSeriesCard(client, s, showProgress, cardModifier, compact = mode == "COMPACT_GRID", onClick = { onSeriesClick(s.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSeriesCard(
    client: KomgaApiClient,
    s: SeriesDto,
    showProgress: Boolean,
    modifier: Modifier = Modifier.width(100.dp),
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            KomgaCover(
                client = client,
                url = client.seriesThumbnailUrl(s.id),
                modifier = Modifier.fillMaxSize(),
            )
            // Compact grid: overlay 书名 inside the cover.
            if (compact) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                                startY = 0f,
                                endY = 48f,
                            ),
                        )
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = s.name,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = s.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showProgress && s.booksCount > 0) {
                // Thin progress bar: read / total.
                val fraction = (s.booksReadCount.toFloat() / s.booksCount).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
    }
}

/** LIST-mode item for a series (thumbnail + title + progress). */
@Composable
private fun HomeSeriesListItem(
    client: KomgaApiClient,
    s: SeriesDto,
    showProgress: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .aspectRatio(3f / 4f),
        ) {
            KomgaCover(
                client = client,
                url = client.seriesThumbnailUrl(s.id),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = s.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showProgress && s.booksCount > 0) {
                val fraction = (s.booksReadCount.toFloat() / s.booksCount).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(3.dp),
                )
            }
        }
    }
}

// ---------- Library tab ----------

@Composable
/**
 * Top-bar library picker (replaces the old AlertDialog). Shows the current
 * library name with a caret; tapping opens a dropdown of all libraries.
 * Selecting one switches the active library in place — no dialog, no extra
 * screen, and back no longer gets trapped in a "pick a library" state.
 */
private fun LibrarySelector(
    libraries: List<LibraryDto>,
    selectedLibraryId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = libraries.firstOrNull { it.id == selectedLibraryId }
    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current?.name ?: composeStringResource(R.string.select_library),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = composeStringResource(R.string.select_library),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (libraries.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(composeStringResource(R.string.no_libraries)) },
                    onClick = { expanded = false },
                    enabled = false,
                )
            }
            libraries.forEach { lib ->
                DropdownMenuItem(
                    text = { Text(lib.name) },
                    trailingIcon = { if (lib.id == selectedLibraryId) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        onSelect(lib.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryTab(
    client: KomgaApiClient,
    selectedLibraryId: String?,
    displayMode: LibraryDisplayMode,
    columns: Int,
    refreshTick: Int,
    sort: LibrarySort,
    onSortModeChange: (LibrarySort) -> Unit,
    readFilter: ReadFilter,
    onReadFilterChange: (ReadFilter) -> Unit,
    filterType: String? = null,
    filterValue: String? = null,
    onFilterClear: () -> Unit = {},
    onSeriesClick: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // ── Mihon-style series multi-select ──
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showReadlistPicker by remember { mutableStateOf(false) }
    var showCollectionPicker by remember { mutableStateOf(false) }

    // 独立的选择模式状态：长按 item 进入选择时置 true，点取消(X)才置 false。
    // 不再用 selectedIds.isNotEmpty() 推导，否则全选后反选=空集会误退选择栏。
    var selectionMode by remember { mutableStateOf(false) }
    val inSelection = selectionMode
    val selectedSeries = remember(selectedIds,  series) { series.filter { it.id in selectedIds } }

    val setSeriesSelect: (String, Boolean) -> Unit = { id, value ->
        selectedIds = if (value) selectedIds + id else selectedIds - id
    }
    val toggleSeriesSelect: (String) -> Unit = { id -> setSeriesSelect(id, id !in selectedIds) }
    val exitSeriesSelection: () -> Unit = { selectionMode = false; selectedIds = emptySet() }

    // 选择状态下拦截系统返回手势：退出选择而不是退出程序
    BackHandler(inSelection) { exitSeriesSelection() }

    fun markSeriesBatch(completed: Boolean) {
        val snapshot = selectedSeries.map { it.id }
        scope.launch {
            runCatching {
                snapshot.forEach { sid ->
                    if (completed) client.markSeriesRead(sid) else client.markSeriesUnread(sid)
                }
            }.onSuccess {
                android.widget.Toast.makeText(
                    context,
                    context.getString(if (completed) R.string.marked_series_read else R.string.marked_series_unread),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                exitSeriesSelection()
            }.onFailure {
                android.widget.Toast.makeText(context, context.getString(R.string.operation_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // sortMode / readFilter (filter) are owned by the top-level toolbar menu
    // (ShelfOptionsMenu); this tab just renders from them.

    suspend fun reload() {
        // A tag/genre/author filter is cross-library: we intentionally omit library_id
        // so Komga searches across ALL libraries (Komga WebUI / Komelia parity). When no
        // filter is active we scope to the selected library as before.
        val isCrossLibraryFilter = !filterType.isNullOrBlank()
        if (selectedLibraryId == null && !isCrossLibraryFilter) {
            // No library chosen yet — the top-bar dropdown is the picker now.
            loading = false
            series = emptyList()
            return
        }
        loading = true
        runCatching {
            client.getSeries(
                libraryId = if (isCrossLibraryFilter) null else selectedLibraryId,
                readStatus = readFilter.komgaValue,
                tag = if (filterType == "tag") listOf(filterValue ?: "") else null,
                genre = if (filterType == "genre") listOf(filterValue ?: "") else null,
                author = if (filterType == "author") listOf(filterValue ?: "") else null,
                sort = sort.komgaSort,
                size = 200,
            ).content
        }
            .onSuccess { series = it; error = null }
            .onFailure { error = context.getString(R.string.load_series_failed, it.message) }
        loading = false
    }

    LaunchedEffect(selectedLibraryId, readFilter, sort, refreshTick, filterType, filterValue) { reload() }

    // Series-level date-read sort uses the Komga series property `readDate` (not the book-level
    // `readProgress.readDate`, which the series endpoint does not recognise and silently ignores).
    val sortedSeries = series

    Column(Modifier.fillMaxSize()) {
        // ── Active tag/genre/author filter chip (from Series detail page tap) ──
        if (!filterType.isNullOrBlank() && !filterValue.isNullOrBlank()) {
            val labelRes = when (filterType) {
                "author" -> R.string.filter_author_active
                "genre" -> R.string.filter_genre_active
                else -> R.string.filter_tag_active
            }
            // author filter value is "name,role"; show only the name in the chip.
            val displayValue = if (filterType == "author") filterValue.substringBefore(",") else filterValue
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = composeStringResource(labelRes, displayValue),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onFilterClear, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = composeStringResource(R.string.clear))
                    }
                }
            }
        }
        // ── Mihon-style selection top bar: 取消 / 已选 N 项 / 全选 ──
        if (inSelection) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = exitSeriesSelection) {
                        Icon(Icons.Filled.Close, contentDescription = composeStringResource(R.string.cancel))
                    }
                    Text(
                        composeStringResource(R.string.selected_count, selectedIds.size),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { selectedIds = series.map { it.id }.toSet() }) {
                        Icon(Icons.Filled.SelectAll, contentDescription = composeStringResource(R.string.select_all))
                    }
                    IconButton(onClick = { selectedIds = series.map { it.id }.toSet() - selectedIds }) {
                        Icon(Icons.Outlined.FlipToBack, contentDescription = composeStringResource(R.string.select_inverse))
                    }
                }
            }
        }

        // Search + filter/sort/display live in the toolbar (Tune button →
        // ShelfOptionsMenu); the shelf body just renders the results.
        val shelfModifier = if (inSelection) Modifier.weight(1f) else Modifier.fillMaxSize()
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { scope.launch { reload() } }) { Text(composeStringResource(R.string.retry)) }
                }
            }
            selectedLibraryId == null && filterType.isNullOrBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(composeStringResource(R.string.select_library_hint))
            }
            series.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(composeStringResource(R.string.no_series_in_library))
            }
            else -> if (displayMode == LibraryDisplayMode.List) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = shelfModifier
                ) {
                    items(sortedSeries, key = { it.id }) { s ->
                        LibrarySeriesListRow(
                            client,
                            s,
                            onClick = { if (inSelection) toggleSeriesSelect(s.id) else onSeriesClick(s.id) },
                            onLongClick = { selectionMode = true; toggleSeriesSelect(s.id) },
                            selected = s.id in selectedIds,
                        )
                    }
                }
            } else {
                // M3.20: the display mode drives BOTH the auto column density
                // (Adaptive min size) AND the grid spacing. This way 紧凑网格 /
                // 舒适网格 always has a visible effect — even when the user pins
                // a fixed column count via the slider (columns > 0, where the
                // Adaptive min size is ignored and the two modes would otherwise
                // render identically).
                val isCompact = displayMode == LibraryDisplayMode.CompactGrid
                val adaptiveMin = if (isCompact) 96.dp else 168.dp
                val hSpace = if (isCompact) 4.dp else 8.dp
                val vSpace = if (isCompact) 6.dp else 12.dp
                val cells = if (columns > 0) {
                    GridCells.Fixed(columns)
                } else {
                    GridCells.Adaptive(minSize = adaptiveMin)
                }
                val gridState = rememberLazyGridState()
                LazyVerticalGrid(
                    state = gridState,
                    columns = cells,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(hSpace),
                    verticalArrangement = Arrangement.spacedBy(vSpace),
                    modifier = shelfModifier
                ) {
                    items(sortedSeries, key = { it.id }) { s ->
                        LibrarySeriesCard(
                            client,
                            s,
                            onClick = { if (inSelection) toggleSeriesSelect(s.id) else onSeriesClick(s.id) },
                            onLongClick = { selectionMode = true; toggleSeriesSelect(s.id) },
                            selected = s.id in selectedIds,
                            titleInside = isCompact,
                        )
                    }
                }
            }
        }

        // ── Mihon-style selection bottom bar: 收藏 / 阅读列表 / 已读 / 未读 ──
        if (inSelection) {
            val selectedIdsSnapshot = selectedSeries.map { it.id }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    SelectionActionItem(
                        icon = Icons.Outlined.Bookmark,
                        label = composeStringResource(R.string.add_to_collection),
                        onClick = { showCollectionPicker = true },
                    )
                    SelectionActionItem(
                        icon = Icons.Outlined.BookmarkAdd,
                        label = composeStringResource(R.string.add_to_readlist),
                        onClick = { showReadlistPicker = true },
                    )
                    SelectionActionItem(
                        icon = Icons.Outlined.DoneAll,
                        label = composeStringResource(R.string.mark_read),
                        onClick = { markSeriesBatch(true) },
                    )
                    SelectionActionItem(
                        icon = Icons.Outlined.RemoveDone,
                        label = composeStringResource(R.string.mark_unread),
                        onClick = { markSeriesBatch(false) },
                    )
                }
            }
        }
    }

    if (showReadlistPicker) {
        SeriesReadlistPickerDialog(
            client = client,
            seriesIds = selectedSeries.map { it.id },
            onDismiss = { showReadlistPicker = false },
            onAdded = { showReadlistPicker = false; exitSeriesSelection() },
        )
    }
    if (showCollectionPicker) {
        SeriesCollectionPickerDialog(
            client = client,
            seriesIds = selectedSeries.map { it.id },
            onDismiss = { showCollectionPicker = false },
            onAdded = { showCollectionPicker = false; exitSeriesSelection() },
        )
    }
}

/**
 * M3.11 / UI simplification: the single toolbar "display options" (Tune)
 * button opens a MihonSY-style tabbed bottom sheet — Filter · Sort · Display —
 * replacing the old separate funnel dropdown + display dialog.
 */
@Composable
private fun ShelfOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    displayMode: LibraryDisplayMode,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    columns: Int,
    onColumnChange: (Int) -> Unit,
    sort: LibrarySort,
    onSortModeChange: (LibrarySort) -> Unit,
    readFilter: ReadFilter,
    onReadFilterChange: (ReadFilter) -> Unit,
) {
    if (!expanded) return

    val tabTitles = listOf(
        composeStringResource(R.string.read_status_header),
        composeStringResource(R.string.sort_header),
        composeStringResource(R.string.display_mode_header),
    )

    TabbedDialog(
        onDismissRequest = onDismiss,
        tabTitles = tabTitles,
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> {
                    ReadFilter.entries.forEach { f ->
                        CheckboxItem(
                            label = f.labelText(),
                            checked = readFilter == f,
                            onClick = { onReadFilterChange(f) },
                        )
                    }
                }
                1 -> {
                    LibrarySortBy.entries.forEach { m ->
                        SortItem(
                            label = m.labelText(),
                            sortDescending = if (sort.sortBy == m) sort.descending else null,
                            onClick = {
                                onSortModeChange(
                                    if (sort.sortBy == m) {
                                        sort.copy(descending = !sort.descending)
                                    } else {
                                        LibrarySort(m, m.defaultDescending)
                                    },
                                )
                            },
                        )
                    }
                }
                2 -> {
                    Text(
                        text = composeStringResource(R.string.display_mode_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LibraryDisplayMode.entries.forEach { m ->
                            FilterChip(
                                selected = displayMode == m,
                                onClick = { onDisplayModeChange(m) },
                                label = { Text(m.labelText()) },
                            )
                        }
                    }
                    if (displayMode != LibraryDisplayMode.List) {
                        SliderItem(
                            value = columns,
                            valueRange = 0..10,
                            label = composeStringResource(R.string.pref_library_columns),
                            valueString = if (columns > 0) {
                                columns.toString()
                            } else {
                                composeStringResource(R.string.label_auto)
                            },
                            onChange = onColumnChange,
                        )
                    }
                }
            }
        }
    }
}

/** Sortable fields on the library shelf, aligned with Komga WebUI. */
private enum class LibrarySortBy(
    @StringRes val labelRes: Int,
    val prefKey: String,
    val komgaField: String?,
    val defaultDescending: Boolean,
) {
    Name(R.string.sort_name, "name", "name", false),
    DateAdded(R.string.sort_date_added, "dateAdded", "createdDate", true),
    DateUpdated(R.string.sort_date_updated, "dateUpdated", "lastModifiedDate", true),
    DateRead(R.string.sort_date_read, "lastread", "readDate", true);

    @Composable
    fun labelText(): String = composeStringResource(labelRes)
}

/** Current sort selection: field + direction. */
@Immutable
private data class LibrarySort(
    val sortBy: LibrarySortBy,
    val descending: Boolean,
) {
    val komgaSort: String?
        get() = sortBy.komgaField?.let { "$it,${if (descending) "desc" else "asc"}" }

    fun toPref(): String = "${sortBy.prefKey},${if (descending) "desc" else "asc"}"

    companion object {
        fun fromPref(v: String): LibrarySort {
            val parts = v.split(",")
            val key = parts.getOrNull(0).orEmpty()
            val dir = parts.getOrNull(1).orEmpty()
            val descending = dir.equals("desc", ignoreCase = true)
            // Backward compatibility with old pref values.
            val by = when (key.lowercase()) {
                "title" -> LibrarySortBy.Name
                "lastmodified" -> LibrarySortBy.DateUpdated
                "lastread", "dateread" -> LibrarySortBy.DateRead
                else -> LibrarySortBy.entries.find { it.prefKey == key } ?: LibrarySortBy.Name
            }
            return LibrarySort(by, descending)
        }
    }
}

/** M3.20: library display modes, mirroring Mihon's LibraryDisplayMode. */
enum class LibraryDisplayMode(@StringRes val labelRes: Int, val prefValue: String) {
    CompactGrid(R.string.display_compact_grid, "COMPACT_GRID"),
    ComfortableGrid(R.string.display_comfortable_grid, "COMFORTABLE_GRID"),
    List(R.string.display_list, "LIST");

    @Composable
    fun labelText(): String = composeStringResource(labelRes)

    companion object {
        fun fromPref(v: String): LibraryDisplayMode =
            entries.find { it.prefValue == v } ?: CompactGrid
    }
}

/**
 * M3.20: display settings dialog — display mode chips + columns slider
 * (0 = auto). Portrait/landscape column counts are stored separately,
 * like Mihon's portraitColumns / landscapeColumns.
 * (DisplaySettingsDialog now lives in ShelfComponents.kt and is shared.)
 */

/** One row for shelf list mode — cover thumbnail + title + read state. */
@Composable
fun LibrarySeriesListRow(
    client: KomgaApiClient,
    series: SeriesDto,
    onClick: () -> Unit,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        border = BorderStroke(2.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KomgaCover(
                client = client,
                url = client.seriesThumbnailUrl(series.id),
                modifier = Modifier.width(48.dp).height(64.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(series.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    text = composeStringResource(R.string.series_books_summary, series.booksCount, series.booksReadCount, series.booksUnreadCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * M3: two-column library card per the UI spec — cover on top, name and a
 * read-state label (未读 N / 已读 x% / 已读完) below.
 */
/**
 * M3.11: compact shelf card in the style of Mihon's library grid —
 * cover on top, unread count pill on the cover (top-left), title below
 * the cover with up to two lines.
 */
@Composable
fun LibrarySeriesCard(
    client: KomgaApiClient,
    series: SeriesDto,
    onClick: () -> Unit,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
    titleInside: Boolean = false,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        border = BorderStroke(2.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            ) {
                KomgaCover(
                    client = client,
                    url = client.seriesThumbnailUrl(series.id),
                    modifier = Modifier.fillMaxSize(),
                )
                // Mihon-style selection check overlay.
                if (selected) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                }
                // Unread count pill — mihon-style overlay on the cover.
                if (series.booksUnreadCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                    ) {
                        Text(
                            text = series.booksUnreadCount.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                // Compact grid overlays the title on the cover bottom (like
                // Mihon's library grid); comfortable grid shows it below.
                if (titleInside) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                                ),
                            )
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = series.name,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!titleInside) {
                Text(
                    text = series.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Mihon-style "add series to readlist" dialog (series-level equivalent of
 * BookShelf's ReadlistPickerDialog). Supports picking an existing readlist
 * or typing a new name to create one.
 */
@Composable
private fun SeriesReadlistPickerDialog(
    client: KomgaApiClient,
    seriesIds: List<String>,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var readlists by remember { mutableStateOf<List<ReadingListDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { client.getReadlists() }
            .onSuccess { readlists = it }
            .onFailure { error = it.message }
        loading = false
    }

    val filtered = remember(readlists, query) {
        if (query.isBlank()) readlists
        else readlists.filter { it.name.contains(query, ignoreCase = true) }
    }

    fun addToReadlist(readlistId: String) {
        scope.launch {
            runCatching { client.addSeriesToReadlist(readlistId, seriesIds) }
                .onSuccess {
                    android.widget.Toast.makeText(context, context.getString(R.string.added_to_readlist), android.widget.Toast.LENGTH_SHORT).show()
                    onAdded()
                }
                .onFailure {
                    android.widget.Toast.makeText(context, context.getString(R.string.add_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
                }
        }
    }

    fun createAndAdd() {
        val name = query.trim()
        if (name.isBlank()) return
        scope.launch {
            runCatching {
                // Komga 禁止创建空阅读列表（bookIds 必须 ≥1），故先把所选系列
                // 展开成书，用完整 bookIds 一次性建表（含分页，覆盖大系列）。
                val allBookIds = mutableListOf<String>()
                for (sid in seriesIds) {
                    var page = 0
                    do {
                        val resp = client.getSeriesBooks(sid, page = page, size = 200)
                        allBookIds += resp.content.map { it.id }
                        page++
                    } while (resp.content.isNotEmpty() && page * resp.size < resp.totalElements)
                }
                if (allBookIds.isEmpty()) {
                    throw KomgaException(context.getString(R.string.no_books_to_add))
                }
                client.createReadlist(name, allBookIds)
            }.onSuccess {
                android.widget.Toast.makeText(context, context.getString(R.string.created_and_added), android.widget.Toast.LENGTH_SHORT).show()
                onAdded()
            }.onFailure {
                android.widget.Toast.makeText(context, context.getString(R.string.create_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(composeStringResource(R.string.add_to_readlist_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(composeStringResource(R.string.search_or_create_readlist)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = ::createAndAdd, enabled = query.isNotBlank()) {
                        Text(composeStringResource(R.string.create))
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Text(error ?: context.getString(R.string.load_failed_short), color = MaterialTheme.colorScheme.error)
                    filtered.isEmpty() -> Text(
                        if (query.isBlank()) composeStringResource(R.string.no_readlists) else composeStringResource(R.string.no_match_create_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Column {
                        filtered.forEach { rl ->
                            TextButton(
                                onClick = { addToReadlist(rl.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(rl.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(composeStringResource(R.string.cancel)) } },
    )
}

/**
 * Mihon-style "add series to collection" dialog. Supports picking an existing
 * collection or typing a new name to create one (createCollection attaches the
 * selected seriesIds in one call).
 */
@Composable
private fun SeriesCollectionPickerDialog(
    client: KomgaApiClient,
    seriesIds: List<String>,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var collections by remember { mutableStateOf<List<CollectionDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { client.getCollections() }
            .onSuccess { collections = it }
            .onFailure { error = it.message }
        loading = false
    }

    val filtered = remember(collections, query) {
        if (query.isBlank()) collections
        else collections.filter { it.name.contains(query, ignoreCase = true) }
    }

    fun addToCollection(collectionId: String) {
        scope.launch {
            runCatching { client.addSeriesToCollection(collectionId, seriesIds) }
                .onSuccess {
                    android.widget.Toast.makeText(context, context.getString(R.string.added_to_collection), android.widget.Toast.LENGTH_SHORT).show()
                    onAdded()
                }
                .onFailure {
                    android.widget.Toast.makeText(context, context.getString(R.string.add_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
                }
        }
    }

    fun createAndAdd() {
        val name = query.trim()
        if (name.isBlank()) return
        scope.launch {
            runCatching { client.createCollection(name, seriesIds) }
                .onSuccess {
                    android.widget.Toast.makeText(context, context.getString(R.string.created_and_added), android.widget.Toast.LENGTH_SHORT).show()
                    onAdded()
                }
                .onFailure {
                    android.widget.Toast.makeText(context, context.getString(R.string.create_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
                }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(composeStringResource(R.string.add_to_collection_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(composeStringResource(R.string.search_or_create_collection)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = ::createAndAdd, enabled = query.isNotBlank()) {
                        Text(composeStringResource(R.string.create))
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Text(error ?: context.getString(R.string.load_failed_short), color = MaterialTheme.colorScheme.error)
                    filtered.isEmpty() -> Text(
                        if (query.isBlank()) composeStringResource(R.string.no_collections) else composeStringResource(R.string.no_match_create_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Column {
                        filtered.forEach { c ->
                            TextButton(
                                onClick = { addToCollection(c.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(composeStringResource(R.string.cancel)) } },
    )
}

// ---------- Lists tab (readlists + collections) ----------

/** 待删除项的描述，用于确认弹窗。 */
private data class DeleteTarget(
    val kind: String, // "readlist" 或 "collection"
    val id: String,
    val name: String,
)

/** Lists tab: shows both reading lists and collections (收藏). */
@Composable
private fun ListsTab(
    client: KomgaApiClient,
    onReadlistClick: (String, String) -> Unit,
    onCollectionClick: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var readlists by remember { mutableStateOf<List<ReadingListDto>>(emptyList()) }
    var collections by remember { mutableStateOf<List<CollectionDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 删除确认弹窗状态：待删除项的类型与 id/名称
    var pendingDelete by remember { mutableStateOf<DeleteTarget?>(null) }

    fun load() {
        loading = true
        error = null
        scope.launch {
            runCatching {
                // 列表端点通常不返回 booksCount（computed 字段被省略），逐条用详情覆盖。
                readlists = client.getReadlists().map { list ->
                    runCatching { client.getReadlist(list.id) }.getOrNull()?.let { detail ->
                        list.copy(booksCount = detail.booksCount, bookIds = detail.bookIds)
                    } ?: list
                }
                // 收藏统计用 seriesIds.size；列表端点已返回，但同样用详情兜底保证准确。
                collections = client.getCollections().map { col ->
                    runCatching { client.getCollection(col.id) }.getOrNull()?.let { detail ->
                        if (detail.seriesIds.isNotEmpty()) col.copy(seriesIds = detail.seriesIds) else col
                    } ?: col
                }
            }.onFailure {
                error = context.getString(R.string.load_failed, it.message)
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Box(Modifier.fillMaxSize()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { load() }) { Text(composeStringResource(R.string.retry)) }
                }
            }
            readlists.isEmpty() && collections.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(composeStringResource(R.string.no_readlists))
                }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (readlists.isNotEmpty()) {
                    item {
                        Text(
                            composeStringResource(R.string.section_readlists),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(readlists) { rl ->
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onReadlistClick(rl.id, rl.name) },
                                        onLongClick = { menuOpen = true },
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val firstBookId = rl.bookIds.firstOrNull()
                                    if (firstBookId != null) {
                                        KomgaCover(
                                            client = client,
                                            url = client.bookThumbnailUrl(firstBookId),
                                            modifier = Modifier.width(42.dp).height(56.dp),
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .width(42.dp)
                                                .height(56.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    RoundedCornerShape(6.dp),
                                                ),
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(rl.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = composeStringResource(R.string.books_count, rl.booksCount),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = "›",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(composeStringResource(R.string.delete)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        pendingDelete = DeleteTarget("readlist", rl.id, rl.name)
                                    },
                                )
                            }
                        }
                    }
                }
                if (collections.isNotEmpty()) {
                    item {
                        Text(
                            composeStringResource(R.string.section_collections),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(collections) { c ->
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onCollectionClick(c.id, c.name) },
                                        onLongClick = { menuOpen = true },
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val firstSeriesId = c.seriesIds.firstOrNull()
                                    if (firstSeriesId != null) {
                                        KomgaCover(
                                            client = client,
                                            url = client.seriesThumbnailUrl(firstSeriesId),
                                            modifier = Modifier.width(42.dp).height(56.dp),
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .width(42.dp)
                                                .height(56.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    RoundedCornerShape(6.dp),
                                                ),
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = composeStringResource(R.string.series_in_collection, c.seriesIds.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = "›",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(composeStringResource(R.string.delete)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        pendingDelete = DeleteTarget("collection", c.id, c.name)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // 删除确认弹窗
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(composeStringResource(R.string.confirm_delete_title)) },
            text = {
                Text(
                    composeStringResource(
                        R.string.confirm_delete_message,
                        if (target.kind == "readlist") {
                            composeStringResource(R.string.section_readlists)
                        } else {
                            composeStringResource(R.string.section_collections)
                        },
                        target.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = target
                        pendingDelete = null
                        scope.launch {
                            runCatching {
                                if (t.kind == "readlist") client.deleteReadlist(t.id)
                                else client.deleteCollection(t.id)
                            }.onFailure {
                                error = context.getString(R.string.delete_failed, it.message)
                            }
                            load()
                        }
                    },
                ) { Text(composeStringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(composeStringResource(R.string.cancel))
                }
            },
        )
    }
}

// ---------- Search tab ----------

/**
 * M3.9: reusable search box embedded at the top of Home / Library / Lists tabs.
 * Typing a query searches series across all libraries (GET /series?search=);
 * when the query is blank the composable renders nothing (the tab shows its
 * normal content). While a search is active, results replace the tab body.
 *
 * Komiho: auto-search on a short debounce (300 ms) after the user stops
 * typing, so they don't need to tap the search button. The manual button is
 * kept for users who want to trigger the search immediately.
 */
@Composable
private fun EmbeddedSearch(
    client: KomgaApiClient,
    modifier: Modifier = Modifier,
    displayMode: LibraryDisplayMode,
    columns: Int,
    libraryScope: String? = null,
    onSeriesClick: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // ── Komelia-style filter panel state (Tags / Authors) ──
    var selTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var selAuthors by remember { mutableStateOf<List<AuthorDto>>(emptyList()) }
    var allTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var allAuthors by remember { mutableStateOf<List<AuthorDto>>(emptyList()) }
    var tagsOpen by remember { mutableStateOf(false) }
    var authorsOpen by remember { mutableStateOf(false) }

    fun authorValue(a: AuthorDto): String = if (a.role.isNullOrBlank()) a.name else "${a.name},${a.role}"

    suspend fun runSearch(q: String) {
        searching = true
        error = null
        runCatching {
            client.getSeries(
                search = q.ifBlank { null },
                tag = selTags.ifEmpty { null },
                author = selAuthors.map { authorValue(it) }.ifEmpty { null },
                size = 100,
            ).content
        }
            .onSuccess { results = it }
            .onFailure { error = context.getString(R.string.search_failed, it.message) }
        searching = false
    }

    val hasFilter = selTags.isNotEmpty() || selAuthors.isNotEmpty()

    // Re-run search when the query or any filter selection changes (debounced 300 ms).
    LaunchedEffect(query, selTags, selAuthors) {
        if (query.isBlank() && !hasFilter) {
            results = emptyList()
            searching = false
            error = null
        } else {
            kotlinx.coroutines.delay(300)
            if (query.isNotBlank() || hasFilter) runSearch(query)
        }
    }

    // Dropdown caches must follow the active library scope: when the user switches
    // between Home (all libraries) and a selected library tab, re-fetch the tag/author
    // lists so they reflect the correct scope.
    LaunchedEffect(libraryScope) {
        allTags = emptyList()
        allAuthors = emptyList()
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(composeStringResource(R.string.search_series_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { scope.launch { if (query.isNotBlank() || hasFilter) runSearch(query) } },
                enabled = (query.isNotBlank() || hasFilter) && !searching,
            ) { Text(composeStringResource(R.string.search_action)) }
        }

        // ── Filter buttons row (Komelia parity): Tags / Genres / Authors ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                FilterChip(
                    selected = tagsOpen,
                    onClick = {
                        tagsOpen = true
                        if (allTags.isEmpty()) {
                            scope.launch { allTags = runCatching { client.getSeriesTags(libraryScope) }.getOrDefault(emptyList()) }
                        }
                    },
                    label = { Text(composeStringResource(R.string.filter_tags) + if (selTags.isNotEmpty()) " (${selTags.size})" else "") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = tagsOpen, onDismissRequest = { tagsOpen = false }) {
                    if (allTags.isEmpty()) {
                        DropdownMenuItem(text = { Text(composeStringResource(R.string.no_match_results)) }, onClick = { tagsOpen = false })
                    }
                    allTags.forEach { t ->
                        val checked = selTags.contains(t)
                        DropdownMenuItem(
                            text = { Text(t) },
                            trailingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = { selTags = if (checked) selTags - t else selTags + t },
                        )
                    }
                }
            }

            Box {
                FilterChip(
                    selected = authorsOpen,
                    onClick = {
                        authorsOpen = true
                        if (allAuthors.isEmpty()) {
                            scope.launch { allAuthors = runCatching { client.getSeriesAuthors(libraryScope) }.getOrDefault(emptyList()) }
                        }
                    },
                    label = { Text(composeStringResource(R.string.filter_authors) + if (selAuthors.isNotEmpty()) " (${selAuthors.size})" else "") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = authorsOpen, onDismissRequest = { authorsOpen = false }) {
                    if (allAuthors.isEmpty()) {
                        DropdownMenuItem(text = { Text(composeStringResource(R.string.no_match_results)) }, onClick = { authorsOpen = false })
                    }
                    allAuthors.forEach { a ->
                        val checked = selAuthors.contains(a)
                        DropdownMenuItem(
                            text = { Text(if (a.role.isNullOrBlank()) a.name else "${a.name} (${a.role})") },
                            trailingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = { selAuthors = if (checked) selAuthors - a else selAuthors + a },
                        )
                    }
                }
            }
        }

        // ── Selected filter chips (removable) ──
        if (hasFilter) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selTags.forEach { t ->
                    FilterChip(
                        selected = true,
                        onClick = { selTags = selTags - t },
                        label = { Text(t) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = composeStringResource(R.string.clear)) },
                    )
                }
                selAuthors.forEach { a ->
                    FilterChip(
                        selected = true,
                        onClick = { selAuthors = selAuthors - a },
                        label = { Text(if (a.role.isNullOrBlank()) a.name else "${a.name} (${a.role})") },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = composeStringResource(R.string.clear)) },
                    )
                }
                TextButton(onClick = {
                    selTags = emptyList(); selAuthors = emptyList()
                }) { Text(composeStringResource(R.string.filter_clear)) }
            }
        }

        when {
            searching -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error)
            }
            (query.isNotBlank() || hasFilter) && results.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(composeStringResource(R.string.no_match_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            query.isNotBlank() || hasFilter -> if (displayMode == LibraryDisplayMode.List) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(results, key = { it.id }) { s ->
                        LibrarySeriesListRow(
                            client,
                            s,
                            onClick = { onSeriesClick(s.id) },
                        )
                    }
                }
            } else {
                // 与库 tab 完全一致的显示模式：compact/comfortable 间距 + 自适应或固定列数
                val isCompact = displayMode == LibraryDisplayMode.CompactGrid
                val adaptiveMin = if (isCompact) 96.dp else 168.dp
                val hSpace = if (isCompact) 4.dp else 8.dp
                val vSpace = if (isCompact) 6.dp else 12.dp
                val cells = if (columns > 0) {
                    GridCells.Fixed(columns)
                } else {
                    GridCells.Adaptive(minSize = adaptiveMin)
                }
                LazyVerticalGrid(
                    columns = cells,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(hSpace),
                    verticalArrangement = Arrangement.spacedBy(vSpace),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(results, key = { it.id }) { s ->
                        LibrarySeriesCard(
                            client,
                            s,
                            onClick = { onSeriesClick(s.id) },
                            titleInside = isCompact,
                        )
                    }
                }
            }
        }
    }
}

// ---------- Settings tab ----------

@Composable
private fun SettingsTab(context: android.content.Context) {
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    var showAppearance by remember { mutableStateOf(false) }
    var showHome by remember { mutableStateOf(false) }
    var showHomePage by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(false) }
    var showReaderSettings by remember { mutableStateOf(false) }

    // MihonSY 风格：分类行列表，点击进入子页面（不再平铺展开全部选项）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TextPreferenceWidget(
            title = composeStringResource(R.string.settings_appearance),
            subtitle = composeStringResource(R.string.settings_appearance_summary),
            icon = Icons.Outlined.Palette,
            onPreferenceClick = { showAppearance = true },
        )
        TextPreferenceWidget(
            title = composeStringResource(R.string.settings_home),
            subtitle = composeStringResource(R.string.settings_home_summary),
            icon = Icons.Filled.Home,
            onPreferenceClick = { showHome = true },
        )
        TextPreferenceWidget(
            title = composeStringResource(R.string.settings_reading),
            subtitle = composeStringResource(R.string.settings_reader_summary),
            icon = Icons.AutoMirrored.Outlined.ChromeReaderMode,
            onPreferenceClick = { showReaderSettings = true },
        )
        TextPreferenceWidget(
            title = composeStringResource(R.string.settings_server),
            subtitle = runCatching { prefs.connection().baseUrl }.getOrDefault(""),
            icon = Icons.Outlined.Cloud,
            onPreferenceClick = { showServer = true },
        )
    }

    if (showAppearance) {
        SettingsCategoryDialog(
            onDismiss = { showAppearance = false },
            title = composeStringResource(R.string.settings_appearance),
        ) { padding -> KomgaAppearanceSettings(Modifier.padding(padding), context) }
    }
    if (showHome) {
        SettingsCategoryDialog(
            onDismiss = { showHome = false },
            title = composeStringResource(R.string.settings_home),
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item {
                    TextPreferenceWidget(
                        title = composeStringResource(R.string.settings_home_page),
                        subtitle = composeStringResource(R.string.settings_home_page_summary),
                        icon = Icons.Filled.Home,
                        onPreferenceClick = { showHomePage = true },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = composeStringResource(R.string.settings_preview_images),
                        subtitle = composeStringResource(R.string.settings_preview_images_summary),
                        icon = Icons.Filled.Image,
                        onPreferenceClick = { showPreview = true },
                    )
                }
            }
        }
    }
    if (showHomePage) {
        SettingsCategoryDialog(
            onDismiss = { showHomePage = false },
            title = composeStringResource(R.string.settings_home_page),
        ) { padding -> KomgaHomeSectionsSettings(Modifier.padding(padding), context) }
    }
    if (showPreview) {
        SettingsCategoryDialog(
            onDismiss = { showPreview = false },
            title = composeStringResource(R.string.settings_preview_images),
        ) { padding -> KomgaPreviewSettings(Modifier.padding(padding), context) }
    }
    if (showServer) {
        SettingsCategoryDialog(
            onDismiss = { showServer = false },
            title = composeStringResource(R.string.settings_server),
        ) { padding -> KomgaServerSettings(Modifier.padding(padding), context, onDismiss = { showServer = false }) }
    }
    if (showReaderSettings) {
        Dialog(
            onDismissRequest = { showReaderSettings = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            CompositionLocalProvider(LocalBackPress provides { showReaderSettings = false }) {
                Surface(Modifier.fillMaxSize()) {
                    Navigator(SettingsReaderScreen)
                }
            }
        }
    }
}

/** 通用分类子页面容器：全屏对话框 + 顶栏返回键（MihonSY 子页面风格）。 */
@Composable
private fun SettingsCategoryDialog(
    onDismiss: () -> Unit,
    title: String,
    content: @Composable (PaddingValues) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = null)
                            }
                        },
                    )
                },
                content = content,
            )
        }
    }
}

@Composable
private fun KomgaAppearanceSettings(modifier: Modifier, context: android.content.Context) {
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val activity = context as? android.app.Activity
    val themeModeEnum = runCatching { ThemeMode.valueOf(prefs.themeMode) }
        .getOrDefault(ThemeMode.SYSTEM)
    val appThemeEnum = runCatching { AppTheme.valueOf(prefs.appTheme) }
        .getOrDefault(AppTheme.DEFAULT)
    // U4: selected skin is held in state so the checkmark appears instantly on
    // click (the widget's internal `ActivityCompat.recreate` is a no-op inside a
    // Dialog, so we drive the actual theme apply via `activity?.recreate()`).
    var appThemeSel by remember { mutableStateOf(appThemeEnum) }
    var amoledSel by remember { mutableStateOf(prefs.themeDarkAmoled) }
    var showAppLanguage by remember { mutableStateOf(false) }

    val currentLangLabel = when (prefs.appLanguage) {
        "zh-CN" -> composeStringResource(R.string.lang_zh_cn)
        "zh-TW" -> composeStringResource(R.string.lang_zh_tw)
        "en" -> composeStringResource(R.string.lang_en)
        else -> composeStringResource(R.string.lang_default)
    }

    LazyColumn(modifier.fillMaxSize()) {
        item { PreferenceGroupHeader(composeStringResource(R.string.settings_group_theme)) }
        item {
            AppThemeModePreferenceWidget(
                value = themeModeEnum,
                onItemClick = {
                    prefs.themeMode = it.name
                    activity?.recreate()
                },
            )
        }
        item {
            AppThemePreferenceWidget(
                value = appThemeSel,
                amoled = amoledSel,
                onItemClick = {
                    prefs.appTheme = it.name
                    appThemeSel = it
                    activity?.recreate()
                },
            )
        }
        item {
            TextPreferenceWidget(
                title = composeStringResource(R.string.settings_amoled),
                widget = {
                    Switch(
                        checked = amoledSel,
                        onCheckedChange = {
                            amoledSel = it
                            prefs.themeDarkAmoled = it
                            activity?.recreate()
                        },
                    )
                },
            )
        }
        item { PreferenceGroupHeader(composeStringResource(R.string.settings_group_display)) }
        item {
            TextPreferenceWidget(
                title = composeStringResource(R.string.settings_app_language),
                subtitle = currentLangLabel,
                onPreferenceClick = { showAppLanguage = true },
            )
        }
    }

    if (showAppLanguage) {
        val langOptions = listOf(
            "" to composeStringResource(R.string.lang_default),
            "zh-CN" to composeStringResource(R.string.lang_zh_cn),
            "zh-TW" to composeStringResource(R.string.lang_zh_tw),
            "en" to composeStringResource(R.string.lang_en),
        )
        AlertDialog(
            onDismissRequest = { showAppLanguage = false },
            title = { Text(composeStringResource(R.string.settings_app_language)) },
            text = {
                Column {
                    langOptions.forEach { (tag, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (prefs.appLanguage != tag) {
                                        prefs.appLanguage = tag
                                        AppCompatDelegate.setApplicationLocales(
                                            if (tag.isEmpty()) {
                                                LocaleListCompat.getEmptyLocaleList()
                                            } else {
                                                LocaleListCompat.forLanguageTags(tag)
                                            },
                                        )
                                    }
                                    showAppLanguage = false
                                    activity?.recreate()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, modifier = Modifier.weight(1f))
                            if (prefs.appLanguage == tag) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

private const val HOME_SECTION_DIVIDER = "__HOME_SECTION_DIVIDER__"

/**
 * 书库 → 主页 → 区块调整。
 * 单列表：活动区块在上方，隐藏分界线以下为已隐藏（划线置灰）。
 * 拖拽跨过分界线即切换显示/隐藏；活动区内、隐藏区内可各自排序。
 */
@Composable
private fun KomgaHomeSectionsSettings(modifier: Modifier, context: android.content.Context) {
    val prefs = remember { KomgaPreferences(context.applicationContext) }

    // 全序列表（String）：活动区 + 分隔线 + 隐藏区。分隔线位置即活动区/隐藏区分界。
    // orderedAll 是唯一真相来源，初始化一次；拖拽只改它，再回写 prefs。
    val orderedAll = remember {
        val vis = prefs.homeSectionOrder.split(',')
            .mapNotNull { name -> runCatching { HomeSection.valueOf(name) }.getOrNull()?.name }
        val hid = HomeSection.entries.filter { it.name !in vis }.map { it.name }
        (vis + listOf(HOME_SECTION_DIVIDER) + hid).toMutableStateList()
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key.toString()
        val toKey = to.key.toString()
        if (fromKey == HOME_SECTION_DIVIDER) return@rememberReorderableLazyListState
        val fromIndex = orderedAll.indexOf(fromKey)
        var toIndex = orderedAll.indexOf(toKey)
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        // 落点在分隔线本身：拖到分界线视作进入隐藏区开头。
        if (toKey == HOME_SECTION_DIVIDER) toIndex += 1
        val moved = orderedAll.removeAt(fromIndex)
        val insertAt = if (toIndex > fromIndex) toIndex - 1 else toIndex
        orderedAll.add(insertAt, moved)
        // 分隔线之前的项视作活动区，持久化为 homeSectionOrder。
        val dividerIdx = orderedAll.indexOf(HOME_SECTION_DIVIDER)
        val active = orderedAll.subList(0, dividerIdx).filter { it != HOME_SECTION_DIVIDER }
        prefs.homeSectionOrder = active.joinToString(",")
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            // 顶标题：标准设置页标题字号（titleMedium），与顶部标题同一格，左缩 16dp。
            Text(
                text = composeStringResource(R.string.settings_block_adjust),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
        items(orderedAll, key = { it }) { key ->
            if (key == HOME_SECTION_DIVIDER) {
                // 分隔线本身也用 ReorderableItem 包裹，保证拖拽落点连续；
                // 无拖拽手柄，仅作视觉分界与“隐藏区”标识。左缩比标题再 2 格（48dp）。
                ReorderableItem(reorderableState, key) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Text(
                            text = composeStringResource(R.string.settings_hidden),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            } else {
                val section = HomeSection.valueOf(key)
                val isHidden = orderedAll.indexOf(key) > orderedAll.indexOf(HOME_SECTION_DIVIDER)
                ReorderableItem(reorderableState, key) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 48.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = section.labelText(),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            textDecoration = null,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Outlined.DragHandle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.draggableHandle(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 书库 → 预览图 → 缓存设置。
 * 缓存上限滑块（0 = 不缓存/实时，最高 500M，默认 100M）+ 清空预览图缓存按钮。
 */
@Composable
private fun KomgaPreviewSettings(modifier: Modifier, context: android.content.Context) {
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    var cacheLimitMb by remember { mutableStateOf((prefs.coverCacheLimitBytes / (1024 * 1024)).toInt()) }

    // 磁盘缓存大小（bytes），用于展示当前占用。
    val diskCacheDir = java.io.File(context.cacheDir, "komga_covers")
    fun diskCacheSizeMb(): Long {
        if (!diskCacheDir.exists()) return 0L
        return diskCacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / (1024 * 1024)
    }
    var cacheUsedMb by remember { mutableStateOf(diskCacheSizeMb()) }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text(
                text = composeStringResource(R.string.settings_cache_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = composeStringResource(R.string.settings_cache_limit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = cacheLimitMb.toFloat(),
                    onValueChange = {
                        val mb = it.toInt().coerceIn(0, 500)
                        cacheLimitMb = mb
                        prefs.coverCacheLimitBytes = mb * 1024L * 1024L
                    },
                    valueRange = 0f..500f,
                    steps = 9, // 0,50,100,...,500
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (cacheLimitMb == 0) "0 M（不缓存 / 实时预览）"
                    else "$cacheLimitMb M（当前占用约 ${cacheUsedMb}M）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            TextPreferenceWidget(
                title = composeStringResource(R.string.settings_clear_cover_cache),
                icon = Icons.Filled.Delete,
                onPreferenceClick = {
                    // 清空 Coil 磁盘缓存（komga_covers）。设置变更下次重建时生效，
                    // 这里直接清现有目录，立即释放空间。
                    runCatching {
                        coil3.ImageLoader(context.applicationContext).diskCache?.clear()
                        diskCacheDir.deleteRecursively()
                    }
                    cacheUsedMb = 0L
                    Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.settings_cover_cache_cleared),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }
}

@Composable
private fun HomeLayoutChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Home tab display options menu (toolbar). Controls layout (CAROUSEL/GRID),
 * the number of items per row in GRID mode, and the per-section item cap.
 */
@Composable
private fun HomeOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    layout: String,
    onLayoutChange: (String) -> Unit,
    displayMode: String,
    onDisplayModeChange: (String) -> Unit,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    sectionLimit: Int,
    onSectionLimitChange: (Int) -> Unit,
) {
    if (!expanded) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(composeStringResource(R.string.home_display_options)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = composeStringResource(R.string.display_mode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryDisplayMode.entries.forEach { m ->
                        HomeLayoutChip(
                            label = composeStringResource(m.labelRes),
                            selected = displayMode == m.prefValue,
                            onClick = { onDisplayModeChange(m.prefValue) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = composeStringResource(R.string.home_layout),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeLayoutChip(
                        label = composeStringResource(R.string.home_layout_carousel),
                        selected = layout == "CAROUSEL",
                        onClick = { onLayoutChange("CAROUSEL") },
                    )
                    HomeLayoutChip(
                        label = composeStringResource(R.string.home_layout_grid),
                        selected = layout == "GRID",
                        onClick = { onLayoutChange("GRID") },
                    )
                }
                Spacer(Modifier.height(12.dp))
                SliderItem(
                    value = columns,
                    valueRange = 0..10,
                    label = composeStringResource(R.string.home_columns),
                    valueString = if (columns > 0) columns.toString() else composeStringResource(R.string.home_columns_auto),
                    onChange = onColumnsChange,
                )
                SliderItem(
                    value = sectionLimit,
                    valueRange = 1..50,
                    label = composeStringResource(R.string.home_section_limit_title),
                    valueString = sectionLimit.toString(),
                    onChange = onSectionLimitChange,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(composeStringResource(R.string.done)) }
        },
    )
}

/** 从服务器地址里取 host 作为连接默认名（与 KomgaConnectActivity 同逻辑）。 */
private fun hostOf(url: String): String {
    return runCatching {
        val u = java.net.URI(url)
        u.host.takeIf { it.isNotBlank() } ?: url
    }.getOrDefault(url)
}

@Composable
private fun KomgaServerSettings(
    modifier: Modifier,
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    var connections by remember { mutableStateOf(prefs.connections()) }
    val activeId = remember(connections) { prefs.activeConnectionId }
    var pendingDelete by remember { mutableStateOf<KomgaConnection?>(null) }

    fun refresh() {
        connections = prefs.connections()
    }

    fun switchTo(id: String) {
        prefs.setActiveConnection(id)
        // 重启主界面，使所有 composable 用新连接重拉数据
        context.startActivity(
            android.content.Intent(context, KomgaMainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    LazyColumn(modifier.fillMaxSize()) {
        item { PreferenceGroupHeader(composeStringResource(R.string.settings_server)) }
        items(connections) { conn ->
            val isActive = conn.id == activeId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (!isActive) switchTo(conn.id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isActive,
                    onClick = { if (!isActive) switchTo(conn.id) },
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    Text(
                        conn.name.ifBlank { hostOf(conn.baseUrl) },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        composeStringResource(R.string.settings_address_fmt, conn.baseUrl),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        composeStringResource(
                            R.string.settings_auth_fmt,
                            composeStringResource(
                                if (conn.authType.name == "API_KEY") {
                                    R.string.auth_api_key
                                } else {
                                    R.string.auth_username_password
                                },
                            ),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (isActive) {
                        Text(
                            composeStringResource(R.string.server_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        // 编辑：打开连接页（编辑模式），关掉本设置页避免返回时状态陈旧
                        context.startActivity(
                            android.content.Intent(context, KomgaConnectActivity::class.java)
                                .putExtra(KomgaConnectActivity.EXTRA_CONNECTION_ID, conn.id),
                        )
                        onDismiss()
                    },
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = composeStringResource(R.string.server_edit),
                    )
                }
                IconButton(onClick = { pendingDelete = conn }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = composeStringResource(R.string.delete),
                    )
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    context.startActivity(android.content.Intent(context, KomgaConnectActivity::class.java))
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(composeStringResource(R.string.server_add))
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(composeStringResource(R.string.delete)) },
            text = { Text(composeStringResource(R.string.server_delete_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        val conn = pendingDelete!!
                        val wasActive = conn.id == activeId
                        prefs.deleteConnection(conn.id)
                        pendingDelete = null
                        // 删的是激活项 → deleteConnection 已自动回退，重启主界面重拉；
                        // 否则原地刷新列表
                        if (wasActive) {
                            context.startActivity(
                                android.content.Intent(context, KomgaMainActivity::class.java)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
                            )
                        } else {
                            refresh()
                        }
                    },
                ) { Text(composeStringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(composeStringResource(R.string.cancel))
                }
            },
        )
    }
}


// ---------- Downloads (offline) tab ----------

/**
 * Komiho V2: 离线下载书架。
 *
 * 纯本地、不联网：从 [KomgaDownloadStore] 读已下载 bookId 列表，经本地 DB
 * 反查 chapter（book 名 + mangaId）→ manga（series 名），列出已下载书。
 * 断网时此 tab 照常可用——点书走 KomgaReaderLauncher.open() 的离线优先逻辑。
 *
 * 封面用占位图标（离线无本地缓存封面；CBZ 内页缩略图留待后续）。
 */
private data class DownloadedChapter(
    val bookId: String,
    val bookName: String,
    val sourceOrder: Long, // Komga 系列内 book 顺序，用于组内排序
)

private data class DownloadedSeries(
    val seriesId: String,
    val seriesName: String,
    val coverPath: String = "",
    val totalBooks: Int = 0,
    val chapters: List<DownloadedChapter>,
)

@Composable
private fun DownloadsTab(
    client: KomgaApiClient,
    onBookClick: suspend (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var seriesList by remember { mutableStateOf<List<DownloadedSeries>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 默认全部展开（已下载的书一般不多）。
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteSeries by remember { mutableStateOf<DownloadedSeries?>(null) }

    fun load() {
        loading = true
        scope.launch {
            val store = KomgaDownloadStore(context)
            // 纯本地聚合：entry 已带 seriesId/seriesName/bookName/number，离线不依赖 DB 反查。
            val grouped = store.allDownloaded().values.groupBy { it.seriesId.takeIf { id -> id.isNotBlank() } ?: it.seriesName }
            val result = grouped.mapNotNull { (_, entries) ->
                val first = entries.first()
                val seriesId = first.seriesId
                val seriesName = first.seriesName.ifBlank { "未知系列" }
                // 单本下载可能没 series meta（旧数据），用章节数兜底；新下载单本也会存封面。
                val meta = seriesId.takeIf { it.isNotBlank() }?.let { store.getSeriesMeta(it) }
                // 封面来源：系列 meta 优先；单本下载没写 meta 但已存 _covers/<seriesId>.jpg，兜底读之。
                val coverPath = meta?.coverPath?.takeIf { it.isNotBlank() && File(it).exists() }
                    ?: seriesId.takeIf { it.isNotBlank() }?.let {
                        val f = File(context.getExternalFilesDir(null), "komga/_covers/$it.jpg")
                        if (f.exists()) f.absolutePath else ""
                    } ?: ""
                val chapters = entries.map { e ->
                    val bid = e.path.substringAfterLast("/").substringBefore(".cbz").let { p ->
                        // 新文件名 001_<bookId>.cbz，旧文件名 <bookId>.cbz
                        if (p.contains("_")) p.substringAfter("_") else p
                    }
                    DownloadedChapter(
                        bookId = bid,
                        bookName = e.bookName.ifBlank { seriesName },
                        sourceOrder = e.number.toLong(),
                    )
                }.sortedBy { it.sourceOrder }
                DownloadedSeries(
                    seriesId = seriesId,
                    seriesName = seriesName,
                    coverPath = coverPath,
                    totalBooks = meta?.totalBooks ?: chapters.size,
                    chapters = chapters,
                )
            }.sortedBy { it.seriesName }
            seriesList = result
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    // 从阅读器返回（可能删了下载）或重进 tab 时刷新。
    val lifecycleContext = LocalContext.current
    DisposableEffect(lifecycleContext) {
        val owner = lifecycleContext as? LifecycleOwner
        if (owner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) load()
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    // 系列级删除确认框。
    pendingDeleteSeries?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSeries = null },
            title = { Text("删除整个系列？") },
            text = { Text("将删除「${s.seriesName}」全部 ${s.chapters.size} 本已下载内容，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val sId = s.seriesId
                    scope.launch {
                        if (sId.isNotBlank()) KomgaDownloadStore(context).deleteSeries(sId)
                        else s.chapters.forEach { KomgaDownloadStore(context).deleteWithFile(it.bookId) }
                        pendingDeleteSeries = null
                        load()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteSeries = null }) { Text("取消") } },
        )
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        seriesList.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "还没有下载的书",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "在系列页点「下载整个系列」即可离线保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(seriesList.size) { si ->
                    val series = seriesList[si]
                    val isExp = expanded.contains(series.seriesId.takeIf { it.isNotBlank() } ?: series.seriesName)
                    // 系列卡片头
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (isExp) expanded - (series.seriesId.takeIf { it.isNotBlank() } ?: series.seriesName) else expanded + (series.seriesId.takeIf { it.isNotBlank() } ?: series.seriesName) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 本地封面（离线可用）。
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(44.dp, 58.dp),
                        ) {
                            if (series.coverPath.isNotBlank()) {
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(context).data(File(series.coverPath)).build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Book, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(series.seriesName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "已下载 ${series.chapters.size} / ${series.totalBooks}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { pendingDeleteSeries = series }) {
                            Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        }
                        Icon(
                            imageVector = if (isExp) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // 展开后章节列表
                    if (isExp) {
                        series.chapters.forEach { ch ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { scope.launch { onBookClick(ch.bookId) } }
                                    .padding(horizontal = 32.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(ch.bookName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            KomgaDownloadStore(context).deleteWithFile(ch.bookId)
                                            load()
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}


// ---------- Shared components ----------

/**
 * M3: read-status filter for the Library tab.
 * Maps to Komga's `read_status` query parameter on GET /series.
 */
private enum class ReadFilter(@StringRes val labelRes: Int, val komgaValue: String?) {
    All(R.string.filter_all, null),
    Unread(R.string.filter_unread, "UNREAD"),
    InProgress(R.string.filter_in_progress, "IN_PROGRESS"),
    Read(R.string.filter_read, "READ"),
    ;

    @Composable
    fun labelText(): String = composeStringResource(labelRes)
}

@Composable
fun SeriesCard(client: KomgaApiClient, series: SeriesDto, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            ) {
                KomgaCover(
                    client = client,
                    url = client.seriesThumbnailUrl(series.id),
                    modifier = Modifier.fillMaxSize(),
                )
                if (series.booksUnreadCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    ) {
                        Text(
                            text = composeStringResource(R.string.unread_count, series.booksUnreadCount),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = series.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}
