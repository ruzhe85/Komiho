package app.mihonsy.komga.data

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho: 聚合页（来源仪表盘）的显示设置。
 *
 * 目前只有一项：每个来源卡片里列出几条「最近阅读」记录（1..10，默认 3）。
 * 用 [PreferenceStore] 而非 SharedPreferences，是为了让聚合页能用
 * `changes()` 流直接跟随改动刷新，不必手动打通设置页 → 聚合页的回调。
 */
object DashboardPreferences {

    const val RECENT_DEFAULT = 3

    /** 可选条数（设置页单选）。 */
    val RECENT_OPTIONS = listOf(1, 3, 5, 10)

    private val prefs: PreferenceStore by lazy { Injekt.get() }

    val recentLimit: Preference<Int> by lazy {
        prefs.getInt("dashboard_recent_limit", RECENT_DEFAULT)
    }

    fun limitValue(): Int = recentLimit.get().coerceIn(1, 10)

    fun setLimit(value: Int) = recentLimit.set(value.coerceIn(1, 10))
}
