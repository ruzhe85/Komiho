package app.mihonsy.komga.data

import kotlinx.serialization.Serializable

/**
 * Komga 服务器连接配置。
 * 认证方式：
 *  - API_KEY: X-API-Key header（推荐，独立认证无需用户名密码）
 *  - BASIC: Authorization: Basic base64(user:pass)
 *  - SESSION: 登录后通过 X-Auth-Token / cookie 复用会话（登录接口返回）
 */
@Serializable
enum class KomgaAuthType { API_KEY, BASIC }

/**
 * 单条 Komga 服务器连接。Komiho V2 支持多个服务器，列表存于
 * [KomgaPreferences]（JSON），[id] 为稳定唯一标识，[name] 为用户可见标签
 * （缺省时用服务器 host）。[KomgaPreferences.connection] 返回当前激活的一条。
 */
@Serializable
data class KomgaConnection(
    val id: String = "",
    val name: String = "",
    val baseUrl: String,
    val authType: KomgaAuthType = KomgaAuthType.API_KEY,
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
)

/**
 * 认证状态：成功后持有会话 token（X-Auth-Token），后续请求复用。
 */
data class KomgaSession(
    val connection: KomgaConnection,
    val authToken: String? = null,
    val cookies: List<String> = emptyList(),
)
