package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.extension
import tachiyomi.source.local.io.Archive.isSupported as isArchiveSupported

sealed interface Format {
    data class Directory(val file: UniFile) : Format
    data class Archive(val file: UniFile) : Format
    data class Epub(val file: UniFile) : Format
    // SY --> Komiho: 本地 PDF（彩色扫描漫画常见）。仅承载数据，真正解析/渲染在 app 模块的
    // PdfPageLoader；本文件在 commonMain，不能引用任何 Android 专用类。
    data class Pdf(val file: UniFile) : Format
    // SY <--
    // SY --> Komiho Phase3: 远程归档（WebDAV；后续 SMB 同走此变体）。
    // 存原始章节 url（`webdav:https://...`）而非 source 实例：本文件在 commonMain，
    // 看不到 core.common 的 RandomAccessSource，由 ChapterLoader 构造 WebDavRandomAccessSource。
    data class RemoteArchive(val remoteUrl: String) : Format
    // SY <--
    // SY --> Komiho Phase3/Phase7: 远程 PDF（WebDAV/SMB）与远程归档同走 url 变体；
    // 解析/渲染复用本地 PdfPageLoader（首次取页时整本落本地缓存，再走本地解析）。
    data class RemotePdf(val remoteUrl: String) : Format
    // SY <--

    class UnknownFormatException : Exception()

    companion object {

        fun valueOf(file: UniFile) = when {
            file.isDirectory -> Directory(file)
            file.extension.equals("epub", true) -> Epub(file)
            // SY --> Komiho: PDF 在 archive 之前判定，避免被误当归档处理（pdf 通常不在归档列表，靠前更稳）。
            file.extension.equals("pdf", true) -> Pdf(file)
            // SY <--
            isArchiveSupported(file) -> Archive(file)
            else -> throw UnknownFormatException()
        }
    }
}
