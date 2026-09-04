package mihon.core.common.archive

import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

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
    private val fallbackCacheDir: File? = null,
) : RandomAccessSource {

    /** 规范化后的请求 URL（宽容中文/空格等未编码字符，逐段百分号编码补齐）。 */
    private val requestUrl = normalizeUrl(url)

    private val authHeader: String? =
        if (username.isNullOrEmpty() && password.isNullOrEmpty()) {
            null
        } else {
            val raw = "${username.orEmpty()}:${password.orEmpty()}"
            "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        }

    // 全局共享客户端（companion 懒加载）：连接池 / 线程池跨章节复用，
    // 避免每个 source 各建一套造成握手风暴（115 风控对高频新建连接更敏感）。
    private val client = sharedClient

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

    /** 懒探测：一次 GET `Range: bytes=0-0` 同时完成「是否支持 Range」与「文件总大小」确认。 */
    private fun ensureProbed() {
        if (probed) return
        synchronized(lock) {
            if (probed) return
            if (closed) throw IOException("WebDAV source closed")
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
                    } else {
                        // 不支持 Range：整本流式落盘 → 本地随机读回退（不崩，代价全量下载）
                        val dir = fallbackCacheDir
                            ?: throw IOException("WebDAV 服务器不支持 Range，且未提供回退缓存目录")
                        dir.mkdirs()
                        val file = File.createTempFile("webdav_fallback_", ".bin", dir)
                        resp.body?.byteStream()?.use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IOException("WebDAV 空响应体: $requestUrl")
                        fallback = LocalRandomAccessSource(file)
                        total = file.length()
                    }
                }
            } finally {
                currentCall = null
            }
            probed = true
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

        /** Range 块大小：1MB。比 libarchive 单次回调（256KB）大，摊薄请求数（防风控核心手段）。 */
        private const val READ_CHUNK = 1024 * 1024

        /** 块缓存字节上限（LRU）：约 16MB，覆盖多页并发解码的活跃窗口。 */
        private const val MAX_CACHE_BYTES = 16L * 1024 * 1024

        /** 宽容解析手输 URL：中文/空格等未编码字符按 UTF-8 百分号编码补齐后重建。 */
        fun normalizeUrl(raw: String): String {
            raw.toHttpUrlOrNull()?.let { return it.toString() }
            val schemeEnd = raw.indexOf("://")
            require(schemeEnd > 0) { "非法 WebDAV URL: $raw" }
            val rest = raw.substring(schemeEnd + 3) // host[:port]/path...
            val slash = rest.indexOf('/')
            require(slash >= 0) { "非法 WebDAV URL（缺路径）: $raw" }
            val authority = rest.substring(0, slash)
            val encodedPath = rest.substring(slash)
                .split('/')
                .joinToString("/") { seg ->
                    // 用 String 重载（API 1+）；Charset 重载要 API 33+
                    if (seg.isEmpty()) seg else URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
                }
            val rebuilt = "${raw.substring(0, schemeEnd)}://$authority$encodedPath"
            return rebuilt.toHttpUrlOrNull()?.toString()
                ?: throw IllegalArgumentException("非法 WebDAV URL: $raw")
        }
    }
}
// SY <--
