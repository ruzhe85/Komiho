package app.mihonsy.komga.data.smb

import com.hierynomus.msdtyp.FileTime
import com.hierynomus.smbj.share.File
import mihon.core.common.archive.RandomAccessSource
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import java.io.IOException
import kotlin.concurrent.Volatile

// SY --> Komiho Phase7：SMB 远程随机读取源 —— [RandomAccessSource] 的 SMB 实现。
//
// 与 [WebDavRandomAccessSource] 的关键差异（也是本类更简单的原因）：
// - **原生随机读**：SMB 的 `File.read(buf, fileOffset)` 直接按偏移取数据，不需要
//   「探测服务器是否支持 Range」「不支持就整本下载」那套回退，也没有 rar/7z
//   必须整本缓存的限制（rar/7z 走 libarchive 回调时同样按偏移读，只是请求更碎）。
// - **有状态会话**：连接/会话由 [SmbSessionManager] 池化，本类只持有打开的文件句柄；
//   读写异常时作废会话，下次访问自动重连。
// - 块缓存：不做。RemoteZipReader 每次按条目精确取所需区间，块缓存对它无收益；
//   libarchive 回调路径（256KB/次）由 SMB 自身的传输缓冲吸收。单次读上限 [MAX_READ]
//   兼顾「请求数」与「单次响应内存」。
//
// 契约（与 WebDAV 实现一致）：read 可返回少于 length；越界返回空数组不抛异常。
class SmbRandomAccessSource(
    private val conn: SmbConnection,
    private val password: String,
    /** 共享内相对路径（`/` 分隔，已含连接起始目录）。 */
    private val relPath: String,
) : RandomAccessSource {

    /** 规范化定位串（页缓存按它分书目录，与 webdav 的 URL 同角色）。 */
    val normalizedUrl: String = "${conn.location()}\\$relPath".replace('/', '\\')

    /**
     * 远程文件指纹（`size:lastWriteEpoch`）。页缓存以此判断文件是否被替换；
     * null = 取不到（共享不支持查询）→ 调用方不启用页缓存，安全降级。
     */
    @Volatile
    var remoteFingerprint: String? = null
        private set

    private val lock = Any()

    @Volatile
    private var closed = false

    private var file: File? = null
    private var total = -1L

    override val size: Long
        get() {
            ensureOpen()
            return total
        }

    override fun read(offset: Long, length: Int): ByteArray {
        ensureOpen()
        val f = file ?: throw IOException("SMB 文件未打开: $normalizedUrl")
        synchronized(lock) {
            if (closed) throw IOException("SMB source closed")
            if (offset < 0) throw IOException("非法 offset: $offset")
            if (offset >= total) return ByteArray(0)

            val want = minOf(length.toLong(), MAX_READ.toLong(), total - offset).toInt()
            val buf = ByteArray(want)
            val n = try {
                f.read(buf, offset)
            } catch (e: Exception) {
                // 会话可能已被 NAS 断开：作废后抛出，调用方重试即触发重连（见 ChapterLoader 兜底）
                SmbSessionManager.invalidate(conn)
                throw IOException("SMB 读取失败 @$offset: ${e.message}", e)
            }
            return when {
                n <= 0 -> ByteArray(0)
                n == buf.size -> buf
                else -> buf.copyOf(n)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // 只关文件句柄；会话/共享由 SmbSessionManager 池化复用。
            runCatching { file?.close() }
            file = null
        }
    }

    /** 懒打开：首次 size/read 时建立会话、打开文件并取指纹。 */
    private fun ensureOpen() {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            if (closed) throw IOException("SMB source closed")
            val f = SmbSessionManager.openFile(conn, password, relPath)
            file = f
            val info = try {
                f.getFileInformation()
            } catch (e: Exception) {
                // 拿不到文件大小绝不能静默置 0：size=0 会让 RemoteZipReader 读 EOCD「未找到」、
                // libarchive 回落同样空，最终表现成误导性的「没有图片」。快速失败报真实原因。
                logcat(LogPriority.WARN) { "[Smb] 文件信息查询失败: ${e.message}" }
                throw IOException("SMB 文件信息查询失败（大小未知）: ${e.message}", e)
            }
            total = info.standardInformation.endOfFile
            remoteFingerprint = info.basicInformation.lastWriteTime.toEpochMillis()
                .let { "$total:$it" }
        }
    }

    /** Windows FILETIME（100ns，1601 起）→ epoch ms。 */
    private fun FileTime.toEpochMillis(): Long =
        (getWindowsTimeStamp() - WINDOWS_TO_UNIX_EPOCH) / 10_000L

    private companion object {
        /** 单次读上限 1MB：多数 NAS 的 MaxReadSize ≥ 1MB，兼顾请求数与内存。 */
        const val MAX_READ = 1024 * 1024
        const val WINDOWS_TO_UNIX_EPOCH = 116_444_736_000_000_000L
    }
}
// SY <--
