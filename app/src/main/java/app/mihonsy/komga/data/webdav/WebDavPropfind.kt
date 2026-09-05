package app.mihonsy.komga.data.webdav

import android.util.Xml
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.common.archive.WebDavRandomAccessSource
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import tachiyomi.core.common.util.system.logcat

// SY --> Komiho Phase4-②：WebDAV PROPFIND 目录浏览。
//
// 复用 WebDavRandomAccessSource 的全局 OkHttpClient（连接池/线程池共享，防风控口径一致），
// 对目录发 Depth-1 PROPFIND，解析 multistatus 得到子目录与归档列表。
// 列出 zip/cbz/rar/7z：zip/cbz 远程随机访问（每页流量 = 条目本身）；rar/7z 无随机
// 访问能力，打开时由 WebDavRandomAccessSource 强制整本缓存到本地（Phase4-② 方案 B）。

/** 目录浏览的一个条目。 */
data class WebDavEntry(
    /** 解码后的显示名（目录名不带尾斜杠）。 */
    val name: String,
    val isDir: Boolean,
    /** 完整 http(s) URL（可直接用于 PROPFIND 下钻或作为章节文件 URL）。 */
    val url: String,
    /** 字节数。服务器未返回（部分服务端不给目录/未知文件的长度）时为 0，仅用于「按大小」排序。 */
    val size: Long = 0L,
    /** 修改时间 epoch ms。服务器未返回或解析失败时为 0，仅用于「按修改时间」排序。 */
    val lastModified: Long = 0L,
) {
    /** 是否为支持的归档。zip/cbz 走远程随机访问；rar/7z 打开时整本缓存到本地
     *  （WebDavRandomAccessSource 强制回退，Phase4-② 方案 B）。 */
    val isArchive: Boolean =
        !isDir && name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTS

    private companion object {
        val ARCHIVE_EXTS = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7")
    }
}

object WebDavPropfind {

    /**
     * 列出 [dirUrl] 的直接子项（Depth-1）。返回子目录 + 归档（zip/cbz/rar/7z），不含自身。
     * 目录在前、归档在后，各自按名称自然排序（Chapter2 < Chapter10）。
     * 失败抛异常（401/405/网络错误等），由调用方决定提示或兜底。
     */
    suspend fun list(conn: WebDavConnection, dirUrl: String): List<WebDavEntry> =
        withContext(Dispatchers.IO) {
            val dir = if (dirUrl.endsWith('/')) dirUrl else "$dirUrl/"
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
            val builder = Request.Builder()
                .url(WebDavRandomAccessSource.normalizeUrl(dir))
                .header("Depth", "1")
                .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
            val pass = WebDavCredentialCrypto.decryptStored(conn.passEnc)
            if (conn.user.isNotBlank()) {
                builder.header("Authorization", Credentials.basic(conn.user, pass))
            }
            val selfPath = decodedPath(dir)
            val xml = WebDavRandomAccessSource.sharedHttpClient().newCall(builder.build())
                // SY: 用 Call.await() 替代阻塞 execute()——协程被取消时（如 WebDavBrowsePane 的
                // dirUrl 因 navRequest 触发重切）能 invokeOnCancellation 调 call.cancel()，
                // OkHttp 直接中断请求；否则 execute() 阻塞 IO 线程不响应协程取消，请求跑完再
                // resume 时父 scope 已退出 → "The coroutine scope left the composition" 报错。
                .await().use { resp ->
                    if (!resp.isSuccessful) {
                        throw Exception("PROPFIND ${resp.code}（目录浏览失败）")
                    }
                    resp.body?.string() ?: throw Exception("PROPFIND 空响应")
                }
            parse(xml, selfPath, dir)
                .also { logcat(LogPriority.DEBUG) { "[WebDav] PROPFIND %s -> %d 项".format(dir, it.size) } }
        }

    // ------------------------------------------------------------ XML 解析

    private fun parse(xml: String, selfPath: String, dirUrl: String): List<WebDavEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        val out = mutableListOf<WebDavEntry>()
        var href: String? = null
        var isDir = false
        var contentLength: String? = null
        var lastModifiedRaw: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            // 去掉 D:/d: 等命名空间前缀再比较
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "response" -> { href = null; isDir = false; contentLength = null; lastModifiedRaw = null }
                    "href" -> href = runCatching { parser.nextText() }.getOrNull()
                    "collection" -> isDir = true
                    "getcontentlength" -> contentLength = runCatching { parser.nextText() }.getOrNull()
                    "getlastmodified" -> lastModifiedRaw = runCatching { parser.nextText() }.getOrNull()
                }

                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "response") {
                    val h = href
                    if (!h.isNullOrBlank()) {
                        val path = decodedPath(h)
                        // 跳过自身（目录集合里第一项是请求的目录本身）
                        if (path.trimEnd('/') != selfPath.trimEnd('/')) {
                            val name = path.trimEnd('/').substringAfterLast('/')
                            if (name.isNotBlank()) {
                                out += WebDavEntry(
                                    name = name,
                                    isDir = isDir,
                                    url = resolveHref(dirUrl, h),
                                    size = contentLength?.trim()?.toLongOrNull() ?: 0L,
                                    lastModified = parseHttpDate(lastModifiedRaw),
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        out.sortWith(
            compareBy<WebDavEntry> { !it.isDir }
                .thenComparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) },
        )
        return out
    }

    /** 解析 HTTP 日期（RFC 1123，如 Sat, 07 Nov 2020 06:35:44 GMT）为 epoch ms；解析失败返回 0。 */
    private fun parseHttpDate(v: String?): Long {
        val raw = v?.trim().orEmpty()
        if (raw.isEmpty()) return 0L
        return runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("GMT")
            }.parse(raw)?.time
        }.getOrNull() ?: 0L
    }

    /** 从 href（路径绝对或完整 URL）取解码后的 path 部分。 */
    private fun decodedPath(href: String): String {
        val raw = if (href.startsWith("http://") || href.startsWith("https://")) {
            runCatching { URI(href).rawPath }.getOrNull() ?: href.substringAfter("://").substringAfter('/')
        } else {
            href
        }
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    /** 把 href 解析成完整 URL：绝对 URL 原样，路径相对 [dirUrl] 解析（处理 %编码）。 */
    private fun resolveHref(dirUrl: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return runCatching { URI(dirUrl).resolve(href).toString() }.getOrDefault(href)
    }
}
// SY <--
