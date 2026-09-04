package app.mihonsy.komga.data.webdav

import kotlinx.serialization.Serializable

// SY --> Komiho Phase4：WebDAV 连接 profile。
//
// 一个连接 = 一台 WebDAV 服务器的访问凭据（名称 + base URL + Basic Auth）。
// 章节 URL 由此演进为 `webdav://<connId>/<完整http(s) URL>`（见 [WebDavConnectionStore]），
// 同一服务器上的多个归档共享凭据，换/加服务器不再互相覆盖。
@Serializable
data class WebDavConnection(
    /** 短随机 id（8 位 hex），章节 URL 引用它；连接改名不改 id，旧章节不受影响。 */
    val id: String,
    /** 显示名称（如「115」「NAS 局域网」）。 */
    val name: String,
    /** base URL，如 `https://dav.example.com:10007/QNAP2`（目录浏览与相对路径拼接的根）。 */
    val baseUrl: String,
    /** Basic Auth 用户名，空串 = 匿名。 */
    val user: String,
    /** Basic Auth 密码，[WebDavCredentialCrypto] 加密后的落盘形态（enc1: 前缀）；空串 = 匿名。 */
    val passEnc: String,
) {
    fun displayName(): String = if (name.isBlank()) baseUrl else name
}
// SY <--
