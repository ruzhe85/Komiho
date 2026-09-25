package eu.kanade.tachiyomi.util.pdf

import android.content.Context
import app.mihonsy.komga.data.smb.SmbConnectionStore
import app.mihonsy.komga.data.smb.SmbRandomAccessSource
import app.mihonsy.komga.data.webdav.WebDavConnectionStore
import java.io.File
import java.io.IOException
import mihon.core.common.archive.RandomAccessSource
import mihon.core.common.archive.RemoteScheme
import mihon.core.common.archive.WebDavRandomAccessSource
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 由远程章节 url（`webdav://<connId>/...` 或 `smb://<connId>/...`）构造随机访问源。
 *
 * 复刻 [eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader] 里 WebDAV/SMB 源的构建逻辑，
 * 抽成独立函数以便 [PdfPageLoader] 在远程 PDF 首次取页时整本下载——归档走 Range 流式读取，
 * PDF 渲染兜底需本地文件，故 PDF 必须整本落到 cache 后再复用本地解析路径。
 */
internal fun remotePdfSource(remoteUrl: String, context: Context): RandomAccessSource {
    return if (RemoteScheme.isSmb(remoteUrl)) {
        val target = SmbConnectionStore.resolve(remoteUrl)
            ?: throw IOException("SMB 连接不存在（可能已删除）: $remoteUrl")
        SmbRandomAccessSource(target.conn, target.password, target.relPath)
    } else {
        val credentials = WebDavConnectionStore.credentialsFor(remoteUrl)
        WebDavRandomAccessSource(
            url = WebDavConnectionStore.extractFullUrl(remoteUrl),
            username = credentials?.first?.ifBlank { null },
            password = credentials?.second?.ifBlank { null },
            fallbackCacheDir = File(context.cacheDir, "webdav_fallback"),
            cacheMaxBytes = Injekt.get<StoragePreferences>().webdavCacheMaxBytes.get(),
        )
    }
}
