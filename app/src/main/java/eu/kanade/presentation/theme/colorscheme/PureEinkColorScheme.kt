package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Komiho: E-Ink 皮肤（外观 → 主题 →「电子墨水」），也是**唯一**一套 e-ink 皮肤。
 *
 * 历史：早期还有一套「原始档位版」（`background` 设成 0xEE / 14 级，靠 1 级色差表达
 * 层级）。14 级与 15 级在 16 级面板上几乎分辨不出，却会让整页背景被抖动成网点 ——
 * 既没换来层级，又在无卡片的页面（设置页一类）上直接表现为一整块灰。那套已下线，
 * 只保留本文件这套墨水屏 UI 的通行做法：**只有黑与白**，层级靠线条与反色表达。
 *
 * 规则：
 * 1. **所有容器色单一值**：`background`、五个 `surfaceContainer*` 与 `surface`
 *    在浅色下全为纯白、深色下全为纯黑。页面内部不再出现任何大面积色块。
 * 2. **层级靠描边**：`outline` 用纯黑（深色下纯白）画 1px 线，`outlineVariant`
 *    用 0x88（8 级）画弱分隔。控件（开关轨道、输入框）在白底上由描边轮廓可见。
 * 3. **强调靠反色**：按钮 / 选中态（导航栏 pill、chip）走黑底白字（深色下白底黑字），
 *    不用彩色也不用中灰 —— 彩色量化到 16 级可能落进中灰，"选中/未选中"就分辨不出。
 * 4. **中灰只出现在"线和字"上**：`onSurfaceVariant`（次要文字，浅色 0x44 /
 *    深色 0xCC）与 `outlineVariant`（0x88）。二者面积小，不构成色块，不会脏。
 * 5. 错误态同样走反色（`error` = 黑），语义由图标与文案承担，不引入红色。
 */
internal object PureEinkColorScheme : BaseColorScheme() {

    private val black = Color(0xFF000000)
    private val white = Color(0xFFFFFFFF)

    /** 8 级 —— 弱分隔线（`outlineVariant`）。 */
    private val gray8 = Color(0xFF888888)

    /** 4 级 —— 浅色下的次要文字（`onSurfaceVariant`）。 */
    private val gray4 = Color(0xFF444444)

    /** 12 级 —— 深色下的次要文字（`onSurfaceVariant`）。 */
    private val gray12 = Color(0xFFCCCCCC)

    override val lightScheme: ColorScheme = lightColorScheme(
        primary = black,
        onPrimary = white,
        primaryContainer = black,
        onPrimaryContainer = white,
        inversePrimary = white,

        secondary = black,
        onSecondary = white,
        secondaryContainer = black,
        onSecondaryContainer = white,

        tertiary = black,
        onTertiary = white,
        tertiaryContainer = black,
        onTertiaryContainer = white,

        error = black,
        onError = white,
        errorContainer = white,
        onErrorContainer = black,

        // 规则 1：页面底色与面板同色，页面内不再有大面积灰。
        background = white,
        onBackground = black,
        surface = white,
        onSurface = black,
        surfaceVariant = white,
        onSurfaceVariant = gray4,

        surfaceContainerLowest = white,
        surfaceContainerLow = white,
        surfaceContainer = white,
        surfaceContainerHigh = white,
        surfaceContainerHighest = white,

        // 规则 2：边界交给描边。
        outline = black,
        outlineVariant = gray8,

        scrim = black,
        inverseSurface = black,
        inverseOnSurface = white,

        surfaceDim = white,
        surfaceBright = white,
    )

    override val darkScheme: ColorScheme = darkColorScheme(
        primary = white,
        onPrimary = black,
        primaryContainer = white,
        onPrimaryContainer = black,
        inversePrimary = black,

        secondary = white,
        onSecondary = black,
        secondaryContainer = white,
        onSecondaryContainer = black,

        tertiary = white,
        onTertiary = black,
        tertiaryContainer = white,
        onTertiaryContainer = black,

        error = white,
        onError = black,
        errorContainer = black,
        onErrorContainer = white,

        background = black,
        onBackground = white,
        surface = black,
        onSurface = white,
        surfaceVariant = black,
        onSurfaceVariant = gray12,

        surfaceContainerLowest = black,
        surfaceContainerLow = black,
        surfaceContainer = black,
        surfaceContainerHigh = black,
        surfaceContainerHighest = black,

        outline = white,
        outlineVariant = gray8,

        scrim = black,
        inverseSurface = white,
        inverseOnSurface = black,

        surfaceDim = black,
        surfaceBright = black,
    )
}
