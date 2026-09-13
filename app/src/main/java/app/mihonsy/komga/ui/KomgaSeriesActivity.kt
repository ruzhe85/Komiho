package app.mihonsy.komga.ui

import eu.kanade.tachiyomi.R
import android.content.res.Configuration
import androidx.compose.ui.res.stringResource as composeStringResource
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import eu.kanade.presentation.util.isTabletUi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.saveable.rememberSaveable
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
        setContent {
            KomihoTheme {
                // Komiho: 二级页面复用主界面的 rail（平板 AUTO/左/右 时），不再「全屏无导航」。
                val navPrefs = remember { KomgaPreferences(applicationContext) }
                KomgaSecondaryNavHost(navPrefs) { modifier -> KomgaSeriesScreen(seriesId, modifier) }
            }
        }
    }
}

@Composable
private fun KomgaSeriesScreen(seriesId: String, modifier: Modifier = Modifier) {
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
    // 每行数量（0 = 自动）。沿用库的列数偏好——网格密度属全局审美设置，不按页拆分。
    var portraitColumns by remember { mutableStateOf(prefs.libraryPortraitColumns) }
    var landscapeColumns by remember { mutableStateOf(prefs.libraryLandscapeColumns) }
    val columns = if (isLandscape) landscapeColumns else portraitColumns
    // U3: book-level display mode (independent from the series shelf).
    var mode by remember { mutableStateOf(LibraryDisplayMode.fromPref(prefs.bookDisplayMode)) }
    // SY: 书籍列表的排序 / 阅读状态筛选。偏好独立于库页（bookSort），
    // 仅按钮形式与库一致——共用一个三页对话框（ShelfOptionsMenu）。
    var bookSort by remember { mutableStateOf(BookSort.fromPref(prefs.bookSort)) }
    var readFilter by remember { mutableStateOf(ReadFilter.All) }
    var optionsOpen by remember { mutableStateOf(false) }
    // 平板 / 手机布局分支：与 MihonSY 一致，smallestScreenWidthDp ≥ 阈值即平板。
    // 手机：整页随书籍一起滚动；平板：左简介（固定可滚）/ 右书籍分栏。
    val isTablet = isTabletUi()

    val loadScope = rememberCoroutineScope()

    fun load() {
        loading = true
        error = null
        loadScope.launch {
            runCatching {
                val s = client.getSeriesDetail(seriesId)
                // SY: 排序与阅读状态筛选都交给 Komga 服务端（与库页同口径）：
                //  - sort=metadata.numberSort 才得到 第1话/第2话… 的正确卷序
                //    （不传时 Komga 按名称字典序返回，第100话会排在第1话前）；
                //  - read_status 直接过滤 UNREAD / READ / IN_PROGRESS。
                // 因此这里不再做客户端重排，否则会覆盖服务端排序结果。
                val b = client.getSeriesBooks(
                    seriesId = seriesId,
                    size = 200,
                    sort = bookSort.komgaSort,
                    readStatus = readFilter.komgaValue,
                ).content
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

    LaunchedEffect(seriesId, bookSort, readFilter) { load() }

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

    // 下一本未读完的书（FAB「继续阅读」的目标）；全部读完时为 null → 不显示 FAB。
    val nextBook = books.firstOrNull { it.readProgress?.completed != true }
    // 已读过任意一本 → FAB 文案为「继续阅读」，否则「开始阅读」。
    val isReading = books.any { it.readProgress != null }

    fun openBook(bookId: String) {
        loadScope.launch {
            runCatching { KomgaReaderLauncher.open(context, client, bookId) }
                .onFailure {
                    android.widget.Toast.makeText(
                        context, "打开阅读器失败：${it.message}", android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(series?.name ?: "系列") },
                actions = {
                    if (series != null) {
                        // SY: 与库页同款的三合一菜单（阅读状态 / 排序 / 显示模式）。
                        IconButton(onClick = { optionsOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = composeStringResource(R.string.cd_display_options),
                            )
                        }
                    }
                },
            )
        },
        // SY: MihonSY 口径的「继续阅读」浮动按钮——原先是 header 里的整宽 OutlinedButton，
        // 在手机端与简介区叠在一起（还抢走了「收回」的点击）。改成右下角 FAB 后两者都消失。
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                text = {
                    Text(
                        text = if (isReading) "继续阅读" else "开始阅读",
                    )
                },
                icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = { nextBook?.let { openBook(it.id) } },
                expanded = true,
                modifier = Modifier.animateFloatingActionButton(
                    visible = nextBook != null,
                    alignment = Alignment.BottomEnd,
                ),
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
                val downloadStore = remember { KomgaDownloadStore(context) }
                val downloader = remember { KomgaBookDownloader(context, client, downloadStore) }
                val downloadStates = remember { mutableStateOf<Map<String, DownloadUiState>>(emptyMap()) }
                fun startDownload(bookId: String) {
                    val book = books.firstOrNull { it.id == bookId }
                    loadScope.launch {
                        downloader.downloadBook(
                            bookId,
                            s.name,
                            s.id,
                            bookName = book?.name ?: "",
                            number = book?.number ?: 0,
                            coverUrl = client.seriesThumbnailUrl(s.id),
                        ).collect { ev ->
                            val cur = downloadStates.value.toMutableMap()
                            when (ev) {
                                is KomgaDownloadEvent.Queued -> cur[ev.bookId] = DownloadUiState.QUEUED
                                is KomgaDownloadEvent.Progress -> cur[ev.bookId] = DownloadUiState.DOWNLOADING
                                is KomgaDownloadEvent.Completed -> {
                                    cur[ev.bookId] = DownloadUiState.DOWNLOADED
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.download_complete),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                is KomgaDownloadEvent.Error -> {
                                    cur[ev.bookId] = DownloadUiState.ERROR
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.download_failed, ev.message),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                                is KomgaDownloadEvent.Canceled -> cur.remove(ev.bookId)
                            }
                            downloadStates.value = cur
                        }
                    }
                }

                val onChipClick: (String, String) -> Unit = { type, value ->
                    // Komga WebUI parity: tap a tag/author → open the Library filtered by it.
                    val intent = android.content.Intent(context, KomgaMainActivity::class.java)
                        .putExtra("filterType", type)
                        .putExtra("filterValue", value)
                    context.startActivity(intent)
                }

                // 手机：整页随书籍一起滚动（简介作为书架列表首个 item）；
                // 平板：左侧固定简介区（自身可滚）/ 右侧书籍滚动，分栏。
                if (isTablet) {
                    Row(Modifier.fillMaxSize().padding(padding)) {
                        Column(
                            Modifier
                                .weight(0.38f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            SeriesDetailHeader(
                                client = client,
                                series = s,
                                books = books,
                                onChipClick = onChipClick,
                            )
                        }
                        Box(Modifier.weight(0.62f)) {
                            BookShelf(
                                client = client,
                                books = books,
                                mode = mode,
                                columns = columns,
                            header = {
                                Column {
                                    Text(
                                        text = "书籍（${books.size}）",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                            },
                                onBookClick = { openBook(it) },
                                onDataChanged = { load() },
                                showDownload = false,
                                downloadState = { bookId ->
                                    downloadStates.value[bookId]
                                        ?: if (downloadStore.isDownloaded(bookId)) DownloadUiState.DOWNLOADED else DownloadUiState.NONE
                                },
                                onDownloadClick = { startDownload(it) },
                            )
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        BookShelf(
                            client = client,
                            books = books,
                            mode = mode,
                            columns = columns,
                            header = {
                                Column {
                                    SeriesDetailHeader(
                                        client = client,
                                        series = s,
                                        books = books,
                                        onChipClick = onChipClick,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "书籍（${books.size}）",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                }
                            },
                            onBookClick = { openBook(it) },
                            onDataChanged = { load() },
                            showDownload = false,
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

    ShelfOptionsMenu(
        expanded = optionsOpen,
        onDismiss = { optionsOpen = false },
        displayMode = mode,
        onDisplayModeChange = {
            mode = it
            prefs.bookDisplayMode = it.prefValue
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
        sortOptions = BookSortBy.entries.map {
            SortOptionUi(it.labelText(), it.prefKey, it.defaultDescending)
        },
        currentSortKey = bookSort.sortBy.prefKey,
        sortDescending = bookSort.descending,
        onSortChange = { key, desc ->
            val next = BookSort(BookSortBy.fromPrefKey(key), desc)
            bookSort = next
            prefs.bookSort = next.toPref()
        },
        readFilter = readFilter,
        onReadFilterChange = { readFilter = it },
    )
}

/**
 * 系列详情头部（封面/作者/状态/标签/简介 + 书籍标题）。
 * 手机端作为书架列表首个 item 一起滚动；平板端放在固定左栏（自身可滚）。
 * 「继续阅读」不再放在这里——MihonSY 口径改为右下角浮动按钮（见 Scaffold.floatingActionButton）。
 */
@Composable
private fun SeriesDetailHeader(
    client: KomgaApiClient,
    series: SeriesDto,
    books: List<BookDto>,
    onChipClick: (String, String) -> Unit = { _, _ -> },
) {
    SeriesHeader(client = client, series = series, books = books, onChipClick = onChipClick)
    Spacer(Modifier.height(16.dp))
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
        // Genre + tag chips. Komga exposes genres and tags as SEPARATE filter
        // facets (genre / tag query params), so we emit them with distinct types
        // — tapping a genre filters by genre, a tag by tag. Both jump to the
        // Library filtered cross-library (Komga WebUI / Komelia parity).
        if (series.metadata.genres.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = composeStringResource(R.string.detail_genres_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FilterChipRow(series.metadata.genres.take(12)) { onChipClick("genre", it) }
        }
        if (series.metadata.tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = composeStringResource(R.string.detail_tags_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FilterChipRow(series.metadata.tags.take(12)) { onChipClick("tag", it) }
        }
        val summary = series.metadata.summary
        if (summary?.isNotBlank() == true) {
            Spacer(Modifier.height(8.dp))
            ExpandableSummary(text = summary)
        }
    }
}

/**
 * MihonSY-style expandable summary: 默认全展开，点击简介或底部按钮切换 展开/收回。
 *
 * 关键点：是否显示切换按钮由 [overflow] 决定，而它**只会被置 true、从不重置**——
 * 早期版本用 `onTextLayout { canCollapse = lineCount > maxLines }`，收回后文本被裁剪到
 * collapsedMaxLines，lineCount 刚好等于阈值 → canCollapse 变 false → 按钮直接消失，
 * 于是「收回后无法再展开」（平板长简介必现）。
 */
@Composable
private fun ExpandableSummary(
    text: String,
    collapsedMaxLines: Int = 6,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var overflow by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result: TextLayoutResult ->
                // 默认展开态 maxLines=MAX，文本不会「溢出」，不能用 hasVisualOverflow
                // 判定（否则按钮永不出现）。改为看真实行数是否超过 collapsedMaxLines——
                // 超过即说明收起后会被裁剪，应常驻切换按钮（与展开态无关）。
                val exceedsCollapsed = result.lineCount > collapsedMaxLines
                overflow = exceedsCollapsed || result.hasVisualOverflow
            },
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (overflow) {
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

/** Reusable row of clickable filter chips (genres / tags) used on the series detail page. */
@Composable
private fun FilterChipRow(
    items: List<String>,
    onChipClick: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onChipClick(item) },
            ) {
                Text(
                    text = item,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}