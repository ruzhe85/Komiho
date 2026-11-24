package app.mihonsy.komga.data.cloud115

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import mihon.core.common.archive.Cloud115AuthFailure
import mihon.core.common.archive.Cloud115RandomAccessSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import tachiyomi.core.common.util.system.logcat

/**
 * Komiho：115 开放平台令牌维护。
 *
 * 只有一条链路：`refresh_token` → `POST passportapi/open/refreshToken` →
 * `access_token`（约 2 小时）+ **新的 refresh_token**（一次性的，旧的立即作废）。
 *
 * 三条必须守住的规则（否则用户会莫名被要求重新授权）：
 *  1. 刷新成功后**立刻原子落盘**（[Cloud115ConnectionStore.updateTokens]）；
 *  2. 同一连接的刷新必须串行（[locks]），并发刷新会互相覆盖、白扔一个有效令牌；
 *  3. 启动时**不要**无脑刷新 —— access_token 没过期就复用，否则会撞 `40140117`
 *     「刷新过于频繁」，那是按账号/IP 计的冷却。
 */
object Cloud115Auth {

    private const val REFRESH_URL = "https://passportapi.115.com/open/refreshToken"

    /** 拿不到 expires_in 时的保守默认值（官方约 7200 秒）。 */
    private const val DEFAULT_TTL_SECONDS = 7200L

    /** 这些 code 说明 refresh_token 本身不能用了 —— 只能引导重新授权。 */
    private val REAUTH_CODES = setOf(
        "40140114",
        "40140115",
        "40140116",
        "40140119",
        "40140120",
    )

    /** 各失效码的人话解释（来自 Simple 115 的实测总结），直接展示给用户。 */
    private val REAUTH_HINTS = mapOf(
        "40140114" to "刷新令牌格式不对（多半是复制时被截断了，注意别漏字符）",
        "40140115" to "刷新令牌签名校验失败（内容被改动过）",
        "40140116" to "刷新令牌已失效（已在别处刷新过，或已解除授权）",
        "40140119" to "刷新令牌已过期",
        "40140120" to "刷新令牌校验失败",
    )

    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * 令牌类失败。
     *
     * 继承 core 的 [Cloud115AuthFailure]（而不是裸 IOException），这样
     * `Cloud115RandomAccessSource` 能识别「授权问题」并**直接失败**，
     * 不会把它当成直链过期去重试（重试只会再打一次刷新接口，撞 `40140117`）。
     */
    class AuthException(
        message: String,
        needsReauth: Boolean,
        cause: Throwable? = null,
    ) : Cloud115AuthFailure(message, needsReauth, cause)

    /**
     * 取当前可用的 access_token（同步；供 core.common 的直链解析器直接调用）。
     *
     * @param force 忽略本地缓存强制刷新（收到 401/40140125 时用）。
     */
    fun accessTokenBlocking(connId: String, force: Boolean = false): String {
        val snapshot = Cloud115ConnectionStore.get(connId)
            ?: throw AuthException("115 连接不存在（可能已删除）", needsReauth = true)
        if (!force && isFresh(snapshot)) return snapshot.accessToken

        val lock = locks.getOrPut(connId) { Any() }
        synchronized(lock) {
            // 双检：等锁期间可能已经被另一个线程刷新过。
            val current = Cloud115ConnectionStore.get(connId)
                ?: throw AuthException("115 连接不存在（可能已删除）", needsReauth = true)
            // force 也要过这道：多个页面/封面同时撞 401 时，先到的那个已经换到新 token，
            // 后面的再刷一次只会白白作废一个有效 refresh_token（还可能撞 40140117）。
            if (current.accessToken != snapshot.accessToken && isFresh(current)) {
                return current.accessToken
            }
            if (!force && isFresh(current)) return current.accessToken
            return refreshBlocking(current)
        }
    }

    /**
     * access_token 是否可直接复用。
     *
     * 不看「还剩多少秒」：保存时已经按 110 分钟记过期时刻（留了 10 分钟余量），
     * 这里只判断有没有过期。提前判断只会诱发不必要的刷新（每次刷新都作废旧 refresh_token）。
     */
    private fun isFresh(conn: Cloud115Connection): Boolean = conn.isAccessUsable()

    private fun refreshBlocking(conn: Cloud115Connection): String {
        val refreshToken = Cloud115ConnectionStore.refreshTokenFor(conn.id)
            ?: throw AuthException("115 授权信息缺失，请重新获取刷新令牌", needsReauth = true)

        val form = "refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
        val request = Request.Builder()
            .url(REFRESH_URL)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", Cloud115Api.USER_AGENT)
            .build()

        val text = try {
            Cloud115RandomAccessSource.sharedHttpClient().newCall(request).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw AuthException("115 授权已失效（HTTP ${resp.code}），请重新获取令牌", true)
                }
                if (!resp.isSuccessful) {
                    throw AuthException("刷新 115 令牌失败 HTTP ${resp.code}", needsReauth = false)
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: AuthException) {
            throw e
        } catch (e: IOException) {
            throw AuthException("刷新 115 令牌失败：${e.message}", needsReauth = false, e)
        }

        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AuthException("115 刷新响应无法解析", needsReauth = false)
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val newRefresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: DEFAULT_TTL_SECONDS

        if (access.isBlank()) {
            val code = obj["code"]?.jsonPrimitive?.contentOrNull
                ?: obj["errno"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val needsReauth = code in REAUTH_CODES
            // 双写：logcat + 应用内 DiagLog（用户导出的是 DiagLog 缓冲，只有 logcat 等于没日志）。
            eu.kanade.tachiyomi.diagnostic.DiagLog.w(
                "Cloud115Auth",
                "刷新令牌失败 conn=${conn.id} code=$code msg=$message",
            )
            logcat(LogPriority.WARN) { "[Cloud115] 刷新令牌失败 code=$code msg=$message" }
            throw AuthException(
                when {
                    // 频控与服务端令牌状态无关，不要去重新授权（重取一份也一样会被限）。
                    code == "40140117" ->
                        "115 提示「刷新令牌过于频繁」（服务端频控，与令牌新旧无关），请等几分钟再试"
                    needsReauth -> buildString {
                        append("115 刷新令牌失败：")
                        append(REAUTH_HINTS[code] ?: "刷新令牌已失效")
                        append("\n请到 api.oplist.org 重新获取一份。")
                        append("注意：同一份刷新令牌不要同时给 OpenList 和本程序用 —— ")
                        append("谁先刷新，另一个手里的就作废了。")
                    }
                    message.isNotBlank() -> "115 拒绝刷新令牌：$message（code=$code）"
                    else -> "115 拒绝刷新令牌（code=$code）"
                },
                needsReauth = needsReauth,
            )
        }

        Cloud115ConnectionStore.updateTokens(
            id = conn.id,
            accessToken = access,
            newRefreshToken = newRefresh,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
        )
        return access
    }
}
