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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.res.stringResource as composeStringResource
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.R
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
    // 长按 series 弹出的操作菜单 + 待移除确认状态。
    var menuSeriesId by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<SeriesDto?>(null) }

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
                SeriesShelf(
                    client = client,
                    series = seriesList,
                    mode = mode,
                    columns = columns,
                    onSeriesClick = { seriesId ->
                        context.startActivity(
                            android.content.Intent(context, KomgaSeriesActivity::class.java)
                                .putExtra("seriesId", seriesId),
                        )
                    },
                    onSeriesLongClick = { menuSeriesId = it },
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

    // 长按 series 弹出的操作菜单：从收藏移除。
    DropdownMenu(
        expanded = menuSeriesId != null,
        onDismissRequest = { menuSeriesId = null },
    ) {
        DropdownMenuItem(
            text = { Text(composeStringResource(R.string.remove_from_collection)) },
            leadingIcon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) },
            onClick = {
                val id = menuSeriesId
                menuSeriesId = null
                if (id != null) {
                    pendingRemove = seriesList.firstOrNull { it.id == id }
                }
            },
        )
    }

    // 从收藏移除 series 的二次确认弹窗。
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(composeStringResource(R.string.confirm_remove_title)) },
            text = { Text(composeStringResource(R.string.confirm_remove_series_message, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = target
                        pendingRemove = null
                        scope.launch {
                            runCatching { client.removeSeriesFromCollection(collectionId, t.id) }
                                .onSuccess {
                                    seriesList = seriesList.filter { it.id != t.id }
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.removed_from_collection),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                .onFailure {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.operation_failed, it.message),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    },
                ) { Text(composeStringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(composeStringResource(R.string.cancel))
                }
            },
        )
    }
}
