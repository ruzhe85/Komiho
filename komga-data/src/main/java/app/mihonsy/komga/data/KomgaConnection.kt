package app.mihonsy.komga.data

/**
 * Komga 服务器连接配置。
 * 认证方式：
 *  - API_KEY: X-API-Key header（推荐，独立认证无需用户名密码）
 *  - BASIC: Authorization: Basic base64(user:pass)
 *  - SESSION: 登录后通过 X-Auth-Token / cookie 复用会话（登录接口返回）
 */
enum class KomgaAuthType { API_KEY, BASIC }

data class KomgaConnection(
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
