package app.mihonsy.komga.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json

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

    // Komiho M3.10: library shelf display mode and sort, persisted across
    // sessions — mirrors Mihon's libraryDisplayMode / librarySortingMode.
    // M3.20: mode is now one of compact/comfy/list (LibraryDisplayMode name).
    // M5: split into two independent prefs — `libraryDisplayMode` drives the
    // SERIES-level shelf (LibraryTab, collections, readlists), `bookDisplayMode`
    // drives the BOOK-level shelf (BookShelf in series/readlist/section pages).
    var libraryDisplayMode: String
        get() = prefs.getString(KEY_LIBRARY_DISPLAY_MODE, "COMPACT_GRID").orEmpty()
        set(v) = prefs.edit().putString(KEY_LIBRARY_DISPLAY_MODE, v).apply()

    /** Book-level (BookShelf) display mode — independent from the series shelf. */
    var bookDisplayMode: String
        get() = prefs.getString(KEY_BOOK_DISPLAY_MODE, "COMFORTABLE_GRID").orEmpty()
        set(v) = prefs.edit().putString(KEY_BOOK_DISPLAY_MODE, v).apply()

    /** M3.20: columns per row, 0 = auto (Adaptive). Portrait/landscape stored
     *  separately like Mihon's portraitColumns / landscapeColumns. */
    var libraryPortraitColumns: Int
        get() = prefs.getInt(KEY_LIBRARY_PORTRAIT_COLUMNS, 0).coerceIn(0, 10)
        set(v) = prefs.edit().putInt(KEY_LIBRARY_PORTRAIT_COLUMNS, v.coerceIn(0, 10)).apply()

    var libraryLandscapeColumns: Int
        get() = prefs.getInt(KEY_LIBRARY_LANDSCAPE_COLUMNS, 0).coerceIn(0, 10)
        set(v) = prefs.edit().putInt(KEY_LIBRARY_LANDSCAPE_COLUMNS, v.coerceIn(0, 10)).apply()

    /** One of "name"/"dateAdded"/"dateUpdated"/"dateRead"/"releaseDate", plus ",asc"/",desc". */
    var librarySort: String
        get() = prefs.getString(KEY_LIBRARY_SORT, "name,asc").orEmpty()
        set(v) = prefs.edit().putString(KEY_LIBRARY_SORT, v).apply()

    /** Home tab: how many series/books each section shows. Default 10, max 50. */
    var homeSectionLimit: Int
        get() = prefs.getInt(KEY_HOME_SECTION_LIMIT, 10).coerceIn(1, 50)
        set(v) = prefs.edit().putInt(KEY_HOME_SECTION_LIMIT, v.coerceIn(1, 50)).apply()

    /**
     * Home tab: how many items per row in GRID layout.
     * 0 = auto (FlowRow decides), otherwise a fixed column count (2..10).
     */
    var homeGridColumns: Int
        get() = prefs.getInt(KEY_HOME_GRID_COLUMNS, 0).coerceIn(0, 10)
        set(v) = prefs.edit().putInt(KEY_HOME_GRID_COLUMNS, v.coerceIn(0, 10)).apply()

    /**
     * Home tab: card density for every section.
     * "COMPACT_GRID" = smaller covers, "COMFORTABLE_GRID" = cover + meta,
     * "LIST" = horizontal row items (thumbnail + text). Mirrors LibraryDisplayMode.
     */
    var homeDisplayMode: String
        get() = prefs.getString(KEY_HOME_DISPLAY_MODE, "COMFORTABLE_GRID").orEmpty()
        set(v) = prefs.edit().putString(KEY_HOME_DISPLAY_MODE, v).apply()

    /**
     * Home tab: visible section order, comma-separated section names
     * (see HomeSection). Sections not in this list are hidden. Defaults to
     * all five in the natural order.
     */
    var homeSectionOrder: String
        get() = prefs.getString(
            KEY_HOME_SECTION_ORDER,
            "ContinueReading,RecentlyAddedBooks,RecentlyAddedSeries,RecentlyUpdatedSeries,RecentlyReadBooks",
        ).orEmpty()
        set(v) = prefs.edit().putString(KEY_HOME_SECTION_ORDER, v).apply()

    /**
     * Home tab: how each section's items are laid out.
     * "CAROUSEL" = horizontal swipe row (default); "GRID" = flat wrapped grid.
     */
    var homeSectionLayout: String
        get() = prefs.getString(KEY_HOME_SECTION_LAYOUT, "CAROUSEL").orEmpty()
        set(v) = prefs.edit().putString(KEY_HOME_SECTION_LAYOUT, v).apply()

    // ---- 预览图 / 封面缓存（书库设置分项）----
    /**
     * 预览图磁盘缓存上限（字节）。范围 0..500M，默认 100M。
     * App.kt 构造 Coil DiskCache 时读取此值（覆盖原硬编码 300M）。
     * 设 0 = 不使用磁盘缓存（等效"实时预览图"，每次刷新重新获取）。
     */
    var coverCacheLimitBytes: Long
        get() = prefs.getLong(KEY_COVER_CACHE_LIMIT, 100L * 1024 * 1024).coerceIn(0L, 500L * 1024 * 1024)
        set(v) = prefs.edit().putLong(KEY_COVER_CACHE_LIMIT, v.coerceIn(0L, 500L * 1024 * 1024)).apply()

    /** Reader: double-page spread mode (Comics-style side-by-side pages). */
    var readerDoublePage: Boolean
        get() = prefs.getBoolean(KEY_READER_DOUBLE_PAGE, false)
        set(v) = prefs.edit().putBoolean(KEY_READER_DOUBLE_PAGE, v).apply()

    /**
     * Reader mode: "" = auto (follow series.metadata.readingDirection:
     * WEBTOON → continuous, otherwise paged), "PAGED" / "CONTINUOUS" = user override.
     */
    var readerMode: String
        get() = prefs.getString(KEY_READER_MODE, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_READER_MODE, v).apply()

    // ---- 外观 / 皮肤（M5 启用 Mihon 皮肤）----
    // 明暗模式："SYSTEM"（跟随系统，默认）/ "LIGHT" / "DARK"。
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "SYSTEM").orEmpty()
        set(v) = prefs.edit().putString(KEY_THEME_MODE, v).apply()

    // 皮肤：AppTheme 枚举名（"DEFAULT"/"MONET"/"CATPPUCCIN"/...），默认 DEFAULT。
    var appTheme: String
        get() = prefs.getString(KEY_APP_THEME, "DEFAULT").orEmpty()
        set(v) = prefs.edit().putString(KEY_APP_THEME, v).apply()

    // 深色 AMOLED 纯黑（仅深色模式生效）。
    var themeDarkAmoled: Boolean
        get() = prefs.getBoolean(KEY_THEME_DARK_AMOLED, false)
        set(v) = prefs.edit().putBoolean(KEY_THEME_DARK_AMOLED, v).apply()

    /**
     * 应用语言："" = 跟随系统（默认），"zh-CN" / "zh-TW" / "en"。
     * 由 KomgaBaseActivity.attachBaseContext 应用（ComponentActivity 不走
     * AppCompatDelegate 的 per-app locale），选择时同时调
     * AppCompatDelegate.setApplicationLocales 以覆盖 MihonSY 的 AppCompat 页面。
     */
    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_APP_LANGUAGE, v).apply()


    // ---- 多服务器连接（JSON 列表 + 激活 id）----
    // 兼容旧版：首次访问时若没有连接列表、但存在旧的 flat 单连接字段，
    // 自动迁移成单条连接（无感升级，旧用户不丢配置）。
    private val json = Json { ignoreUnknownKeys = true }

    private var migrated = false

    fun hasConnection(): Boolean = connections().isNotEmpty()

    fun connections(): List<KomgaConnection> {
        ensureMigrated()
        val raw = prefs.getString(KEY_CONNECTIONS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<KomgaConnection>>(raw) }
            .getOrDefault(emptyList())
    }

    var activeConnectionId: String
        get() = prefs.getString(KEY_ACTIVE_CONNECTION_ID, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_ACTIVE_CONNECTION_ID, v).apply()

    /** 当前激活的连接（无则取列表第一条，都没有返回空默认）。 */
    fun connection(): KomgaConnection {
        val list = connections()
        if (list.isEmpty()) return KomgaConnection(baseUrl = "")
        return list.firstOrNull { it.id == activeConnectionId } ?: list.first()
    }

    fun getConnection(id: String): KomgaConnection? =
        connections().firstOrNull { it.id == id }

    /** 新增或更新一条连接（按 id 匹配）。若无激活连接则自动设为激活。 */
    fun saveConnection(conn: KomgaConnection) {
        val list = connections().toMutableList()
        val idx = list.indexOfFirst { it.id == conn.id }
        if (idx >= 0) list[idx] = conn else list.add(conn)
        if (activeConnectionId.isEmpty() && list.isNotEmpty()) {
            activeConnectionId = if (idx >= 0) conn.id else list.last().id
        }
        persist(list)
    }

    /** 切换激活服务器。 */
    fun setActiveConnection(id: String) {
        activeConnectionId = id
    }

    /** 删除一条连接；若删的是激活项，自动回退到列表中第一条（无则清空）。 */
    fun deleteConnection(id: String) {
        val list = connections().toMutableList()
        list.removeAll { it.id == id }
        persist(list)
        if (activeConnectionId == id) {
            activeConnectionId = list.firstOrNull()?.id.orEmpty()
        }
    }

    /** 仅清除连接数据，保留显示/主题/语言等其他偏好（修旧 clear() 误清全部的 bug）。 */
    fun clearConnections() {
        prefs.edit()
            .remove(KEY_CONNECTIONS)
            .remove(KEY_ACTIVE_CONNECTION_ID)
            .remove(KEY_BASE_URL)
            .remove(KEY_AUTH_TYPE)
            .remove(KEY_API_KEY)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .apply()
    }

    private fun persist(list: List<KomgaConnection>) {
        prefs.edit().putString(KEY_CONNECTIONS, json.encodeToString(list)).apply()
    }

    private fun ensureMigrated() {
        if (migrated) return
        migrated = true
        if (prefs.contains(KEY_CONNECTIONS)) return
        val bu = prefs.getString(KEY_BASE_URL, "").orEmpty()
        if (bu.isNotBlank()) {
            val conn = KomgaConnection(
                id = java.util.UUID.randomUUID().toString(),
                name = hostOf(bu),
                baseUrl = bu,
                authType = authType,
                apiKey = apiKey,
                username = username,
                password = password,
            )
            persist(listOf(conn))
            activeConnectionId = conn.id
        }
    }

    private fun hostOf(url: String): String {
        return runCatching {
            val u = java.net.URI(url)
            u.host.takeIf { it.isNotBlank() } ?: url
        }.getOrDefault(url)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_CONNECTIONS = "connections"
        const val KEY_ACTIVE_CONNECTION_ID = "active_connection_id"
        const val KEY_AUTH_TYPE = "auth_type"
        const val KEY_API_KEY = "api_key"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_LIBRARY_DISPLAY_MODE = "library_display_mode"
        const val KEY_BOOK_DISPLAY_MODE = "book_display_mode"
        const val KEY_LIBRARY_SORT = "library_sort"
        const val KEY_LIBRARY_PORTRAIT_COLUMNS = "library_portrait_columns"
        const val KEY_LIBRARY_LANDSCAPE_COLUMNS = "library_landscape_columns"
        const val KEY_HOME_SECTION_LIMIT = "home_section_limit"
        const val KEY_HOME_GRID_COLUMNS = "home_grid_columns"
        const val KEY_HOME_DISPLAY_MODE = "home_display_mode"
        const val KEY_HOME_SECTION_ORDER = "home_section_order"
        const val KEY_HOME_SECTION_LAYOUT = "home_section_layout"
        const val KEY_COVER_CACHE_LIMIT = "cover_cache_limit"
        const val KEY_READER_DOUBLE_PAGE = "reader_double_page"
        const val KEY_READER_MODE = "reader_mode"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_APP_THEME = "app_theme"
        const val KEY_THEME_DARK_AMOLED = "theme_dark_amoled"
        const val KEY_APP_LANGUAGE = "app_language"
    }
}
