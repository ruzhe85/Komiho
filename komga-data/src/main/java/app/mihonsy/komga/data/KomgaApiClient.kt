package app.mihonsy.komga.data

import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.LibraryDto
import app.mihonsy.komga.data.model.PageDto
import app.mihonsy.komga.data.model.PageableDto
import app.mihonsy.komga.data.model.ReadProgressDto
import app.mihonsy.komga.data.model.ReadProgressUpdateDto
import app.mihonsy.komga.data.model.ReadingListDto
import app.mihonsy.komga.data.model.SeriesDto
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer
import okio.source
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Komga REST API 客户端（重写，非插件机制）。
 * 以 Komga 为唯一真源，所有方法直接对应当前服务器的实时状态。
 */
class KomgaApiClient(
    private val connection: KomgaConnection,
    private val client: OkHttpClient = defaultOkHttp(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private var authToken: String? = null
    private var cookies: List<String> = emptyList()

    init {
        require(connection.baseUrl.isNotBlank()) { "服务器地址不能为空" }
    }

    // ---------- 认证 ----------

    /** 登录 / 验证凭据，成功返回当前用户（非 null 表示可用）。 */
    suspend fun login(): String? {
        return withIOContext {
            val request = Request.Builder()
                .url(apiUrl("/api/v1/login"))
                .apply { authHeaders() }
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) null else body
            }
        }
    }

    /** 测试连接：尝试获取库列表，成功即连接有效。 */
    suspend fun testConnection(): Result<Unit> {
        return runCatching { getLibraries() }.map { }
    }

    // ---------- 库 ----------

    suspend fun getLibraries(): List<LibraryDto> {
        return get("/api/v1/libraries", ListSerializer())
    }

    // ---------- 系列 ----------

    suspend fun getSeries(
        libraryId: String? = null,
        search: String? = null,
        readStatus: String? = null,
        genre: String? = null,
        tag: String? = null,
        page: Int = 0,
        size: Int = 60,
        sort: String? = null,
    ): PageableDto<SeriesDto> {
        val params = buildMap {
            libraryId?.let { put("library_id", it) }
            search?.let { put("search", it) }
            readStatus?.let { put("read_status", it) }
            genre?.let { put("genre", it) }
            tag?.let { put("tag", it) }
            put("page", page.toString())
            put("size", size.toString())
            sort?.let { put("sort", it) }
        }
        return get("/api/v1/series", PageableSerializer(), params)
    }

    suspend fun getSeriesDetail(seriesId: String): SeriesDto {
        return get("/api/v1/series/$seriesId", ObjectSerializer())
    }

    suspend fun getSeriesBooks(
        seriesId: String,
        page: Int = 0,
        size: Int = 100,
    ): PageableDto<BookDto> {
        return get("/api/v1/series/$seriesId/books", PageableSerializer(), mapOf("page" to page.toString(), "size" to size.toString()))
    }

    // ---------- 书 ----------

    suspend fun getBook(bookId: String): BookDto {
        return get("/api/v1/books/$bookId", ObjectSerializer())
    }

    suspend fun getBookPages(bookId: String): List<PageDto> {
        return get("/api/v1/books/$bookId/pages", ListSerializer())
    }

    /** 页图片 URL（阅读器加载用，需认证） */
    fun pageImageUrl(bookId: String, pageNumber: Int, raw: Boolean = false): String {
        val suffix = if (raw) "/raw" else ""
        return apiUrl("/api/v1/books/$bookId/pages/$pageNumber$suffix")
    }

    /** 封面 URL */
    fun seriesThumbnailUrl(seriesId: String): String {
        return apiUrl("/api/v1/series/$seriesId/thumbnail")
    }

    fun bookThumbnailUrl(bookId: String): String {
        return apiUrl("/api/v1/books/$bookId/thumbnail")
    }

    // ---------- 进度 ----------
    // 读取进度在 book 详情（BookDto.readProgress）；这里只写回。
    suspend fun updateReadProgress(bookId: String, page: Int, completed: Boolean = false) {
        postJson(
            "/api/v1/books/$bookId/read-progress",
            ReadProgressUpdateDto(page = page, completed = completed),
            ReadProgressUpdateDto.serializer(),
        )
    }

    // ---------- 阅读列表 ----------

    suspend fun getReadlists(): List<ReadingListDto> {
        return get("/api/v1/readlists", ListSerializer())
    }

    suspend fun getReadlist(id: String): ReadingListDto {
        return get("/api/v1/readlists/$id", ObjectSerializer())
    }

    // ---------- 底层 ----------

    /**
     * 下载带认证的二进制（封面/页图片）。
     * 由调用方决定用什么解码（BitmapFactory / Coil）。
     */
    suspend fun downloadBytes(url: String): ByteArray {
        return withIOContext {
            val request = Request.Builder()
                .url(apiUrl(url))
                .apply { authHeaders() }
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw KomgaException("下载失败（${resp.code}）")
                resp.body?.source()?.buffer()?.readByteArray() ?: throw KomgaException("响应为空")
            }
        }
    }

    private fun apiUrl(path: String): String {
        val base = connection.baseUrl.trimEnd('/')
        return if (path.startsWith("http")) path else "$base$path"
    }

    private fun HttpUrl.Builder.addParams(params: Map<String, String>): HttpUrl.Builder {
        params.forEach { (k, v) -> addQueryParameter(k, v) }
        return this
    }

    private fun Request.Builder.authHeaders(): Request.Builder {
        when (connection.authType) {
            KomgaAuthType.API_KEY -> {
                if (connection.apiKey.isNotBlank()) {
                    addHeader("X-API-Key", connection.apiKey)
                }
            }
            KomgaAuthType.BASIC -> {
                addHeader("Authorization", Credentials.basic(connection.username, connection.password))
            }
        }
        authToken?.let { addHeader("X-Auth-Token", it) }
        if (cookies.isNotEmpty()) {
            addHeader("Cookie", cookies.joinToString("; "))
        }
        return this
    }

    private suspend fun <T> get(
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        params: Map<String, String> = emptyMap(),
    ): T {
        return withIOContext {
            val urlBuilder = apiUrl(path).toHttpUrl().newBuilder().apply { addParams(params) }
            val request = Request.Builder()
                .url(urlBuilder.build())
                .apply { authHeaders() }
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                handleResponse(resp) { body ->
                    json.decodeFromString(serializer, body)
                } ?: throw KomgaException("响应为空")
            }
        }
    }

    private suspend fun <T> postJson(path: String, body: T, serializer: kotlinx.serialization.KSerializer<T>) {
        withIOContext {
            val request = Request.Builder()
                .url(apiUrl(path).toHttpUrl())
                .apply { authHeaders() }
                .post(json.encodeToString(serializer, body).toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { resp ->
                handleResponse(resp) { null }
            }
        }
    }

    private fun <T> handleResponse(resp: Response, parse: (String) -> T?): T? {
        if (!resp.isSuccessful) {
            logcat(LogPriority.ERROR, tag = "KomgaApi") { "HTTP ${resp.code} ${resp.message}" }
            if (resp.code == 401) {
                throw KomgaAuthException("认证失败（401），请检查 API Key 或账号密码")
            }
            if (resp.code >= 500) {
                throw KomgaException("服务器错误（${resp.code}）")
            }
            throw KomgaException("请求失败（${resp.code}）")
        }
        // 保存会话 token（如返回）
        resp.header("X-Auth-Token")?.let { authToken = it }
        resp.headers("Set-Cookie").let { if (it.isNotEmpty()) cookies = it }
        val body = resp.body?.source()?.buffer()?.readUtf8().orEmpty()
        return if (body.isBlank()) null else parse(body)
    }

    companion object {
        fun defaultOkHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // serializer helpers（用 reified 类型参数）
    private inline fun <reified T> ListSerializer(): kotlinx.serialization.KSerializer<List<T>> =
        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer())

    private inline fun <reified T> PageableSerializer(): kotlinx.serialization.KSerializer<PageableDto<T>> =
        PageableDto.serializer(kotlinx.serialization.serializer())

    private inline fun <reified T> ObjectSerializer(): kotlinx.serialization.KSerializer<T> =
        kotlinx.serialization.serializer()
}

class KomgaException(message: String) : Exception(message)
class KomgaAuthException(message: String) : Exception(message)
