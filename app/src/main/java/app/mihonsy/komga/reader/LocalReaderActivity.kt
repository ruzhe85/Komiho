package app.mihonsy.komga.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.viewinterop.AndroidView
import com.github.chrisbanes.photoview.PhotoView
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.loader.ArchivePageLoader
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.EpubPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.common.archive.archiveReader
import tachiyomi.source.local.io.Format
import kotlin.math.max

/**
 * Komiho：本地漫画浏览器专用阅读器。
 *
 * 与 Mihon 的图源/书架模型**完全无关**：不注册 Source、不写 library DB、不创建
 * manga/chapter 行。直接拿用户选中的文件（归档/epub/散图目录）交给 Mihon 既有的
 * [PageLoader] 解出页面，再用自带捏合缩放/拖动的 [PhotoView] 渲染。
 *
 * 入口：intent 带 [EXTRA_URI]（UniFile 的 content URI）；调用方用 [launch] 最方便。
 */
class LocalReaderActivity : ComponentActivity() {

    // 保持 loader 存活直到 Activity 销毁（归档 reader 不能在解码前关闭），
    // 销毁时统一回收，避免 fd / mmap 资源泄漏。
    private var loader: PageLoader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrEmpty()) {
            finish()
            return
        }
        val file = UniFile.fromUri(this, Uri.parse(uriString))
        if (file == null || !file.exists()) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                ReaderContent(file = file)
            }
        }
    }

    override fun onDestroy() {
        loader?.recycle()
        loader = null
        super.onDestroy()
    }

    @Composable
    private fun ReaderContent(file: UniFile) {
        val context = LocalContext.current
        var pages by remember { mutableStateOf<List<ReaderPage>?>(null) }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(file) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val format = Format.valueOf(file)
                    val created = when (format) {
                        is Format.Directory -> DirectoryPageLoader(file)
                        is Format.Archive -> ArchivePageLoader(file.archiveReader(context))
                        is Format.Epub -> EpubPageLoader(file.archiveReader(context))
                    }
                    this@LocalReaderActivity.loader = created
                    created.getPages()
                }.onSuccess { p -> pages = p }
                    .onFailure { e -> errorMsg = e.message ?: e.javaClass.simpleName }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                errorMsg != null -> MessageText(errorMsg!!)
                pages == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                pages!!.isEmpty() -> MessageText("没有可读的图片")
                else -> PagerView(pages = pages!!)
            }
        }
    }

    @Composable
    private fun PagerView(pages: List<ReaderPage>) {
        val pagerState = rememberPagerState { pages.size }
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                val page = pages[index]
                var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(page) {
                    val bmp = withContext(Dispatchers.IO) {
                        runCatching { decodePage(page) }.getOrNull()
                    }
                    bitmap = bmp
                }
                AndroidView(
                    factory = { PhotoView(it) },
                    update = { pv -> pv.setImageBitmap(bitmap) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = "${pagerState.currentPage + 1} / ${pages.size}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }

    @Composable
    private fun MessageText(msg: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(msg, color = Color.White)
        }
    }

    /** 读 page.stream（原始图像字节），按长边下采样避免大图 OOM。 */
    private fun decodePage(page: ReaderPage): Bitmap? {
        val data = page.stream?.invoke()?.use { it.readBytes() } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        val longSide = max(opts.outWidth, opts.outHeight).coerceAtLeast(1)
        opts.inSampleSize = (longSide / 2000f).toInt().coerceAtLeast(1)
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
    }

    companion object {
        const val EXTRA_URI = "local_reader_uri"

        fun launch(context: Context, file: UniFile) {
            val intent = Intent(context, LocalReaderActivity::class.java).apply {
                putExtra(EXTRA_URI, file.uri.toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
