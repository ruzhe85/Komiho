package app.mihonsy.komga.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import app.mihonsy.komga.data.download.KomgaBookDownloader
import app.mihonsy.komga.data.download.KomgaDownloadStore
import app.mihonsy.komga.data.download.DownloadUiState
import app.mihonsy.komga.data.download.KomgaDownloadEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.model.AuthorDto
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.SeriesDto

/**
 * Komiho series detail (mihon book-detail style):
 * - Header: cover + read counts + status + tags + summary (no big title —
 *   the title is in the TopAppBar to avoid showing it twice)
 * - Authors are taken from the first book's metadata.authors since the
 *   series endpoint does not return authors
 * - Books are shown as a compact shelf grid (the same BookShelfCard used
 *   in section full lists)
 */
class KomgaSeriesActivity : KomgaBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seriesId = intent.getStringExtra("seriesId").orEmpty()
        setContent { KomihoTheme { KomgaSeriesScreen(seriesId) } }
    }
}

@Composable
private fun KomgaSeriesScreen(seriesId: String) {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val client = remember { KomgaApiClient(prefs.connection()) }

    var series by remember { mutableStateOf<SeriesDto?>(null) }
    var books by remember { mutableStateOf<List<BookDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current
    val isLandscape = remember(configuration) {
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    // Column count for this page's grid (0 = auto).
    val columns = if (isLandscape) prefs.libraryLandscapeColumns else prefs.libraryPortraitColumns
    // U3: book-level display mode (independent from the series shelf).
    val mode = LibraryDisplayMode.fromPref(prefs.bookDisplayMode)
    var displayOpen by remember { mutableStateOf(false) }

    val loadScope = rememberCoroutineScope()

    fun load() {
        loading = true
        error = null
        loadScope.launch {
            runCatching {
                val s = client.getSeriesDetail(seriesId)
                val b = client.getSeriesBooks(seriesId, size = 200).content
                s to b
            }.onSuccess {
                series = it.first
                books = it.second
            }.onFailure {
                error = it.message
            }
            loading = false
        }
    }

    LaunchedEffect(seriesId) { load() }

    // 从阅读器返回（阅读进度变化）时自动刷新列表。
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(series?.name ?: "系列") },
                actions = {
                    if (series != null) {
                        ShelfModeToggle(mode) { displayOpen = true }
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
            }
            series != null -> {
                val s = series!!
                Column(Modifier.fillMaxSize().padding(padding)) {
                    val downloadStore = remember { KomgaDownloadStore(context) }
                    val downloader = remember { KomgaBookDownloader(context, client, downloadStore) }
                    val downloadStates = remember { mutableStateOf<Map<String, DownloadUiState>>(emptyMap()) }
                    fun startDownload(bookId: String) {
                        loadScope.launch {
                            downloader.downloadBook(bookId, s.name).collect { ev ->
                                val cur = downloadStates.value.toMutableMap()
                                when (ev) {
                                    is KomgaDownloadEvent.Queued -> cur[ev.bookId] = DownloadUiState.QUEUED
                                    is KomgaDownloadEvent.Progress -> cur[ev.bookId] = DownloadUiState.DOWNLOADING
                                    is KomgaDownloadEvent.Completed -> cur[ev.bookId] = DownloadUiState.DOWNLOADED
                                    is KomgaDownloadEvent.Error -> cur[ev.bookId] = DownloadUiState.ERROR
                                    is KomgaDownloadEvent.Canceled -> cur.remove(ev.bookId)
                                }
                                downloadStates.value = cur
                            }
                        }
                    }
                    // Fixed header: metadata + resume button + section title.
                    SeriesHeader(
                        client = client,
                        series = s,
                        books = books,
                        onChipClick = { type, value ->
                            // Komga WebUI parity: tap a tag/author → Library filtered by it.
                            val intent = android.content.Intent(context, KomgaMainActivity::class.java)
                                .putExtra("filterType", type)
                                .putExtra("filterValue", value)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            context.startActivity(intent)
                            (context as? android.app.Activity)?.finish()
                        },
                    )
                    val nextBook = books.firstOrNull { it.readProgress?.completed != true }
                    if (nextBook != null) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                loadScope.launch {
                                    runCatching { KomgaReaderLauncher.open(context, client, nextBook.id) }
                                        .onFailure {
                                            android.widget.Toast.makeText(
                                                context, "打开阅读器失败：${it.message}", android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            Text("继续阅读${nextBook.metadata.number?.let { " · 第 $it 话" } ?: ""}")
                        }
                    } else {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "已全部读完",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "书籍（${books.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    // Unified shelf — grid or list, same as everywhere else.
                    Box(Modifier.weight(1f)) {
                        BookShelf(
                            client = client,
                            books = books,
                            mode = mode,
                            columns = columns,
                            onBookClick = { bookId ->
                                loadScope.launch {
                                    runCatching { KomgaReaderLauncher.open(context, client, bookId) }
                                        .onFailure {
                                            android.widget.Toast.makeText(
                                                context, "打开阅读器失败：${it.message}", android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                }
                            },
                            onDataChanged = { load() },
                            showDownload = true,
                            downloadState = { bookId ->
                                downloadStates.value[bookId]
                                    ?: if (downloadStore.isDownloaded(bookId)) DownloadUiState.DOWNLOADED else DownloadUiState.NONE
                            },
                            onDownloadClick = { startDownload(it) },
                        )
                    }
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

@Composable
private fun SeriesHeader(
    client: KomgaApiClient,
    series: SeriesDto,
    books: List<BookDto>,
    onChipClick: (String, String) -> Unit = { _, _ -> },
) {
    // Authors come from the first book's metadata (Komga series endpoint doesn't
    // include authors), so we fall back to book metadata.
    val authors = series.metadata.authors.ifEmpty {
        books.firstOrNull()?.metadata?.authors.orEmpty()
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            KomgaCover(
                client = client,
                url = client.seriesThumbnailUrl(series.id),
                modifier = Modifier.width(90.dp).height(120.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = "${series.booksReadCount} / ${series.booksCount} 已读 · ${series.booksUnreadCount} 未读",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                if (authors.isNotEmpty()) {
                    AuthorChips(authors) { name, role ->
                        // Komga author filter expects "name,role" format.
                        val value = if (role.isNullOrBlank()) name else "$name,$role"
                        onChipClick("author", value)
                    }
                }
                val status = series.metadata.status
                if (!status.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Tag chips — tapping one jumps to the Library filtered by that tag
        // (matches Komga WebUI behaviour). Genres + tags are merged and shown
        // as individual clickable chips.
        val tags = series.metadata.genres + series.metadata.tags
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.take(12).forEach { tag ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onChipClick("tag", tag) },
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        val summary = series.metadata.summary
        if (summary?.isNotBlank() == true) {
            Spacer(Modifier.height(8.dp))
            ExpandableSummary(text = summary)
        }
    }
}

/**
 * Mihon-style expandable summary: defaults to FULLY expanded so the whole
 * description is visible on open. Once the text exceeds [collapsedMaxLines]
 * a "收回" (collapse) button appears — tapping it folds the text back to
 * [collapsedMaxLines] and swaps the button to "展开" (expand) again.
 */
@Composable
private fun ExpandableSummary(
    text: String,
    collapsedMaxLines: Int = 6,
) {
    var expanded by remember { mutableStateOf(true) }
    // True only after the first layout pass, when we know how many lines the
    // text actually takes — this avoids flashing the toggle for short blurbs.
    var canCollapse by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result: TextLayoutResult ->
                canCollapse = result.lineCount > collapsedMaxLines
            },
        )
        if (canCollapse) {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (expanded) "收回" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AuthorChips(
    authors: List<AuthorDto>,
    onAuthorClick: (String, String?) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        authors.take(6).forEach { author ->
            val role = author.role?.takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.clickable { onAuthorClick(author.name, author.role) },
            ) {
                Text(
                    text = "$role${author.name}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}