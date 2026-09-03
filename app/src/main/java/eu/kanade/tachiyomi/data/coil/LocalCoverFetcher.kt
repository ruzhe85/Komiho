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
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max

/**
 * 本地（文件型来源）封面的 Coil 取图器 —— 自带独立文件级缓存（`filesDir/komiho_local_covers/`），
 * **不再与 Komga 共用全局 Coil DiskCache**（komga_covers）。两路缓存完全隔离。
 *
 * 与 Komga 封面的差异：
 *  - 缓存隔离：本地封面落 `filesDir`（自管、跨冷启动稳定），Komga 封面走 HTTP + 全局
 *    Coil DiskCache（cacheDir/komga_covers，受 coverCacheLimitBytes 上限控制，0 = 实时模式关缓存）。
 *    两者互不挤占、互不 LRU 淘汰。
 *  - 数据来源：本地没有"封面 URL"，封面来源有三类——
 *    目录取首张图、归档（CBZ/ZIP）取首张图条目、单图文件本身；epub 无封面。
 *
 * 落盘策略：**先采样再压缩**（450px / JPEG q80，约 40–70KB/张）。降采样只为控制
 * `filesDir` 缓存体积，与 Komga 共享池无关（已分离）。
 */
// SY --> Komiho: 本地封面自带 filesDir 缓存，与 Komga 封面缓存隔离
class LocalCoverFetcher(
    private val context: Context,
    private val data: LocalCoverData,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // 自带一层确定性的文件级磁盘缓存：命中直接返回已落盘的 450px JPEG，
        // 跳过解包/解码，冷启动与跨重启均秒显。缓存落在 filesDir（AOSP 下 filesDir
        // 跨冷启动默认保留，不会像共享的全局 Coil DiskCache 那样被 LRU/低存储回收
        // 导致每次冷启动重新解包全部本地封面转圈几秒）。
        val bytes = cachedCoverBytes()
            ?: run {
                val b = readCoverBytes()
                    ?: throw IllegalStateException("Local cover unavailable: ${data.file.uri}")
                writeCoverCache(b)
                b
            }
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(bytes),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    /** 缓存键：uri + lastModified（文件被替换后 lastModified 变化 → 自动失效）。 */
    private val cacheKey: String get() = "${data.file.uri};${data.lastModified}"

    private fun cacheFile(): File {
        val dir = File(context.filesDir, "komiho_local_covers").apply { mkdirs() }
        return File(dir, sha256(cacheKey) + ".jpg")
    }

    /** 命中（文件存在且非空）则读取；否则返回 null 让上层解包并回填。 */
    private fun cachedCoverBytes(): ByteArray? {
        val f = cacheFile()
        if (f.isFile && f.length() > 0) {
            return runCatching { f.readBytes() }.getOrNull()
        }
        return null
    }

    private fun writeCoverCache(bytes: ByteArray) {
        runCatching {
            val f = cacheFile()
            val tmp = File(f.parentFile, f.nameWithoutExtension + ".tmp")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(f)) tmp.delete() else { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
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
        // 配 q80 单张约 40–70KB，本地缓存（komiho_local_covers）能装上千张而不占 Komga 共享池。
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
 * 缓存键含 lastModified：本地文件换了封面后 key 变化 → 命中新文件、旧 sha256 文件
 * 留在 filesDir 成为孤儿（无自动 LRU 淘汰）。如需回收空间可清 komiho_local_covers 目录，
 * 不影响 Komga 缓存。
 */
class LocalCoverKeyer : Keyer<LocalCoverData> {
    override fun key(data: LocalCoverData, options: Options): String {
        return "${data.file.uri};${data.lastModified}"
    }
}
// SY <--
