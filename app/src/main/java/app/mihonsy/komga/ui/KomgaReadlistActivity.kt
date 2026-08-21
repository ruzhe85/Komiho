package app.mihonsy.komga.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.ReadingListDto
import kotlinx.coroutines.launch

/**
 * Komiho M3: readlist detail — shows the books inside a reading list.
 * Data is fetched live from Komga (GET /api/v1/readlists/{id}/books).
 * Renders the same compact BookShelfCard grid as the rest of the app so
 * the look matches the library / section-list / series-detail pages.
 */
class KomgaReadlistActivity : KomgaBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val readlistId = intent.getStringExtra("readlistId").orEmpty()
        val readlistName = intent.getStringExtra("readlistName").orEmpty()
        setContent { KomihoTheme { KomgaReadlistScreen(readlistId, readlistName) } }
    }
}

@Composable
private fun KomgaReadlistScreen(readlistId: String, readlistName: String) {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val client = remember { KomgaApiClient(prefs.connection()) }
    val scope = rememberCoroutineScope()

    var readlist by remember { mutableStateOf<ReadingListDto?>(null) }
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

    fun reload() {
        scope.launch {
            runCatching {
                val rl = client.getReadlist(readlistId)
                val bs = client.getReadlistBooks(readlistId)
                rl to bs
            }.onSuccess {
                readlist = it.first
                books = it.second
            }.onFailure {
                error = it.message
            }
            loading = false
        }
    }

    LaunchedEffect(readlistId) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(readlist?.name ?: readlistName) },
                actions = {
                    if (books.isNotEmpty()) {
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { client.getReadlistBooks(readlistId) }
                                .onSuccess { books = it; error = null }
                                .onFailure { error = "加载失败：${it.message}" }
                        }
                    }) { Text("重试") }
                }
            }
            books.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("该阅读列表暂无书籍")
            }
            else -> Box(Modifier.fillMaxSize().padding(padding)) {
                BookShelf(
                    client = client,
                    books = books,
                    mode = mode,
                    columns = columns,
                    onBookClick = { bookId ->
                        scope.launch {
                            runCatching { KomgaReaderLauncher.open(context, client, bookId) }
                                .onFailure {
                                    android.widget.Toast.makeText(
                                        context, "打开阅读器失败：${it.message}", android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    },
                    onDataChanged = { reload() },
                )
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