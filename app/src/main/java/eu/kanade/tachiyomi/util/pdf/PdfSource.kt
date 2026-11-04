package eu.kanade.tachiyomi.util.pdf

import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Komiho (2026-11-04): PDF 的**按需随机读取**层。
 *
 * 以前 `PdfParser` 用 `File.readBytes()` 把整份文件读进堆：352MB 的画集就是 352MB 常驻内存
 * （整章阅读期间不释放），还要和渲染位图（12–96MB/张 × 预取）抢堆，必然 OOM；而且文件多大
 * 就要多大内存，2GB 的扫描本根本打不开。
 *
 * 现在改成 mmap（堆外，走系统页缓存）+ 分块扫描：
 * - 常驻内存只随「当前访问的对象/流」增长，与文件总大小无关；
 * - 顺序扫描按 [CHUNK_BYTES] 分块读，块间保留 `needle.size - 1` 重叠，内存恒定；
 * - 超过 2GB（ByteBuffer 索引上限）的文件退回 `RandomAccessFile` 逐段读，同样不进堆。
 */
class PdfSource(path: String) : Closeable {

    private val raf = RandomAccessFile(path, "r")

    /** 文件长度；超过 Int.MAX_VALUE 时截断（2GB 上限，PDF 实际用不到）。 */
    val size: Int = raf.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private val mapped: MappedByteBuffer? = if (raf.length() <= Int.MAX_VALUE) {
        runCatching {
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }.getOrNull()
    } else {
        null
    }

    /** 读一个字节；越界返回 0（调用方都带边界判断，这里只是兜底）。 */
    fun get(index: Int): Byte {
        if (index < 0 || index >= size) return 0
        mapped?.let { return it.get(index) }
        return synchronized(raf) {
            raf.seek(index.toLong())
            raf.read().toByte()
        }
    }

    /** 读一段字节，越界自动裁剪。 */
    fun read(offset: Int, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return ByteArray(0)
        val len = minOf(length, size - offset)
        if (len <= 0) return ByteArray(0)
        val out = ByteArray(len)
        val buf = mapped
        if (buf != null) {
            // 用「设置 position + 批量 get」而不是逐字节 get：扫描 300MB 时逐字节是 3 亿次调用，
            // 批量拷贝差两个数量级。position 是共享状态，因此必须同步。
            synchronized(buf) {
                buf.position(offset)
                buf.get(out, 0, len)
            }
            return out
        }
        synchronized(raf) {
            raf.seek(offset.toLong())
            raf.readFully(out)
        }
        return out
    }

    /** 前向查找 [needle]，返回首次出现的起始偏移；找不到返回 -1。 */
    fun indexOf(needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return -1
        var base = maxOf(0, from)
        val overlap = needle.size - 1
        while (base < size) {
            val block = read(base, CHUNK_BYTES + overlap)
            if (block.isEmpty()) return -1
            val hit = indexOfBytes(block, needle)
            if (hit >= 0) return base + hit
            if (block.size <= overlap) return -1
            base += block.size - overlap
        }
        return -1
    }

    /** 反向查找最后一个 [needle]（在 [before] 之前）；找不到返回 -1。 */
    fun lastIndexOf(needle: ByteArray, before: Int = size): Int {
        if (needle.isEmpty()) return -1
        var end = minOf(before, size)
        val overlap = needle.size - 1
        while (end > 0) {
            val start = maxOf(0, end - CHUNK_BYTES - overlap)
            val block = read(start, end - start)
            if (block.isNotEmpty()) {
                val hit = lastIndexOfBytes(block, needle)
                if (hit >= 0) return start + hit
            }
            if (start == 0) return -1
            end = start + overlap
        }
        return -1
    }

    /** [offset] 处是否以 [needle] 开头。 */
    fun startsWith(needle: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + needle.size > size) return false
        for (i in needle.indices) {
            if (get(offset + i) != needle[i]) return false
        }
        return true
    }

    override fun close() {
        runCatching { raf.close() }
    }

    private companion object {
        /** 扫描块大小：4MB 块 + 重叠窗口，顺序扫一个 300MB 文件只有几十次读。 */
        const val CHUNK_BYTES = 4 * 1024 * 1024

        fun indexOfBytes(hay: ByteArray, needle: ByteArray): Int {
            val last = hay.size - needle.size
            var i = 0
            while (i <= last) {
                if (hay[i] == needle[0]) {
                    var j = 1
                    while (j < needle.size && hay[i + j] == needle[j]) j++
                    if (j == needle.size) return i
                }
                i++
            }
            return -1
        }

        fun lastIndexOfBytes(hay: ByteArray, needle: ByteArray): Int {
            var i = hay.size - needle.size
            while (i >= 0) {
                var j = 0
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
                i--
            }
            return -1
        }
    }
}
