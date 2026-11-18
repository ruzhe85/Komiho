package eu.kanade.tachiyomi.util.lang

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * [readAllBytesCompact] 的边界测试（issue #16）。
 *
 * 重点锁住这些易错分支：
 * - 预分配长度与实际内容不一致（远端文件被换/截短）时不能截断或补零；
 * - `InputStream.read()` 返回 0 表示真实的 0x00 字节而非 EOF，拼接时不能丢；
 * - 跨 64KB 分块边界的内容必须完整。
 */
class ByteStreamExtensionsTest {

    @Test
    fun `exact hint returns identical bytes`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(data, ByteArrayInputStream(data).readAllBytesCompact(data.size.toLong()))
    }

    @Test
    fun `no hint preserves bytes and embedded zeros`() {
        val data = byteArrayOf(0, 1, 0, 2, 0)
        assertArrayEquals(data, ByteArrayInputStream(data).readAllBytesCompact())
    }

    @Test
    fun `hint smaller than content keeps the whole stream`() {
        // 预分配只覆盖前 3 字节，第 4 字节恰好是 0x00：
        // 曾把增长分支写成 `if (extra > 0)`，会丢掉这个合法零字节、损坏图片。
        val data = byteArrayOf(9, 8, 7, 0, 6, 5)
        assertArrayEquals(data, ByteArrayInputStream(data).readAllBytesCompact(3))
    }

    @Test
    fun `hint larger than content trims to actual length`() {
        val data = byteArrayOf(1, 2, 3)
        assertArrayEquals(data, ByteArrayInputStream(data).readAllBytesCompact(10))
    }

    @Test
    fun `empty stream returns empty array`() {
        assertTrue(ByteArrayInputStream(ByteArray(0)).readAllBytesCompact().isEmpty())
        assertTrue(ByteArrayInputStream(ByteArray(0)).readAllBytesCompact(10).isEmpty())
    }

    @Test
    fun `multi chunk content over 64k preserved`() {
        val data = ByteArray(150_000) { (it % 251).toByte() }
        assertArrayEquals(data, ByteArrayInputStream(data).readAllBytesCompact())
        assertArrayEquals(data, ByteArrayInputStream(data).readAllBytesCompact(data.size.toLong()))
    }

    @Test
    fun `hint growth spanning chunk boundary preserved`() {
        val data = ByteArray(100_000) { (it % 256).toByte() }
        // 预估 64KB、实际 10 万：走「读满后再探 EOF + 分块拼接」的罕见分支。
        val out = ByteArrayInputStream(data).readAllBytesCompact(65_536L)
        assertEquals(data.size, out.size)
        assertArrayEquals(data, out)
    }
}
