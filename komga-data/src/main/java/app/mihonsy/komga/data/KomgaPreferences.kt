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

    /** One of "title"/"lastModified"/"lastRead"/"dateAdded", plus ",asc"/",desc". */
    var librarySort: String
        get() = prefs.getString(KEY_LIBRARY_SORT, "title,asc").orEmpty()
        set(v) = prefs.edit().putString(KEY_LIBRARY_SORT, v).apply()

    /** Home tab: how many series/books each section shows. Default 10, max 50. */
    var homeSectionLimit: Int
        get() = prefs.getInt(KEY_HOME_SECTION_LIMIT, 10).coerceIn(1, 50)
        set(v) = prefs.edit().putInt(KEY_HOME_SECTION_LIMIT, v.coerceIn(1, 50)).apply()

    /**
     * Home tab: how many items per row in GRID layout.
     * 0 = auto (FlowRow decides), otherwise a fixed column count (2..8).
     */
    var homeGridColumns: Int
        get() = prefs.getInt(KEY_HOME_GRID_COLUMNS, 0).coerceIn(0, 8)
        set(v) = prefs.edit().putInt(KEY_HOME_GRID_COLUMNS, v.coerceIn(0, 8)).apply()

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
        const val KEY_LIBRARY_DISPLAY_MODE = "library_display_mode"
        const val KEY_BOOK_DISPLAY_MODE = "book_display_mode"
        const val KEY_LIBRARY_SORT = "library_sort"
        const val KEY_LIBRARY_PORTRAIT_COLUMNS = "library_portrait_columns"
        const val KEY_LIBRARY_LANDSCAPE_COLUMNS = "library_landscape_columns"
        const val KEY_HOME_SECTION_LIMIT = "home_section_limit"
        const val KEY_HOME_GRID_COLUMNS = "home_grid_columns"
        const val KEY_HOME_SECTION_ORDER = "home_section_order"
        const val KEY_HOME_SECTION_LAYOUT = "home_section_layout"
        const val KEY_READER_DOUBLE_PAGE = "reader_double_page"
        const val KEY_READER_MODE = "reader_mode"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_APP_THEME = "app_theme"
        const val KEY_THEME_DARK_AMOLED = "theme_dark_amoled"
        const val KEY_APP_LANGUAGE = "app_language"
    }
}
