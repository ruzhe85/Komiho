package app.mihonsy.komga.data.cloud115

import android.os.SystemClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import mihon.core.common.archive.Cloud115RandomAccessSource
import mihon.core.common.archive.Cloud115Resolved
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import tachiyomi.core.common.util.system.logcat

/**
 * Komiho：115 开放平台 OpenAPI（同步实现）。
 *
 * 为什么是同步：直链解析要被 `core/common` 的 [Cloud115RandomAccessSource] 在
 * `read()` 里同步调用（`RandomAccessSource` 是阻塞式接口）；UI 侧统一用
 * `withContext(Dispatchers.IO)` 包一层即可，不必在数据层再引一套协程。
 *
 * 端点（与 OpenList 的 `115-sdk-go` 一致）：
 *  - `GET  proapi/open/ufile/files`  —— 列目录（单页上限 1150）
 *  - `POST proapi/open/ufile/downurl` —— 取直链（`pick_code`，**明文 JSON，不需要 RSA**）
 *  - `POST passportapi/open/refreshToken` —— 见 [Cloud115Auth]
 */
object Cloud115Api {

    /**
     * 115 直链与 UA 绑定（`f=1`）：取链与读数据必须是**同一个** UA，换一个就 403。
     * 用桌面 Chrome 的 UA，和 Simple 115 / OpenList 的行为一致。
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val BASE = "https://proapi.115.com"
    private const val FILES_URL = "$BASE/open/ufile/files"
    private const val DOWN_URL = "$BASE/open/ufile/downurl"

    /** 取链最小间隔：115 对「取链」按请求频次风控（Simple 115 实测值 300ms）。 */
    private const val FETCH_INTERVAL_MS = 300L

    /** 直链内存缓存时长：一次阅读会话内够用，且不落库（直链约 3 天过期）。 */
    private const val URL_CACHE_TTL_MS = 5 * 60 * 1000L

    /**
     * 单页条数：115 单页上限 1150（OpenList 默认 200）。
     * 浏览面板只取第一页，取 1000 基本覆盖真实漫画目录；超出的情况留待后续加分页
     * （比 200 先少一次「目录被截断」的投诉）。
     */
    private const val PAGE_LIMIT = 1000

    private data class CachedUrl(val resolved: Cloud115Resolved, val at: Long)

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()
    private val throttleLock = Any()
    private var lastFetchAt = 0L

    /** access_token 过期（可刷新后重试）；与 [Cloud115Auth.AuthException] 区分。 */
    private class TokenExpired : IOException("access_token expired")

    // ------------------------------------------------------------ 列目录

    /** 列 [cid] 的直接子项（目录 + 文件）。目录在前、各自按名称自然序由调用方排序。 */
    fun listBlocking(connId: String, cid: String, offset: Int = 0, limit: Int = PAGE_LIMIT): Cloud115Page =
        withToken(connId) { token ->
            val url = FILES_URL.toHttpUrl().newBuilder()
                .addQueryParameter("cid", Cloud115ConnectionStore.normalizeCid(cid))
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("show_dir", "1")
                // 不传 cur：它的语义是「只显示当前文件夹内文件」，带上可能把子目录过滤掉。
                .addQueryParameter("o", "file_name")
                .addQueryParameter("asc", "1")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val text = execute(request, "列目录")
            parseFiles(text, offset, limit)
        }

    private fun parseFiles(text: String, offset: Int, limit: Int): Cloud115Page {
        val root = asObject(text, "列目录")
        ensureOk(root, "列目录")
        val data = root["data"] as? JsonArray ?: JsonArray(emptyList())
        val entries = data.mapNotNull { element ->
            (element as? JsonObject)?.let { Cloud115Entry.fromJson(it) }
        }
        return Cloud115Page(
            entries = entries,
            total = root.str("count")?.toIntOrNull() ?: entries.size,
            offset = root.str("offset")?.toIntOrNull() ?: offset,
            limit = root.str("limit")?.toIntOrNull() ?: limit,
        )
    }

    // ------------------------------------------------------------ 取直链

    /**
     * 用 pickcode 换直链（带节流与 5 分钟内存缓存）。
     *
     * 返回的 [Cloud115Resolved] 同时给出 URL 与额外请求头：115 的 `f=3` 文件除了 UA，
     * 还要带上取链响应里的 `Set-Cookie`，否则读数据会 403。
     */
    fun downloadBlocking(connId: String, pickCode: String, forceRefresh: Boolean = false): Cloud115Resolved {
        val key = "$connId|$pickCode"
        if (forceRefresh) {
            // 上层已判定这条直链失效（403/断流）：缓存里那份不可再用，直接丢弃重取。
            urlCache.remove(key)
        } else {
            val now = SystemClock.elapsedRealtime()
            urlCache[key]?.let { cached ->
                if (now - cached.at < URL_CACHE_TTL_MS) return cached.resolved
                urlCache.remove(key)
            }
        }
        throttle()
        val resolved = withToken(connId) { token ->
            val form = "pick_code=" + pickCode
            val request = Request.Builder()
                .url(DOWN_URL)
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .build()
            Cloud115RandomAccessSource.sharedHttpClient().newCall(request).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) throw TokenExpired()
                if (!resp.isSuccessful) throw IOException("115 取链失败 HTTP ${resp.code}")
                val text = resp.body?.string().orEmpty()
                val cookies = resp.headers.values("Set-Cookie")
                    .mapNotNull { it.substringBefore(';').trim().takeIf { c -> c.contains('=') } }
                    .joinToString("; ")
                val headers = if (cookies.isBlank()) emptyMap() else mapOf("Cookie" to cookies)
                Cloud115Resolved(url = parseDownloadUrl(text), headers = headers)
            }
        }
        urlCache[key] = CachedUrl(resolved, SystemClock.elapsedRealtime())
        return resolved
    }

    /**
     * 解析 downurl 响应。SDK 侧顶层就是 `map[fileId]{file_name,file_size,pick_code,sha1,url:{url}}`，
     * 这里同时兼容被 `data` 包裹的形态与单条目直返。
     */
    private fun parseDownloadUrl(text: String): String {
        val root = asObject(text, "取链")
        // 错误响应会带 code/message（成功时没有这两个键或 code=0）。
        // 令牌过期在 downurl 上通常走「业务 code」而不是 HTTP 401 —— 必须在这里识别，
        // 否则不会触发刷新重试，用户会看到一条莫名其妙的取链失败。
        val code = root.str("code")
        if (!code.isNullOrEmpty() && code != "0" && root["url"] == null) {
            if (isTokenErrorCode(code)) throw TokenExpired()
            throw IOException("115 取链失败 code=$code ${root.str("message").orEmpty()}")
        }
        fun urlOf(entry: JsonObject): String? =
            (entry["url"] as? JsonObject)?.get("url")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

        (root["data"] as? JsonObject)?.let { data ->
            urlOf(data)?.let { return it }
            firstEntryUrl(data)?.let { return it }
        }
        urlOf(root)?.let { return it }
        firstEntryUrl(root)?.let { return it }
        throw IOException("115 取链响应里没有 url")
    }

    private fun firstEntryUrl(obj: JsonObject): String? {
        for ((_, value) in obj) {
            val entry = value as? JsonObject ?: continue
            (entry["url"] as? JsonObject)?.get("url")?.let { url ->
                runCatching { url.jsonPrimitive.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------ 测试连接

    /** 「测试连接」用：列一次根目录，能列出即授权有效。失败抛异常（含可读原因）。 */
    fun testConnection(connId: String): Int {
        val page = listBlocking(connId, Cloud115ConnectionStore.get(connId)?.rootCid ?: "0", limit = 20)
        return page.entries.size
    }

    // ------------------------------------------------------------ 内部

    /** 执行请求并返回响应体文本；401/403 记为令牌过期。 */
    private fun execute(request: Request, what: String): String =
        Cloud115RandomAccessSource.sharedHttpClient().newCall(request).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw TokenExpired()
            if (!resp.isSuccessful) throw IOException("115 $what 失败 HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }

    /** 带令牌执行；命中 [TokenExpired] 时强制刷新一次再重试。 */
    private fun <T> withToken(connId: String, block: (String) -> T): T {
        val token = Cloud115Auth.accessTokenBlocking(connId)
        return try {
            block(token)
        } catch (e: TokenExpired) {
            logcat(LogPriority.DEBUG) { "[Cloud115] access_token 失效，刷新后重试 conn=$connId" }
            try {
                block(Cloud115Auth.accessTokenBlocking(connId, force = true))
            } catch (e2: TokenExpired) {
                // 刷新过还是过期：别把私有异常（文案 "access_token expired"）丢给 UI，
                // 换成一条能照着做事的提示。
                throw Cloud115Auth.AuthException(
                    "115 授权已失效，请在来源管理里重新获取刷新令牌",
                    needsReauth = true,
                    cause = e2,
                )
            }
        }
    }

    /** 115 的令牌类业务错误码（400 系）：刷新后重试仍失败就说明授权没了。 */
    private fun isTokenErrorCode(code: String): Boolean =
        code == "40140125" || code == "40140116" || code == "40140119" || code == "40140120"

    private fun asObject(text: String, what: String): JsonObject =
        runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IOException("115 $what 响应无法解析")

    /** 业务错误码检查（成功 = code 缺省或 "0"；state=false/0 也算失败）。 */
    private fun ensureOk(root: JsonObject, what: String) {
        val code = root.str("code")
        if (!code.isNullOrEmpty() && code != "0") {
            if (isTokenErrorCode(code)) throw TokenExpired()
            throw IOException("115 $what 失败 code=$code ${root.str("message").orEmpty()}")
        }
        val state = root["state"]
        val ok = when {
            state == null -> true
            else -> runCatching { state.jsonPrimitive.content }.getOrNull()?.let { it == "1" || it == "true" } ?: true
        }
        if (!ok) {
            throw IOException("115 $what 失败 ${root.str("message").orEmpty()}")
        }
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    /** 节流：两次取链至少隔 [FETCH_INTERVAL_MS]（跨线程全局串行）。 */
    private fun throttle() {
        synchronized(throttleLock) {
            val now = SystemClock.elapsedRealtime()
            val wait = FETCH_INTERVAL_MS - (now - lastFetchAt)
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastFetchAt = SystemClock.elapsedRealtime()
        }
    }

    /** 连接被删除 / 换了授权时清掉直链缓存。 */
    fun invalidateCache(connId: String? = null) {
        if (connId == null) {
            urlCache.clear()
        } else {
            urlCache.keys.removeAll { it.startsWith("$connId|") }
        }
    }
}
