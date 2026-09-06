package eu.kanade.tachiyomi.ui.reader.loader

import app.mihonsy.komga.data.webdav.WebDavConnection
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import app.mihonsy.komga.data.webdav.WebDavPropfind
import app.mihonsy.komga.data.webdav.WebDavRandomAccessSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
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
            .map { it.url }
        if (images.isEmpty()) throw IOException("目录内没有图片：$dirUrl")
        return images.mapIndexed { i, url ->
            ReaderPage(i).apply {
                stream = { fetchBytes(url) }
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
        val bytes = WebDavRandomAccessSource.sharedHttpClient().newCall(builder.build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("图片下载失败 HTTP ${resp.code}: $url")
                resp.body?.bytes() ?: ByteArray(0)
            }
        check(bytes.isNotEmpty()) { "图片下载为空：$url" }
        return bytes
    }
}
// SY <--
