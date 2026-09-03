package mihon.core.common.archive

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * 基于本地真实文件的随机读取实现。
 *
 * 主要用途（Phase 2）：验证 `libarchive callback + SeekCallback` 架构在真机上对任意 ZIP
 * 随机读取的正确性（SEEK_SET / SEEK_CUR / SEEK_END 三种 whence）。
 * 验证通过后，WebDAV / SMB 复用同一个 [ArchiveReader]，无需改动 Reader / PageLoader。
 */
class LocalRandomAccessSource(file: File) : RandomAccessSource {

    private val raf = RandomAccessFile(file, "r")

    override val size: Long
        get() = raf.length()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return EMPTY
        val available = (raf.length() - offset).coerceAtLeast(0L)
        val n = min(length.toLong(), available).toInt()
        if (n == 0) return EMPTY
        val buf = ByteArray(n)
        raf.seek(offset)
        var read = 0
        while (read < n) {
            val r = raf.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    override fun close() = raf.close()

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
