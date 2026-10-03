package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

enum class AppTheme(val titleRes: StringResource?) {
    DEFAULT(MR.strings.label_default),
    MONET(MR.strings.theme_monet),
    CATPPUCCIN(MR.strings.theme_catppuccin),
    GREEN_APPLE(MR.strings.theme_greenapple),
    LAVENDER(MR.strings.theme_lavender),
    MIDNIGHT_DUSK(MR.strings.theme_midnightdusk),
    NORD(MR.strings.theme_nord),
    STRAWBERRY_DAIQUIRI(MR.strings.theme_strawberrydaiquiri),
    TAKO(MR.strings.theme_tako),
    TEALTURQUOISE(MR.strings.theme_tealturquoise),
    TIDAL_WAVE(MR.strings.theme_tidalwave),
    YINYANG(MR.strings.theme_yinyang),
    YOTSUBA(MR.strings.theme_yotsuba),
    MONOCHROME(MR.strings.theme_monochrome),

    /**
     * Komiho: E-Ink 专用黑白皮肤（见 [EinkColorScheme]）。与 [MONOCHROME] 的区别是
     * 色值全部对齐面板 16 级的真实档位、且把 tonal 层级压平改用描边表达 —— 在
     * 墨水屏上 M3 那套连续灰会量化进同一级，卡片与对话框会糊成一片。
     *
     * 「外观」里的 E-Ink 模式开关会自动切到这一项并强制浅色 + 禁 AMOLED；
     * 这里保留为可单独选择的主题项，方便不开模式时也能用。
     */
    EINK(MR.strings.theme_eink),

    // Deprecated
    DARK_BLUE(null),
    HOT_PINK(null),
    BLUE(null),

    // SY -->
    PURE_RED(null),
    // SY <--
}
