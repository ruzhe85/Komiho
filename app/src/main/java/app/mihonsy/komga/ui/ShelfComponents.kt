package app.mihonsy.komga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.SeriesDto

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
            contentDescription = if (mode == LibraryDisplayMode.List) "切换平铺" else "切换列表",
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
                LibrarySeriesListRow(client, s) { onSeriesClick(s.id) }
            }
        }
    } else {
        val minSize = if (mode == LibraryDisplayMode.ComfortableGrid) 168.dp else 108.dp
        val cells = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(minSize = minSize)
        LazyVerticalGrid(
            columns = cells,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(series) { s ->
                LibrarySeriesCard(client, s) { onSeriesClick(s.id) }
            }
        }
    }
}

/** Unified book shelf (grid or list rows). columns: 0 = auto-adaptive. */
@Composable
fun BookShelf(
    client: KomgaApiClient,
    books: List<BookDto>,
    mode: LibraryDisplayMode,
    columns: Int = 0,
    onBookClick: (String) -> Unit,
) {
    if (mode == LibraryDisplayMode.List) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(books) { b ->
                BookShelfListRow(client, b) { onBookClick(b.id) }
            }
        }
    } else {
        val minSize = if (mode == LibraryDisplayMode.ComfortableGrid) 168.dp else 108.dp
        val cells = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(minSize = minSize)
        LazyVerticalGrid(
            columns = cells,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            gridItems(books) { b ->
                BookShelfCard(
                    client = client,
                    book = b,
                    // Compact grid overlays the title on the cover; comfortable
                    // grid keeps the title below the cover.
                    titleInside = mode == LibraryDisplayMode.CompactGrid,
                ) { onBookClick(b.id) }
            }
        }
    }
}

/** List-mode book row — cover thumbnail + title + series + read state. */
@Composable
fun BookShelfListRow(client: KomgaApiClient, book: BookDto, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
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
                rp?.completed == true -> "已读"
                rp != null && rp.page > 0 && book.media.pagesCount > 0 ->
                    (rp.page.toFloat() / book.media.pagesCount * 100).toInt().toString() + "%"
                rp != null && rp.page > 0 -> "已读 ${rp.page}页"
                else -> "未读"
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
        title = { Text("显示设置") },
        text = {
            Column {
                Text(
                    text = "显示模式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LibraryDisplayMode.entries) { m ->
                        FilterChip(
                            selected = displayMode == m,
                            onClick = { onModeChange(m) },
                            label = { Text(m.label) },
                        )
                    }
                }
                if (displayMode != LibraryDisplayMode.List) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "每行数量（${if (isLandscape) "横屏" else "竖屏"}）",
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
                        text = if (columnCount == 0) "自动" else "${columnCount} 列",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}