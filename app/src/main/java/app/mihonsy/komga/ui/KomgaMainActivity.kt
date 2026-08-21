package app.mihonsy.komga.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    // Library tab also gets per-orientation column counts.
    val configuration = LocalConfiguration.current
    val isLandscape = remember(configuration) {
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    var portraitColumns by remember { mutableStateOf(prefs.libraryPortraitColumns) }
    var landscapeColumns by remember { mutableStateOf(prefs.libraryLandscapeColumns) }
    val columns = if (isLandscape) landscapeColumns else portraitColumns
    var displayOpen by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(MainTab.entries[currentTab].labelText()) },
                actions = {
                    // Shelf toggle on Library/Lists tabs only — the Home tab
                    // has no grid modes (its sections are configured in
                    // Settings), and Settings manages itself.
                    val currentTabEnum = MainTab.entries[currentTab]
                    if (currentTabEnum == MainTab.Library || currentTabEnum == MainTab.Lists) {
                        ShelfModeToggle(displayMode) { displayOpen = true }
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
                    ) { seriesId ->
                        context.startActivity(Intent(context, KomgaSeriesActivity::class.java).putExtra("seriesId", seriesId))
                    }
                    MainTab.Lists -> ListsTab(client) { rlId, rlName ->
                        context.startActivity(
                            Intent(context, KomgaReadlistActivity::class.java)
                                .putExtra("readlistId", rlId)
                                .putExtra("readlistName", rlName),
                        )
                    }
                    MainTab.Settings -> SettingsTab(context)
                }
            }
        }
    }

    if (displayOpen) {
        // Dialog-local state: the slider must only recompose the dialog while
        // dragging — writing page-level columns per tick recomposed the whole
        // library grid and made drags unresponsive (worst on narrow portrait
        // screens). Values sync to prefs/page state on dismiss.
        var dialogMode by remember { mutableStateOf(displayMode) }
        var dialogColumns by remember {
            mutableStateOf(if (isLandscape) prefs.libraryLandscapeColumns else prefs.libraryPortraitColumns)
        }
        DisplaySettingsDialog(
            displayMode = dialogMode,
            onModeChange = { dialogMode = it },
            columnCount = dialogColumns,
            isLandscape = isLandscape,
            onColumnChange = { dialogColumns = it },
            onDismiss = {
                displayMode = dialogMode
                prefs.libraryDisplayMode = dialogMode.prefValue
                if (isLandscape) {
                    landscapeColumns = dialogColumns
                    prefs.libraryLandscapeColumns = dialogColumns
                } else {
                    portraitColumns = dialogColumns
                    prefs.libraryPortraitColumns = dialogColumns
                }
                displayOpen = false
            },
        )
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
    onSeriesClick: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // M3: read-status filter (all / unread / in-progress / read)
    var readFilter by remember { mutableStateOf(ReadFilter.All) }
    // M3.20: sort persisted.
    var sortMode by remember { mutableStateOf(LibrarySortMode.fromPref(prefs.librarySort)) }
    // M3.11: dropdown menus for filter / sort (collapsed from chip rows).
    var filterOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    // Display mode + columns come from KomgaMainScreen; the library picker
    // lives in the bottom bar (top-level dialog). The library tab only
    // renders the selected library's series.

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
        // Search is hosted at the screen level (title-row icon), not per tab.
        // Library picker moved to the bottom-bar icon (top-level dialog);
        // this row only keeps the filter/sort funnel.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            // Funnel: collapses read-status filter + sort into a single
            // DropdownMenu (header + two groups).
            Box {
                androidx.compose.material3.IconButton(onClick = { filterOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = composeStringResource(R.string.cd_filter_sort),
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = filterOpen,
                    onDismissRequest = { filterOpen = false },
                ) {
                    Text(
                        text = composeStringResource(R.string.read_status_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    ReadFilter.entries.forEach { f ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(f.labelText()) },
                            onClick = {
                                readFilter = f
                            },
                            leadingIcon = {
                                if (readFilter == f) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                    )
                                }
                            },
                        )
                    }
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    Text(
                        text = composeStringResource(R.string.sort_header),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    LibrarySortMode.entries.forEach { m ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(m.labelText()) },
                            onClick = {
                                sortMode = m
                                prefs.librarySort = m.prefValue
                            },
                            leadingIcon = {
                                if (sortMode == m) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

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
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(sortedSeries) { s ->
                        LibrarySeriesListRow(client, s) { onSeriesClick(s.id) }
                    }
                }
            } else {
                // M3.20: columns from the user setting (0 = auto → Adaptive).
                // Comfortable grid uses a wider minimum so it renders fewer
                // columns per row than the compact grid.
                val adaptiveMin = if (displayMode == LibraryDisplayMode.ComfortableGrid) 168.dp else 108.dp
                val cells = if (columns > 0) {
                    GridCells.Fixed(columns)
                } else {
                    GridCells.Adaptive(minSize = adaptiveMin)
                }
                LazyVerticalGrid(
                    columns = cells,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(sortedSeries) { s ->
                        LibrarySeriesCard(client, s) { onSeriesClick(s.id) }
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
fun LibrarySeriesListRow(client: KomgaApiClient, series: SeriesDto, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
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
fun LibrarySeriesCard(client: KomgaApiClient, series: SeriesDto, onClick: () -> Unit) {
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
            }
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

// ---------- Lists tab (readlists + collections) ----------

/** Sub-sections inside the Lists tab. */

@Composable
private fun ListsTab(client: KomgaApiClient, onReadlistClick: (String, String) -> Unit) {
    // User: the Lists tab shows reading lists only (系列/收藏 sections removed).
    ReadlistsContent(client, onReadlistClick)
}

/** 阅读列表子内容（原 ReadlistsTab 主体）。 */
@Composable
private fun ReadlistsContent(client: KomgaApiClient, onReadlistClick: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var readlists by remember { mutableStateOf<List<ReadingListDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { client.getReadlists() }
            .onSuccess { readlists = it }
            .onFailure { error = context.getString(R.string.load_readlists_failed, it.message) }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { client.getReadlists() }
                                .onSuccess { readlists = it; error = null }
                                .onFailure { error = context.getString(R.string.load_failed, it.message) }
                        }
                    }) { Text(composeStringResource(R.string.retry)) }
                }
            }
            readlists.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(composeStringResource(R.string.no_readlists))
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(readlists) { rl ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onReadlistClick(rl.id, rl.name) },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Cover of the first book in the list (if any) — the
                            // thumbnail URL is derivable from the book id directly.
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
                }
            }
        }
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
                value = appThemeEnum,
                amoled = amoledSel,
                onItemClick = { prefs.appTheme = it.name },
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

    fun move(section: HomeSection, delta: Int) {
        val cur = visibleSections.indexOf(section)
        val target = cur + delta
        if (cur >= 0 && target in 0 until visibleSections.size) {
            val list = visibleSections.toMutableList()
            list[cur] = list[target]
            list[target] = section
            persistOrder(list)
        }
    }

    LazyColumn(modifier.fillMaxSize()) {
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
        items(visibleSections) { section ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { move(section, -1) },
                    enabled = visibleSections.indexOf(section) > 0,
                ) { Text("↑", style = MaterialTheme.typography.titleMedium) }
                Text(
                    text = section.labelText(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { move(section, +1) },
                    enabled = visibleSections.indexOf(section) < visibleSections.size - 1,
                ) { Text("↓", style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = { persistOrder(visibleSections.filter { it != section }) }) {
                    Text(composeStringResource(R.string.settings_hide))
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
