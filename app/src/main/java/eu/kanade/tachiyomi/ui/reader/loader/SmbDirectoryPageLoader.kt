package eu.kanade.tachiyomi.ui.reader.loader

import app.mihonsy.komga.data.smb.SmbBrowse
import app.mihonsy.komga.data.smb.SmbConnection
import app.mihonsy.komga.data.smb.SmbSessionManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import java.io.IOException

// SY --> Komiho Phase7: SMB 散图目录章节 —— 目录内没有归档、直接是图片时，
// 点目录内图片 = 把所在目录当一章打开，图片按自然序成页。
//
// 与 ArchivePageLoader 的差异：页面不是归档条目而是服务器上的独立文件，
// 每页懒打开（SmbSessionManager.openFile，原生 offset 读，会话由池复用）。
// 目录列举放在 getPages()（suspend、IO 上下文）；读取口径与 ArchivePageLoader
// 的防御一致：回收/空字节快速失败报真实原因。
internal class SmbDirectoryPageLoader(
    private val conn: SmbConnection,
    private val password: String,
    /** 目录 relPath（`/` 分隔；空共享连接含共享段前缀）。 */
    private val dirRel: String,
) : PageLoader() {

    override var isLocal: Boolean = false

    override suspend fun getPages(): List<ReaderPage> {
        val images = SmbBrowse.list(conn, password, dirRel)
            .filter { it.isImage }
            .sortedWith { a, b -> a.name.compareToCaseInsensitiveNaturalOrder(b.name) }
            .map { it.path }
        if (images.isEmpty()) throw IOException("目录内没有图片：$dirRel")
        return images.mapIndexed { i, path ->
            ReaderPage(i).apply {
                // stream 契约：() -> InputStream（ByteArray 需包一层）。
                stream = { readPageBytes(path).inputStream() }
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    private fun readPageBytes(path: String): ByteArray {
        check(!isRecycled) { "页面读取时章节已被回收（翻页/换章竞态）——重载即恢复" }
        // smbj 的 File 是句柄不是 InputStream：getInputStream() 取实时流再读全量。
        val bytes = SmbSessionManager.openFile(conn, password, path).use { f ->
            f.getInputStream().buffered().use { it.readBytes() }
        }
        check(bytes.isNotEmpty()) { "图片读取为空（章节流已被回收关闭）：$path——重载即恢复" }
        return bytes
    }
}
// SY <--
