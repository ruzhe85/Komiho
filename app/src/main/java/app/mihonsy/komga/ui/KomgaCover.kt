package app.mihonsy.komga.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient

/**
 * Komiho cover: downloads via the authenticated KomgaApiClient. The
 * decoded bitmap is remembered so a stable `url` (the same series/book
 * re-entering the composition) reuses the in-memory bitmap — no
 * re-download when switching tabs. Cross-screen re-opening is not
 * cached (would need an LRU or disk cache); the Coil switchover is
 * parked until we have a clean Coil 3.5.x httpHeaders path.
 */
@Composable
fun KomgaCover(
    client: KomgaApiClient,
    url: String?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        bitmap = null
        failed = false
        if (url.isNullOrBlank()) return@LaunchedEffect
        runCatching { client.downloadBytes(url) }
            .onSuccess { bytes ->
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            .onFailure { failed = true }
    }

    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            failed -> ColorPainter(MaterialTheme.colorScheme.errorContainer)
            url.isNullOrBlank() -> ColorPainter(Color.Transparent)
            else -> ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
        }
        // Show a small spinner while the bitmap is still loading.
        if (bmp == null && !failed && !url.isNullOrBlank()) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}
