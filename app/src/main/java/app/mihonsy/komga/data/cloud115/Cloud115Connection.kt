package app.mihonsy.komga.data.cloud115

import kotlinx.serialization.Serializable

/**
 * Komiho：115 网盘来源的连接 profile（开放平台 OpenAPI 认证）。
 *
 * 与 WebDAV/SMB 的差别：115 没有「服务器地址」，只有一份授权（refresh_token）和一个
 * 起始目录 id。章节 URL 由此演进为
 * `115://<connId>/<parentCid>/<pickCode>/<fileName>`（见 [Cloud115ConnectionStore]）。
 */
@Serializable
data class Cloud115Connection(
    /** 短随机 id（8 位 hex），章节 URL 引用它；改名不改 id，旧章节不受影响。 */
    val id: String,
    /** 显示名称（如「我的 115」）。 */
    val name: String,
    /** 浏览起点目录 id，`0` = 根目录。 */
    val rootCid: String = "0",
    /** refresh_token 的落盘形态（[app.mihonsy.komga.data.webdav.WebDavCredentialCrypto] 加密）。 */
    val refreshTokenEnc: String,
    /**
     * access_token 明文缓存。只有约 2 小时有效期，丢了也只是多刷新一次，
     * 因此不加密（避免每次请求都走 Keystore）。
     */
    val accessToken: String = "",
    /** access_token 过期时刻（epoch ms）；0 = 未知（按已过期处理）。 */
    val accessExpiresAt: Long = 0L,
) {
    fun displayName(): String = if (name.isBlank()) "115（$rootCid）" else name

    /**
     * access_token 是否还在有效期内。
     *
     * 保存时按「现在 + 110 分钟」记（官方约 2 小时，留 10 分钟余量，与 Simple 115 同口径）。
     * **只要这里为 true 就绝不刷新** —— refresh_token 一次性，能不动就不动。
     */
    fun isAccessUsable(now: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && accessExpiresAt > now
}
