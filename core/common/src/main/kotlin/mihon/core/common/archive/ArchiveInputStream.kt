package mihon.core.common.archive

import eu.kanade.tachiyomi.util.storage.CbzCrypto
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import kotlin.math.min
import mihon.core.common.archive.ArchiveEntry as MihonArchiveEntry

class ArchiveInputStream : InputStream() {

    private val lock = Any()

    @Volatile
    private var isClosed = false

    private val archive = Archive.readNew()

    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    // 内存映射（本地 mmap）构造器
    constructor(buffer: Long, size: Long, encrypted: Boolean) : this() {
        openMemory(buffer, size, encrypted)
    }

    // 回调式（RandomAccessSource）构造器：Local / WebDAV / SMB 共用，真正随机访问
    constructor(source: RandomAccessSource, encrypted: Boolean) : this() {
        openCallback(CallbackState(source), encrypted)
    }

    private fun openMemory(buffer: Long, size: Long, encrypted: Boolean) {
        try {
            if (encrypted) Archive.readAddPassphrase(archive, CbzCrypto.getDecryptedPasswordCbz())
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenMemoryUnsafe(archive, buffer, size)
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    // SY -->
    private fun openCallback(state: CallbackState, encrypted: Boolean) {
        try {
            if (encrypted) Archive.readAddPassphrase(archive, CbzCrypto.getDecryptedPasswordCbz())
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            // 让 libarchive 通过回调从 RandomAccessSource 任意偏移拉数据（含 EOCD / 中央目录）
            Archive.readOpen2(archive, state, OPEN_CALLBACK, ReadCallback(state), SKIP_CALLBACK, CLOSE_CALLBACK)
            Archive.readSetSeekCallback(archive, SeekCallback(state))
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }
    // SY <--

    private fun read(buffer: ByteBuffer) {
        buffer.clear()
        Archive.readData(archive, buffer)
        buffer.flip()
    }

    override fun read(): Int {
        read(oneByteBuffer)
        return if (oneByteBuffer.hasRemaining()) oneByteBuffer.get().toUByte().toInt() else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val buffer = ByteBuffer.wrap(b, off, len)
        read(buffer)
        return if (buffer.hasRemaining()) buffer.remaining() else -1
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }

        Archive.readFree(archive)
    }

    fun getNextEntry(): MihonArchiveEntry? {
        return Archive.readNextHeader(archive).takeUnless { it == 0L }?.let { entry ->
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString() ?: return null
            val isFile = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
            // SY -->
            val isEncrypted = ArchiveEntry.isEncrypted(entry)
            // SY <--
            MihonArchiveEntry(
                name,
                isFile,
                // SY -->
                isEncrypted,
                // SY <--
            )
        }
    }

    // SY -->
    /**
     * 回调式读取的共享状态：libarchive 的 Read/Seek/Skip 回调都操作同一个 [position]。
     * [buffer]/[scratch] 复用，避免每页大量分配。
     */
    private class CallbackState(val source: RandomAccessSource) {
        val buffer = ByteBuffer.allocate(READ_CHUNK)
        val scratch = ByteArray(READ_CHUNK)
        var position = 0L
    }

    private class ReadCallback(private val state: CallbackState) : Archive.ReadCallback<CallbackState> {
        override fun onRead(archive: Long, data: CallbackState?): ByteBuffer {
            val st = data ?: error("null client data")
            st.buffer.clear()
            val bytes = st.source.read(st.position, READ_CHUNK)
            if (bytes.isNotEmpty()) {
                System.arraycopy(bytes, 0, st.scratch, 0, bytes.size)
                st.buffer.put(st.scratch, 0, bytes.size)
            }
            st.buffer.flip()
            st.position += bytes.size
            return st.buffer
        }
    }

    private class SeekCallback(private val state: CallbackState) : Archive.SeekCallback<CallbackState> {
        override fun onSeek(archive: Long, data: CallbackState?, offset: Long, whence: Int): Long {
            val st = data ?: error("null client data")
            st.position = when (whence) {
                SEEK_SET -> offset
                SEEK_CUR -> st.position + offset
                SEEK_END -> st.source.size + offset
                else -> throw ArchiveException("Invalid whence: $whence")
            }
            return st.position
        }
    }

    private companion object {
        // 256KB 预读块：WebDAV 阶段复用同一缓冲即可把大量小读取合并为较少的大 Range 请求
        const val READ_CHUNK = 256 * 1024
        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2

        val OPEN_CALLBACK = object : Archive.OpenCallback<CallbackState> {
            override fun onOpen(archive: Long, data: CallbackState?) {}
        }
        val CLOSE_CALLBACK = object : Archive.CloseCallback<CallbackState> {
            override fun onClose(archive: Long, data: CallbackState?) {}
        }
        val SKIP_CALLBACK = object : Archive.SkipCallback<CallbackState> {
            override fun onSkip(archive: Long, data: CallbackState?, request: Long): Long {
                val st = data ?: error("null client data")
                val remaining = (st.source.size - st.position).coerceAtLeast(0L)
                val skip = min(request, remaining)
                st.position += skip
                return skip
            }
        }
    }
    // SY <--
}
