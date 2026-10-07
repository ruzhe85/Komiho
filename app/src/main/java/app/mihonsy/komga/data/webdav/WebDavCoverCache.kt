package app.mihonsy.komga.data.webdav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
// SY --> Komiho Phase7: 页缓存上提为共用实现（data.remote 包），需显式导入。
import app.mihonsy.komga.data.remote.CachingArchiveHandle
import app.mihonsy.komga.data.remote.RemotePageCache
// SY <--
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.mobi.MobiExtractor
import eu.kanade.tachiyomi.util.pdf.PdfRenderFallback
import tachiyomi.source.local.io.Format
import kotlinx.coroutines.flow.MutableStateFlow
// SY: 散图目录封面需在后台上拉目录（PROPFIND 为 suspend）。
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import mihon.core.common.archive.ArchiveHandle
import mihon.core.common.archive.ArchiveReader
import mihon.core.common.archive.WebDavRandomAccessSource
import mihon.core.common.archive.RemoteZipReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

// SY --> Komiho Phase4: WebDAV 历史/书签封面 —— 打开章节时「顺便」生成首图封面。
// 思路（用户定）：ChapterLoader 打开 WebDAV 章节时若封面缓存缺失，则用独立连接按
// Range 拉首图（尾部 64KB + 中央目录 + 首图条目，通常 < 1MB 流量），采样压缩后落
// filesDir/komiho_webdav_covers/；历史/书签行读磁盘显示。
// Komiho (2026-10-02): 新增「WebDAV显示封面」并发档位（设置→高级，0-6 默认 0）——
// 档位 = **浏览器侧**封面显示/预取的开关与速度：0 = 浏览器不显示也不预取封面（不发请求），
// ≥1 = 浏览器按档位并发拉取显示（全局并发槽位 + 随档位缩放的最小间隔 + 失败退避 10 分钟，
// 见 [coverForDisplay]）。**打开章节「顺便生成」不受档位限制**（见 [generateAsync] 的 fromBrowser）：
// 无论档位多少，打开 WebDAV 章节都会照常生成封面（限流按 1 槽位算），历史/书签/聚合卡
// 因此始终有封面（见 [coverCachedOnly]）。
// 生成失败（不支持压缩/加密无密码/网络）只影响本次，
// 下次自动重试。缓存键 = 完整文件 URL 的 sha256，无自动 LRU（每张
// 40-70KB，可存上千张；如需回收可清 komiho_webdav_covers 目录，不影响其他缓存）。
object WebDavCoverCache {

    /** 封面缓存目录名（filesDir 下）。public 供设置页统计/清除，避免目录名漂移。 */
    const val DIR = "komiho_webdav_covers"

    /** 设置 → 高级 →「WebDAV显示封面」：0 = 不再拉取封面（不发请求，已缓存仍显示），1-6 = 拉封面并发上限。 */
    const val KEY_COVER_CONCURRENCY = "komiho_webdav_cover_concurrency"
    private const val MAX_CONCURRENCY = 6

    /** 全局限流：相邻两次封面网络任务的最小间隔。随「并发档位」缩放——档位越高越快，
     *  既保留低档位的防风控口径，又让高档位真正提速（档位 1 ≈ 400ms，6 ≈ 100ms 下限）。 */
    private const val BASE_INTERVAL_MS = 400L
    private const val MIN_INTERVAL_FLOOR_MS = 100L
    private fun intervalMs(concurrency: Int): Long =
        (BASE_INTERVAL_MS / concurrency.coerceAtLeast(1)).coerceAtLeast(MIN_INTERVAL_FLOOR_MS)

    /** 失败退避：同章节失败后多久内不再尝试（避免受限模式下反复打请求）。 */
    private const val FAIL_RETRY_MS = 10 * 60 * 1000L

    /** 当前封面并发档位（0-6，默认 0 = 防风控最保守）。 */
    fun coverConcurrency(): Int =
        Injekt.get<PreferenceStore>().getInt(KEY_COVER_CONCURRENCY, 0).get().coerceIn(0, MAX_CONCURRENCY)
    private const val MAX_PX = 450
    private const val JPEG_QUALITY = 80

    /** 正在生成的章节 URL（同窗口去重：连开同一章/快速翻卷只触发一次）。 */
    private val inFlight = mutableSetOf<String>()

    /** 失败退避表：章节 URL → 上次失败时刻（elapsedRealtime）。 */
    private val failAt = HashMap<String, Long>()

    // 全局限流原语：在飞的封面任务计数 + 最小间隔时刻。并发档位每次进入时现读，
    // 用户改档立即生效，无需重建信号量。
    private val slotLock = Object()
    private var activeSlots = 0
    private val paceLock = Object()
    private var lastStart = 0L

    /** 「当前浏览目录」代号：浏览器每进入一个目录自增一次。封面任务入队时记录该代号，
     *  执行前若已过期（用户切走了）则直接作废——实现「最新目录优先」，不让旧目录的积压
     *  把新目录的封面堵在 400ms 限流队列后面。 */
    @Volatile private var activeEpoch = 0L

    /** 浏览器进入某目录时调用：自增代号并返回，使上一目录尚未执行的封面任务全部作废。 */
    fun beginDirectory(): Long {
        activeEpoch++
        return activeEpoch
    }

    /** 当前目录代号（只读，供搜索结果等"不改目录"场景沿用当前代号）。 */
    fun currentEpoch(): Long = activeEpoch

    /** 封面落盘版本号：每次成功写入 +1。展示侧 collect 它作为重组 key，生成完即可上屏。 */
    val coverTick = MutableStateFlow(0L)

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

    /** 只读缓存、不触发生成。**与档位无关**：档位 0（关闭）只表示不再拉取新封面，
     *  已落盘的封面仍应显示（历史/书签/聚合卡）；未缓存则返回 null。
     *  供「一次映射全量条目」的路径使用——那些路径若触发生成会把整份历史灌进限流队列。 */
    fun coverCachedOnly(context: Context, chapterUrl: String): File? {
        if (!isWebDavChapter(chapterUrl)) return null
        return coverFile(context, chapterUrl)?.takeIf { it.isFile && it.length() > 0 }
    }

    /** 展示用封面（**WebDAV 浏览器列表**）：
     *  档位 0 → 永远 null（浏览器不显示、不发请求）；
     *  档位 ≥1 → 命中缓存直接返回；缓存缺失则**由列表页触发限流后台生成**（本轮先占位，
     *  生成完成后 [coverTick] 自增驱动重组上屏）。列表补拉与打开章节共用同一条限流队列，
     *  突发几十行也只是按并发档位排队 + 400ms 间隔慢慢补，不会形成请求风暴。
     *  注：历史/书签/聚合卡走 [coverCachedOnly]，**不受档位影响**。 */
    fun coverForDisplay(context: Context, chapterUrl: String): File? {
        if (!isWebDavChapter(chapterUrl)) return null
        val file = coverFile(context, chapterUrl)?.takeIf { it.isFile && it.length() > 0 }
        if (file != null) return if (coverConcurrency() <= 0) null else file
        generateAsync(context, chapterUrl, startDelayMs = 0L, epoch = activeEpoch, fromBrowser = true)
        return null
    }

    /**
     * 异步生成封面（对外入口统一走这里）：
     * @param startDelayMs 生成前延迟，仅阅读器路径需要（3s，避开与阅读线程同时整本缓存
     * 下载的竞态）；列表补拉传 0。延迟在**并发槽位外**睡，不占槽位拖慢排队。
     * @param fromBrowser true = 浏览器列表触发（受「WebDAV显示封面」档位限制，0 档直接返回）；
     *  false（默认）= 阅读器「打开章节顺便生成」等非浏览器路径，**不受档位限制**——0 档只关
     *  浏览器显示/预取，打开章节仍照常生成，历史/书签/聚合卡才有封面可显示。
     */
    fun generateAsync(
        context: Context,
        chapterUrl: String,
        startDelayMs: Long = 3000L,
        epoch: Long? = null,
        fromBrowser: Boolean = false,
    ) {
        if (!isWebDavChapter(chapterUrl)) return
        val concurrency = coverConcurrency()
        if (fromBrowser && concurrency <= 0) return
        // 0 档且非浏览器路径时仍要跑：限流按 1 槽位算，避免 0 槽位死等。
        val effectiveConcurrency = concurrency.coerceAtLeast(1)
        val target = coverFile(context, chapterUrl) ?: return
        if (target.isFile && target.length() > 0) return
        // 失败退避：10 分钟内失败过的章节不再发请求。
        synchronized(failAt) {
            val f = failAt[chapterUrl]
            if (f != null && android.os.SystemClock.elapsedRealtime() - f < FAIL_RETRY_MS) return
        }
        synchronized(inFlight) {
            if (!inFlight.add(chapterUrl)) return
        }
        // SY: 散图目录章节（URL 尾斜杠）没有归档句柄，走「拉目录首图」分支；
        // 归档章节仍走原来的「拆包取首图」。
        val isDirectory = WebDavConnectionStore.extractFullUrl(chapterUrl).endsWith('/')
        val app = context.applicationContext
        // 目录限定任务（epoch 非空）记录入队代号：执行前若已过期（用户切走）则作废。
        val myEpoch = epoch
        thread(name = "webdav-cover", isDaemon = true) {
            // 读路径延迟：不占并发槽位（浏览器补拉传 0 直进队列）。
            if (startDelayMs > 0) Thread.sleep(startDelayMs)
            // 全局限流：并发槽位（用户档位，0 档按 1 算）+ 相邻任务最小间隔。改档即时生效。
            synchronized(slotLock) {
                while (activeSlots >= effectiveConcurrency) slotLock.wait()
                activeSlots++
            }
            try {
                // 目录限定任务若已过期（用户切走）→ 作废，直接释放槽位（finally 清理），
                // 不进入 400ms 节奏，把节奏让给最新目录。
                if (myEpoch != null && myEpoch != activeEpoch) return@thread
                synchronized(paceLock) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val wait = intervalMs(effectiveConcurrency) - (now - lastStart)
                    if (wait > 0) Thread.sleep(wait)
                    lastStart = android.os.SystemClock.elapsedRealtime()
                }
                if (isDirectory) {
                    // 散图每页独立 GET，不存在整本缓存竞态，无需延迟。
                    runCatching { generateFromDirectory(chapterUrl, target) }
                        .onFailure {
                            recordFailure(chapterUrl)
                            logcat(LogPriority.INFO) { "[WebDavCover] 目录封面生成失败：${it.message}" }
                        }
                    return@thread
                }
                runCatching { generate(app, chapterUrl, target) }
                    .onFailure {
                        recordFailure(chapterUrl)
                        logcat(LogPriority.INFO) { "[WebDavCover] 封面生成失败：${it.message}" }
                    }
            } finally {
                // 生成结束才放出去：期间列表重组再触发会被 inFlight 去重挡住，不会重复拉。
                synchronized(inFlight) { inFlight.remove(chapterUrl) }
                synchronized(slotLock) {
                    activeSlots--
                    slotLock.notifyAll()
                }
            }
        }
    }

    private fun recordFailure(chapterUrl: String) {
        synchronized(failAt) { failAt[chapterUrl] = android.os.SystemClock.elapsedRealtime() }
    }

    private fun generate(context: Context, chapterUrl: String, target: File) {
        val credentials = WebDavConnectionStore.credentialsFor(chapterUrl)
        // SY: PDF 章节封面 = 整本落本地临时文件，系统 PdfRenderer 渲第 0 页（与 LocalCoverFetcher 同口径）。
        val fullUrl = WebDavConnectionStore.extractFullUrl(chapterUrl)
        val insecureTls = WebDavConnectionStore.insecureTlsFor(chapterUrl)
        if (fullUrl.endsWith(".pdf", ignoreCase = true)) {
            generateFromPdf(context, fullUrl, credentials, target, insecureTls)
            return
        }
        // SY --> Komiho: MOBI/AZW3/AZW 章节封面 = 整本落临时文件，libmobi 抽封面/首图
        //（与 MobiPageLoader 同引擎）。DRM/纯文字书抽不出 → 放弃（无封面，失败退避兜住频率）。
        if (fullUrl.substringBefore('?').substringAfterLast('.', "").lowercase() in Format.MOBI_EXTENSIONS) {
            generateFromMobi(context, fullUrl, credentials, target, insecureTls)
            return
        }
        // SY <--
        // 独立连接（不复用阅读器的 ArchivePageLoader 句柄，避免生命周期竞争）；
        // 与阅读器同一 fallback 目录，服务器不支持 Range 整本缓存时通常可命中已有文件。
        val source = WebDavRandomAccessSource(
            url = WebDavConnectionStore.extractFullUrl(chapterUrl),
            username = credentials?.first?.ifBlank { null },
            password = credentials?.second?.ifBlank { null },
            // 连接级「忽略 HTTPS 证书校验」。
            insecureTls = insecureTls,
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
     * SY: PDF 章节封面 —— 整本落本地临时文件，系统 PdfRenderer 渲第 0 页
     * （与 LocalCoverFetcher / SmbCoverFetcher 同口径）。加密 PDF 渲不出来则放弃（阅读器会弹密码框）。
     */
    private fun generateFromPdf(
        context: Context,
        fullUrl: String,
        credentials: Pair<String, String>?,
        target: File,
        insecureTls: Boolean,
    ) {
        val dir = File(context.cacheDir, "komiho_webdav_pdf_cover").apply { mkdirs() }
        val tmp = File(dir, sha256(fullUrl) + ".pdf")
        if (!tmp.exists() || tmp.length() == 0L) {
            runCatching {
                val src = WebDavRandomAccessSource(
                    url = fullUrl,
                    username = credentials?.first?.ifBlank { null },
                    password = credentials?.second?.ifBlank { null },
                    insecureTls = insecureTls,
                    fallbackCacheDir = File(context.cacheDir, "webdav_fallback"),
                    cacheMaxBytes = Injekt.get<StoragePreferences>().webdavCacheMaxBytes.get(),
                )
                src.use { s ->
                    val len = s.size
                    FileOutputStream(tmp).use { out ->
                        var offset = 0L
                        while (offset < len) {
                            val buf = s.read(offset, min(1 shl 20, (len - offset).toInt()))
                            if (buf.isEmpty()) break
                            out.write(buf)
                            offset += buf.size
                        }
                    }
                }
            }.getOrNull() ?: return
        }
        val bmp = PdfRenderFallback.renderPageBitmap(tmp.absolutePath, 0, MAX_PX) ?: return
        writeCover(bmp, target)
    }

    /**
     * Komiho: MOBI/AZW3/AZW 章节封面 —— 整本落本地临时文件，libmobi 抽图后取封面/首图
     * （与阅读器 MobiPageLoader 同引擎）。DRM（MobiDrmException）/纯文字书抽不出图 → 无封面。
     */
    private fun generateFromMobi(
        context: Context,
        fullUrl: String,
        credentials: Pair<String, String>?,
        target: File,
        insecureTls: Boolean,
    ) {
        val dir = File(context.cacheDir, "komiho_webdav_mobi_cover").apply { mkdirs() }
        val tmp = File(dir, sha256(fullUrl) + ".mobi")
        if (!tmp.exists() || tmp.length() == 0L) {
            runCatching {
                val src = WebDavRandomAccessSource(
                    url = fullUrl,
                    username = credentials?.first?.ifBlank { null },
                    password = credentials?.second?.ifBlank { null },
                    insecureTls = insecureTls,
                    fallbackCacheDir = File(context.cacheDir, "webdav_fallback"),
                    cacheMaxBytes = Injekt.get<StoragePreferences>().webdavCacheMaxBytes.get(),
                )
                src.use { s ->
                    val len = s.size
                    FileOutputStream(tmp).use { out ->
                        var offset = 0L
                        while (offset < len) {
                            val buf = s.read(offset, min(1 shl 20, (len - offset).toInt()))
                            if (buf.isEmpty()) break
                            out.write(buf)
                            offset += buf.size
                        }
                    }
                }
            }.getOrNull() ?: return
        }
        val book = runCatching {
            MobiExtractor.extractImages(tmp, File(dir, "pages_" + sha256(fullUrl)))
        }.getOrNull() ?: return
        val img = book.coverFile ?: book.imageFiles.firstOrNull() ?: return
        val bmp = decodeSampled({ ByteArrayInputStream(img.readBytes()) }, MAX_PX) ?: return
        writeCover(bmp, target)
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
        val client = if (conn.insecureTls) {
            WebDavRandomAccessSource.insecureHttpClient()
        } else {
            WebDavRandomAccessSource.sharedHttpClient()
        }
        val bytes = client.newCall(builder.build())
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
            coverTick.value = coverTick.value + 1
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
