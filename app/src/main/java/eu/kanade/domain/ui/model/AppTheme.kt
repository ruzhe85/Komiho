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
     * Komiho: E-Ink 专用黑白皮肤（见 [PureEinkColorScheme]）—— 也是**唯一**一套 e-ink 皮肤。
     *
     * 与 [MONOCHROME] 的区别：色值对齐面板 16 级的真实档位，`background` 与面板同色
     * （浅色全白 / 深色全黑），页面内不出现大面积中间灰，层级只靠描边与反色表达。
     * 原先那套「原始档位版」（0xEE 背景、靠 tonal 层级）已下线 —— 它在有卡片的页面上
     * 仍会出现中间灰，与墨水屏的实际观感不符。
     *
     * 「外观」里的 E-Ink 模式开关会自动切到这一项并强制浅色 + 禁 AMOLED；
     * 不开模式时也可以单独选它。
     */
    EINK_PURE(MR.strings.theme_eink),

    // Deprecated
    DARK_BLUE(null),
    HOT_PINK(null),
    BLUE(null),

    // SY -->
    PURE_RED(null),
    // SY <--
}
