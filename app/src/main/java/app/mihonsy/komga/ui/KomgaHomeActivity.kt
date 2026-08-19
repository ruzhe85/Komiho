package app.mihonsy.komga.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.model.LibraryDto
import app.mihonsy.komga.data.model.SeriesDto
import kotlinx.coroutines.launch

/**
 * Komga 主界面：库切换 + 系列网格（M1）。
 * 数据全部实时来自 Komga 服务器。
 */
class KomgaHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KomgaHomeScreen() }
    }
}

@Composable
private fun KomgaHomeScreen() {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var client by remember { mutableStateOf(KomgaApiClient(prefs.connection())) }
    var libraries by remember { mutableStateOf<List<LibraryDto>>(emptyList()) }
    var selectedLibrary by remember { mutableStateOf<String?>(null) }
    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!prefs.hasConnection()) {
            context.startActivity(Intent(context, KomgaConnectActivity::class.java))
            return@LaunchedEffect
        }
        runCatching { client.getLibraries() }
            .onSuccess { libs ->
                libraries = libs
                selectedLibrary = libs.firstOrNull()?.id
            }
            .onFailure {
                error = "无法连接服务器：${it.message}"
                loading = false
            }
    }

    LaunchedEffect(selectedLibrary) {
        if (selectedLibrary != null) {
            loading = true
            runCatching { client.getSeries(libraryId = selectedLibrary, size = 100) }
                .onSuccess { series = it.content }
                .onFailure { error = "加载系列失败：${it.message}" }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("我的媒体库") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // 库切换 chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(libraries.size) { i ->
                    val lib = libraries[i]
                    FilterChip(
                        selected = lib.id == selectedLibrary,
                        onClick = { selectedLibrary = lib.id },
                        label = { Text(lib.name) },
                    )
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.TextButton(onClick = {
                            scope.launch {
                                runCatching { client.getSeries(libraryId = selectedLibrary, size = 100) }
                                    .onSuccess { series = it.content; error = null }
                                    .onFailure { error = "加载失败：${it.message}" }
                            }
                        }) { Text("重试") }
                    }
                }
                series.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("该库暂无系列")
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(series) { s ->
                        SeriesCard(client, s) {
                            context.startActivity(
                                Intent(context, KomgaSeriesActivity::class.java)
                                    .putExtra("seriesId", s.id),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCard(client: KomgaApiClient, series: SeriesDto, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            ) {
                KomgaCover(
                    client = client,
                    url = client.seriesThumbnailUrl(series.id),
                    modifier = Modifier.fillMaxSize(),
                )
                if (series.booksUnreadCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    ) {
                        Text(
                            text = "${series.booksUnreadCount} 未读",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = series.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}
