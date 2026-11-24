package mihon.core.common.archive

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import logcat.LogPriority
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat

/**
 * Komiho：115 授权类失败（令牌失效 / 刷新失败）。
 *
 * 单独成型是为了让本文件能把「授权问题」与「直链过期」区分开：
 * 前者重试没有意义（每次都再打一次刷新接口，反而会撞 `40140117 刷新过于频繁`），
 * 必须直接失败并把可读原因交给上层。
 */
open class Cloud115AuthFailure(
    message: String,
    /** true = refresh_token 本身不能用了，只能引导用户重新获取令牌。 */
    val needsReauth: Boolean,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Komiho：115 网盘直链的随机读取结果。
 *
 * 直链本身有时效且与取链时的 UA 绑定（`f=1`）；部分文件是 `f=3`，还要带上取链响应里的
 * `Set-Cookie`，所以把「URL + 额外请求头」一起交出来，而不是只给 URL。
 */
data class Cloud115Resolved(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Komiho：115 网盘远程随机读取源 —— [RandomAccessSource] 的 115 实现。
 *
 * 与 [WebDavRandomAccessSource] 同一副骨架（Range 探测 / 1MB 块 LRU / close 取消在途请求 /
 * rar-7z 整本回退），只换三处：
 *
 *  1. **认证与地址**：URL 不是章节里写死的，而是首次打开时用 `pickCode` 现取直链
 *     （115 直链有时效，绝不落库）；请求头由 [resolve] 返回（UA + 可选 Cookie）。
 *  2. **直链失效**：403 / 中途断流时重新取链一次再重试（最多 [MAX_REAUTH_RETRIES] 次），
 *     不把过期直链的失败当成数据错误。
 *  3. **指纹**：优先用列表接口给的 `size:sha1` 前 8 位（比 Last-Modified 可靠），
 *     拿不到才回落到探测出的总大小 —— 页缓存的失效判定依赖它。
 *
 * 线程安全与 WebDAV 同口径：[ArchivePageLoader] 会并发解码多页共享同一 source，
 * 全部状态在 [lock] 内访问；同一时刻只有一条 Range 请求在飞，正好满足 115「单直链
 * 同刻只允许一条连接」的约束。
 */
class Cloud115RandomAccessSource(
    /** 115 文件提取码，取直链用。 */
    private val pickCode: String,
    /** 文件名（只用于扩展名分流：rar/7z 走整本回退）。 */
    fileName: String,
    /**
     * 章节 URL（`115://<connId>/<parentCid>/<pickCode>/<fileName>`），
     * 作为页缓存的分书键 —— 与连接、位置一起唯一确定一个文件。
     */
    private val chapterUrl: String,
    /**
     * 列表接口给出的内容指纹（`<size>:<sha1 前 8 位>`）。非空时页缓存**立即**可用，
     * 不必等懒探测完成；仍然会在探测后用真实总大小校正一次。
     */
    private val fingerprintHint: String? = null,
    /** 与取链时逐字符一致的 UA（115 直链校验它，换一个就 403）。 */
    private val userAgent: String,
    /**
     * 同步取直链 + 额外请求头；失败抛 [IOException]。
     *
     * 参数 `forceRefresh = true` 表示「上次拿到的直链已经失效，请跳过任何缓存重新取」——
     * 没有这个开关，403 重试会一直拿到 API 层缓存里那条坏链，重试等于空转。
     */
    private val resolve: (forceRefresh: Boolean) -> Cloud115Resolved,
    private val fallbackCacheDir: File? = null,
    /** 整本缓存磁盘上限（字节）：超限按 lastModified LRU 淘汰；0/负数 = 不限。 */
    private val cacheMaxBytes: Long = Long.MAX_VALUE,
    /** 整本下载（rar/7z 强制回退 / 非 Range 回退）进度回调（0f..1f）。 */
    private val onProgress: ((Float) -> Unit)? = null,
) : RandomAccessSource {

    /** 远程文件扩展名（小写）。 */
    private val ext: String = fileName.substringAfterLast('.', "").lowercase()

    /** 文件名（只用于「大文件缓存确认」的提示文案；主构造参数不加 val 时成员函数不可见）。 */
    private val displayName: String = fileName

    /**
     * rar/7z 等不支持随机访问的格式：跳过 Range 探测，直接整本缓存到 [fallbackCacheDir]。
     * 理由与 WebDAV 侧一致（条目头与数据交错，远程随机访问等于反复从头扫）。
     */
    private val forceFallback = ext in FORCED_FALLBACK_EXTS

    /** 页缓存按它分书目录（章节 URL 含 connId + parentCid + pickCode，天然稳定）。 */
    val normalizedUrl: String get() = chapterUrl

    val isForcedFallback: Boolean get() = forceFallback

    /**
     * 页缓存的失效键素材。列表已经给了 `size:sha1` 时**一开始就有值**，
     * 页缓存不必等首次探测（否则所有页在第一次打开时都读不到缓存）。
     */
    @Volatile
    var remoteFingerprint: String? = fingerprintHint
        private set

    private val client = sharedClient

    private val lock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var probed = false

    /** 大文件整本缓存是否已获得用户批准（避免重复弹窗）。 */
    @Volatile
    private var fallbackApprovalDone = false

    private var total = -1L
    private var fallback: LocalRandomAccessSource? = null

    /** 当前手头的直链与请求头；失效（403/断流）后置空，下次用时重新取。 */
    private var currentUrl: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()

    /** 下次取链是否必须绕过 API 层缓存（[invalidateUrl] 置位，[ensureUrl] 消费）。 */
    private var needFreshUrl = false

    private val chunks = ArrayDeque<Chunk>()
    private var cachedBytes = 0L

    /**
     * 在途请求。**必须是 @Volatile**：`close()` 刻意不抢 lock（避免退出时卡在网络等待），
     * 它要靠锁外读这个字段来 cancel；不加 volatile 可能读到 null，导致整本缓存下载在
     * 用户退出阅读器后继续跑（24MB 还能忍，2.4GB 就是事故）。
     */
    @Volatile
    private var currentCall: Call? = null

    private class Chunk(val start: Long, val bytes: ByteArray) {
        val end: Long = start + bytes.size
    }

    override val size: Long
        get() {
            ensureProbed()
            return total
        }

    override fun read(offset: Long, length: Int): ByteArray {
        ensureProbed()
        fallback?.let { return it.read(offset, length) }
        synchronized(lock) {
            if (closed) throw IOException("115 source closed")
            if (offset < 0) throw IOException("非法 offset: $offset")
            if (offset >= total) return ByteArray(0)

            chunks.firstOrNull { offset >= it.start && offset < it.end }?.let { hit ->
                val from = (offset - hit.start).toInt()
                val avail = minOf(hit.bytes.size - from, length)
                return hit.bytes.copyOfRange(from, from + avail)
            }

            val end = (offset + READ_CHUNK - 1L).coerceAtMost(total - 1)
            val bytes = rangeGetWithReauth(offset, end)
            if (bytes.isNotEmpty()) {
                chunks.addLast(Chunk(offset, bytes))
                cachedBytes += bytes.size
                while (cachedBytes > MAX_CACHE_BYTES && chunks.size > 1) {
                    cachedBytes -= chunks.removeFirst().bytes.size
                }
                if (cachedBytes > MAX_CACHE_BYTES) {
                    cachedBytes -= chunks.removeFirst().bytes.size
                }
            }
            return bytes.copyOf(minOf(bytes.size, length))
        }
    }

    override fun close() {
        // 刻意**不抢 lock**：取链是同步网络调用（最长 60s），若关闭时正好有人在 resolve()，
        // 等锁会让「退出阅读器 / 切章」卡在网络上。这里只置标记 + 取消在途请求 + 关整本缓存
        // 句柄；块缓存只是内存，随对象回收。read() 入口会看到 closed 并立即失败。
        if (closed) return
        closed = true
        currentCall?.cancel()
        val local = fallback
        fallback = null
        runCatching { local?.close() }
    }

    // ------------------------------------------------------------ 探测 / 整本回退

    /**
     * 用 `Range: bytes=0-0` 只探总大小（取 `Content-Range` 的 total）。
     * 失败 / 不支持 Range 返回 -1，调用方按「大小未知」处理（不拦截）。
     */
    private fun probeRemoteSize(): Long = try {
        val call = client.newCall(newRequestBuilder().header("Range", "bytes=0-0").build())
        currentCall = call
        try {
            call.execute().use { resp ->
                if (resp.code != 206) {
                    -1L
                } else {
                    resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                }
            }
        } finally {
            currentCall = null
        }
    } catch (e: IOException) {
        -1L
    }

    private fun ensureProbed() {
        if (probed) return
        // 大文件确认最长要等用户两分钟，放在锁外做（与 WebDAV 同口径）；
        // 确认过就记住，避免并发探测重复弹窗。
        if (forceFallback && !fallbackApprovalDone) {
            val probedSize = probeRemoteSize()
            if (!RemoteFallbackGate.requestApproval(displayName, probedSize)) {
                throw IOException("已取消整本缓存：$displayName（${probedSize / 1024 / 1024}MB）")
            }
            fallbackApprovalDone = true
        }
        synchronized(lock) {
            if (probed) return
            if (closed) throw IOException("115 source closed")
            if (forceFallback) {
                val file = ensureFallbackFile()
                fallback = LocalRandomAccessSource(file)
                total = file.length()
                remoteFingerprint = fingerprintHint ?: "${file.length()}:"
                probed = true
                return
            }
            val call = client.newCall(newRequestBuilder().header("Range", "bytes=0-0").build())
            currentCall = call
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("115 探测失败 HTTP ${resp.code}（$pickCode）")
                    }
                    if (resp.code == 206) {
                        total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: throw IOException("115 未返回 Content-Range（$pickCode）")
                        remoteFingerprint = fingerprintHint ?: "$total:"
                    } else {
                        // 不支持 Range（理论上 115 不会这样）：整本落盘后本地随机读，功能不崩。
                        val dir = fallbackCacheDir
                            ?: throw IOException("115 直链不支持 Range，且未提供回退缓存目录")
                        dir.mkdirs()
                        val file = fallbackFile(dir, ext)
                        val tmp = File(dir, file.name + ".part")
                        val totalLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                        // 不支持 Range ⇒ 只能整本下载，同样要过大文件确认。
                        if (!RemoteFallbackGate.requestApproval(displayName, totalLen)) {
                            throw IOException("已取消整本缓存：$displayName（${totalLen / 1024 / 1024}MB）")
                        }
                        try {
                            resp.body?.byteStream()?.use { input ->
                                tmp.outputStream().use { output ->
                                    copyWithProgress(input, output, totalLen) { p -> onProgress?.invoke(p) }
                                }
                            } ?: throw IOException("115 空响应体（$pickCode）")
                        } catch (e: Throwable) {
                            tmp.delete()
                            throw e
                        }
                        if (file.exists()) file.delete()
                        if (!tmp.renameTo(file)) {
                            tmp.delete()
                            throw IOException("115 整本缓存落盘失败: ${file.name}")
                        }
                        fallback = LocalRandomAccessSource(file)
                        total = file.length()
                        remoteFingerprint = fingerprintHint ?: "${file.length()}:"
                    }
                }
            } finally {
                currentCall = null
            }
            probed = true
        }
    }

    /**
     * 整本缓存（rar/7z）落盘：文件名 = pickCode 的 SHA-256 前 8 字节 + 扩展名，跨会话复用；
     * 先写 `.part` 再原子改名，避免中断留下半包被误用。
     */
    private fun ensureFallbackFile(): File {
        // 用户已经退出（close 置 closed）时不要再发起整本下载 —— 那是 2GB 级别的浪费。
        if (closed) throw IOException("115 source closed")
        val dir = fallbackCacheDir ?: throw IOException("115 整本缓存需要回退缓存目录")
        dir.mkdirs()
        val file = fallbackFile(dir, ext)
        if (file.exists() && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            logcat(LogPriority.DEBUG) { "[Cloud115] 命中整本缓存，跳过下载: ${file.name}" }
            return file
        }
        enforceCacheBudget(dir, keepName = file.name)
        val tmp = File(dir, file.name + ".part")
        val call = client.newCall(newRequestBuilder().build())
        currentCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("115 下载失败 HTTP ${resp.code}（$pickCode）")
                }
                val totalLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                resp.body?.byteStream()?.use { input ->
                    tmp.outputStream().use { output ->
                        copyWithProgress(input, output, totalLen) { p -> onProgress?.invoke(p) }
                    }
                } ?: throw IOException("115 空响应体（$pickCode）")
            }
        } catch (e: Throwable) {
            // 取消/失败都要删半包：文件名命中的判据是「存在且长度 > 0」，
            // 留一个截断的 .part 不会命中，但会一直占着磁盘。
            tmp.delete()
            throw e
        } finally {
            currentCall = null
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("115 整本缓存落盘失败: ${file.name}")
        }
        enforceCacheBudget(dir, keepName = file.name)
        logcat(LogPriority.DEBUG) { "[Cloud115] 整本缓存完成: ${file.name} (${file.length()} B)" }
        return file
    }

    private fun enforceCacheBudget(dir: File, keepName: String) {
        if (cacheMaxBytes <= 0L) return
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("cloud115_") && !f.name.endsWith(".part") }
            ?: return
        var sum = files.sumOf { it.length() }
        if (sum <= cacheMaxBytes) return
        files.filter { it.name != keepName }
            .sortedBy { it.lastModified() }
            .forEach { victim ->
                if (sum <= cacheMaxBytes) return
                val len = victim.length()
                if (victim.delete()) {
                    sum -= len
                    logcat(LogPriority.DEBUG) { "[Cloud115] 磁盘缓存超限，淘汰: ${victim.name} ($len B)" }
                }
            }
    }

    /**
     * 整本缓存文件名：pickCode 的 SHA-256 前 8 字节（不用 `hashCode`——32 位在几千个文件上
     * 就有可观的碰撞概率，撞了会把两个不同文件当成同一份缓存复用）。
     */
    private fun fallbackFile(dir: File, ext: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pickCode.toByteArray())
        val key = digest.take(8).joinToString("") { "%02x".format(it) }
        return File(dir, "cloud115_$key.${ext.ifBlank { "bin" }}")
    }

    // ------------------------------------------------------------ HTTP

    /**
     * Range 读取 [start]..[endInclusive]（闭区间）。416 视作越界返回空。
     * 403/断流视为直链失效：清掉当前直链、重新取一次再重试（最多 [MAX_REAUTH_RETRIES] 次）。
     */
    private fun rangeGetWithReauth(start: Long, endInclusive: Long): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return rangeGetOnce(start, endInclusive)
            } catch (e: Cloud115AuthFailure) {
                // 授权类失败重试无意义：needsReauth 时上层要引导重新取令牌，否则也只是白打刷新接口。
                throw e
            } catch (e: Cloud115UrlExpired) {
                if (attempt++ >= MAX_REAUTH_RETRIES) {
                    throw IOException("115 直链拒绝访问（$pickCode）：${e.message}", e)
                }
            } catch (e: IOException) {
                // 断流/超时也按「直链可能失效」处理：重取一次再试，仍失败才把异常交给上层。
                if (attempt++ >= MAX_REAUTH_RETRIES) throw e
            }
            invalidateUrl()
        }
    }

    /** 单次 Range 请求。403/401 抛 [Cloud115UrlExpired]，让外层重取直链。 */
    private fun rangeGetOnce(start: Long, endInclusive: Long): ByteArray {
        val call = client.newCall(
            newRequestBuilder().header("Range", "bytes=$start-$endInclusive").build(),
        )
        currentCall = call
        try {
            call.execute().use { resp ->
                if (resp.code == 416) return ByteArray(0)
                if (resp.code == 403 || resp.code == 401) {
                    throw Cloud115UrlExpired("HTTP ${resp.code}")
                }
                if (!resp.isSuccessful) throw IOException("115 HTTP ${resp.code}（$pickCode）")
                return resp.body?.bytes() ?: throw IOException("115 空响应体（$pickCode）")
            }
        } finally {
            currentCall = null
        }
    }

    /** 直链失效的内部信号（不对外暴露，避免上层把它当数据错误）。 */
    private class Cloud115UrlExpired(message: String) : IOException(message)

    private fun invalidateUrl() {
        synchronized(lock) {
            currentUrl = null
            currentHeaders = emptyMap()
            needFreshUrl = true
            chunks.clear()
            cachedBytes = 0
        }
        logcat(LogPriority.DEBUG) { "[Cloud115] 直链失效，重新取链（$pickCode）" }
    }

    private fun newRequestBuilder(): Request.Builder {
        val (url, headers) = ensureUrl()
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        b.header("User-Agent", userAgent)
        return b
    }

    /** 取（或复用）当前直链；URL 必须能被 OkHttp 解析。 */
    private fun ensureUrl(): Pair<String, Map<String, String>> {
        synchronized(lock) {
            currentUrl?.let { return it to currentHeaders }
            // 失效后必须绕过 API 层 5 分钟缓存，否则会拿到同一条坏链、重试空转。
            val force = needFreshUrl
            needFreshUrl = false
            val resolved = try {
                resolve(force)
            } catch (e: Throwable) {
                // 取链失败：保留 force 标记，下次再试仍然绕过缓存。
                needFreshUrl = force
                throw e
            }
            val normalized = resolved.url.toHttpUrlOrNull()?.toString()
                ?: throw IOException("115 返回的直链非法（$pickCode）")
            currentUrl = normalized
            currentHeaders = resolved.headers
            return normalized to currentHeaders
        }
    }

    /**
     * 流式落盘（整本缓存）。
     *
     * 每块都检查 [closed]：`close()` 只能 cancel 在途 Call，取消不保证立刻打断底层 read，
     * 这里再兜一道 —— 用户退出阅读器后必须尽快停下，并把 `.part` 留给调用方删掉。
     */
    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        if (total > 0L) onProgress(0f)
        val buffer = ByteArray(256 * 1024)
        var written = 0L
        var read = input.read(buffer)
        while (read != -1) {
            if (closed) throw IOException("115 整本缓存已取消（source closed）")
            output.write(buffer, 0, read)
            written += read
            if (total > 0L) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            read = input.read(buffer)
        }
        output.flush()
    }

    companion object {
        /** 直链失效后的重取次数（不含首次）。 */
        private const val MAX_REAUTH_RETRIES = 2

        /** Range 块大小：1MB（与 WebDAV 同口径，摊薄请求数，降低 115 频控风险）。 */
        private const val READ_CHUNK = 1024 * 1024

        private const val MAX_CACHE_BYTES = 16L * 1024 * 1024

        private val FORCED_FALLBACK_EXTS = setOf("rar", "cbr", "7z", "cb7")

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /** 供 app 层（取链 / 列目录）复用同一连接池。 */
        fun sharedHttpClient(): OkHttpClient = sharedClient
    }
}
