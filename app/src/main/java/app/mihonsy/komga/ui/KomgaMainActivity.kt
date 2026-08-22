package app.mihonsy.komga.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.CollectionDto
import app.mihonsy.komga.data.model.LibraryDto
import app.mihonsy.komga.data.model.ReadingListDto
import app.mihonsy.komga.data.model.SeriesDto
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KomihoTheme { KomgaMainScreen(refreshSignal) } }
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
    Settings(R.string.tab_settings, Icons.Filled.Settings),
    ;

    @Composable
    fun labelText(): String = composeStringResource(labelRes)
}

@Composable
private fun KomgaMainScreen(refreshSignal: MutableStateFlow<Int>) {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val client = remember { KomgaApiClient(prefs.connection()) }

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
    var librarySortMode by remember { mutableStateOf(LibrarySortMode.fromPref(prefs.librarySort)) }
    var libraryReadFilter by remember { mutableStateOf(ReadFilter.All) }
    var shelfMenuOpen by remember { mutableStateOf(false) }
    // Library tab also gets per-orientation column counts (read-only from
    // prefs; the old columns slider was folded into the toolbar menu).
    val configuration = LocalConfiguration.current
    val isLandscape = remember(configuration) {
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    var portraitColumns by remember { mutableStateOf(prefs.libraryPortraitColumns) }
    var landscapeColumns by remember { mutableStateOf(prefs.libraryLandscapeColumns) }
    val columns = if (isLandscape) landscapeColumns else portraitColumns

    // Library picker — owned at the top level so the bottom-bar icon can
    // pop a dialog listing all libraries; picking one enters that library.
    var libraries by remember { mutableStateOf<List<LibraryDto>>(emptyList()) }
    var selectedLibraryId by remember { mutableStateOf<String?>(null) }
    var libraryPickerOpen by remember { mutableStateOf(false) }

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
    // Close the search field when switching tabs (it is not shown on Settings).
    LaunchedEffect(currentTab) {
        searchOpen = false
    }

    // 统一返回手势处理：KomgaMainActivity 是 task 根 Activity，内部 4 个 tab +
    // 选库态 + 搜索态 + 菜单态都靠本地状态切换（无返回栈），必须手动拦截，
    // 否则返回手势会直接 finish Activity = 退出程序。
    // 优先级：选择态→退出选择；搜索框/菜单/picker 打开→关闭；
    // Library 且已选库→先清空选库（回到选库界面）；非 Home tab→回 Home；
    // Home 根→交还系统默认（退出程序）。
    val interceptBack = searchOpen || shelfMenuOpen || libraryPickerOpen ||
        (currentTab == MainTab.Library.ordinal && selectedLibraryId != null) ||
        currentTab != MainTab.Home.ordinal
    BackHandler(enabled = interceptBack) {
        when {
            searchOpen -> searchOpen = false
            shelfMenuOpen -> shelfMenuOpen = false
            libraryPickerOpen -> libraryPickerOpen = false
            currentTab == MainTab.Library.ordinal && selectedLibraryId != null -> libraryPickerOpen = true
            currentTab != MainTab.Home.ordinal -> currentTab = MainTab.Home.ordinal
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(MainTab.entries[currentTab].labelText()) },
                actions = {
                    val currentTabEnum = MainTab.entries[currentTab]
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
                                sortMode = librarySortMode,
                                onSortModeChange = {
                                    librarySortMode = it
                                    prefs.librarySort = it.prefValue
                                },
                                readFilter = libraryReadFilter,
                                onReadFilterChange = { libraryReadFilter = it },
                            )
                        }
                    }
                    if (currentTabEnum != MainTab.Settings) {
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
                                // User flow: tap the library icon → picker
                                // dialog → pick a library → enter it.
                                libraryPickerOpen = true
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
                EmbeddedSearch(client) { seriesId ->
                    context.startActivity(Intent(context, KomgaSeriesActivity::class.java).putExtra("seriesId", seriesId))
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (MainTab.entries[currentTab]) {
                    MainTab.Home -> HomeTab(client = client, refreshTick = refreshTick) { seriesId ->
                        context.startActivity(Intent(context, KomgaSeriesActivity::class.java).putExtra("seriesId", seriesId))
                    }
                    MainTab.Library -> LibraryTab(
                        client = client,
                        selectedLibraryId = selectedLibraryId,
                        displayMode = displayMode,
                        columns = columns,
                        refreshTick = refreshTick,
                        sortMode = librarySortMode,
                        onSortModeChange = { librarySortMode = it },
                        readFilter = libraryReadFilter,
                        onReadFilterChange = { libraryReadFilter = it },
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
                    MainTab.Settings -> SettingsTab(context)
                }
            }
        }
    }

    if (libraryPickerOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { libraryPickerOpen = false },
            title = { Text(composeStringResource(R.string.select_library)) },
            text = {
                Column {
                    if (libraries.isEmpty()) {
                        Text(
                            text = composeStringResource(R.string.no_libraries),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    libraries.forEach { lib ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedLibraryId = lib.id
                                    currentTab = MainTab.Library.ordinal
                                    libraryPickerOpen = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = lib.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (lib.id == selectedLibraryId) {
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
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { libraryPickerOpen = false }) { Text(composeStringResource(R.string.cancel)) }
            },
        )
    }
}

// ---------- Home tab ----------

@Composable
private fun HomeTab(client: KomgaApiClient, refreshTick: Int, onSeriesClick: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // M3.17: per-section item count is user-configurable in Settings
    // (default 10, clamp 1..15 in KomgaPreferences).
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val itemLimit = prefs.homeSectionLimit

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

    LaunchedEffect(Unit, refreshTick) { loadAll() }

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
                                        HomeContinueReadingRow(client, data.inProgress) { bookId, bookName ->
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
                                        HomeBookRow(client, data.addedBooks) { bookId, bookName ->
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
                                        HomeSeriesRow(client, data.addedSeries, showProgress = false) { onSeriesClick(it) }
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
                                        HomeSeriesRow(client, data.recentSeries, showProgress = false) { onSeriesClick(it) }
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
                                        HomeBookRow(client, data.readBooks) { bookId, bookName ->
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
    onBookClick: (String, String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(books.size) { i ->
            val b = books[i]
            Column(
                modifier = Modifier
                    .width(100.dp)
                    .clickable { onBookClick(b.id, b.metadata.title ?: b.name) },
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
                }
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
}

/** Horizontal row of continue-reading books with a thin progress bar. */
@Composable
private fun HomeContinueReadingRow(
    client: KomgaApiClient,
    books: List<BookDto>,
    onBookClick: (String, String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(books.size) { i ->
            val b = books[i]
            Column(
                modifier = Modifier
                    .width(100.dp)
                    .clickable { onBookClick(b.id, b.metadata.title ?: b.name) },
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
    onSeriesClick: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(series.size) { i ->
            val s = series[i]
            Column(
                modifier = Modifier
                    .width(100.dp)
                    .clickable { onSeriesClick(s.id) },
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
                }
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
}

// ---------- Library tab ----------

@Composable
private fun LibraryTab(
    client: KomgaApiClient,
    selectedLibraryId: String?,
    displayMode: LibraryDisplayMode,
    columns: Int,
    refreshTick: Int,
    sortMode: LibrarySortMode,
    onSortModeChange: (LibrarySortMode) -> Unit,
    readFilter: ReadFilter,
    onReadFilterChange: (ReadFilter) -> Unit,
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
        if (selectedLibraryId != null) {
            loading = true
            runCatching {
                client.getSeries(
                    libraryId = selectedLibraryId,
                    readStatus = readFilter.komgaValue,
                    sort = sortMode.komgaSort,
                    size = 200,
                ).content
            }
                .onSuccess { series = it; error = null }
                .onFailure { error = context.getString(R.string.load_series_failed, it.message) }
            loading = false
        }
    }

    LaunchedEffect(selectedLibraryId, readFilter, sortMode, refreshTick) { reload() }

    // Client-side sort for "最近阅读" (Komga has no read-progress sort field).
    val sortedSeries = remember(series, sortMode) {
        if (sortMode == LibrarySortMode.LastRead) {
            series.sortedByDescending { s ->
                s.booksInProgressCount + (if (s.booksUnreadCount == 0) 1 else 0)
            }
        } else {
            series
        }
    }

    Column(Modifier.fillMaxSize()) {
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
    sortMode: LibrarySortMode,
    onSortModeChange: (LibrarySortMode) -> Unit,
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
                    LibrarySortMode.entries.forEach { m ->
                        SortItem(
                            label = m.labelText(),
                            sortDescending = if (sortMode == m) true else null,
                            onClick = { onSortModeChange(m) },
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

/** Shelf display modes (mihon libraryDisplayMode analog). */
private enum class LibrarySortMode(@StringRes val labelRes: Int, val komgaSort: String?, val prefValue: String) {
    Title(R.string.sort_title, "name,asc", "title,asc"),
    LastModified(R.string.sort_last_modified, "dateModified,desc", "lastModified,desc"),
    LastRead(R.string.sort_last_read, null, "lastRead,desc"),
    DateAdded(R.string.sort_date_added, "createdDate,desc", "dateAdded,desc");

    @Composable
    fun labelText(): String = composeStringResource(labelRes)

    companion object {
        fun fromPref(v: String): LibrarySortMode =
            entries.find { it.prefValue == v } ?: Title
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
    onSeriesClick: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun runSearch(q: String) {
        searching = true
        error = null
        runCatching { client.getSeries(search = q, size = 100).content }
            .onSuccess { results = it }
            .onFailure { error = context.getString(R.string.search_failed, it.message) }
        searching = false
    }

    // Auto-search when the query becomes non-empty, debounced 300 ms.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            error = null
        } else {
            kotlinx.coroutines.delay(300)
            // The user may have cleared the box during the delay.
            if (query.isNotBlank()) runSearch(query)
        }
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
                onClick = { scope.launch { if (query.isNotBlank()) runSearch(query) } },
                enabled = query.isNotBlank() && !searching,
            ) { Text(composeStringResource(R.string.search_action)) }
        }

        when {
            searching -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error)
            }
            query.isNotBlank() && results.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(composeStringResource(R.string.no_match_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            query.isNotBlank() -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results) { s ->
                    SeriesCard(client, s) { onSeriesClick(s.id) }
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
            subtitle = prefs.baseUrl,
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
        ) { padding -> KomgaHomeSettings(Modifier.padding(padding), context) }
    }
    if (showServer) {
        SettingsCategoryDialog(
            onDismiss = { showServer = false },
            title = composeStringResource(R.string.settings_server),
        ) { padding -> KomgaServerSettings(Modifier.padding(padding), context) }
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

@Composable
private fun KomgaHomeSettings(modifier: Modifier, context: android.content.Context) {
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    var sectionLimit by remember { mutableStateOf(prefs.homeSectionLimit) }
    var sectionOrder by remember { mutableStateOf(prefs.homeSectionOrder) }

    fun persistOrder(order: List<HomeSection>) {
        sectionOrder = order.joinToString(",") { it.name }
        prefs.homeSectionOrder = sectionOrder
    }

    val visibleSections = remember(sectionOrder) {
        sectionOrder.split(',')
            .mapNotNull { name -> runCatching { HomeSection.valueOf(name) }.getOrNull() }
    }
    val hiddenSections = remember(sectionOrder) {
        HomeSection.entries.filter { it !in visibleSections }
    }

    val lazyListState = rememberLazyListState()
    val orderedSections = remember { visibleSections.toMutableStateList() }
    LaunchedEffect(sectionOrder) {
        if (!reorderableState.isAnyItemDragging) {
            orderedSections.clear()
            orderedSections.addAll(visibleSections)
        }
    }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val item = orderedSections.removeAt(from.index)
        orderedSections.add(to.index, item)
        persistOrder(orderedSections.toList())
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = lazyListState,
    ) {
        item { PreferenceGroupHeader(composeStringResource(R.string.settings_home)) }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = composeStringResource(R.string.settings_section_limit),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        sectionLimit = (sectionLimit - 1).coerceAtLeast(1)
                        prefs.homeSectionLimit = sectionLimit
                    },
                    enabled = sectionLimit > 1,
                ) { Text("-", style = MaterialTheme.typography.titleLarge) }
                Text(
                    text = "$sectionLimit",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(36.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(
                    onClick = {
                        sectionLimit = (sectionLimit + 1).coerceAtMost(15)
                        prefs.homeSectionLimit = sectionLimit
                    },
                    enabled = sectionLimit < 15,
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
        }
        item {
            Text(
                text = composeStringResource(R.string.settings_home_sections_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(orderedSections, key = { it.name }) { section ->
            ReorderableItem(reorderableState, section.name) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DragHandle,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .draggableHandle(),
                    )
                    Text(
                        text = section.labelText(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val remaining = orderedSections.filter { it != section }
                        orderedSections.clear()
                        orderedSections.addAll(remaining)
                        persistOrder(remaining)
                    }) {
                        Text(composeStringResource(R.string.settings_hide))
                    }
                }
            }
        }
        if (hiddenSections.isNotEmpty()) {
            item {
                Text(
                    text = composeStringResource(R.string.settings_hidden),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(hiddenSections) { section ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = section.labelText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { persistOrder(visibleSections + section) }) {
                        Text(composeStringResource(R.string.settings_show))
                    }
                }
            }
        }
    }
}

@Composable
private fun KomgaServerSettings(modifier: Modifier, context: android.content.Context) {
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    LazyColumn(modifier.fillMaxSize()) {
        item { PreferenceGroupHeader(composeStringResource(R.string.settings_server)) }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = composeStringResource(R.string.settings_address_fmt, prefs.baseUrl),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = composeStringResource(
                        R.string.settings_auth_fmt,
                        composeStringResource(
                            if (prefs.authType.name == "API_KEY") {
                                R.string.auth_api_key
                            } else {
                                R.string.auth_username_password
                            },
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            TextPreferenceWidget(
                title = composeStringResource(R.string.settings_reconfigure),
                onPreferenceClick = {
                    context.startActivity(Intent(context, KomgaConnectActivity::class.java))
                },
            )
        }
        item {
            TextPreferenceWidget(
                title = composeStringResource(R.string.settings_clear_login),
                onPreferenceClick = {
                    prefs.clear()
                    Toast.makeText(context, context.getString(R.string.connection_cleared), Toast.LENGTH_SHORT).show()
                    context.startActivity(
                        Intent(context, KomgaConnectActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                },
            )
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
