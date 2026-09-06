package app.mihonsy.komga.data.smb

import kotlinx.serialization.Serializable

// SY --> Komiho Phase7：SMB 连接 profile。
//
// 一个连接 = 一台 SMB 服务器的访问凭据（名称 + 主机/端口 + 共享名 + 域/用户/密码）。
// 与 WebDAV 同构：章节 URL 记为 `smb://<connId>/<共享内相对路径>`（见 [SmbConnectionStore]），
// 凭据按 connId 精确取，换/加服务器不再互相覆盖。
//
// 差异点：SMB 会话**有状态**（连接 → 会话 → 树连接），由 [SmbSessionManager] 统一
// 池化与重连；WebDAV 无状态，每次请求独立。
@Serializable
data class SmbConnection(
    /** 短随机 id（8 位 hex），章节 URL 引用它；连接改名不改 id，旧章节不受影响。 */
    val id: String,
    /** 显示名称（如「家庭 NAS」「书房台式机」）。 */
    val name: String,
    /** 主机名或 IP（如 `192.168.1.10`）。 */
    val host: String,
    /** TCP 端口，默认 445。 */
    val port: Int = DEFAULT_PORT,
    /** 共享名（不含前后反斜杠，如 `manga`）。 */
    val share: String,
    /** 共享内的起始目录（统一用 `/` 分隔存储；下发前转 SMB 反斜杠）。空 = 共享根。 */
    val path: String = "",
    /** 域（Windows 域环境用；工作组/家用 NAS 留空）。 */
    val domain: String = "",
    /** 用户名，空串 = 匿名（guest）。 */
    val user: String = "",
    /** [WebDavCredentialCrypto] 加密后的落盘形态（enc1: 前缀）；空串 = 无密码。 */
    val passEnc: String = "",
) {
    fun displayName(): String = if (name.isBlank()) location() else name

    /** `\\host\share\path` 形态，用于列表副标题与日志（不含密码）。 */
    fun location(): String = buildString {
        append("\\\\").append(host)
        if (port != DEFAULT_PORT) append(':').append(port)
        append('\\').append(share)
        val p = path.trim('/')
        if (p.isNotEmpty()) append('\\').append(p.replace('/', '\\'))
    }

    companion object {
        const val DEFAULT_PORT = 445
    }
}
// SY <--
