package app.mihonsy.komga.data.cloud115

import android.content.Context
import android.graphics.Bitmap
import app.mihonsy.komga.data.remote.CacheLimits
import app.mihonsy.komga.data.remote.CachingArchiveHandle
import app.mihonsy.komga.data.remote.RemotePageCache
import app.mihonsy.komga.data.webdav.WebDavCoverCache
import eu.kanade.tachiyomi.data.coil.decodeCoverBitmap
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import logcat.LogPriority
import mihon.core.common.archive.ArchiveHandle
import mihon.core.common.archive.ArchiveReader
import mihon.core.common.archive.Cloud115RandomAccessSource
import mihon.core.common.archive.RemoteZipReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat

/**
 * Komiho：115 历史/书签/聚合卡封面 —— 打开章节时「顺便」生成首图封面。
 *
 * 与 [WebDavCoverCache] 同思路（Range 拉尾部中央目录 + 首图条目，通常 < 1MB 流量），
 * 但只覆盖压缩包这一种形态（当前 115 来源只做 cbz/zip/rar/7z）：
 *  - 不做散图目录（115 来源不提供目录章节）；
 *  - 不做 PDF/MOBI（暂不支持这两类）。
 *
 * 并发与限流直接复用 WebDAV 侧那套并发档位（[WebDavCoverCache.coverConcurrency]），
 * 不新增设置项 —— 两个来源都在打同一个 115/服务器，口径一致才不会被风控。
 * 缓存键 = 章节 URL 的 sha256；封面图 40~70KB，可存上千张，不做自动 LRU。
 */
object Cloud115CoverCache {

    /** 封面缓存目录名（filesDir 下），public 供设置页统计/清除。 */
    const val DIR = "komiho_cloud115_covers"

    private const val MAX_PX = 450
    private const val JPEG_QUALITY = 80

    /** 失败退避：同章节失败后 10 分钟内不再尝试（授权失效时不至于反复打请求）。 */
    private const val FAIL_RETRY_MS = 10 * 60 * 1000L

    /** 邻接任务最小间隔：与 WebDAV 封面同口径（并发档位越高越快）。 */
    private const val BASE_INTERVAL_MS = 400L
    private const val MIN_INTERVAL_FLOOR_MS = 50L

    private val inFlight = mutableSetOf<String>()
    private val failAt = HashMap<String, Long>()

    private val slotLock = Object()
    private var activeSlots = 0
    private val paceLock = Object()
    private var lastStart = 0L

    /** 封面落盘版本号：成功后 +1，展示侧 collect 它作为重组 key。 */
    val coverTick = MutableStateFlow(0L)

    fun isCloud115Chapter(chapterUrl: String): Boolean = chapterUrl.startsWith("115://")

    /** 封面缓存文件（不保证存在）。 */
    private fun coverFile(context: Context, chapterUrl: String): File? {
        if (!isCloud115Chapter(chapterUrl)) return null
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, "v1-" + sha256(chapterUrl) + ".jpg")
    }

    /** 只读缓存、不触发生成（历史/书签/聚合卡用）。 */
    fun coverCachedOnly(context: Context, chapterUrl: String): File? =
        coverFile(context, chapterUrl)?.takeIf { it.isFile && it.length() > 0 }

    /** 展示用封面（115 浏览列表用）：命中直接返回，缺失则排队后台生成。 */
    fun coverForDisplay(context: Context, chapterUrl: String, enabled: Boolean): File? {
        if (!enabled) return null
        val file = coverCachedOnly(context, chapterUrl)
        if (file != null) return file
        generateAsync(context, chapterUrl, startDelayMs = 0L)
        return null
    }

    /**
     * 异步生成封面。阅读器路径默认延迟 3 秒（避开与阅读线程同时整本缓存/取链的竞态），
     * 列表补拉传 0。延迟在并发槽位外睡，不占槽位。
     */
    fun generateAsync(context: Context, chapterUrl: String, startDelayMs: Long = 3000L) {
        if (!isCloud115Chapter(chapterUrl)) return
        val target = coverFile(context, chapterUrl) ?: return
        if (target.isFile && target.length() > 0) return
        synchronized(failAt) {
            val f = failAt[chapterUrl]
            if (f != null && android.os.SystemClock.elapsedRealtime() - f < FAIL_RETRY_MS) return
        }
        synchronized(inFlight) { if (!inFlight.add(chapterUrl)) return }

        val concurrency = WebDavCoverCache.coverConcurrency().coerceAtLeast(1)
        val app = context.applicationContext
        thread(name = "cloud115-cover", isDaemon = true) {
            if (startDelayMs > 0) Thread.sleep(startDelayMs)
            synchronized(slotLock) {
                while (activeSlots >= concurrency) slotLock.wait()
                activeSlots++
            }
            try {
                synchronized(paceLock) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val wait = (BASE_INTERVAL_MS / concurrency).coerceAtLeast(MIN_INTERVAL_FLOOR_MS) -
                        (now - lastStart)
                    if (wait > 0) Thread.sleep(wait)
                    lastStart = android.os.SystemClock.elapsedRealtime()
                }
                runCatching { generate(app, chapterUrl, target) }
                    .onFailure {
                        synchronized(failAt) { failAt[chapterUrl] = android.os.SystemClock.elapsedRealtime() }
                        logcat(LogPriority.INFO) { "[Cloud115Cover] 封面生成失败：${it.message}" }
                    }
            } finally {
                synchronized(inFlight) { inFlight.remove(chapterUrl) }
                synchronized(slotLock) {
                    activeSlots--
                    slotLock.notifyAll()
                }
            }
        }
    }

    private fun generate(context: Context, chapterUrl: String, target: File) {
        val ref = Cloud115ConnectionStore.parseChapterUrl(chapterUrl)
            ?: throw IllegalStateException("115 章节 URL 非法: $chapterUrl")
        val conn = Cloud115ConnectionStore.match(chapterUrl)
            ?: throw IllegalStateException("115 连接不存在（可能已删除）: $chapterUrl")
        // 独立 source（不复用阅读器句柄，避免生命周期竞争）；与阅读器共用 fallback 目录，
        // rar/7z 整本缓存通常可直接命中已有文件。
        Cloud115RandomAccessSource(
            pickCode = ref.pickCode,
            fileName = ref.fileName,
            chapterUrl = chapterUrl,
            fingerprintHint = null,
            userAgent = Cloud115Api.USER_AGENT,
            resolve = { force -> Cloud115Api.downloadBlocking(conn.id, ref.pickCode, force) },
            fallbackCacheDir = File(context.cacheDir, "cloud115_fallback"),
            cacheMaxBytes = CacheLimits.maxBytes(context),
        ).use { source ->
            val delegate: ArchiveHandle = try {
                RemoteZipReader(source)
            } catch (e: Exception) {
                ArchiveReader(source)
            }
            val handle: ArchiveHandle = CachingArchiveHandle(
                delegate = delegate,
                cache = RemotePageCache(
                    root = File(context.cacheDir, "remote_pages"),
                    maxBytes = CacheLimits.maxBytes(context),
                ),
                metaKey = {
                    source.remoteFingerprint?.let { fp -> "${source.normalizedUrl}|$fp" }
                },
            )
            handle.use { h ->
                // 加密包无密码时首图读不出来，直接放弃（阅读器会弹密码框，输对后下次自然生成）。
                if (h.encrypted && h.wrongPassword != false) return
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
                val bmp = decodeCoverBitmap("cloud115 cover", MAX_PX) { ByteArrayInputStream(raw) } ?: return
                writeCover(bmp, target)
            }
        }
    }

    private fun writeCover(bmp: Bitmap, target: File) {
        runCatching {
            val tmp = File(target.parentFile, target.nameWithoutExtension + ".tmp")
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                tmp.writeBytes(out.toByteArray())
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            coverTick.value = coverTick.value + 1
        }
        bmp.recycle()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
