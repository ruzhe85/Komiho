package app.mihonsy.komga.data.webdav

import android.util.Xml
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
// 只列 zip/cbz：WebDAV 远程随机访问仅对 zip 高效（WebDavZipReader），
// rar/7z 等会整本流式下载，不提供入口避免误用。

/** 目录浏览的一个条目。 */
data class WebDavEntry(
    /** 解码后的显示名（目录名不带尾斜杠）。 */
    val name: String,
    val isDir: Boolean,
    /** 完整 http(s) URL（可直接用于 PROPFIND 下钻或作为章节文件 URL）。 */
    val url: String,
) {
    /** 是否为支持的归档（zip/cbz）。 */
    val isArchive: Boolean =
        !isDir && name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTS

    private companion object {
        val ARCHIVE_EXTS = setOf("zip", "cbz")
    }
}

object WebDavPropfind {

    /**
     * 列出 [dirUrl] 的直接子项（Depth-1）。返回子目录 + 归档（zip/cbz），不含自身。
     * 目录在前、归档在后，各自按名称自然排序（Chapter2 < Chapter10）。
     * 失败抛异常（401/405/网络错误等），由调用方决定提示或兜底。
     */
    suspend fun list(conn: WebDavConnection, dirUrl: String): List<WebDavEntry> =
        withContext(Dispatchers.IO) {
            val dir = if (dirUrl.endsWith('/')) dirUrl else "$dirUrl/"
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
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
                .execute().use { resp ->
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
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            // 去掉 D:/d: 等命名空间前缀再比较
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "response" -> { href = null; isDir = false }
                    "href" -> href = runCatching { parser.nextText() }.getOrNull()
                    "collection" -> isDir = true
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
