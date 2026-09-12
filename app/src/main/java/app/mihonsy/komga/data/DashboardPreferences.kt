package app.mihonsy.komga.data

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho: 聚合页（来源仪表盘）的显示设置。
 *
 * 「每个来源卡片里列出几条最近阅读」现在按来源独立配置（0 = 隐藏该来源卡片），
 * 设置入口在来源管理的每行眼睛开关；未单独设置过的来源回落到旧全局值
 * [recentLimit]（外观设置时代的遗留，保留作默认兜底）。
 * 用 [PreferenceStore] 而非 SharedPreferences，是为了让聚合页能用
 * `changes()` 流直接跟随改动刷新，不必手动打通设置页 → 聚合页的回调。
 */
object DashboardPreferences {

    const val RECENT_DEFAULT = 3

    /** 每来源条数上限（滑块 0..7，0 = 隐藏）。 */
    const val RECENT_MAX = 7

    /** 可选条数（旧设置页单选，仅作兼容保留）。 */
    val RECENT_OPTIONS = listOf(1, 3, 5, 10)

    private val prefs: PreferenceStore by lazy { Injekt.get() }

    val recentLimit: Preference<Int> by lazy {
        prefs.getInt("dashboard_recent_limit", RECENT_DEFAULT)
    }

    /** 聚合页刷新版本号：任意 per-source 改动时 +1，聚合页以其 changes() 为刷新 key。 */
    private val version: Preference<Int> by lazy {
        prefs.getInt("dashboard_recent_version", 0)
    }
    val versionValue: Int get() = version.get()
    fun versionChanges(): kotlinx.coroutines.flow.Flow<Int> = version.changes()

    private fun limitKey(sourceId: String) = "dashboard_recent_limit_$sourceId"

    fun limitValue(): Int = recentLimit.get().coerceIn(1, 10)

    fun setLimit(value: Int) = recentLimit.set(value.coerceIn(1, 10))

    /** 某来源的最近条数（1..7 = 显示 N 条；0 = 隐藏）。未设置过则回落旧全局值。 */
    fun limitFor(sourceId: String): Int {
        val stored = prefs.getInt(limitKey(sourceId), -1).get()
        return if (stored >= 0) stored else recentLimit.get().coerceIn(0, RECENT_MAX)
    }

    /** 写入某来源的最近条数（0 = 隐藏），并 bump 版本号让聚合页立即刷新。 */
    fun setLimitFor(sourceId: String, value: Int) {
        prefs.getInt(limitKey(sourceId), -1).set(value.coerceIn(0, RECENT_MAX))
        version.set(version.get() + 1)
    }
}
