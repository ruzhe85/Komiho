package eu.kanade.tachiyomi.data.coil

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import app.mihonsy.komga.data.smb.SmbBrowse
import app.mihonsy.komga.data.smb.SmbConnection
import app.mihonsy.komga.data.smb.SmbRandomAccessSource
import app.mihonsy.komga.data.smb.SmbSessionManager
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import eu.kanade.tachiyomi.util.mobi.MobiExtractor
import eu.kanade.tachiyomi.util.pickCoverFirstImage
import eu.kanade.tachiyomi.util.pdf.PdfRenderFallback
import logcat.LogPriority
import mihon.core.common.archive.ArchiveHandle
import mihon.core.common.archive.ArchiveReader
import mihon.core.common.archive.RemoteZipReader
import okio.Buffer
import okio.FileSystem
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

// SY --> Komiho Phase7: SMB 浏览列表封面 —— 对齐本地模式（LocalCoverFetcher）：
// 归档拆包取首图、单图文件直接显示、目录列目录取首图（cover 优先，2026-09 对齐本地），
// 自带 filesDir 文件级缓存（先采样再压缩 450px/JPEG q80），
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
                    ?: run {
                        // 解码失败已由 decodeCoverBitmap 记录；这里记录「连首图都没取到」。
                        eu.kanade.tachiyomi.diagnostic.DiagLog.w(
                            "CoverDecode",
                            "无可用首图 smb path=${data.relPath}",
                        )
                        throw IllegalStateException("SMB cover unavailable: ${data.relPath}")
                    }
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

    /** 缓存键：v2 前缀（封面规则改 cover 优先作废旧缓存）+ connId + relPath + lastModified（远端文件替换后自动失效）。 */
    private val cacheKey: String get() = "v2;${data.conn.id};${data.relPath};${data.lastModified}"

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

    private suspend fun readCoverBytes(): ByteArray? {
        val password = WebDavCredentialCrypto.decryptStored(data.conn.passEnc)
        val bitmap = when {
            // SY: 散图目录当封面（对齐本地 LocalCoverFetcher 目录分支）：列目录 →
            // cover 优先/自然序取第一张图 → 读该图字节。目录封面多一次列目录请求，
            // 生成后走 filesDir 缓存，不再重复发起。
            data.isDir -> readDirectoryFirstImageBytes(password)?.let { raw ->
                decodeSampled({ ByteArrayInputStream(raw) }, MAX_PX)
            }
            // SY: PDF 封面 = 整本落本地临时文件，系统 PdfRenderer 渲第 0 页（与 LocalCoverFetcher 同口径）。
            data.isPdf -> readPdfFirstPageBitmap(password)
            // Komiho: MOBI/AZW3/AZW 封面 = 整本落临时文件 → libmobi 抽封面/首图（与 SmbCoverCache /
            // WebDavCoverCache 的 generateFromMobi 同口径）。不能落到下面的归档分支 —— MOBI 不是 zip，
            // 用 RemoteZipReader/libarchive 打开必然失败，这正是此前「MOBI 在 SMB 浏览器没封面」的原因。
            data.isMobi -> readMobiCoverBitmap(password)
            data.isImage ->
                // 单图：直接读文件字节。
                SmbSessionManager.openFile(data.conn, password, data.relPath).use { f ->
                    f.getInputStream().buffered().use { it.readBytes() }
                }.let { raw -> decodeSampled({ ByteArrayInputStream(raw) }, MAX_PX) }
            else ->
                // 归档：首图与阅读器同一口径（cover 优先，无 cover 则自然序 2<10）。
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
    private fun readArchiveFirstImage(password: String, relPath: String = data.relPath): ByteArray? {
        val source = SmbRandomAccessSource(data.conn, password, relPath)
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
                    pickCoverFirstImage(
                        seq.filter { it.isFile && ImageUtil.isImage(it.name) }.map { it.name }.toList(),
                    )
                }
            }.getOrNull() ?: return null
            h.getInputStream(firstName)?.use { it.readBytes() }
        }
    }

    /** 目录封面字节：列目录 → cover 优先/自然序取第一张散图 → 读该图字节；
     *  目录内无散图（只有归档/子目录）时回落目录内第一个归档的首图。 */
    private suspend fun readDirectoryFirstImageBytes(password: String): ByteArray? {
        val entries = runCatching { SmbBrowse.list(data.conn, password, data.relPath) }
            .getOrNull() ?: return null
        val imgs = entries.filter { !it.isDir && it.isImage }
        val picked = pickCoverFirstImage(imgs.map { it.name })
        if (picked != null) {
            val path = imgs.firstOrNull { it.name == picked }?.path ?: return null
            // smbj 的 File 是句柄不是 InputStream：getInputStream() 取实时流再读全量。
            return SmbSessionManager.openFile(data.conn, password, path).use { f ->
                f.getInputStream().buffered().use { it.readBytes() }
            }.takeIf { it.isNotEmpty() }
        }
        // 回落：目录内第一个归档（cover 优先/自然序）→ 拆包取其首图。
        val archives = entries.filter { !it.isDir && it.isArchive }
        val archName = pickCoverFirstImage(archives.map { it.name }) ?: return null
        val archPath = archives.firstOrNull { it.name == archName }?.path ?: return null
        return readArchiveFirstImage(password, archPath)
    }

    /** PDF 封面：SMB 整本落到本地临时文件，系统 PdfRenderer 渲第 0 页（与 LocalCoverFetcher 同口径）。 */
    private fun readPdfFirstPageBitmap(password: String, relPath: String = data.relPath): Bitmap? {
        val dir = File(context.cacheDir, "komiho_smb_pdf_cover").apply { mkdirs() }
        val tmp = File(dir, sha256("${data.conn.id};$relPath") + ".pdf")
        if (!tmp.exists() || tmp.length() == 0L) {
            runCatching {
                SmbSessionManager.openFile(data.conn, password, relPath).use { f ->
                    f.getInputStream().buffered().use { input ->
                        FileOutputStream(tmp).use { input.copyTo(it) }
                    }
                }
            }.getOrNull() ?: return null
        }
        return PdfRenderFallback.renderPageBitmap(tmp.absolutePath, 0, MAX_PX)
    }

    /**
     * Komiho: MOBI/AZW3/AZW 封面 —— 整本落本地临时文件后交 libmobi 抽图，取 `coverFile`（EXTH
     * 封面）优先、否则第一张内嵌图（与 [app.mihonsy.komga.data.smb.SmbCoverCache] 的
     * `generateFromMobi` 同引擎同口径）。DRM（[MobiExtractor] 抛异常）或纯文字书抽不出图 → 无封面。
     *
     * 临时目录刻意与章节封面那条路（`komiho_smb_mobi_cover`）**分开**：两者的临时文件名算法相同
     * （`sha256(connId;relPath)`），共用目录会互相删掉对方正在抽的文件。抽完即清。
     */
    private fun readMobiCoverBitmap(password: String, relPath: String = data.relPath): Bitmap? {
        val dir = File(context.cacheDir, DIR_MOBI).apply { mkdirs() }
        val key = sha256("${data.conn.id};$relPath")
        val tmp = File(dir, "$key.mobi")
        val pagesDir = File(dir, "pages_$key")
        return try {
            if (!tmp.exists() || tmp.length() == 0L) {
                runCatching {
                    SmbSessionManager.openFile(data.conn, password, relPath).use { f ->
                        f.getInputStream().buffered().use { input ->
                            FileOutputStream(tmp).use { input.copyTo(it) }
                        }
                    }
                }.getOrNull() ?: return null
            }
            val book = runCatching { MobiExtractor.extractImages(tmp, pagesDir) }.getOrNull() ?: return null
            val img = book.coverFile ?: book.imageFiles.firstOrNull() ?: return null
            decodeSampled({ ByteArrayInputStream(img.readBytes()) }, MAX_PX)
        } catch (e: Throwable) {
            logcat(LogPriority.INFO) { "[SmbCover] MOBI 封面生成失败：$relPath — ${e.message}" }
            null
        } finally {
            runCatching { tmp.delete() }
            runCatching { pagesDir.deleteRecursively() }
        }
    }

    /** 采样解码统一走 [decodeCoverBitmap]（AVIF/JXL 交给 ImageDecoder；失败写一条诊断日志）。 */
    private fun decodeSampled(open: () -> InputStream, maxPx: Int): Bitmap? =
        decodeCoverBitmap("smb path=${data.relPath}", maxPx, open)

    class Factory(private val context: Context) : Fetcher.Factory<SmbCoverData> {
        override fun create(data: SmbCoverData, options: Options, imageLoader: ImageLoader): Fetcher {
            return SmbCoverFetcher(context, data)
        }
    }

    companion object {
        private const val MAX_PX = 450
        private const val JPEG_QUALITY = 80

        /** MOBI/AZW3/AZW 抽图用的临时目录（cacheDir 下，抽完即清；与章节封面那条路分开）。 */
        private const val DIR_MOBI = "komiho_smb_mobi_cover_browse"
    }
}

/** SMB 浏览封面请求体：连接 + 共享内相对路径 + 修改时间（缓存失效）+ 是否单图/目录。 */
data class SmbCoverData(
    val conn: SmbConnection,
    val relPath: String,
    val lastModified: Long,
    val isImage: Boolean,
    /** SY: 目录条目（散图目录封面 = 目录内第一张图，cover 优先）。 */
    val isDir: Boolean = false,
    /** SY: PDF 条目封面 = 系统 PdfRenderer 渲第 0 页（与 LocalCoverFetcher 同口径）。 */
    val isPdf: Boolean = false,
    /** Komiho: MOBI/AZW3/AZW 条目封面 = 整本落临时文件 + libmobi 抽「EXTH 封面 / 首图」。 */
    val isMobi: Boolean = false,
)

class SmbCoverKeyer : Keyer<SmbCoverData> {
    override fun key(data: SmbCoverData, options: Options): String {
        return "v2;${data.conn.id};${data.relPath};${data.lastModified}"
    }
}
// SY <--
