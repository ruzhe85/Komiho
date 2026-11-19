package eu.kanade.tachiyomi.ui.reader.loader

import app.mihonsy.komga.data.webdav.WebDavConnection
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import app.mihonsy.komga.data.webdav.WebDavPropfind
import mihon.core.common.archive.WebDavRandomAccessSource
import eu.kanade.tachiyomi.diagnostic.DiagLog
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.lang.readAllBytesCompact
import java.io.IOException

// SY --> Komiho Phase7: WebDAV 散图目录章节 —— 对齐本地模式与 SMB 的散图做法：
// 目录内没有归档、直接是图片（或子目录散图）时，点目录内图片 = 把所在目录当一章打开，
// 图片按自然序成页。
//
// 与 SmbDirectoryPageLoader 同构，差异只在取流方式：每页 = HTTP GET 单文件
// （复用 WebDavRandomAccessSource 的全局 OkHttpClient + Basic 认证，防风控口径一致）。
// 目录列举在 getPages()（suspend、IO 上下文），每页懒拉取，不整本下载。
internal class WebDavDirectoryPageLoader(
    private val conn: WebDavConnection,
    /** 目录完整 URL（尾斜杠）。 */
    private val dirUrl: String,
) : PageLoader() {

    override var isLocal: Boolean = false

    override suspend fun getPages(): List<ReaderPage> {
        val images = WebDavPropfind.list(conn, dirUrl)
            .filter { it.isImage }
            .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
        if (images.isEmpty()) throw IOException("目录内没有图片：$dirUrl")
        return images.mapIndexed { i, entry ->
            ReaderPage(i).apply {
                // issue #17：PROPFIND 已给出 getcontentlength，留给缩略图做分配前的准入判断。
                sourceSize = entry.size
                // 缩略图直通字节，避免从 stream 再复制一份整页（峰值 1 倍而非 2 倍）。
                rawBytes = { fetchBytes(entry.url) }
                stream = { fetchBytes(entry.url).inputStream() }
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    private fun fetchBytes(url: String): ByteArray {
        check(!isRecycled) { "页面读取时章节已被回收（翻页/换章竞态）——重载即恢复" }
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
                if (!resp.isSuccessful) throw IOException("图片下载失败 HTTP ${resp.code}: $url")
                val body = resp.body ?: throw IOException("图片响应为空：$url")
                // issue #16：ResponseBody.bytes() 走 ByteArrayOutputStream 扩容；
                // 有 Content-Length 时按长度精确分配，峰值从 3～4 倍降到 1 倍。
                body.byteStream().use { it.readAllBytesCompact(body.contentLength()) }
            }
        check(bytes.isNotEmpty()) { "图片下载为空：$url" }
        if (bytes.size >= LARGE_PAGE_THRESHOLD) {
            DiagLog.d("WebDavPageRead", "page=${bytes.size}B compact-read url=$url")
        }
        return bytes
    }

    private companion object {
        const val LARGE_PAGE_THRESHOLD = 8 * 1024 * 1024
    }
}
// SY <--
