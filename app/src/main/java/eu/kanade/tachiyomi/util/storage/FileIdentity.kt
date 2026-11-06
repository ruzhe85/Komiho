package eu.kanade.tachiyomi.util.storage

/**
 * Komiho (2026-11-06): 文件身份 —— 与位置无关的稳定标识。
 *
 * 为什么不能直接用路径/文件名：本地改个名、SMB/WebDAV 挪个目录，`chapters.url` 就失效，
 * 进度与历史全断。也不能用 SAF 的 documentId（externalstorage provider 里它就是路径）
 * 或 WebDAV 的 ETag（取决于服务器实现，SMB 没有）。
 *
 * 只用**文件大小 + 修改时间（秒）**：
 * - 移动/改名：两者都不变 → key 不变 → 进度跟得上；
 * - 内容被替换/重新下载：mtime 变 → 新 key → 进度归零（符合预期）；
 * - 不需要读文件内容：本地一次 stat、WebDAV/SMB 直接用列表里已有的字段，
 *   远程目录**零额外请求**（这也是不掺 CRC 的原因：SAF 下每文件一次 provider IPC，
 *   几百个文件就是几秒，收益却只是防一种几乎不存在的碰撞）。
 *
 * 已知取舍：同一文件的副本会共享进度（内容相同本来就该共享）；两个**不同**文件
 * 恰好大小相同且 mtime 到秒也相同才会串（实际只出现在批量复制的同名副本上）。
 */
object FileIdentity {

    private const val PREFIX = "v1"

    /**
     * 计算 content_key；[size] 不可用（<= 0）时返回 null —— 读不到的条目不能共用一个 key，
     * 否则所有异常项会串成一团。
     */
    fun of(size: Long, lastModified: Long): String? {
        // 归一到秒：File.lastModified 是毫秒，但 SAF/WebDAV 有些 provider 只给到秒，
        // 不归一的话同一个文件在两种访问方式下会算出两个 key。
        val mtimeSec = if (lastModified > 0L) lastModified / 1000L else 0L
        // size 缺失（SAF 某些 provider 对文档返回 0）时用 mtime 兜底 —— 列表和打开入口读的是
        // 同一个 UniFile，两边会同时是 0，所以仍然能对上。两边都为 0 时才放弃（否则所有条目
        // 会共用一个 key，进度互相串）。
        if (size <= 0L && mtimeSec == 0L) return null
        return "$PREFIX:${size.coerceAtLeast(0L)}:$mtimeSec"
    }
}
