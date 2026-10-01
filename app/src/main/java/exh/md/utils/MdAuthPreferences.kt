package exh.md.utils

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Komiho: MangaDex 登录 token 的最小偏好封装。
 * 存储沿用旧的 track_token_60 key，
 * 这里保留同样的存储 key（track_token_60），以兼容既有登录态。
 */
class MangaDexAuthPreferences(preferenceStore: PreferenceStore) {
    val trackToken: Preference<String> = preferenceStore.getString(
        Preference.privateKey("track_token_60"),
        "",
    )
}

@Serializable
data class MALOAuth(
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresIn: Long,
    @SerialName("created_at")
    @EncodeDefault
    val createdAt: Long = System.currentTimeMillis() / 1000,
) {
    // Assumes expired a minute earlier
    private val adjustedExpiresIn: Long = (expiresIn - 60)

    fun isExpired() = createdAt + adjustedExpiresIn < System.currentTimeMillis() / 1000
}
