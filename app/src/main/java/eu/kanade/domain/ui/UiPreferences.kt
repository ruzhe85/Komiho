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

    /**
     * 阅读器是否按墨水屏灰阶渲染漫画位图。仅影响位图链路，App UI 不受影响。
     *
     * Komiho (2026-10-10): **默认关**。解耦后它不再是「E-Ink 模式的子项」而是独立滤镜
     * （见 [isEinkGrayscaleActive]），沿用 `true` 会让**普通模式**的用户也默认被灰阶化 ——
     * 那是回归。开 E-Ink 模式时由 `App.onCreate` 的联动自动勾上；存量 E-Ink 用户由那里的
     * 幂等对齐补一次。
     */
    val einkRenderGrayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_eink_render_grayscale", false)

    /**
     * 彩色页是否保持彩色。彩色墨水屏（Spectra 6 一类）上这是更好的选择；
     * 黑白屏无副作用（黑白页的彩色像素占比接近 0）。
     */
    val einkKeepColorPages: Preference<Boolean> = preferenceStore.getBoolean("pref_eink_keep_color_pages", true)

    /** 灰阶量化级数：4 / 8 / 16。16 是各家的通用档（KCC 的 Palette16、KoReader 移植文档同）。 */
    val einkGrayLevels: Preference<Int> = preferenceStore.getInt("pref_eink_gray_levels", 16)

    // Komiho (2026-10-04): E-Ink 收敛前的**用户原值**备份。
    //
    // E-Ink 模式要把阅读器调成墨水屏友好的样子（白底 + 无翻页动画），但用户原来的选择不能丢：
    // 开启时先把当前值快照到这里，关闭 E-Ink 时原样写回并清掉（见 `EinkReaderDefaults.sync`）。
    // 判据是 `isSet()` —— 备份存在即代表这一项**当前正被 E-Ink 改写**，此时 sync 只保证 E-Ink
    // 的目标值仍然生效、**不会更新备份**，所以用户在 E-Ink 期间手改的值不会污染「原值」。
    //
    // 注意：这四个只是 E-Ink 的现场快照，不是业务偏好，别当垃圾数据清理。

    /** E-Ink 前的阅读器背景（`ReaderPreferences.readerTheme`：0 白 / 1 黑 / 2 灰 / 3 自动）。 */
    val einkBackupReaderTheme: Preference<Int> = preferenceStore.getInt("pref_eink_backup_reader_theme", 0)

    /** E-Ink 前的页模式「翻页动画」（`ReaderPreferences.pageTransitionsPager`）。 */
    val einkBackupPageTransitionsPager: Preference<Boolean> =
        preferenceStore.getBoolean("pref_eink_backup_page_transitions_pager", true)

    /** E-Ink 前的条漫「翻页动画」v1（`ReaderPreferences.pageTransitionsWebtoon`）。 */
    val einkBackupPageTransitionsWebtoon: Preference<Boolean> =
        preferenceStore.getBoolean("pref_eink_backup_page_transitions_webtoon", true)

    /** E-Ink 前的条漫「翻页动画」v2（`ReaderPreferences.pageTransitionsWebtoonV2`）。 */
    val einkBackupPageTransitionsWebtoonV2: Preference<Boolean> =
        preferenceStore.getBoolean("pref_eink_backup_page_transitions_webtoon_v2", false)

    /**
     * E-Ink 模式是否**应当生效**。动画链路与 Coil 请求会高频读它，所以放一个派生属性
     * 免得每处都写两个条件的与。
     */
    val isEinkModeActive: Boolean get() = einkMode.get()

    /** E-Ink 模式下动画是否应归零。 */
    val isEinkAnimationOff: Boolean get() = einkMode.get() && einkDisableAnimation.get()

    /**
     * 阅读器是否应做灰阶渲染。
     *
     * Komiho (2026-10-10): 与 E-Ink 模式**解耦**。它现在是一个独立的滤镜开关（设置 → 滤镜），
     * 普通模式也能开；E-Ink 主开关只做**双向联动**（开时自动勾上、关时自动关闭，见 `App.onCreate`
     * 对 `einkMode` 的订阅），用户之后可以自行改回去，改动不会被覆盖。
     *
     * 为什么不再强制：灰阶化是**主观**的（量化必然让画面变糙），和「关动画」那种纯收益不同，
     * 所以 E-Ink 激活期间允许用户把它关掉 —— 此时画面交给面板自身去色，不做曲线与量化优化。
     */
    val isEinkGrayscaleActive: Boolean get() = einkRenderGrayscale.get()

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
