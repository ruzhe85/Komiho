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
import app.mihonsy.komga.data.KomgaPreferences
import coil3.compose.SubcomposeAsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/**
 * Komiho cover: loads the Komga thumbnail through Coil's global
 * [coil3.ImageLoader] (auth carried by [KomgaApiClient]'s OkHttp client).
 *
 * Two layers of caching apply:
 *  - Coil's disk cache (`komga_covers`, size from the "cache limit" pref,
 *    default 100 MiB) holds the decoded bitmap, so covers survive screen
 *    changes and app restarts without re-download.
 *  - The OkHttp network cache revalidates via etag/304, so a cover changed
 *    on the server is automatically refreshed.
 *
 * When the user sets the cache limit to 0 (live/preview mode), we disable
 * both memory and disk caching on the request so the cover is re-fetched on
 * every refresh — no separate toggle needed.
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

        val prefs = KomgaPreferences(context.applicationContext)

        // SY: 必须走 MangaCoverFetcher（按 sourceId 解析 KomgaSource，自动带上鉴权头
        // X-API-Key / Basic），不能直接传裸 URL——默认 network fetcher 不带鉴权，
        // 表现为「首次添加源后封面全空，随便点开一本才正常」（之前的实际 bug）。
        // mangaId 传 0 即可：无自定义封面，直接走网络请求 + 磁盘缓存。
        val coverModel = tachiyomi.domain.manga.model.MangaCover(
            mangaId = 0L,
            sourceId = app.mihonsy.komga.source.KomgaSource.ID,
            isMangaFavorite = false,
            ogUrl = url,
            lastModified = 0L,
        )
        val imageRequest = ImageRequest.Builder(context).data(coverModel)
        // Cache limit 0 = live mode: disable memory + disk caching so the
        // cover is always re-fetched from the server.
        if (prefs.coverCacheLimitBytes <= 0L) {
            imageRequest.memoryCachePolicy(CachePolicy.DISABLED)
            imageRequest.diskCachePolicy(CachePolicy.DISABLED)
        }

        SubcomposeAsyncImage(
            model = imageRequest.build(),
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
