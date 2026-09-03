package mihon.core.common.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.hippo.unifile.UniFile
import me.zhanghai.android.libarchive.ArchiveException
import tachiyomi.core.common.storage.openFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.InputStream

class ArchiveReader : Closeable {

    val size: Long
    private val address: Long?
    private val source: RandomAccessSource?

    // 本地 mmap 构造器（SAF / content uri 走这条，保持原有行为）
    constructor(pfd: ParcelFileDescriptor) {
        size = pfd.statSize
        address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)
        source = null
        checkEncryptionStatus()
    }

    // 回调式构造器（Local / WebDAV / SMB 走这条，真正随机访问，不整本 mmap）
    constructor(source: RandomAccessSource) {
        this.source = source
        size = source.size
        address = null
        checkEncryptionStatus()
    }

    // SY -->
    var encrypted: Boolean = false
        private set
    var wrongPassword: Boolean? = null
        private set
    // 每 reader 实例唯一（用于 ArchivePageLoader 建临时目录）；mmap 路径取 mmap 地址，回调路径取 source 实例哈希
    val archiveHashCode: Int
        get() = address?.hashCode() ?: source!!.hashCode()
    // SY <--

    private fun newStream(encrypted: Boolean): ArchiveInputStream =
        if (address != null) ArchiveInputStream(address, size, encrypted)
        else ArchiveInputStream(source!!, encrypted)

    inline fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T =
        newStream(encrypted).use { block(generateSequence { it.getNextEntry() }) }

    fun getInputStream(entryName: String): InputStream? {
        val archive = newStream(encrypted)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    // SY -->
    private fun checkEncryptionStatus() {
        val archive = newStream(false)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.isEncrypted) {
                    encrypted = true
                    isPasswordIncorrect(entry.name)
                    break
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
    }

    private fun isPasswordIncorrect(entryName: String) {
        try {
            getInputStream(entryName).use { stream ->
                stream!!.read()
            }
        } catch (e: ArchiveException) {
            if (e.message == "Incorrect passphrase") {
                wrongPassword = true
                return
            }
            throw e
        }
        wrongPassword = false
    }
    // SY <--

    override fun close() {
        if (address != null) Os.munmap(address, size)
        source?.close()
    }
}

fun UniFile.archiveReader(context: Context): ArchiveReader {
    val path = filePath
    if (!path.isNullOrEmpty()) {
        // SY --> Phase2: 真实文件走 LocalRandomAccessSource 回调路径，真机验证 libarchive seek 架构；
        //             SAF / content uri 仍走原 mmap 路径，行为不变
        return ArchiveReader(LocalRandomAccessSource(File(path)))
        // SY <--
    }
    return openFileDescriptor(context, "r").use { ArchiveReader(it) }
}
