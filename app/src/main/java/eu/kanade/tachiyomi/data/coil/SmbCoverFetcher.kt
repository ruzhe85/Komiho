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
import app.mihonsy.komga.data.smb.SmbConnection
import app.mihonsy.komga.data.smb.SmbRandomAccessSource
import app.mihonsy.komga.data.smb.SmbSessionManager
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.common.archive.ArchiveHandle
import mihon.core.common.archive.ArchiveReader
import mihon.core.common.archive.RemoteZipReader
import okio.Buffer
import okio.FileSystem
import tachiyomi.core.common.util.system.ImageUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max

// SY --> Komiho Phase7: SMB 浏览列表封面 —— 对齐本地模式（LocalCoverFetcher）：
// 归档拆包取首图、单图文件直接显示，自带 filesDir 文件级缓存（先采样再压缩 450px/JPEG q80），
// 与本地（komiho_local_covers）/阅读器打开时生成的（komiho_smb_covers）互不混用。
// 缓存键含 lastModified：远端文件被替换后自动失效。
class SmbCoverFetcher(
    private val context: Context,
    private val data: SmbCoverData,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bytes = cachedCoverBytes()
            ?: run {
                val b = readCoverBytes()
                    ?: throw IllegalStateException("SMB cover unavailable: ${data.relPath}")
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

    /** 缓存键：connId + relPath + lastModified（远端文件替换后自动失效）。 */
    private val cacheKey: String get() = "${data.conn.id};${data.relPath};${data.lastModified}"

    private fun cacheFile(): File {
        val dir = File(context.filesDir, "komiho_smb_browse_covers").apply { mkdirs() }
        return File(dir, sha256(cacheKey) + ".jpg")
    }

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
        val password = WebDavCredentialCrypto.decryptStored(data.conn.passEnc)
        val bitmap = if (data.isImage) {
            // 单图：直接读文件字节。
            SmbSessionManager.openFile(data.conn, password, data.relPath).use { f ->
                f.getInputStream().buffered().use { it.readBytes() }
            }.let { raw -> decodeSampled({ ByteArrayInputStream(raw) }, MAX_PX) }
        } else {
            // 归档：首图与阅读器同一口径（自然排序 2<10）。
            readArchiveFirstImage(password)?.let { raw ->
                decodeSampled({ ByteArrayInputStream(raw) }, MAX_PX)
            }
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

    /** 归档首图字节：RemoteZipReader 中央目录直读（每页流量=条目本身），失败回落 libarchive。 */
    private fun readArchiveFirstImage(password: String): ByteArray? {
        val source = SmbRandomAccessSource(data.conn, password, data.relPath)
        val delegate: ArchiveHandle = try {
            RemoteZipReader(source)
        } catch (e: Exception) {
            runCatching { ArchiveReader(source) }.getOrElse { err ->
                runCatching { source.close() }
                throw err
            }
        }
        return delegate.use { h ->
            if (h.encrypted && h.wrongPassword != false) return null
            val firstName = runCatching {
                h.useEntries { seq ->
                    seq.filter { it.isFile && ImageUtil.isImage(it.name) }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                        .firstOrNull()?.name
                }
            }.getOrNull() ?: return null
            h.getInputStream(firstName)?.use { it.readBytes() }
        }
    }

    /** 两次 decode：先读边界算 inSampleSize，再采样解码。 */
    private fun decodeSampled(open: () -> InputStream, maxPx: Int): Bitmap? {
        open().use { first ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(first, null, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            val sample = max(1, max(opts.outWidth, opts.outHeight) / maxPx)
            open().use { second ->
                val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
                return BitmapFactory.decodeStream(second, null, opts2)
            }
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<SmbCoverData> {
        override fun create(data: SmbCoverData, options: Options, imageLoader: ImageLoader): Fetcher {
            return SmbCoverFetcher(context, data)
        }
    }

    companion object {
        private const val MAX_PX = 450
        private const val JPEG_QUALITY = 80
    }
}

/** SMB 浏览封面请求体：连接 + 共享内相对路径 + 修改时间（缓存失效）+ 是否单图。 */
data class SmbCoverData(
    val conn: SmbConnection,
    val relPath: String,
    val lastModified: Long,
    val isImage: Boolean,
)

class SmbCoverKeyer : Keyer<SmbCoverData> {
    override fun key(data: SmbCoverData, options: Options): String {
        return "${data.conn.id};${data.relPath};${data.lastModified}"
    }
}
// SY <--
