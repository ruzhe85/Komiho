package app.mihonsy.komga.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitPointerEvent
import androidx.compose.foundation.gestures.awaitPointerEventScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.SeriesDto
import kotlin.math.ceil
import kotlinx.coroutines.launch

/**
 * Komiho M3.10: full list for a Home section ("全部" button).
 * Loads the whole section (series or books) and shows it as a compact grid;
 * tapping an item opens the series detail / reader.
 */
class KomgaSectionListActivity : KomgaBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sectionName = intent.getStringExtra("section").orEmpty()
        val section = runCatching { HomeSection.valueOf(sectionName) }.getOrDefault(
            HomeSection.RecentlyUpdatedSeries,
        )
        setContent { KomihoTheme { KomgaSectionListScreen(section) } }
    }
}

@Composable
private fun KomgaSectionListScreen(section: HomeSection) {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val client = remember { KomgaApiClient(prefs.connection()) }
    val scope = rememberCoroutineScope()

    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var books by remember { mutableStateOf<List<BookDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current
    val isLandscape = remember(configuration) {
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    // Column count for this page's grid (0 = auto).
    val columns = if (isLandscape) prefs.libraryLandscapeColumns else prefs.libraryPortraitColumns

    suspend fun load() {
        loading = true
        error = null
        runCatching {
            if (section.isSeries) {
                val s = when (section) {
                    HomeSection.RecentlyAddedSeries -> client.getSeries(sort = "createdDate,desc", size = 200).content
                    // Recently updated series — official /series/updated endpoint.
                    HomeSection.RecentlyUpdatedSeries -> client.getUpdatedSeries(size = 200)
                    else -> client.getSeries(sort = "dateModified,desc", size = 200).content
                }
                series = s
            } else {
                // 继续阅读 = in-progress books, 最近阅读书籍 = finished books —
                // both sorted by the read timestamp, matching the web dashboard.
                val sort = when (section) {
                    HomeSection.ContinueReading, HomeSection.RecentlyReadBooks -> "readProgress.readDate,desc"
                    else -> "createdDate,desc"
                }
                val readStatus = when (section) {
                    HomeSection.ContinueReading -> "IN_PROGRESS"
                    HomeSection.RecentlyReadBooks -> "READ"
                    else -> null
                }
                books = client.getBooks(readStatus = readStatus, sort = sort, size = 200).content
            }
        }.onFailure {
            error = "加载失败：${it.message}"
        }
        loading = false
    }

    LaunchedEffect(section) { load() }

    // Read directly from prefs every recomposition so the TopAppBar toggle
    // shows the current mode across the whole app.
    // Book-level display mode (independent from the series shelf).
    val mode = LibraryDisplayMode.fromPref(prefs.bookDisplayMode)
    var displayOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section.labelText()) },
                actions = {
                    ShelfModeToggle(mode) { displayOpen = true }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { scope.launch { load() } }) { Text("重试") }
                }
            }
            (section.isSeries && series.isEmpty()) || (!section.isSeries && books.isEmpty()) -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无内容")
            }
            else -> Box(Modifier.fillMaxSize().padding(padding)) {
                if (section.isSeries) {
                    SeriesShelf(client, series, mode, columns) { s ->
                        context.startActivity(
                            android.content.Intent(context, KomgaSeriesActivity::class.java)
                                .putExtra("seriesId", s),
                        )
                    }
                } else {
                    BookShelf(
                        client = client,
                        books = books,
                        mode = mode,
                        columns = columns,
                        onBookClick = { b ->
                            scope.launch {
                                runCatching { KomgaReaderLauncher.open(context, client, b) }
                                    .onFailure {
                                        android.widget.Toast.makeText(
                                            context, "打开阅读器失败：${it.message}", android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                            }
                        },
                        onDataChanged = { scope.launch { load() } },
                    )
                }
            }
        }
    }

    var dialogMode by remember { mutableStateOf(mode) }
    var dialogColumns by remember {
        mutableStateOf(if (isLandscape) prefs.libraryLandscapeColumns else prefs.libraryPortraitColumns)
    }
    if (displayOpen) {
        DisplaySettingsDialog(
            displayMode = dialogMode,
            onModeChange = {
                dialogMode = it
                prefs.bookDisplayMode = it.prefValue
            },
            columnCount = dialogColumns,
            isLandscape = isLandscape,
            onColumnChange = {
                dialogColumns = it
                if (isLandscape) prefs.libraryLandscapeColumns = it else prefs.libraryPortraitColumns = it
            },
            onDismiss = { displayOpen = false },
        )
    }
}

/**
 * Compact book card. titleInside=true overlays the title on the cover's
 * bottom (compact grid); false puts it below the cover (comfortable grid).
 */
@Composable
fun BookShelfCard(
    client: KomgaApiClient,
    book: BookDto,
    modifier: Modifier = Modifier,
    titleInside: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDragSelect: () -> Unit = {},
    dragSelecting: Boolean = false,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(2.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // While a drag-select gesture is active, mark this card when the
            // pointer passes over it (Mihon-style slide-to-select).
            .pointerInput(dragSelecting) {
                if (!dragSelecting) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move ||
                            event.type == PointerEventType.Enter
                        ) {
                            if (event.changes.any { it.pressed }) onDragSelect()
                        }
                    }
                }
            },
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            ) {
                KomgaCover(
                    client = client,
                    url = client.bookThumbnailUrl(book.id),
                    modifier = Modifier.fillMaxSize(),
                )
                // Read-state pill — overlay on the cover (mihon style).
                val rp = book.readProgress
                val pillText = when {
                    rp?.completed == true -> "已读"
                    rp != null && rp.page > 0 && book.media.pagesCount > 0 -> {
                        val pct = (rp.page.toFloat() / book.media.pagesCount * 100).toInt()
                        "$pct%"
                    }
                    rp != null && rp.page > 0 -> "已读 ${rp.page}页"
                    else -> null
                }
                if (pillText != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                    ) {
                        Text(
                            text = pillText,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                if (titleInside) {
                    // Title overlaid on the cover bottom with a dark scrim.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                                ),
                            )
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = book.metadata.title ?: book.name,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        book.seriesTitle?.let {
                            Text(
                                text = it,
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (!titleInside) {
                Text(
                    text = book.metadata.title ?: book.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                book.seriesTitle?.let {
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