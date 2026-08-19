package app.mihonsy.komga.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Komga 服务器连接配置持久化。
 * 凭据敏感信息存储于 SharedPreferences（后续可迁移到 EncryptedSharedPreferences）。
 */
class KomgaPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("komga_connection", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_BASE_URL, v).apply()

    var authType: KomgaAuthType
        get() = runCatching { KomgaAuthType.valueOf(prefs.getString(KEY_AUTH_TYPE, KomgaAuthType.API_KEY.name)!!) }
            .getOrDefault(KomgaAuthType.API_KEY)
        set(v) = prefs.edit().putString(KEY_AUTH_TYPE, v.name).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_API_KEY, v).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_USERNAME, v).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    fun hasConnection(): Boolean = baseUrl.isNotBlank()

    fun connection(): KomgaConnection = KomgaConnection(
        baseUrl = baseUrl,
        authType = authType,
        apiKey = apiKey,
        username = username,
        password = password,
    )

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_AUTH_TYPE = "auth_type"
        const val KEY_API_KEY = "api_key"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
    }
}
