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
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.pickCoverFirstImage
import mihon.core.common.archive.archiveReader
import okio.Buffer
import okio.FileSystem
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.Archive
import eu.kanade.tachiyomi.util.pdf.PdfRenderFallback
import eu.kanade.tachiyomi.util.mobi.MobiExtractor
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.source.local.io.Format
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * 本地（文件型来源）封面的 Coil 取图器 —— 自带独立文件级缓存（`filesDir/komiho_local_covers/`），
 * **不再与 Komga 共用全局 Coil DiskCache**（komga_covers）。两路缓存完全隔离。
 *
 * 与 Komga 封面的差异：
 *  - 缓存隔离：本地封面落 `filesDir`（自管、跨冷启动稳定），Komga 封面走 HTTP + 全局
 *    Coil DiskCache（cacheDir/komga_covers，Coil 默认 LRU 淘汰；上限已取消，不再有「0=实时模式」，本设置项「清除预览图」整行点击即清）。
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
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // v3 前缀：封面选取规则改为 cover 优先（此前是自然序第一），旧缓存内容口径不同，作废重生成。
        return File(dir, "v3-" + sha256(cacheKey) + ".jpg")
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
        // SY: 「首页」口径——cover 优先（文件名含 cover 不分大小写），无 cover 命中则
        // 自然序取第一（2<10）。与阅读器自然排序口径保持存储序字典序不会拿到错页。
        // 两分支统一走 pickCoverFirstImage（见 ImageCoverPicker.kt）。
        val bitmap = when {
            file.isDirectory -> {
                // SY: 目录封面 —— 散图优先（cover 优先/自然序取第一张）；
                // 目录内无散图（只有归档）时回落目录内第一个归档的首图（与 SMB 目录封面同口径）。
                val files = file.listFiles().orEmpty().filter { it.isFile }
                val imgs = files.filter { ImageUtil.isImage(it.name) }
                val pickedImg = pickCoverFirstImage(imgs.mapNotNull { it.name })?.let { picked ->
                    imgs.firstOrNull { it.name == picked }
                }
                when {
                    pickedImg != null -> decodeSampled({ pickedImg.openInputStream() }, MAX_PX)
                    else -> files.firstOrNull { Archive.isSupported(it) }?.let { f ->
                        f.archiveReader(context).use { reader ->
                            val name = reader.useEntries { seq ->
                                pickCoverFirstImage(
                                    seq.filter { it.isFile && ImageUtil.isImage(it.name) }
                                        .map { it.name }.toList(),
                                )
                            } ?: return@use null
                            reader.getInputStream(name)?.use { stream ->
                                // 先整段读入内存再解码：archiveReader 的 PFD 会在 .use 结束时关闭，
                                // 若把流带出去二次开档读 mmap 会触发 native 闪退。
                                val raw = stream.readBytes()
                                decodeSampled({ ByteArrayInputStream(raw) }, MAX_PX)
                            }
                        }
                    }
                }
            }
            Archive.isSupported(file) -> file.archiveReader(context).use { reader ->
                val name = reader.useEntries { seq ->
                    pickCoverFirstImage(
                        seq.filter { it.isFile && ImageUtil.isImage(it.name) }.map { it.name }.toList(),
                    )
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
            // SY --> Komiho: PDF 封面 = 系统 PdfRenderer 渲第 0 页（彩色扫描漫画常见，DCT 直通
            // 保留网点；此处只取缩略，落到与归档一致的 450px JPEG 缓存）。
            // Komiho (2026-10-01): SAF 模式不能拿 filePath 判定 —— content:// 的 filePath 可能
            // 解码出真实路径但直开 EACCES（与 archiveReader 同一坑）。按 uri scheme 走：
            // file:// 才用路径，content:// 一律 contentResolver 直开描述符；整段 runCatching，
            // 任何失败都按「无封面」处理而不是让 fetch 抛错。
            file.name?.endsWith("pdf", ignoreCase = true) == true -> runCatching {
                val pfd = if (file.uri?.scheme == "file" && file.filePath != null) {
                    android.os.ParcelFileDescriptor.open(
                        java.io.File(file.filePath!!),
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                } else {
                    context.contentResolver.openFileDescriptor(file.uri, "r") ?: return@runCatching null
                }
                pfd.use { PdfRenderFallback.renderPageBitmap(it, 0, MAX_PX) }
            }.getOrNull()
            // SY <--
            ImageUtil.isImage(file.name) -> decodeSampled({ file.openInputStream() }, MAX_PX)
            // SY --> Komiho: MOBI/AZW3/AZW 封面 = 整本落临时文件 → libmobi 抽「EXTH 封面 / 首图」
            //（与 SMB / WebDAV 的 generateFromMobi 同口径）。SAF 的 content:// 拿不到可直读路径，
            // 而 libmobi 只吃文件路径，所以必须先拷一份临时文件；DRM 书 / 纯文字书抽不出图 → 无封面。
            isMobi(file.name) -> readMobiCoverBitmap(file)
            // SY <--
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

    /** 采样解码统一走 [ImageUtil.decodeSampled]（AVIF/JXL 交给 ImageDecoder，见 issue #10）。 */
    private fun decodeSampled(open: () -> InputStream?, maxPx: Int): Bitmap? =
        ImageUtil.decodeSampled(open, maxPx)

    /** Komiho: 是否为 MOBI / AZW3 / AZW（Kindle 容器，共用 libmobi 抽图）。 */
    private fun isMobi(name: String?): Boolean =
        !name.isNullOrBlank() && name.substringAfterLast('.', "").lowercase() in Format.MOBI_EXTENSIONS

    /**
     * Komiho: MOBI/AZW3/AZW 封面 —— 整本落 cacheDir 临时文件后交 libmobi 抽图，取 `coverFile`
     * （EXTH 封面）优先、否则第一张内嵌图（与 SMB / WebDAV 现有口径一致）。
     *
     * 为什么必须落临时文件：libmobi 只吃**文件路径**，而 SAF 的 `content://` 拿不到可直读路径。
     * 抽完即清（临时 mobi + 抽出的 pages 目录）—— 一本几十 MB 的 AZW3 不该在 cacheDir 长期占地；
     * 结果本身已按 450px JPEG 缓存在 filesDir（`komiho_local_covers`）。
     */
    private fun readMobiCoverBitmap(file: UniFile): Bitmap? {
        val dir = File(context.cacheDir, DIR_MOBI).apply { mkdirs() }
        val key = sha256("${file.uri};${data.lastModified}")
        val tmp = File(dir, "$key.mobi")
        val pagesDir = File(dir, "pages_$key")
        return try {
            if (!tmp.exists() || tmp.length() == 0L) {
                val copied = file.openInputStream()?.use { input ->
                    FileOutputStream(tmp).use { input.copyTo(it) }
                    true
                } ?: false
                if (!copied) return null
            }
            val book = runCatching { MobiExtractor.extractImages(tmp, pagesDir) }.getOrNull() ?: return null
            val img = book.coverFile ?: book.imageFiles.firstOrNull() ?: return null
            decodeSampled({ ByteArrayInputStream(img.readBytes()) }, MAX_PX)
        } catch (e: Throwable) {
            logcat(LogPriority.INFO) { "[LocalCover] MOBI 封面生成失败：${file.name} — ${e.message}" }
            null
        } finally {
            runCatching { tmp.delete() }
            runCatching { pagesDir.deleteRecursively() }
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<LocalCoverData> {
        override fun create(data: LocalCoverData, options: Options, imageLoader: ImageLoader): Fetcher {
            return LocalCoverFetcher(context, data)
        }
    }

    companion object {
        /** 封面缓存目录名（filesDir 下）。设为 public 供设置页统计/清除，避免目录名漂移。 */
        const val DIR = "komiho_local_covers"

        // 主流客户端的封面缩略图档位：封面格子实际显示尺寸远小于此，450px 足够；
        // 配 q80 单张约 40–70KB，本地缓存（komiho_local_covers）能装上千张而不占 Komga 共享池。
        private const val MAX_PX = 450
        private const val JPEG_QUALITY = 80

        /** MOBI/AZW3/AZW 抽图用的临时目录（cacheDir 下，抽完即清）。 */
        private const val DIR_MOBI = "komiho_local_mobi_cover"
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
