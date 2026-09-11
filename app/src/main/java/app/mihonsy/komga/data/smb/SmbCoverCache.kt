package app.mihonsy.komga.data.smb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.mihonsy.komga.data.remote.CachingArchiveHandle
import app.mihonsy.komga.data.remote.RemotePageCache
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
// SY: 散图目录封面需在后台上列目录（SmbBrowse.list 为 suspend）。
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import mihon.core.common.archive.ArchiveHandle
import mihon.core.common.archive.ArchiveReader
import mihon.core.common.archive.RemoteZipReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.max

// SY --> Komiho Phase7: SMB 历史/书签封面 —— 镜像 WebDavCoverCache，打开章节时「顺便」生成。
// 与 WebDAV 版差异：
// - 无「延迟 3s」：SMB 原生按偏移读，不存在 rar/7z 整本缓存下载的竞态窗口。
// - 无回退缓存目录：SmbRandomAccessSource 不需要整本兜底文件。
// - 密码来自 SmbConnectionStore.resolve（Keystore 解密），会话由 SmbSessionManager 池化。
// 缓存键 = 章节 URL 的 sha256（connId + 路径天然唯一）；生成失败只影响本次，下次打开重试。
object SmbCoverCache {

    /** 封面缓存目录名（filesDir 下）。public 供设置页统计/清除，避免目录名漂移。 */
    const val DIR = "komiho_smb_covers"
    private const val MAX_PX = 450
    private const val JPEG_QUALITY = 80

    /** 正在生成的章节 URL（同窗口去重：连开同一章/快速翻卷只触发一次）。 */
    private val inFlight = mutableSetOf<String>()

    /** 是否 SMB 章节（`smb://<connId>/<relPath>`）。 */
    fun isSmbChapter(chapterUrl: String): Boolean = chapterUrl.startsWith(SmbConnectionStore.CONN_URL_PREFIX)

    /** 封面缓存文件（不管存在与否）。 */
    private fun coverFile(context: Context, chapterUrl: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, sha256(chapterUrl) + ".jpg")
    }

    /** 已生成的封面文件（历史/书签行用；null = 显示占位图标，不发请求）。 */
    fun existingCoverFile(context: Context, chapterUrl: String): File? {
        if (!isSmbChapter(chapterUrl)) return null
        return coverFile(context, chapterUrl).takeIf { it.isFile && it.length() > 0 }
    }

    /** 异步生成封面：缓存已有则跳过；同窗口去重；后台线程执行、失败静默（logcat）。 */
    fun generateAsync(context: Context, chapterUrl: String) {
        if (!isSmbChapter(chapterUrl)) return
        val target = coverFile(context, chapterUrl)
        if (target.isFile && target.length() > 0) return
        synchronized(inFlight) {
            if (!inFlight.add(chapterUrl)) return
        }
        // SY: 散图目录章节（URL 尾斜杠）没有归档句柄，走「拉目录首图」分支；
        // 归档章节仍走原来的「拆包取首图」。
        val isDirectory = chapterUrl.endsWith('/')
        val app = context.applicationContext
        thread(name = "smb-cover", isDaemon = true) {
            // 先移出去：失败后下次打开还能重试。
            synchronized(inFlight) { inFlight.remove(chapterUrl) }
            if (isDirectory) {
                runCatching { generateFromDirectory(chapterUrl, target) }
                    .onFailure { logcat(LogPriority.INFO) { "[SmbCover] 目录封面生成失败：${it.message}" } }
                return@thread
            }
            runCatching { generate(app, chapterUrl, target) }
                .onFailure { logcat(LogPriority.INFO) { "[SmbCover] 封面生成失败：${it.message}" } }
        }
    }

    private fun generate(context: Context, chapterUrl: String, target: File) {
        val resolved = SmbConnectionStore.resolve(chapterUrl)
            ?: throw IllegalStateException("SMB 连接不存在: $chapterUrl")
        // 独立 source（不复用阅读器的 ArchivePageLoader 句柄，避免生命周期竞争）。
        val source = SmbRandomAccessSource(resolved.conn, resolved.password, resolved.relPath)
        val delegate: ArchiveHandle = try {
            RemoteZipReader(source)
        } catch (e: Exception) {
            // 与 ChapterLoader 同款回落：中央目录直读不支持 → libarchive 回调路径。
            runCatching { ArchiveReader(source) }.getOrElse { err ->
                runCatching { source.close() }
                throw err
            }
        }
        // 封面生成同样吃页级缓存（首图已缓存则零网络；与阅读器共用 remote_pages）。
        val handle: ArchiveHandle = CachingArchiveHandle(
            delegate = delegate,
            cache = RemotePageCache(
                root = File(context.cacheDir, "remote_pages"),
                maxBytes = Injekt.get<StoragePreferences>().webdavCacheMaxBytes.get(),
            ),
            metaKey = {
                source.remoteFingerprint?.let { fp -> "${source.normalizedUrl}|$fp" }
            },
        )
        handle.use { h ->
            // 加密包：无密码（null）或密码错误（true）时首图读不出来，直接放弃
            //（阅读器会弹密码框，输对后下次打开自然能生成）。
            if (h.encrypted && h.wrongPassword != false) return
            // 「首页」必须与阅读器同一口径：自然排序（2.jpg < 10.jpg）取第一个图片条目。
            val firstName = runCatching {
                h.useEntries { seq ->
                    seq.filter { it.isFile && ImageUtil.isImage(it.name) }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                        .firstOrNull()?.name
                }
            }.getOrNull() ?: return
            val raw = runCatching {
                h.getInputStream(firstName)?.use { it.readBytes() }
            }.getOrNull() ?: return
            val bmp = decodeSampled({ ByteArrayInputStream(raw) }, MAX_PX) ?: return
            writeCover(bmp, target)
        }
    }

    /**
     * SY: 散图目录章节的封面 —— 列目录 → 自然序取第一张图（与阅读器首页同口径）→
     * 读该图字节 → 采样压缩落盘。目录章节没有归档句柄，故单独一条路径。
     */
    private fun generateFromDirectory(chapterUrl: String, target: File) {
        val resolved = SmbConnectionStore.resolve(chapterUrl)
            ?: throw IllegalStateException("SMB 连接不存在（可能已删除）: $chapterUrl")
        val dirRel = SmbConnectionStore.extractRelPath(chapterUrl).trim('/')
        val firstPath = runBlocking {
            SmbBrowse.list(resolved.conn, resolved.password, dirRel)
                .filter { it.isImage }
                .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
                .firstOrNull()?.path
        } ?: return
        // smbj 的 File 是句柄不是 InputStream：getInputStream() 取实时流再读全量。
        val raw = SmbSessionManager.openFile(resolved.conn, resolved.password, firstPath).use { f ->
            f.getInputStream().buffered().use { it.readBytes() }
        }
        check(raw.isNotEmpty()) { "封面图片为空：$firstPath" }
        val bmp = decodeSampled({ ByteArrayInputStream(raw) }, MAX_PX) ?: return
        writeCover(bmp, target)
    }

    /** 采样压缩落盘（临时文件 + rename，rename 失败回落 copy）。 */
    private fun writeCover(bmp: Bitmap, target: File) {
        runCatching {
            val tmp = File(target.parentFile, target.nameWithoutExtension + ".tmp")
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                tmp.writeBytes(out.toByteArray())
            }
            if (tmp.renameTo(target)) {
                tmp.delete()
            } else {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        bmp.recycle()
    }

    /** 两次 decode：先读边界算 inSampleSize，再采样解码（与 LocalCoverFetcher 同策略）。 */
    private fun decodeSampled(open: () -> ByteArrayInputStream, maxPx: Int): Bitmap? {
        runCatching {
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
        return null
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
// SY <--
