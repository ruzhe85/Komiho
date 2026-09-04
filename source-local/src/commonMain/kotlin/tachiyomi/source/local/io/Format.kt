package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.extension
import tachiyomi.source.local.io.Archive.isSupported as isArchiveSupported

sealed interface Format {
    data class Directory(val file: UniFile) : Format
    data class Archive(val file: UniFile) : Format
    data class Epub(val file: UniFile) : Format
    // SY --> Komiho Phase3: 远程归档（WebDAV；后续 SMB 同走此变体）。
    // 存原始章节 url（`webdav:https://...`）而非 source 实例：本文件在 commonMain，
    // 看不到 core.common 的 RandomAccessSource，由 ChapterLoader 构造 WebDavRandomAccessSource。
    data class RemoteArchive(val remoteUrl: String) : Format
    // SY <--

    class UnknownFormatException : Exception()

    companion object {

        fun valueOf(file: UniFile) = when {
            file.isDirectory -> Directory(file)
            file.extension.equals("epub", true) -> Epub(file)
            isArchiveSupported(file) -> Archive(file)
            else -> throw UnknownFormatException()
        }
    }
}
