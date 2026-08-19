package app.mihonsy.komga.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.SeriesDto
import kotlin.math.ceil

/**
 * 系列详情：元数据 + tag + 书籍列表（M1）。
 * 已读/未读/进度实时来自服务器。
 */
class KomgaSeriesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seriesId = intent.getStringExtra("seriesId").orEmpty()
        setContent { KomgaSeriesScreen(seriesId) }
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

    LaunchedEffect(seriesId) {
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

    Scaffold(
        topBar = { TopAppBar(title = { Text(series?.name ?: "系列") }) },
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
            }
            series != null -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
            ) {
                item { SeriesHeader(client, series!!) }
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "书籍（${books.size}）",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(books) { book -> BookRow(book) { bookId ->
                    context.startActivity(
                        android.content.Intent(context, KomgaReaderActivity::class.java)
                            .putExtra("bookId", bookId)
                            .putExtra("bookName", book.metadata.title ?: book.name),
                    )
                } }
            }
        }
    }
}

@Composable
private fun SeriesHeader(client: KomgaApiClient, series: SeriesDto) {
    Row(modifier = Modifier.fillMaxWidth()) {
        KomgaCover(
            client = client,
            url = client.seriesThumbnailUrl(series.id),
            modifier = Modifier.width(90.dp).height(120.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(series.name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${series.booksReadCount} / ${series.booksCount} 已读 · ${series.booksUnreadCount} 未读",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // tag chips
            val tags = series.metadata.genres + series.metadata.tags
            if (tags.isNotEmpty()) {
                Text(
                    text = tags.take(6).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val summary = series.metadata.summary
            if (summary?.isNotBlank() == true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun BookRow(book: BookDto, onClick: (String) -> Unit = {}) {
    Column {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(book.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(book.metadata.title ?: book.name, style = MaterialTheme.typography.bodyLarge)
                if (book.metadata.number != null) {
                    Text(
                        text = "第 ${book.metadata.number} 话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val rp = book.readProgress
            when {
                rp?.completed == true -> Text(
                    text = "已读",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rp != null && rp.page > 0 && book.media.pagesCount > 0 -> Text(
                    text = "读到 ${ceil(rp.page.toDouble() / book.media.pagesCount * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                else -> Text(
                    text = "未读",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
