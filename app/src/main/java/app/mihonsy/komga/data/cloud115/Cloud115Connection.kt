package app.mihonsy.komga.data.cloud115

import kotlinx.serialization.Serializable

/**
 * Komiho：115 网盘来源的连接 profile（开放平台 OpenAPI 认证）。
 *
 * 与 WebDAV/SMB 的差别：115 没有「服务器地址」，只有一份授权（refresh_token）和一个
 * 起始目录 id。章节 URL 演进为
 * `115://<userId>/<parentCid>/<pickCode>/<fileName>`（见 [Cloud115ConnectionStore]）。
 *
 * **章节 URL 用 [userId] 而不是 [id]**：多设备各自授权时 token 不同、本机 id 也不一样，
 * 但 `user_id` 是账号级的 —— 用它做键，历史 / 进度 / 页缓存 / 封面缓存天然跨设备一致，
 * 同步时不需要改写 URL。本机 id 只用于 UI 与令牌存储。
 */
@Serializable
data class Cloud115Connection(
    /** 本机短随机 id（8 位 hex），只用于 UI 与令牌存取，**不进章节 URL**。 */
    val id: String,
    /** 显示名称（如「我的 115」）。 */
    val name: String,
    /**
     * 115 账号 id（`/open/user/info` 的 `data.user_id`）。
     *
     * 章节 URL 的主键，也是同步合并的主键。空串表示尚未取到（旧数据），
     * 此时 [Cloud115ConnectionStore.toChapterUrl] 会退回本机 id。
     */
    val userId: String = "",
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

    /** 章节 URL 的主键：优先账号级 [userId]，旧数据没取到时退回本机 [id]。 */
    fun urlOwnerId(): String = userId.ifBlank { id }

    /**
     * access_token 是否还在有效期内。
     *
     * 保存时按「现在 + 110 分钟」记（官方约 2 小时，留 10 分钟余量，与 Simple 115 同口径）。
     * **只要这里为 true 就绝不刷新** —— refresh_token 一次性，能不动就不动。
     */
    fun isAccessUsable(now: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && accessExpiresAt > now
}
