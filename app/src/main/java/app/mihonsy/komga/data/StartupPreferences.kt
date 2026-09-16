package app.mihonsy.komga.data

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho: 冷启动落点设置（入口在来源管理页的「启动」区）。
 *
 * 语义严格限定为**冷启动**：进程启动时决定一次落点。配置变更（横竖屏、主题/语言切换）
 * 与从最近任务返回由 `rememberSaveable` 保留当前 tab，**不会**重新应用本设置。
 */
object StartupPreferences {

    enum class Behavior {
        /** 停在「最近」页（默认）。 */
        RECENT,

        /** 回到上次选中来源的内容首页：文件源 = 上次访问目录，Komga = 主页。 */
        LAST_SOURCE,

        /** 直接打开上次在读的那本书并翻到上次页码；取不到时回落到「最近」页。 */
        CONTINUE_READING,
        ;

        companion object {
            /** 未知/已下线的存储值一律回退默认，避免升级后打不开主界面。 */
            fun fromId(id: String?): Behavior =
                Behavior.entries.firstOrNull { it.name == id } ?: RECENT
        }
    }

    private const val KEY = "startup_behavior"

    private val prefs: PreferenceStore by lazy { Injekt.get() }

    private val behaviorPref: Preference<String> by lazy {
        prefs.getString(KEY, Behavior.RECENT.name)
    }

    fun behavior(): Behavior = Behavior.fromId(behaviorPref.get())

    fun setBehavior(value: Behavior) {
        behaviorPref.set(value.name)
    }
}
