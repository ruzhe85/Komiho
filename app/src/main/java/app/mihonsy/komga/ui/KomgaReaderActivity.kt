package app.mihonsy.komga.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.PageDto
import kotlinx.coroutines.launch

/**
 * Komga 阅读器（M2 简化版）：逐页浏览 + 进度双向同步。
 * - 打开时从 readProgress 恢复
 * - 翻页 PATCH 写回（page）
 * - 最后一页 → completed
 * 后续迁移到 MihonSY 完整阅读器（条漫/增强）。
 */
class KomgaReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookId = intent.getStringExtra("bookId").orEmpty()
        val bookName = intent.getStringExtra("bookName") ?: ""
        setContent { KomgaReaderScreen(bookId, bookName) }
    }
}

@Composable
private fun KomgaReaderScreen(bookId: String, bookName: String) {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val client = remember { KomgaApiClient(prefs.connection()) }
    val scope = rememberCoroutineScope()

    var book by remember { mutableStateOf<BookDto?>(null) }
    var pages by remember { mutableStateOf<List<PageDto>>(emptyList()) }
    var startPage by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId) {
        runCatching {
            val b = client.getBook(bookId)
            val p = client.getBookPages(bookId)
            b to p
        }.onSuccess {
            book = it.first
            pages = it.second
            startPage = (it.first.readProgress?.page ?: 0).coerceIn(0, (it.second.size - 1).coerceAtLeast(0))
        }.onFailure {
            error = it.message
            Toast.makeText(context, "加载失败：${it.message}", Toast.LENGTH_LONG).show()
        }
        loading = false
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(bookName) }) },
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null || pages.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(error ?: "无页面")
            }
            else -> ReaderPager(client, bookId, pages, startPage) { page ->
                // 翻页写回进度
                val b = book
                if (b != null) {
                    val completed = page >= pages.size - 1
                    scope.launch {
                        runCatching {
                            client.updateReadProgress(bookId, page = page, completed = completed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPager(
    client: KomgaApiClient,
    bookId: String,
    pages: List<PageDto>,
    startPage: Int,
    onPageChange: (Int) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = startPage) { pages.size }

    LaunchedEffect(pagerState.currentPage) {
        onPageChange(pagerState.currentPage)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
            PageImage(client = client, bookId = bookId, pageNumber = pages[index].number)
        }
        Text(
            text = "${pagerState.currentPage + 1} / ${pages.size}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        )
    }
}

@Composable
private fun PageImage(client: KomgaApiClient, bookId: String, pageNumber: Int) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(bookId, pageNumber) {
        bitmap = null
        failed = false
        runCatching { client.downloadBytes(client.pageImageUrl(bookId, pageNumber)) }
            .onSuccess { bytes ->
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            .onFailure { failed = true }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
            failed -> Text("页面加载失败", color = MaterialTheme.colorScheme.error)
            else -> CircularProgressIndicator()
        }
    }
}
