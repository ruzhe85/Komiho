package eu.kanade.domain.ui

import android.os.Build
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UiPreferences(
    preferenceStore: PreferenceStore,
) {

    val themeMode = preferenceStore.getEnum(
        "pref_theme_mode_key",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ThemeMode.SYSTEM
        } else {
            ThemeMode.LIGHT
        },
    )

    val appTheme: Preference<AppTheme> = preferenceStore.getEnum(
        "pref_app_theme",
        if (DeviceUtil.isDynamicColorAvailable) {
            AppTheme.MONET
        } else {
            AppTheme.DEFAULT
        },
    )

    val themeDarkAmoled: Preference<Boolean> = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    val relativeTime: Preference<Boolean> = preferenceStore.getBoolean("relative_time_v2", true)

    val dateFormat: Preference<String> = preferenceStore.getString("app_date_format", "")

    val tabletUiMode: Preference<TabletUiMode> = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    val imagesInDescription: Preference<Boolean> = preferenceStore.getBoolean("pref_render_images_description", true)

    // SY --> Komiho: E-Ink 模式

    /**
     * E-Ink 模式总开关。**应用级**（不在阅读器设置里），三件事由它派生：
     * 1. 全局动画归零（见 `EinkMotion`）—— 墨水屏每次动画 = 多次全刷 = 残影 + 功耗；
     * 2. 皮肤切到 [AppTheme.EINK]（见 `EinkColorScheme`）—— 现成的 [AppTheme.MONOCHROME]
     *    把五个 surfaceContainer 全设成同色，层级只靠 tonal elevation，在 16 级面板上
     *    会量化进同一级而糊成一片；
     * 3. 阅读器按墨水屏灰阶渲染（见 `EinkGray`）—— 16 级量化 + 曲线，CPU-only 引擎。
     *
     * 开关打开时**一次性**把 [appTheme] 写成 [AppTheme.EINK]；关闭时皮肤保持不变
     * （不擅自改回用户原选），因为关掉 AMOLED 之类我们一个值都不动。
     */
    val einkMode: Preference<Boolean> = preferenceStore.getBoolean("pref_eink_mode", false)

    /**
     * E-Ink 模式下关掉全局动画。**默认开**且可独立关 —— 关动画是纯收益（无残影、省电），
     * 而灰阶化是主观的（量化必然让画面变糙），两者必须能分开取舍。
     */
    val einkDisableAnimation: Preference<Boolean> = preferenceStore.getBoolean("pref_eink_disable_animation", true)

    /** 阅读器是否按墨水屏灰阶渲染漫画位图。仅影响位图链路，App UI 不受影响。 */
    val einkRenderGrayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_eink_render_grayscale", true)

    /**
     * 彩色页是否保持彩色。彩色墨水屏（Spectra 6 一类）上这是更好的选择；
     * 黑白屏无副作用（黑白页的彩色像素占比接近 0）。
     */
    val einkKeepColorPages: Preference<Boolean> = preferenceStore.getBoolean("pref_eink_keep_color_pages", true)

    /** 灰阶量化级数：4 / 8 / 16。16 是各家的通用档（KCC 的 Palette16、KoReader 移植文档同）。 */
    val einkGrayLevels: Preference<Int> = preferenceStore.getInt("pref_eink_gray_levels", 16)

    /**
     * E-Ink 模式是否**应当生效**。动画链路与 Coil 请求会高频读它，所以放一个派生属性
     * 免得每处都写两个条件的与。
     */
    val isEinkModeActive: Boolean get() = einkMode.get()

    /** E-Ink 模式下动画是否应归零。 */
    val isEinkAnimationOff: Boolean get() = einkMode.get() && einkDisableAnimation.get()

    /** E-Ink 模式下阅读器是否应做灰阶渲染。 */
    val isEinkGrayscaleActive: Boolean get() = einkMode.get() && einkRenderGrayscale.get()

    /** E-Ink 模式下允许的灰阶级数（收敛到合法档位，避免脏值写进量化表）。 */
    fun einkGrayLevelsOrDefault(): Int = einkGrayLevels.get().takeIf { it in EINK_GRAY_LEVELS } ?: 16

    // SY <--

    // SY -->

    val expandFilters: Preference<Boolean> = preferenceStore.getBoolean("eh_expand_filters", false)

    val useNewSourceNavigation: Preference<Boolean> = preferenceStore.getBoolean("use_new_source_navigation", true)

    val bottomBarLabels: Preference<Boolean> = preferenceStore.getBoolean("pref_show_bottom_bar_labels", true)

    val showNavUpdates: Preference<Boolean> = preferenceStore.getBoolean("pref_show_updates_button", true)

    val showNavHistory: Preference<Boolean> = preferenceStore.getBoolean("pref_show_history_button", true)

    // SY <--

    companion object {
        /** 灰阶量化的合法档位。16 为主流墨水屏的真实级数。 */
        val EINK_GRAY_LEVELS = intArrayOf(4, 8, 16)

        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}
