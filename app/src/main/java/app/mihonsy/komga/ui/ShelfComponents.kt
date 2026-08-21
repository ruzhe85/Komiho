package app.mihonsy.komga.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource as composeStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.ReadingListDto
import app.mihonsy.komga.data.model.SeriesDto
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch

/**
 * Shared shelf rendering for every page that lists series or books.
 * One display mode (LibraryDisplayMode from KomgaPreferences) drives
 * every page, so the app looks identical everywhere:
 *  - CompactGrid / ComfortableGrid → adaptive LazyVerticalGrid
 *  - List → LazyColumn of row cards
 */

/** Grid/list toggle button for TopAppBar actions. */
@Composable
fun ShelfModeToggle(mode: LibraryDisplayMode, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (mode == LibraryDisplayMode.List) Icons.Filled.GridView else Icons.Filled.ViewList,
            contentDescription = if (mode == LibraryDisplayMode.List) composeStringResource(R.string.cd_toggle_grid) else composeStringResource(R.string.cd_toggle_list),
        )
    }
}

/** Unified series shelf (grid or list rows). columns: 0 = auto-adaptive. */
@Composable
fun SeriesShelf(
    client: KomgaApiClient,
    series: List<SeriesDto>,
    mode: LibraryDisplayMode,
    columns: Int = 0,
    onSeriesClick: (String) -> Unit,
) {
    if (mode == LibraryDisplayMode.List) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(series) { s ->
                LibrarySeriesListRow(client, s, onClick = { onSeriesClick(s.id) })
            }
        }
    } else {
        // The display mode drives BOTH the auto column density AND the grid
        // spacing, so 紧凑网格 / 舒适网格 always has a visible effect — even
        // with a fixed column count (where the Adaptive min size is ignored).
        val isCompact = mode == LibraryDisplayMode.CompactGrid
        val minSize = if (isCompact) 96.dp else 168.dp
        val hSpace = if (isCompact) 4.dp else 8.dp
        val vSpace = if (isCompact) 6.dp else 12.dp
        val cells = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(minSize = minSize)
        LazyVerticalGrid(
            columns = cells,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(hSpace),
            verticalArrangement = Arrangement.spacedBy(vSpace),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(series) { s ->
                LibrarySeriesCard(client, s, onClick = { onSeriesClick(s.id) })
            }
        }
    }
}

/**
 * Unified book shelf with Mihon-style multi-select.
 *
 * - Short tap: open the book (or toggle selection if already in selection mode).
 * - Long press: enter selection mode (or toggle the pressed book).
 * - Selection toolbar (mihon library style) appears above the grid:
 *   "已选 N 项" + 标记已读 / 标记未读 / 加入阅读列表 / 取消.
 *
 * The "add to readlist" toolbar button opens a Mihon-style dialog that lets
 * the user either pick an existing readlist or type a new name to create one.
 */
@Composable
fun BookShelf(
    client: KomgaApiClient,
    books: List<BookDto>,
    mode: LibraryDisplayMode,
    columns: Int = 0,
    onBookClick: (String) -> Unit,
    onDataChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showReadlistPicker by remember { mutableStateOf(false) }

    // ── Mihon-style drag-to-select (划动选择) ──
    // Long-press enters selection mode AND starts a drag gesture: every item
    // the finger slides over afterwards is flipped to `dragValue` (true=select).
    // Releasing the finger ends the drag; tapping individual items still toggles.
    var dragActive by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(true) }

    val inSelection = selectedIds.isNotEmpty()
    val selectedBooks = remember(selectedIds, books) { books.filter { it.id in selectedIds } }

    val setSelect: (String, Boolean) -> Unit = { id, value ->
        selectedIds = if (value) selectedIds + id else selectedIds - id
    }
    val toggleSelect: (String) -> Unit = { id ->
        setSelect(id, id !in selectedIds)
    }
    val exitSelection: () -> Unit = { selectedIds = emptySet() }

    val startDragSelect: (String) -> Unit = { id ->
        // Enter selection mode on long-press and begin drag-selecting this item.
        val newValue = id !in selectedIds
        setSelect(id, newValue)
        dragActive = true
        dragValue = newValue
    }
    val onDragSelectAt: (String) -> Unit = { id ->
        if (dragActive) setSelect(id, dragValue)
    }

    // Resolve which item id sits under a pointer Y (relative to the lazy
    // container's content area), using the lazy layout's visible items.
    val resolveItemAtList: (LazyListLayoutInfo, Float, Float) -> String? = { info, py, _ ->
        val hit = info.visibleItemsInfo.firstOrNull { item ->
            py >= item.offset && py < item.offset + item.size
        }
        hit?.key as? String
    }
    val resolveItemAtGrid: (LazyGridLayoutInfo, Float, Float) -> String? = { info, py, _ ->
        val hit = info.visibleItemsInfo.firstOrNull { item ->
            py >= item.offset && py < item.offset + item.size
        }
        hit?.key as? String
    }

    fun performBatchUpdate(completed: Boolean) {
        val snapshot = selectedBooks
        scope.launch {
            runCatching {
                snapshot.forEach { b ->
                    if (completed) {
                        client.updateReadProgress(b.id, b.media.pagesCount.coerceAtLeast(1), true)
                    } else {
                        client.deleteReadProgress(b.id)
                    }
                }
            }.onSuccess {
                android.widget.Toast.makeText(context, context.getString(R.string.batch_marked, snapshot.size), android.widget.Toast.LENGTH_SHORT).show()
                exitSelection()
                onDataChanged()
            }.onFailure {
                android.widget.Toast.makeText(context, context.getString(R.string.operation_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showReadlistPicker) {
        ReadlistPickerDialog(
            client = client,
            bookIds = selectedBooks.map { it.id },
            onDismiss = { showReadlistPicker = false },
            onAdded = {
                showReadlistPicker = false
                exitSelection()
                onDataChanged()
            },
        )
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
                    IconButton(onClick = exitSelection) {
                        Icon(Icons.Filled.Close, contentDescription = composeStringResource(R.string.cancel))
                    }
                    Text(
                        composeStringResource(R.string.selected_count, selectedIds.size),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { selectedIds = books.map { it.id }.toSet() }) {
                        Icon(Icons.Filled.SelectAll, contentDescription = composeStringResource(R.string.select_all))
                    }
                }
            }
        }

        val itemOnClick: (BookDto) -> Unit = { b ->
            if (inSelection) toggleSelect(b.id) else onBookClick(b.id)
        }

        if (mode == LibraryDisplayMode.List) {
            val listState = rememberLazyListState()
            val padTop = with(LocalDensity.current) { 4.dp.toPx() }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (inSelection) Modifier.weight(1f) else Modifier)
                    // Mihon-style slide-to-select: long-press starts the drag,
                    // then every row the finger passes over gets selected.
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { start: Offset ->
                                val y = start.y - padTop
                                val id = resolveItemAtList(listState.layoutInfo, y, start.x)
                                if (id != null) startDragSelect(id)
                            },
                            onDrag = { change: PointerInputChange, _: Offset ->
                                change.consume()
                                val y = change.position.y - padTop
                                val id = resolveItemAtList(listState.layoutInfo, y, change.position.x)
                                if (id != null) onDragSelectAt(id)
                            },
                            onDragEnd = { dragActive = false },
                            onDragCancel = { dragActive = false },
                        )
                    },
            ) {
                items(books, key = { it.id }) { b ->
                    BookShelfListRow(
                        client = client,
                        book = b,
                        selected = b.id in selectedIds,
                        onClick = { itemOnClick(b) },
                        onLongClick = { startDragSelect(b.id) },
                    )
                }
            }
        } else {
            // Display mode drives density + spacing (see SeriesShelf above),
            // so the mode choice is always visible regardless of the slider.
            val isCompact = mode == LibraryDisplayMode.CompactGrid
            val minSize = if (isCompact) 96.dp else 168.dp
            val hSpace = if (isCompact) 4.dp else 8.dp
            val vSpace = if (isCompact) 6.dp else 12.dp
            val cells = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(minSize = minSize)
            val gridState = rememberLazyGridState()
            val padTop = with(LocalDensity.current) { 8.dp.toPx() }
            LazyVerticalGrid(
                state = gridState,
                columns = cells,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(hSpace),
                verticalArrangement = Arrangement.spacedBy(vSpace),
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (inSelection) Modifier.weight(1f) else Modifier)
                    // Mihon-style slide-to-select across grid cells.
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { start: Offset ->
                                val y = start.y - padTop
                                val id = resolveItemAtGrid(gridState.layoutInfo, y, start.x)
                                if (id != null) startDragSelect(id)
                            },
                            onDrag = { change: PointerInputChange, _: Offset ->
                                change.consume()
                                val y = change.position.y - padTop
                                val id = resolveItemAtGrid(gridState.layoutInfo, y, change.position.x)
                                if (id != null) onDragSelectAt(id)
                            },
                            onDragEnd = { dragActive = false },
                            onDragCancel = { dragActive = false },
                        )
                    },
            ) {
                gridItems(books, key = { it.id }) { b ->
                    BookShelfCard(
                        client = client,
                        book = b,
                        // Compact grid overlays the title on the cover; comfortable
                        // grid keeps the title below the cover.
                        titleInside = mode == LibraryDisplayMode.CompactGrid,
                        selected = b.id in selectedIds,
                        onClick = { itemOnClick(b) },
                        onLongClick = { startDragSelect(b.id) },
                    )
                }
            }
        }

        // ── Mihon-style selection bottom action bar: 阅读列表 / 已读 / 未读 ──
        if (inSelection) {
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
                        icon = Icons.Outlined.BookmarkAdd,
                        label = composeStringResource(R.string.add_to_readlist),
                        onClick = { showReadlistPicker = true },
                    )
                    SelectionActionItem(
                        icon = Icons.Outlined.DoneAll,
                        label = composeStringResource(R.string.mark_read),
                        onClick = { performBatchUpdate(true) },
                    )
                    SelectionActionItem(
                        icon = Icons.Outlined.RemoveDone,
                        label = composeStringResource(R.string.mark_unread),
                        onClick = { performBatchUpdate(false) },
                    )
                }
            }
        }
    }
}

/** A single icon + label button in the Mihon-style selection bottom bar. */
@Composable
fun RowScope.SelectionActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(1f)
            .combinedClickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = label)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * Mihon-style "add to readlist" dialog. The top row is a search-or-create
 * field + "创建" button. Typing a non-existing name and tapping 创建 both
 * creates the readlist and adds the selected books. Typing a query filters
 * the list of existing readlists below; tapping one adds the books.
 */
@Composable
private fun ReadlistPickerDialog(
    client: KomgaApiClient,
    bookIds: List<String>,
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
            runCatching { client.addBooksToReadlist(readlistId, bookIds) }
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
            runCatching { client.createReadlist(name) }
                .onSuccess { created ->
                    runCatching { client.addBooksToReadlist(created.id, bookIds) }
                        .onSuccess {
                            android.widget.Toast.makeText(context, context.getString(R.string.created_and_added), android.widget.Toast.LENGTH_SHORT).show()
                            onAdded()
                        }
                        .onFailure {
                            android.widget.Toast.makeText(context, context.getString(R.string.created_but_add_failed, it.message), android.widget.Toast.LENGTH_LONG).show()
                        }
                }
                .onFailure {
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
                    TextButton(
                        onClick = ::createAndAdd,
                        enabled = query.isNotBlank(),
                    ) { Text(composeStringResource(R.string.create)) }
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

/** List-mode book row — cover thumbnail + title + series + read state. */
@Composable
fun BookShelfListRow(
    client: KomgaApiClient,
    book: BookDto,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(2.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KomgaCover(
                client = client,
                url = client.bookThumbnailUrl(book.id),
                modifier = Modifier.width(48.dp).height(64.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = book.metadata.title ?: book.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val seriesTitle = book.seriesTitle
                if (seriesTitle != null) {
                    Text(
                        text = seriesTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val rp = book.readProgress
            val status = when {
                rp?.completed == true -> composeStringResource(R.string.book_status_read)
                rp != null && rp.page > 0 && book.media.pagesCount > 0 ->
                    (rp.page.toFloat() / book.media.pagesCount * 100).toInt().toString() + "%"
                rp != null && rp.page > 0 -> composeStringResource(R.string.book_status_read_pages, rp.page)
                else -> composeStringResource(R.string.book_status_unread)
            }
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = if (rp?.completed == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Shared display-settings dialog. Mode + columns-per-row are always
 * editable (columns slider hides for list mode). Callers must keep the
 * dialog's local state so the selected chip highlights immediately.
 */
@Composable
fun DisplaySettingsDialog(
    displayMode: LibraryDisplayMode,
    onModeChange: (LibraryDisplayMode) -> Unit,
    columnCount: Int,
    isLandscape: Boolean,
    onColumnChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(composeStringResource(R.string.display_settings_title)) },
        text = {
            Column {
                Text(
                    text = composeStringResource(R.string.display_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LibraryDisplayMode.entries) { m ->
                        FilterChip(
                            selected = displayMode == m,
                            onClick = { onModeChange(m) },
                            label = { Text(m.labelText()) },
                        )
                    }
                }
                if (displayMode != LibraryDisplayMode.List) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = composeStringResource(
                            R.string.columns_per_row,
                            composeStringResource(if (isLandscape) R.string.orientation_landscape else R.string.orientation_portrait),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 0 = auto.
                    Slider(
                        value = columnCount.toFloat(),
                        onValueChange = { onColumnChange(it.toInt()) },
                        valueRange = 0f..10f,
                        steps = 9,
                    )
                    Text(
                        text = if (columnCount == 0) composeStringResource(R.string.auto) else composeStringResource(R.string.columns_count, columnCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(composeStringResource(R.string.done)) } },
    )
}