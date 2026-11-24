package app.mihonsy.komga.data.cloud115

import android.os.SystemClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    private const val SEARCH_URL = "$BASE/open/ufile/search"
    private const val DOWN_URL = "$BASE/open/ufile/downurl"

    /** 取链最小间隔：115 对「取链」按请求频次风控（Simple 115 实测值 300ms）。 */
    private const val FETCH_INTERVAL_MS = 300L

    /** 直链内存缓存时长：一次阅读会话内够用，且不落库（直链约 3 天过期）。 */
    private const val URL_CACHE_TTL_MS = 5 * 60 * 1000L

    /** 单页条数：115 单页上限 1150（OpenList 默认 200），翻页由 [listBlocking] 负责。 */
    private const val PAGE_SIZE = 1150

    /** 防御上限：单目录最多扫这么多条（正常漫画目录远小于此，防服务端 offset 异常时死循环）。 */
    private const val MAX_SCAN_ENTRIES = 20_000

    /** 搜索接口单页条数（官方默认 20；`offset + limit` 不允许超过 10000）。 */
    private const val SEARCH_PAGE_SIZE = 200

    /** 搜索接口的硬上限：`offset + limit ≤ 10000`。 */
    private const val SEARCH_MAX = 10_000

    /** 搜索翻页的最小间隔（搜索同样吃 115 的频率限制）。 */
    private const val SEARCH_INTERVAL_MS = 200L

    private data class CachedUrl(val resolved: Cloud115Resolved, val at: Long)

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()
    private val throttleLock = Any()
    private var lastFetchAt = 0L

    /** access_token 过期（可刷新后重试）；与 [Cloud115Auth.AuthException] 区分。 */
    private class TokenExpired : IOException("access_token expired")

    // ------------------------------------------------------------ 列目录

    /**
     * 列 [cid] 的全部直接子项（目录 + 文件），自动翻页。
     *
     * Simple 115 踩过的两个坑，这里必须照做：
     *  1. 单页上限约 1150，大目录要按 `offset` 累加，一次取全让文件浏览器一屏显示；
     *  2. 服务端会**回显实际生效的 offset**，与请求值不一致说明没按 offset 翻页（被夹回首页），
     *     此时必须**丢弃本页并停止** —— 否则既重复追加又可能无限翻页。
     * 参数 `cur=1` + `count_folders=1` 也是它的取值：前者表示只看当前目录内，后者让 count 准确。
     */
    fun listBlocking(connId: String, cid: String): Cloud115Page = withToken(connId) { token ->
        val all = mutableListOf<Cloud115Entry>()
        var offset = 0
        var total = 0
        while (true) {
            val page = fetchPage(token, cid, offset)
            if (page.servedOffset != offset) {
                logcat(LogPriority.WARN) {
                    "[Cloud115] 翻页异常 cid=$cid 请求 offset=$offset 返回 ${page.servedOffset}，丢弃本页并停止"
                }
                break
            }
            all += page.entries
            total = page.total
            val got = page.entries.size
            if (got == 0 || got < PAGE_SIZE || (total > 0 && offset + got >= total)) break
            offset += got
            if (offset >= MAX_SCAN_ENTRIES) {
                logcat(LogPriority.WARN) { "[Cloud115] 目录条目超过 $MAX_SCAN_ENTRIES，停止翻页 cid=$cid" }
                break
            }
        }
        Cloud115Page(entries = all, total = if (total > 0) total else all.size, offset = 0, limit = all.size)
    }

    private class PageResult(
        val entries: List<Cloud115Entry>,
        val total: Int,
        val servedOffset: Int,
    )

    private fun fetchPage(token: String, cid: String, offset: Int): PageResult {
        val url = FILES_URL.toHttpUrl().newBuilder()
            .addQueryParameter("cid", Cloud115ConnectionStore.normalizeCid(cid))
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("show_dir", "1")
            .addQueryParameter("cur", "1")
            .addQueryParameter("count_folders", "1")
            .addQueryParameter("o", "file_name")
            .addQueryParameter("asc", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val root = asObject(execute(request, "列目录"), "列目录")
        ensureOk(root, "列目录")
        val entries = flattenEntries(root["data"])
        return PageResult(
            entries = entries,
            total = root.str("count")?.toIntOrNull() ?: entries.size,
            servedOffset = root.str("offset")?.toIntOrNull() ?: offset,
        )
    }

    /**
     * 把 `data` 摊平成条目数组。
     *
     * SDK 里 `GetFilesResp` 的 data 声明成**二维数组**（`[][]File`），而线上多数情况是一维；
     * 两种都吃，避免哪天真的回了二维就把整页条目全丢掉（表现是「目录明明有文件却显示为空」）。
     */
    private fun flattenEntries(raw: JsonElement?): List<Cloud115Entry> {
        val out = mutableListOf<Cloud115Entry>()
        fun walk(el: JsonElement?) {
            when (el) {
                is JsonArray -> el.forEach { walk(it) }
                is JsonObject -> {
                    // 少数响应会再包一层 { list: [...] }；有 list 数组时展开它，
                    // 否则把对象本身当条目解析（条目自身没有 list 字段，不会误判）。
                    val list = el["list"]
                    if (list is JsonArray) {
                        walk(list)
                    } else {
                        Cloud115Entry.fromJson(el)?.let { out.add(it) }
                    }
                }
                else -> Unit
            }
        }
        walk(raw)
        return out
    }

    // ------------------------------------------------------------ 搜索

    /**
     * 用官方搜索接口（`/open/ufile/search`）找文件，**不做本地扫描**。
     *
     * 与列目录的差异：
     *  - 参数是 `search_value` + `cid`（cid 指定在哪个目录下递归搜，`0` = 全盘）；
     *  - 返回条目字段名完全不同（`file_name/file_id/pick_code/parent_id/file_category`），
     *    由 [Cloud115Entry.fromJson] 的两套字段名兼容；
     *  - `offset + limit` 不能超过 10000。
     */
    fun searchBlocking(connId: String, keyword: String, cid: String = "0"): List<Cloud115Entry> {
        val kw = keyword.trim()
        if (kw.isBlank()) return emptyList()
        return withToken(connId) { token ->
            val all = mutableListOf<Cloud115Entry>()
            var offset = 0
            while (true) {
                val (entries, total) = fetchSearchPage(token, kw, cid, offset)
                all += entries
                val next = offset + entries.size
                // 终止条件优先看「本页是否满页」：count 缺失时 total 只有本页条数，
                // 直接拿 `next >= total` 判断会导致永远只取到第一页。
                if (entries.isEmpty() || entries.size < SEARCH_PAGE_SIZE) break
                if (total > 0 && next >= total) break
                if (next >= SEARCH_MAX) break
                offset = next
                sleepQuietly(SEARCH_INTERVAL_MS)
            }
            all
        }
    }

    private fun fetchSearchPage(
        token: String,
        keyword: String,
        cid: String,
        offset: Int,
    ): Pair<List<Cloud115Entry>, Int> {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("search_value", keyword)
            .addQueryParameter("cid", Cloud115ConnectionStore.normalizeCid(cid))
            .addQueryParameter("limit", SEARCH_PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val root = asObject(execute(request, "搜索"), "搜索")
        ensureOk(root, "搜索")
        // 同样校验 offset 回显：服务端若忽略 offset 就会一直返回第一页，
        // 不校验的话上层会重复累加同一页直到 1 万条上限（白打几十次请求）。
        val served = root.str("offset")?.toIntOrNull() ?: offset
        if (served != offset) {
            logcat(LogPriority.WARN) {
                "[Cloud115] 搜索翻页异常 请求 offset=$offset 返回 $served，丢弃本页并停止"
            }
            return emptyList<Cloud115Entry>() to 0
        }
        val entries = flattenEntries(root["data"])
        return entries to (root.str("count")?.toIntOrNull() ?: entries.size)
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

    /**
     * 「测试连接」用：只取根目录第一页，能列出即授权有效（不翻页，避免大根目录拖慢保存）。
     * 失败抛异常（含可读原因）。
     */
    fun testConnection(connId: String): Int = withToken(connId) { token ->
        val cid = Cloud115ConnectionStore.get(connId)?.rootCid ?: "0"
        fetchPage(token, cid, 0).entries.size
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

    /** 静默等待（被中断时保留中断标记，不吞异常）。 */
    private fun sleepQuietly(ms: Long) {
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

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
