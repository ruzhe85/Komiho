package app.mihonsy.komga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.KomgaApiClient
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest

/**
 * Komiho cover: loads the Komga thumbnail through Coil's global
 * [coil3.ImageLoader] (auth carried by [KomgaApiClient]'s OkHttp client).
 *
 * Two layers of caching apply:
 *  - Coil's disk cache (`komga_covers`, 300 MiB) holds the decoded bitmap,
 *    so covers survive screen changes and app restarts without re-download.
 *  - The OkHttp network cache revalidates via etag/304, so a cover changed
 *    on the server is automatically refreshed.
 *
 * The `url` itself is the cache key, which is stable per series/book
 * thumbnail.
 */
@Composable
fun KomgaCover(
    client: KomgaApiClient,
    url: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            // No cover available — leave the placeholder surface as-is.
            return@Box
        }

        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(url)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            },
            error = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer),
                )
            },
        )
    }
}
