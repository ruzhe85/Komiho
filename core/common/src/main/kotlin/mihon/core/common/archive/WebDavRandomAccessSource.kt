package mihon.core.common.archive

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import logcat.LogPriority
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import tachiyomi.core.common.util.system.logcat

// SY --> Komiho Phase3
/**
 * WebDAV（HTTP）远程随机读取源 —— [RandomAccessSource] 的远程实现。
 *
 * 原理：HTTP Range 请求（206 Partial Content）按 1MB 块拉数据，配合
 * [ArchiveInputStream] 的 libarchive Read/Seek/Skip 回调实现「远程随机访问 ZIP 内部条目」，
 * **不整本下载**。Reader / PageLoader / 缓存零感知（与 Local 同一抽象，符合实施方案核心思路）。
 *
 * - 能力探测（懒）：首个 GET `Range: bytes=0-0`：
 *   - `206` → 从 Content-Range 取文件总大小，启用随机访问；
 *   - `200` → 服务器不支持 Range：整本流式下载到 [fallbackCacheDir] 缓存文件，
 *     之后包装 [LocalRandomAccessSource] 按本地随机读（功能不崩，代价是全量下载）。
 *   - rar/7z 等 [FORCED_FALLBACK_EXTS] 格式：跳过探测直接整本缓存（这类格式无法
 *     随机访问，反复从头扫等于流量爆炸；整本一次下载流量确定性最好，缓存跨会话复用）。
 * - 块缓冲：按字节上限 [MAX_CACHE_BYTES]（约 16MB）做 LRU，块大小 [READ_CHUNK]（1MB）。
 *   大块摊薄请求数——对 115 这类按请求频次触发风控的服务器，每次 Range 拉得越多、
 *   请求越少越安全；缓冲未命中才发新的 Range 请求。块大于单次读回调所需（256KB），
 *   命中时部分返回即可，多页并发解码时各页位置互不踩踏。
 * - 线程安全：[ArchivePageLoader] 会并发解码多页共享同一 source（Phase 2 已验证），
 *   全部状态在 [lock] 内访问；每次请求独立 Call，close 时取消 in-flight 请求
 *   （本地 FileChannel 读不需要取消，网络阻塞读必须可打断）。
 * - 鉴权：Basic Auth（user/pass 可空，匿名时省略 Authorization 头）。
 */
class WebDavRandomAccessSource(
    url: String,
    username: String? = null,
    password: String? = null,
    /** 忽略 HTTPS 证书校验（自签名/缺中间证书的服务器）；按连接开关显式传入，默认关闭。 */
    insecureTls: Boolean = false,
    private val fallbackCacheDir: File? = null,
    /** 整本缓存磁盘上限（字节）：超限按 lastModified LRU 淘汰；0/负数 = 不限。设置-存储可调。 */
    private val cacheMaxBytes: Long = Long.MAX_VALUE,
    // SY --> Komiho: 整本下载（rar/7z 强制回退 / 非 Range 回退）进度回调（值域 0f..1f）；不传则不报进度。
    private val onProgress: ((Float) -> Unit)? = null,
    // SY <--
) : RandomAccessSource {

    /** 规范化后的请求 URL（宽容中文/空格等未编码字符，逐段百分号编码补齐）。 */
    private val requestUrl = normalizeUrl(url)

    /**
     * rar/7z 等不支持随机访问的格式（Phase4-② 方案 B）：跳过 Range 探测，
     * 打开时直接整本缓存到 [fallbackCacheDir] 后按本地读。
     * 这类格式条目头与数据交错（7z 头部还压缩），远程「随机访问」等价于反复从头扫，
     * 流量爆炸；一次性整本下载反而是流量确定性最好的方式（1× 文件大小，只慢一次）。
     */
    private val forceFallback = remoteExt() in FORCED_FALLBACK_EXTS

    /** 远程文件扩展名（小写，已按 URL 规范化，query 已剥离）。 */
    private fun remoteExt(): String =
        requestUrl.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    // SY --> Komiho Phase5：页级磁盘缓存的失效键素材。
    /** 规范化 URL（页缓存按它分书目录；阅读器与封面缓存共用同一键）。 */
    val normalizedUrl: String get() = requestUrl

    /** rar/7z 强制整本回退时页缓存不启用（整本已落盘，页缓存只会双份占空间）。 */
    val isForcedFallback: Boolean get() = forceFallback

    /**
     * 探测完成后的远程文件指纹（`size:Last-Modified`；无 Last-Modified 时为 `size:`）。
     * 页缓存以此判断「远程文件是否被替换」；null = 尚未探测（调用方此时不应读写页缓存）。
     */
    @Volatile
    var remoteFingerprint: String? = null
        private set
    // SY <--

    private val authHeader: String? =
        if (username.isNullOrEmpty() && password.isNullOrEmpty()) {
            null
        } else {
            val raw = "${username.orEmpty()}:${password.orEmpty()}"
            "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        }

    // 全局共享客户端（companion 懒加载）：连接池 / 线程池跨章节复用，
    // 避免每个 source 各建一套造成握手风暴（115 风控对高频新建连接更敏感）。
    // 开启「忽略证书校验」的连接换用信任所有证书的客户端，其余行为（连接池/超时）不变。
    private val client = if (insecureTls) insecureHttpClient() else sharedClient

    private val lock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var probed = false
    private var total = -1L
    private var fallback: LocalRandomAccessSource? = null

    /** 最近缓存的块（start inclusive / end exclusive），字节总量上限 [MAX_CACHE_BYTES]。 */
    private val chunks = ArrayDeque<Chunk>()
    private var cachedBytes = 0L
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
            if (closed) throw IOException("WebDAV source closed")
            if (offset < 0) throw IOException("非法 offset: $offset")
            if (offset >= total) return ByteArray(0)

            // 缓冲命中：返回可以少于 length（契约允许；libarchive 据此推进 position）
            chunks.firstOrNull { offset >= it.start && offset < it.end }?.let { hit ->
                val from = (offset - hit.start).toInt()
                val avail = minOf(hit.bytes.size - from, length)
                return hit.bytes.copyOfRange(from, from + avail)
            }

            // 未命中：Range 拉一块（在 total 处截断）
            val end = (offset + READ_CHUNK - 1L).coerceAtMost(total - 1)
            val bytes = rangeGet(offset, end)
            if (bytes.isNotEmpty()) {
                chunks.addLast(Chunk(offset, bytes))
                cachedBytes += bytes.size
                // 按字节上限 LRU：从最旧块开始淘汰，直到总量回到预算内
                while (cachedBytes > MAX_CACHE_BYTES && chunks.size > 1) {
                    cachedBytes -= chunks.removeFirst().bytes.size
                }
                // 单块超预算（理论不可能，1MB << 16MB）时兜底丢弃
                if (cachedBytes > MAX_CACHE_BYTES) {
                    cachedBytes -= chunks.removeFirst().bytes.size
                }
            }
            return bytes.copyOf(minOf(bytes.size, length))
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // 打断阻塞中的网络读（翻页跳页时旧请求立即失效，不占连接）
            currentCall?.cancel()
            chunks.clear()
            cachedBytes = 0
            fallback?.close()
            fallback = null
        }
    }

    /** 懒探测：一次 GET `Range: bytes=0-0` 同时完成「是否支持 Range」与「文件总大小」确认。
     *  [forceFallback]（rar/7z 等）跳过探测，直接整本缓存到本地再随机读。 */
    private fun ensureProbed() {
        if (probed) return
        synchronized(lock) {
            if (probed) return
            if (closed) throw IOException("WebDAV source closed")
            if (forceFallback) {
                val file = ensureFallbackFile()
                fallback = LocalRandomAccessSource(file)
                total = file.length()
                remoteFingerprint = "${file.length()}:${file.lastModified()}"
                probed = true
                return
            }
            val call = client.newCall(newRequestBuilder().header("Range", "bytes=0-0").build())
            currentCall = call
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("WebDAV 探测失败 HTTP ${resp.code}: $requestUrl")
                    }
                    if (resp.code == 206) {
                        // Content-Range: bytes 0-0/<total>
                        total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: throw IOException("WebDAV 206 未返回 Content-Range: $requestUrl")
                        remoteFingerprint = "$total:${resp.header("Last-Modified").orEmpty()}"
                    } else {
                        // 不支持 Range：整本流式落盘（本次响应体即全量数据，直接消费）
                        // → 本地随机读回退（不崩，代价全量下载）
                        val dir = fallbackCacheDir
                            ?: throw IOException("WebDAV 服务器不支持 Range，且未提供回退缓存目录")
                        dir.mkdirs()
                        val file = fallbackFile(dir)
                        val tmp = File(dir, file.name + ".part")
                        val totalLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                        resp.body?.byteStream()?.use { input ->
                            tmp.outputStream().use { output ->
                                // SY --> Komiho: 整本下载带进度（Content-Length 可得时上报百分比）。
                                copyWithProgress(input, output, totalLen) { p -> onProgress?.invoke(p) }
                            }
                        } ?: throw IOException("WebDAV 空响应体: $requestUrl")
                        if (file.exists()) file.delete()
                        tmp.renameTo(file)
                        fallback = LocalRandomAccessSource(file)
                        total = file.length()
                        remoteFingerprint = "${file.length()}:${file.lastModified()}"
                    }
                }
            } finally {
                currentCall = null
            }
            probed = true
        }
    }

    /**
     * 整本缓存落盘（rar/7z 强制回退路径）：文件名 = URL hash + 扩展名，跨会话复用——
     * 已存在完整缓存则跳过下载（重新打开同一卷零流量，命中时刷新 lastModified 作 LRU 依据）。
     * 先落 `.part` 临时文件、完成后原子改名，避免中断留下半包被误用。
     * 磁盘缓存目录受 [cacheMaxBytes] 预算约束，超限淘汰最旧文件。
     */
    private fun ensureFallbackFile(): File {
        val dir = fallbackCacheDir ?: throw IOException("WebDAV 整本缓存需要回退缓存目录")
        dir.mkdirs()
        val file = fallbackFile(dir)
        if (file.exists() && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            logcat(LogPriority.DEBUG) { "[WebDav] 命中整本缓存，跳过下载: ${file.name}" }
            return file
        }
        enforceCacheBudget(dir, keepName = file.name)
        val tmp = File(dir, file.name + ".part")
        // SY --> Komiho: 先用 HEAD 取总大小，整本下载过程上报进度百分比（取不到则不报进度，保持转圈）。
        val totalLen = runCatching { headLength() }.getOrNull() ?: -1L
        val call = client.newCall(newRequestBuilder().build())
        currentCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("WebDAV 下载失败 HTTP ${resp.code}: $requestUrl")
                }
                resp.body?.byteStream()?.use { input ->
                    tmp.outputStream().use { output ->
                        copyWithProgress(input, output, totalLen) { p -> onProgress?.invoke(p) }
                    }
                } ?: throw IOException("WebDAV 空响应体: $requestUrl")
            }
        } finally {
            currentCall = null
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("整本缓存落盘失败: ${file.name}")
        }
        // 新文件计入后若仍超预算，先淘汰别的（不含本次文件，下次打开再自然轮转）
        enforceCacheBudget(dir, keepName = file.name)
        logcat(LogPriority.DEBUG) { "[WebDav] 整本缓存完成: ${file.name} (${file.length()} B)" }
        return file
    }

    /**
     * 磁盘缓存预算（LRU）：目录内 `webdav_*` 完整文件（排除 .part 与 [keepName]）按
     * lastModified 从旧到新淘汰，直到总量回到 [cacheMaxBytes] 内。0/负数 = 不限。
     * 正被打开章节占用的文件在 Linux 下删除无碍（句柄存活期仍可读）。
     */
    private fun enforceCacheBudget(dir: File, keepName: String) {
        if (cacheMaxBytes <= 0L) return
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("webdav_") && !f.name.endsWith(".part") }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= cacheMaxBytes) return
        files.filter { it.name != keepName }
            .sortedBy { it.lastModified() }
            .forEach { victim ->
                if (total <= cacheMaxBytes) return
                val len = victim.length()
                if (victim.delete()) {
                    total -= len
                    logcat(LogPriority.DEBUG) { "[WebDav] 磁盘缓存超限，淘汰: ${victim.name} ($len B)" }
                }
            }
    }

    /** 整本缓存的稳定文件名：URL hash（8 位 hex）+ 远程扩展名（缺省 bin）。 */
    private fun fallbackFile(dir: File): File =
        File(dir, "webdav_" + String.format("%08x", requestUrl.hashCode()) + "." + remoteExt().ifBlank { "bin" })

    /**
     * 带进度拷贝：从 [input] 拷到 [output]，约每 256KB 上报一次 [onProgress]（值域 0f..1f）。
     * [total] <= 0 时无法计算百分比，仅上报起始 0f（保持转圈，不抛异常）。
     */
    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        onProgress(0f)
        if (total <= 0L) {
            input.copyTo(output)
            return
        }
        val buffer = ByteArray(256 * 1024)
        var written = 0L
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            written += read
            onProgress((written.toFloat() / total).coerceIn(0f, 1f))
        }
        output.flush()
    }

    /** HEAD 取 Content-Length（整本下载总大小）；服务器不支持 HEAD 或缺失时返回 null。 */
    private fun headLength(): Long? {
        val call = client.newCall(newRequestBuilder().head().build())
        currentCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return null
                return resp.header("Content-Length")?.toLongOrNull()
            }
        } finally {
            currentCall = null
        }
    }

    /** Range 读取 [start]..[endInclusive]（闭区间）。416 视作越界返回空（与契约一致）。 */
    private fun rangeGet(start: Long, endInclusive: Long): ByteArray {
        val call = client.newCall(
            newRequestBuilder().header("Range", "bytes=$start-$endInclusive").build(),
        )
        currentCall = call
        try {
            call.execute().use { resp ->
                if (resp.code == 416) return ByteArray(0)
                if (!resp.isSuccessful) throw IOException("WebDAV HTTP ${resp.code}: $requestUrl")
                return resp.body?.bytes() ?: throw IOException("WebDAV 空响应体: $requestUrl")
            }
        } finally {
            currentCall = null
        }
    }

    private fun newRequestBuilder(): Request.Builder {
        val b = Request.Builder().url(requestUrl)
        authHeader?.let { b.header("Authorization", it) }
        return b
    }

    companion object {
        /** 章节 url 前缀：`webdav:` + 完整 http(s) URL（Phase 4 扩展为 `webdav://<connId>/<path>`）。 */
        const val URL_PREFIX = "webdav:"

        /** 全局共享 OkHttpClient：连接池/线程池跨所有 WebDAV 章节复用，降低新建连接频次。 */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * 忽略 HTTPS 证书校验的共享 OkHttpClient（自签名/缺中间证书的服务器用）。
         * 仅在连接/同步显式开启开关时使用；连接池独立于 [sharedClient]，避免污染其状态。
         */
        val insecureClient: OkHttpClient by lazy {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), SecureRandom())
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .sslSocketFactory(ctx.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .build()
        }

        /** Range 块大小：1MB。比 libarchive 单次回调（256KB）大，摊薄请求数（防风控核心手段）。 */
        private const val READ_CHUNK = 1024 * 1024

        /** 共享 OkHttpClient 访问器：app 层 PROPFIND 目录浏览等复用同一连接池（Phase4-②）。 */
        fun sharedHttpClient(): OkHttpClient = sharedClient

        /** 忽略证书校验客户端访问器（app 层封面/浏览等直接调用点用）。 */
        fun insecureHttpClient(): OkHttpClient = insecureClient

        /** 块缓存字节上限（LRU）：约 16MB，覆盖多页并发解码的活跃窗口。 */
        private const val MAX_CACHE_BYTES = 16L * 1024 * 1024

        /** 这些扩展名的远程归档不支持随机访问，打开时整本缓存到本地（Phase4-② 方案 B）。 */
        private val FORCED_FALLBACK_EXTS = setOf("rar", "cbr", "7z", "cb7")

        /**
         * 去掉 host 末尾的 DNS 根标记「.」：部分 WebDAV 服务器在 PROPFIND 的 href 里返回
         * FQDN 绝对名（如 `dav.ruzhe.dpdns.org.`），HTTP URL 不需要该尾点，且 OkHttp 会
         * 拒绝带尾点的 host（→「非法 WebDAV URL」）。统一剥掉，避免服务端 href 直接被当
         * 成下一层目录 URL 时崩溃。仅剥 host，不动 path/query 里的点。
         */
        fun stripRootDot(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url
            val rest = url.substring(schemeEnd + 3) // host[:port]/path...
            val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, slash)
            val colon = authority.indexOf(':')
            val host = if (colon < 0) authority else authority.substring(0, colon)
            if (!host.endsWith('.')) return url
            val strippedAuthority = if (colon < 0) {
                host.removeSuffix(".")
            } else {
                host.removeSuffix(".") + authority.substring(colon)
            }
            return url.substring(0, schemeEnd + 3) + strippedAuthority + rest.substring(slash)
        }

        /** 宽容解析手输 URL：中文/空格等未编码字符按 UTF-8 百分号编码补齐后重建。 */
        fun normalizeUrl(raw: String): String {
            val cleaned = stripRootDot(raw)
            cleaned.toHttpUrlOrNull()?.let { return it.toString() }
            val schemeEnd = cleaned.indexOf("://")
            // SY: 报错打印**剥点后**的 cleaned 而非 raw——raw 里的尾点会让人误判成
            // 「stripRootDot 没生效」，实际失败原因往往在别处（如未编码字符）。
            require(schemeEnd > 0) { "非法 WebDAV URL: $cleaned" }
            val rest = cleaned.substring(schemeEnd + 3) // host[:port]/path...
            val slash = rest.indexOf('/')
            require(slash >= 0) { "非法 WebDAV URL（缺路径）: $cleaned" }
            val authority = rest.substring(0, slash)
            val encodedPath = rest.substring(slash)
                .split('/')
                .joinToString("/") { seg ->
                    // 用 String 重载（API 1+）；Charset 重载要 API 33+
                    if (seg.isEmpty()) seg else URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
                }
            val rebuilt = "${cleaned.substring(0, schemeEnd)}://$authority$encodedPath"
            return rebuilt.toHttpUrlOrNull()?.toString()
                ?: throw IllegalArgumentException("非法 WebDAV URL: $cleaned")
            }

            // ---- 写入能力（同步中心 / 备份推送用）：复用 sharedHttpClient + Basic Auth ----

            /** 构造 Basic Auth 头（user/pass 皆空返回 null，即匿名）。 */
            fun basicAuth(user: String?, pass: String?): String? {
                if (user.isNullOrEmpty() && pass.isNullOrEmpty()) return null
                val raw = "${user.orEmpty()}:${pass.orEmpty()}"
                return "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
            }

            /** 建单个目录（MKCOL）。已存在（405）视为成功。返回是否成功。 */
            fun mkcol(url: String, auth: String?, client: OkHttpClient = sharedClient): Boolean {
                val req = Request.Builder().url(normalizeUrl(url)).apply {
                    auth?.let { header("Authorization", it) }
                    method("MKCOL", null)
                }.build()
                client.newCall(req).execute().use { resp ->
                    return resp.isSuccessful || resp.code == 405
                }
            }

            /** 逐级确保目录存在：对 relPath 的每一段依次 MKCOL（已存在则忽略）。 */
            fun ensureDir(baseUrl: String, relPath: String, auth: String?, client: OkHttpClient = sharedClient) {
                val segments = relPath.trim('/').split('/').filter { it.isNotEmpty() }
                var cur = baseUrl.trimEnd('/')
                for (seg in segments) {
                    cur = "$cur/$seg"
                    mkcol(cur, auth, client)
                }
            }

            /** 写文件（PUT），覆盖同名。 */
            fun putFile(url: String, auth: String?, content: ByteArray, client: OkHttpClient = sharedClient) {
                val body = RequestBody.create(null, content)
                val req = Request.Builder().url(normalizeUrl(url)).apply {
                    auth?.let { header("Authorization", it) }
                    put(body)
                }.build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("WebDAV PUT 失败 HTTP ${resp.code}: $url")
                }
            }

            /** 读文件（GET）。404 抛 FileNotFoundException。 */
            fun getFile(url: String, auth: String?, client: OkHttpClient = sharedClient): ByteArray {
                val req = Request.Builder().url(normalizeUrl(url)).apply {
                    auth?.let { header("Authorization", it) }
                }.build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 404) throw FileNotFoundException("WebDAV 文件不存在: $url")
                    if (!resp.isSuccessful) throw IOException("WebDAV GET 失败 HTTP ${resp.code}: $url")
                    return resp.body?.bytes() ?: throw IOException("WebDAV 空响应: $url")
                }
            }

            /** 列目录（PROPFIND Depth 1），返回该目录下条目的完整 href。 */
            fun propfind(url: String, auth: String?, client: OkHttpClient = sharedClient): List<String> {
                val body = RequestBody.create(
                    null,
                    "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:resourcetype/></D:prop></D:propfind>",
                )
                val req = Request.Builder().url(normalizeUrl(url)).apply {
                    auth?.let { header("Authorization", it) }
                    header("Depth", "1")
                    method("PROPFIND", body)
                }.build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("WebDAV PROPFIND 失败 HTTP ${resp.code}: $url")
                    val xml = resp.body?.string() ?: return emptyList()
                    return parseHrefs(xml)
                }
            }

            /** 删文件（DELETE）。404 视为成功。 */
            fun deleteFile(url: String, auth: String?, client: OkHttpClient = sharedClient) {
                val req = Request.Builder().url(normalizeUrl(url)).apply {
                    auth?.let { header("Authorization", it) }
                    delete()
                }.build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 404) {
                        throw IOException("WebDAV DELETE 失败 HTTP ${resp.code}: $url")
                    }
                }
            }

            /** 从 PROPFIND 多状态 XML 里抽所有 href（兼容 `href` 与 `D:href` 等带前缀写法）。 */
            private fun parseHrefs(xml: String): List<String> {
                val regex = Regex("<([\\w]+:)?href[^>]*>([\\s\\S]*?)</([\\w]+:)?href>", RegexOption.IGNORE_CASE)
                return regex.findAll(xml).map { it.groupValues[2].trim() }.toList()
        }
    }
}
