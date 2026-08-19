package app.mihonsy.komga.data

import app.mihonsy.komga.data.model.BookDto
import app.mihonsy.komga.data.model.CollectionDto
import app.mihonsy.komga.data.model.LibraryDto
import app.mihonsy.komga.data.model.PageDto
import app.mihonsy.komga.data.model.PageableDto
import app.mihonsy.komga.data.model.ReadProgressDto
import app.mihonsy.komga.data.model.ReadProgressUpdateDto
import app.mihonsy.komga.data.model.ReadingListDto
import app.mihonsy.komga.data.model.SeriesDto
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    /** Recently updated series — GET /api/v1/series/updated (official endpoint, mirrors the web UI). */
    suspend fun getUpdatedSeries(size: Int = 200): List<SeriesDto> {
        val params = buildMap { put("size", size.toString()) }
        val page: PageableDto<SeriesDto> = get("/api/v1/series/updated", PageableSerializer(), params)
        return page.content
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

    /** Next book in the series — GET /api/v1/books/{id}/next. null = no next. */
    suspend fun getBookNext(bookId: String): BookDto? {
        return runCatching { get<BookDto>("/api/v1/books/$bookId/next", ObjectSerializer()) }.getOrNull()
    }

    /** Previous book in the series — GET /api/v1/books/{id}/previous. null = none. */
    suspend fun getBookPrevious(bookId: String): BookDto? {
        return runCatching { get<BookDto>("/api/v1/books/$bookId/previous", ObjectSerializer()) }.getOrNull()
    }

    /** Global books list (Home "recently added books" / "recently read books"). */
    suspend fun getBooks(
        sort: String? = null,
        size: Int = 20,
        page: Int = 0,
        readStatus: String? = null,
    ): PageableDto<BookDto> {
        val params = buildMap {
            put("page", page.toString())
            put("size", size.toString())
            sort?.let { put("sort", it) }
            readStatus?.let { put("read_status", it) }
        }
        return get("/api/v1/books", PageableSerializer(), params)
    }

    /**
     * Continue-reading books — GET /api/v1/books/ondeck. This is the data
     * source behind the Komga web UI's "Continue Reading" row (books with
     * a read progress, next up to read), NOT series?read_status=IN_PROGRESS.
     */
    suspend fun getBooksOnDeck(size: Int = 20): List<BookDto> {
        val params = buildMap {
            put("size", size.toString())
        }
        val page: PageableDto<BookDto> = get("/api/v1/books/ondeck", PageableSerializer(), params)
        return page.content
    }

    suspend fun getBookPages(bookId: String): List<PageDto> {
        return get("/api/v1/books/$bookId/pages", ListSerializer())
    }

    /** 页图片 URL（阅读器加载用，需认证） */
    fun pageImageUrl(bookId: String, pageNumber: Int, raw: Boolean = false): String {
        val suffix = if (raw) "/raw" else ""
        return apiUrl("/api/v1/books/$bookId/pages/$pageNumber$suffix")
    }

    /**
     * Cover thumbnail URLs. NOTE: Komga's thumbnail endpoints accept NO size
     * query parameters (openapi confirmed — /series/{id}/thumbnail and
     * /books/{id}/thumbnail have only the id path param). A ?height= hint is
     * silently ignored, so the URL must stay identical to what the Komga web
     * UI uses. Thumbnail resolution is fixed by what the server generated;
     * to get sharper covers the server must regenerate them
     * (PUT /api/v1/books/thumbnails?for_bigger_result_only=true, ADMIN).
     */
    fun seriesThumbnailUrl(seriesId: String): String {
        return apiUrl("/api/v1/series/$seriesId/thumbnail")
    }

    fun bookThumbnailUrl(bookId: String): String {
        return apiUrl("/api/v1/books/$bookId/thumbnail")
    }

    // ---------- 进度 ----------
    // 读取进度在 book 详情（BookDto.readProgress）；这里只写回。
    // Komiho fix: Komga updates read progress via PATCH (the handoff doc §4
    // says PATCH; the previous code used POST → 404/405, so progress never
    // synced to the server — exactly what the user observed).
    suspend fun updateReadProgress(bookId: String, page: Int, completed: Boolean = false) {
        patchJson(
            "/api/v1/books/$bookId/read-progress",
            ReadProgressUpdateDto(page = page, completed = completed),
            ReadProgressUpdateDto.serializer(),
        )
    }

    /** Removes the read progress of a book (Komga's "mark as unread"). */
    suspend fun deleteReadProgress(bookId: String) {
        withIOContext {
            val request = Request.Builder()
                .url(apiUrl("/api/v1/books/$bookId/read-progress").toHttpUrl())
                .apply { authHeaders() }
                .delete()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw KomgaException("清除进度失败（${resp.code}）")
            }
        }
    }

    // ---------- 阅读列表 ----------

    // Komiho fix: /readlists returns PageableDto<ReadingListDto> (same shape as
    // /series), NOT a bare array. The previous ListSerializer() raised
    // "Expected start of array '[', but had '{'" — exactly what the user saw.
    suspend fun getReadlists(): List<ReadingListDto> {
        val page: PageableDto<ReadingListDto> = get("/api/v1/readlists", PageableSerializer())
        return page.content
    }

    // /readlists/{id} returns a single ReadingListDto (not paginated) — matches
    // the official keiyoushi extension which does parseAs<ReadListDto>() here.
    suspend fun getReadlist(id: String): ReadingListDto {
        return get("/api/v1/readlists/$id", ObjectSerializer())
    }

    /** Books inside a readlist — GET /api/v1/readlists/{id}/books?unpaged=true */
    suspend fun getReadlistBooks(readlistId: String): List<BookDto> {
        // Explicit type so the reified PageableSerializer<T> can infer T=BookDto.
        val page: PageableDto<BookDto> =
            get("/api/v1/readlists/$readlistId/books?unpaged=true", PageableSerializer())
        return page.content
    }

    /** Adds books to a readlist — POST /api/v1/readlists/{id}/books {"bookIds": [...]} */
    suspend fun addBooksToReadlist(readlistId: String, bookIds: List<String>) {
        postJson(
            "/api/v1/readlists/$readlistId/books",
            buildJsonObject { put("bookIds", JsonArray(bookIds.map { JsonPrimitive(it) })) },
            JsonObject.serializer(),
        )
    }

    // ---------- 收藏（collections） ----------

    /** GET /api/v1/collections?unpaged=true */
    suspend fun getCollections(): List<CollectionDto> {
        val page: PageableDto<CollectionDto> =
            get("/api/v1/collections?unpaged=true", PageableSerializer())
        return page.content
    }

    /** GET /api/v1/collections/{id} — single collection detail. */
    suspend fun getCollection(collectionId: String): CollectionDto {
        return get("/api/v1/collections/$collectionId", ObjectSerializer())
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
                // Komiho fix: same as handleResponse — use ResponseBody.bytes() instead
                // of source()?.buffer()?.readByteArray() which could return empty.
                resp.body?.bytes() ?: throw KomgaException("响应为空")
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

    /** Auth headers as a map, for Coil ImageRequests (covers/pages). */
    fun authHeadersMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        when (connection.authType) {
            KomgaAuthType.API_KEY -> {
                if (connection.apiKey.isNotBlank()) {
                    map["X-API-Key"] = connection.apiKey
                }
            }
            KomgaAuthType.BASIC -> {
                map["Authorization"] = Credentials.basic(connection.username, connection.password)
            }
        }
        authToken?.let { map["X-Auth-Token"] = it }
        if (cookies.isNotEmpty()) {
            map["Cookie"] = cookies.joinToString("; ")
        }
        return map
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

    /** Same as postJson but with the PATCH verb (Komga read-progress uses PATCH). */
    private suspend fun <T> patchJson(path: String, body: T, serializer: kotlinx.serialization.KSerializer<T>) {
        withIOContext {
            val request = Request.Builder()
                .url(apiUrl(path).toHttpUrl())
                .apply { authHeaders() }
                .patch(json.encodeToString(serializer, body).toRequestBody(jsonMedia))
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
        // Komiho fix: use ResponseBody.string() — the safe, documented way to read a
        // response body. The previous `body?.source()?.buffer()?.readUtf8()` chain
        // could yield an empty Buffer on some OkHttp 5 internals, which made every
        // successful response decode as blank → "响应为空" even when the server
        // returned valid JSON (confirmed against a live Komga server).
        val body = resp.body?.string().orEmpty()
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
