package app.mihonsy.komga.data.smb

import com.hierynomus.msdtyp.FileTime
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// SY --> Komiho Phase7：SMB 目录浏览。
//
// 镜像 WebDavPropfind：列出直接子项（目录 + 归档），其余文件不显示（漫画库里没有意义）。
// 路径口径统一为 `/` 分隔的「共享内相对路径」（含连接上配置的起始目录），
// 只在真正下发到 SMB 前转反斜杠（[SmbSessionManager.toSmbPath]）。
//
// 会话断开时作废会话重试一次——这是 SMB 与无状态 WebDAV 最大的行为差异：
// 首次唤醒休眠的 NAS 常常第一次请求失败、第二次成功。

/** 目录浏览的一个条目。 */
data class SmbEntry(
    /** 显示名。 */
    val name: String,
    val isDir: Boolean,
    /** 共享内相对路径（`/` 分隔，含连接起始目录），可直接作为章节 relPath。 */
    val path: String,
    /** 字节数（目录为 0）。 */
    val size: Long = 0L,
    /** 修改时间 epoch ms；解析失败为 0，仅用于排序。 */
    val lastModified: Long = 0L,
) {
    /** 是否为支持的归档（与本地/WebDAV 同口径）。 */
    val isArchive: Boolean =
        !isDir && name.substringAfterLast('.', "").lowercase() in SMB_ARCHIVE_EXTS
}

/** 支持的归档扩展名（浏览过滤与条目判定共用）。 */
internal val SMB_ARCHIVE_EXTS = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7")

object SmbBrowse {

    /** 浏览起始路径 = 连接上配置的共享内起始目录（空 = 共享根）。 */
    fun rootPath(conn: SmbConnection): String = conn.path

    /**
     * 列出 [relPath] 的直接子项（目录在前、归档在后，各自按名称不区分大小写排序）。
     * 失败抛异常（认证失败/共享不存在/网络错误），由调用方提示。
     * 连接未配置共享（[SmbConnection.share] 为空）时走「服务器根」模式：
     * relPath 空 = 枚举全部共享（srvsvc RPC），relPath 第一段 = 共享名。
     * @param password 明文密码（匿名传空串）
     */
    suspend fun list(conn: SmbConnection, password: String, relPath: String): List<SmbEntry> =
        withContext(Dispatchers.IO) {
            try {
                listOnce(conn, password, relPath)
            } catch (e: Exception) {
                // 会话可能已断（NAS 休眠/换网）：作废后重试一次，仍失败才上抛。
                logcat(LogPriority.DEBUG) { "[Smb] 列目录失败，作废会话重试: ${e.message}" }
                SmbSessionManager.invalidate(conn)
                listOnce(conn, password, relPath)
            }
        }

    private fun listOnce(conn: SmbConnection, password: String, relPath: String): List<SmbEntry> {
        // SY: 连接未配置共享 → 根路径枚举共享列表，子路径第一段为共享名。
        if (conn.share.isBlank()) {
            val norm = relPath.trim('/')
            if (norm.isEmpty()) {
                return SmbSessionManager.listShares(conn, password).map { name ->
                    SmbEntry(name = name, isDir = true, path = name)
                }
            }
            val shareName = norm.substringBefore('/')
            val subPath = norm.substringAfter('/', "")
            return listInShare(conn, password, shareName, subPath)
        }
        return listInShare(conn, password, conn.share, relPath)
    }

    private fun listInShare(conn: SmbConnection, password: String, shareName: String, relPath: String): List<SmbEntry> {
        val share: DiskShare = SmbSessionManager.share(conn, password, shareName)
        val dirPath = SmbSessionManager.toSmbPath(relPath)
        val items = share.list(dirPath)
        val out = ArrayList<SmbEntry>(items.size)
        for (info in items) {
            val name = info.fileName
            if (name.isBlank() || name == "." || name == "..") continue
            val isDir = info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0L
            if (!isDir && name.substringAfterLast('.', "").lowercase() !in SMB_ARCHIVE_EXTS) continue
            // 条目 path 保持「与 list 入参同口径」：未配置共享的连接带共享段前缀。
            val entryPath = if (conn.share.isBlank() && shareName.isNotBlank()) {
                SmbBrowse.join(shareName, name)
            } else {
                join(relPath, name)
            }
            out += SmbEntry(
                name = name,
                isDir = isDir,
                path = entryPath,
                size = if (isDir) 0L else info.endOfFile,
                lastModified = info.lastWriteTime.toEpochMillisSafe(),
            )
        }
        out.sortWith(
            compareBy<SmbEntry> { !it.isDir }
                .thenComparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) },
        )
        return out
    }

    /** 子路径拼接（`relPath` 为空时直接是 name）。 */
    fun join(relPath: String, name: String): String {
        val base = relPath.trim('/')
        return if (base.isEmpty()) name else "$base/$name"
    }

    private fun FileTime.toEpochMillisSafe(): Long = try {
        (getWindowsTimeStamp() - 116_444_736_000_000_000L) / 10_000L
    } catch (e: Exception) {
        0L
    }

    private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L
}
// SY <--
