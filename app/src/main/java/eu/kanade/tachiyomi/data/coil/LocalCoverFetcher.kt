package eu.kanade.tachiyomi.data.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.hippo.unifile.UniFile
import mihon.core.common.archive.archiveReader
import okio.Buffer
import okio.FileSystem
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.Archive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max

/**
 * 本地（文件型来源）封面的 Coil 取图器 —— 让本地浏览 tab 复用全局 Coil DiskCache，
 * 与 Komga 封面**共用同一个缓存池、同一个上限**（见 `App.kt` 的 `komga_covers`）。
 *
 * 与 Komga 封面的差异：本地没有"封面 URL"，封面来源有三类——
 * 目录取首张图、归档（CBZ/ZIP）取首张图条目、单图文件本身；epub 无封面。
 *
 * 落盘策略：**先采样再压缩**（450px / JPEG q80，约 40–70KB/张）。
 * 本地归档里的首图原图常在 1–3MB，若按原图落盘，几百张就会吃满共享上限并
 * LRU 掉 Komga 封面，因此这里主动降采样后再交给 Coil 缓存。
 */
// SY --> Komiho: 本地封面缓存（与 Komga 共用全局 Coil DiskCache）
class LocalCoverFetcher(
    private val context: Context,
    private val data: LocalCoverData,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bytes = readCoverBytes()
            ?: throw IllegalStateException("Local cover unavailable: ${data.file.uri}")
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(bytes),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    private fun readCoverBytes(): ByteArray? {
        val file = data.file
        val bitmap = when {
            file.isDirectory -> file.listFiles().orEmpty()
                .firstOrNull { it.isFile && ImageUtil.isImage(it.name) }
                ?.let { decodeSampled({ it.openInputStream() }, MAX_PX) }
            Archive.isSupported(file) -> file.archiveReader(context).use { reader ->
                val name = reader.useEntries { seq ->
                    seq.firstOrNull { ImageUtil.isImage(it.name) }?.name
                }
                if (name == null) {
                    null
                } else {
                    reader.getInputStream(name)?.use { stream ->
                        // 先整段读入内存再解码：archiveReader 的 PFD 会在 .use 结束时关闭，
                        // 若把流带出去二次开档读 mmap 会触发 native 闪退。
                        val raw = stream.readBytes()
                        decodeSampled({ ByteArrayInputStream(raw) }, MAX_PX)
                    }
                }
            }
            file.name?.endsWith("epub", ignoreCase = true) == true -> null
            ImageUtil.isImage(file.name) -> decodeSampled({ file.openInputStream() }, MAX_PX)
            else -> null
        }
        return bitmap?.let { bmp ->
            runCatching {
                ByteArrayOutputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.toByteArray()
                }
            }.getOrNull().also { bmp.recycle() }
        }
    }

    /** 两次 decode：先读边界算 inSampleSize，再采样解码；open 提供可重开的输入流。 */
    private fun decodeSampled(open: () -> InputStream?, maxPx: Int): Bitmap? {
        open()?.use { first ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(first, null, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            val sample = max(1, max(opts.outWidth, opts.outHeight) / maxPx)
            open()?.use { second ->
                val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
                return BitmapFactory.decodeStream(second, null, opts2)
            }
        }
        return null
    }

    class Factory(private val context: Context) : Fetcher.Factory<LocalCoverData> {
        override fun create(data: LocalCoverData, options: Options, imageLoader: ImageLoader): Fetcher {
            return LocalCoverFetcher(context, data)
        }
    }

    companion object {
        // 主流客户端的封面缩略图档位：封面格子实际显示尺寸远小于此，450px 足够；
        // 配 q80 单张约 40–70KB，共享缓存池能装上千张而不挤掉 Komga 封面。
        private const val MAX_PX = 450
        private const val JPEG_QUALITY = 80
    }
}

/** 本地封面请求体：文件句柄 + 修改时间（后者用于缓存失效）。 */
data class LocalCoverData(
    val file: UniFile,
    val lastModified: Long,
)

/**
 * 缓存键含 lastModified：本地文件换了封面后 key 变化 → 自动重新解码，
 * 旧条目交给 DiskCache 的 LRU 自然淘汰，无需手动清缓存。
 */
class LocalCoverKeyer : Keyer<LocalCoverData> {
    override fun key(data: LocalCoverData, options: Options): String {
        return "${data.file.uri};${data.lastModified}"
    }
}
// SY <--
