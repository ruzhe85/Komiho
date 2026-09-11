package app.mihonsy.komga.data.webdav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
// SY --> Komiho Phase7: 页缓存上提为共用实现（data.remote 包），需显式导入。
import app.mihonsy.komga.data.remote.CachingArchiveHandle
import app.mihonsy.komga.data.remote.RemotePageCache
// SY <--
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
// SY: 散图目录封面需在后台上拉目录（PROPFIND 为 suspend）。
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import mihon.core.common.archive.ArchiveHandle
import mihon.core.common.archive.ArchiveReader
import mihon.core.common.archive.WebDavRandomAccessSource
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

// SY --> Komiho Phase4: WebDAV 历史/书签封面 —— 打开章节时「顺便」生成首图封面。
// 思路（用户定）：ChapterLoader 打开 WebDAV 章节时若封面缓存缺失，则用独立连接按
// Range 拉首图（尾部 64KB + 中央目录 + 首图条目，通常 < 1MB 流量），采样压缩后落
// filesDir/komiho_webdav_covers/；之后历史/书签行直接读磁盘显示，**不向服务器发任何
// 请求**（与 Browse tab 无封面同口径防风控）。生成失败（不支持压缩/加密无密码/网络）
// 只影响本次，下次打开自动重试。缓存键 = 完整文件 URL 的 sha256，无自动 LRU（每张
// 40-70KB，可存上千张；如需回收可清 komiho_webdav_covers 目录，不影响其他缓存）。
object WebDavCoverCache {

    /** 封面缓存目录名（filesDir 下）。public 供设置页统计/清除，避免目录名漂移。 */
    const val DIR = "komiho_webdav_covers"
    private const val MAX_PX = 450
    private const val JPEG_QUALITY = 80

    /** 正在生成的章节 URL（同窗口去重：连开同一章/快速翻卷只触发一次）。 */
    private val inFlight = mutableSetOf<String>()

    /** 是否 WebDAV 章节（双格式 webdav://connId/URL 与 webdav:URL）。 */
    fun isWebDavChapter(chapterUrl: String): Boolean = chapterUrl.startsWith("webdav:")

    /** 封面缓存文件（不管存在与否）；URL 解析失败返回 null。 */
    private fun coverFile(context: Context, chapterUrl: String): File? {
        val fullUrl = runCatching { WebDavConnectionStore.extractFullUrl(chapterUrl) }.getOrNull()
        if (fullUrl.isNullOrBlank()) return null
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // v2 前缀：封面口径修正（存储序→自然序）后旧缓存内容可能是错误页面，直接作废重生成。
        return File(dir, "v2-" + sha256(fullUrl) + ".jpg")
    }

    /** 已生成的封面文件（历史/书签行用；null = 显示占位图标，不发请求）。 */
    fun existingCoverFile(context: Context, chapterUrl: String): File? {
        if (!isWebDavChapter(chapterUrl)) return null
        return coverFile(context, chapterUrl)?.takeIf { it.isFile && it.length() > 0 }
    }

    /** 异步生成封面：缓存已有则跳过；同窗口去重；后台线程执行、失败静默（logcat）。 */
    fun generateAsync(context: Context, chapterUrl: String) {
        if (!isWebDavChapter(chapterUrl)) return
        val target = coverFile(context, chapterUrl) ?: return
        if (target.isFile && target.length() > 0) return
        synchronized(inFlight) {
            if (!inFlight.add(chapterUrl)) return
        }
        // SY: 散图目录章节（URL 尾斜杠）没有归档句柄，走「拉目录首图」分支；
        // 归档章节仍走原来的「拆包取首图」。
        val isDirectory = WebDavConnectionStore.extractFullUrl(chapterUrl).endsWith('/')
        val app = context.applicationContext
        thread(name = "webdav-cover", isDaemon = true) {
            // 先移出去：失败后下次打开还能重试。
            synchronized(inFlight) { inFlight.remove(chapterUrl) }
            if (isDirectory) {
                // 散图每页独立 GET，不存在整本缓存竞态，无需延迟。
                runCatching { generateFromDirectory(chapterUrl, target) }
                    .onFailure { logcat(LogPriority.INFO) { "[WebDavCover] 目录封面生成失败：${it.message}" } }
                return@thread
            }
            // 延迟 3s 再拉：避开与阅读线程同时整本缓存下载的竞态（rar/7z 无 Range 场景
            // ensureFallbackFile 无跨实例互斥）；Range 服务器无此问题，延迟无感。
            Thread.sleep(3000)
            runCatching { generate(app, chapterUrl, target) }
                .onFailure { logcat(LogPriority.INFO) { "[WebDavCover] 封面生成失败：${it.message}" } }
        }
    }

    private fun generate(context: Context, chapterUrl: String, target: File) {
        val credentials = WebDavConnectionStore.credentialsFor(chapterUrl)
        // 独立连接（不复用阅读器的 ArchivePageLoader 句柄，避免生命周期竞争）；
        // 与阅读器同一 fallback 目录，服务器不支持 Range 整本缓存时通常可命中已有文件。
        val source = WebDavRandomAccessSource(
            url = WebDavConnectionStore.extractFullUrl(chapterUrl),
            username = credentials?.first?.ifBlank { null },
            password = credentials?.second?.ifBlank { null },
            fallbackCacheDir = File(context.cacheDir, "webdav_fallback"),
            cacheMaxBytes = Injekt.get<StoragePreferences>().webdavCacheMaxBytes.get(),
        )
        val delegate: ArchiveHandle = try {
            RemoteZipReader(source)
        } catch (e: Exception) {
            // 与 ChapterLoader 同款回落：中央目录直读不支持 → libarchive 回调路径。
            runCatching { ArchiveReader(source) }.getOrElse { err ->
                runCatching { source.close() }
                throw err
            }
        }
        // SY --> Komiho Phase5/Phase7: 封面生成同样吃页级缓存（首图已缓存则零网络）。
        // 目录名与 ChapterLoader 一致为 remote_pages（WebDAV/SMB 共用同一份页缓存）。
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
        // SY <--
        handle.use { h ->
            // 加密包：无密码（null）或密码错误（true）时首图读不出来，直接放弃
            //（阅读器会弹密码框，输对后下次打开自然能生成）。
            if (h.encrypted && h.wrongPassword != false) return
            // 「首页」必须与阅读器同一口径：zip 条目的存储顺序 ≠ 阅读顺序（1,10,11,2…），
            // 须按 ArchivePageLoader 同款自然排序（2.jpg < 10.jpg）取第一个图片条目。
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
     * SY: 散图目录章节的封面 —— 列目录（PROPFIND）→ 自然序取第一张图（与阅读器首页同口径）
     * → GET 该图 → 采样压缩落盘。只发 1 次列表 + 1 次图片请求。
     * 目录章节没有归档句柄，无法复用 [generate] 的拆包逻辑，故单独一条路径。
     */
    private fun generateFromDirectory(chapterUrl: String, target: File) {
        val connId = chapterUrl.removePrefix(WebDavConnectionStore.CONN_URL_PREFIX).substringBefore('/')
        val conn = WebDavConnectionStore.all().firstOrNull { it.id == connId }
            ?: throw IllegalStateException("WebDAV 连接不存在（可能已删除）: $chapterUrl")
        val dirUrl = WebDavConnectionStore.extractFullUrl(chapterUrl)
            .let { if (it.endsWith('/')) it else "$it/" }
        val firstUrl = runBlocking {
            WebDavPropfind.list(conn, dirUrl)
                .filter { it.isImage }
                .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
                .firstOrNull()?.url
        } ?: return
        val raw = fetchBytes(conn, firstUrl)
        val bmp = decodeSampled({ ByteArrayInputStream(raw) }, MAX_PX) ?: return
        writeCover(bmp, target)
    }

    /** 单图 GET（Basic 认证；复用阅读器同一条 OkHttpClient，防风控口径与页面读取一致）。 */
    private fun fetchBytes(conn: WebDavConnection, url: String): ByteArray {
        val builder = okhttp3.Request.Builder().url(url)
        val pass = WebDavCredentialCrypto.decryptStored(conn.passEnc)
        if (conn.user.isNotBlank()) {
            builder.header("Authorization", okhttp3.Credentials.basic(conn.user, pass))
        }
        val bytes = WebDavRandomAccessSource.sharedHttpClient().newCall(builder.build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("封面图片下载失败 HTTP ${resp.code}: $url")
                resp.body?.bytes() ?: ByteArray(0)
            }
        check(bytes.isNotEmpty()) { "封面图片为空：$url" }
        return bytes
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
