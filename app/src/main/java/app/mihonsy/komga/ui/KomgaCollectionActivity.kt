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
import app.mihonsy.komga.data.model.CollectionDto
import app.mihonsy.komga.data.model.SeriesDto
import kotlinx.coroutines.launch

/**
 * Komiho M3: collection detail — series inside a Komga collection.
 *
 * Collections carry only seriesIds, so we resolve each id via the
 * series thumbnail endpoint and the collection detail endpoint.
 */
class KomgaCollectionActivity : KomgaBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val collectionId = intent.getStringExtra("collectionId").orEmpty()
        val collectionName = intent.getStringExtra("collectionName").orEmpty()
        setContent { KomihoTheme { KomgaCollectionScreen(collectionId, collectionName) } }
    }
}

@Composable
private fun KomgaCollectionScreen(collectionId: String, collectionName: String) {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val client = remember { KomgaApiClient(prefs.connection()) }
    val scope = rememberCoroutineScope()

    var collection by remember { mutableStateOf<CollectionDto?>(null) }
    var seriesList by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val configuration = LocalConfiguration.current
    val isLandscape = remember(configuration) {
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    // Column count for this page's grid (0 = auto).
    val columns = if (isLandscape) prefs.libraryLandscapeColumns else prefs.libraryPortraitColumns
    // U3: shared display mode (live from prefs).
    val mode = LibraryDisplayMode.fromPref(prefs.libraryDisplayMode)
    var displayOpen by remember { mutableStateOf(false) }

    LaunchedEffect(collectionId) {
        runCatching {
            val c = client.getCollection(collectionId)
            val series = c.seriesIds.mapNotNull { id ->
                runCatching { client.getSeriesDetail(id) }.getOrNull()
            }
            c to series
        }.onSuccess {
            collection = it.first
            seriesList = it.second
        }.onFailure {
            error = it.message
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(collection?.name ?: collectionName) },
                actions = {
                    if (collection != null) {
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
                            runCatching { client.getCollection(collectionId).seriesIds }
                                .onSuccess { error = null; loading = true }
                                .onFailure { error = "加载失败：${it.message}" }
                            loading = false
                        }
                    }) { Text("重试") }
                }
            }
            seriesList.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("该收藏暂无系列")
            }
            else -> Box(Modifier.fillMaxSize().padding(padding)) {
                SeriesShelf(client, seriesList, mode, columns) { seriesId ->
                    context.startActivity(
                        android.content.Intent(context, KomgaSeriesActivity::class.java)
                            .putExtra("seriesId", seriesId),
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
                prefs.libraryDisplayMode = it.prefValue
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
