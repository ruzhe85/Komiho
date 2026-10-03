package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Komiho: E-Ink 专用黑白皮肤（应用设置 → 外观 → E-Ink 模式时自动启用）。
 *
 * 与 [MonochromeColorScheme] 的区别，也是本文件存在的理由：后者把五个
 * `surfaceContainer*` 全设成同一个白/黑，层级**只**靠 Material 的 tonal elevation
 * 表达 —— 那在 LCD 上是连续灰、在 16 级墨水屏上却会把相邻层级量化进同一级，
 * 卡片 / 对话框 / 底部栏会互相糊成一片。
 *
 * 因此这里的取值全部落在**面板 16 级的真实档位**上（KCC 的 `Palette16` 是
 * `0x00, 0x11, 0x22, …, 0xFF`，步长 17）：
 *
 * | 档位 | 值     | 级 |
 * |------|--------|----|
 * | 0    | 0x00   | 0  |
 * | 4    | 0x44   | 4  |
 * | 8    | 0x88   | 8  |
 * | 12   | 0xCC   | 12 |
 * | 14   | 0xEE   | 14 |
 * | 15   | 0xFF   | 15 |
 *
 * 规则：
 * 1. **只用这 6 档**，不碰相邻档（相邻级差仅 17/255，在墨水屏上基本分辨不出）。
 * 2. **相邻语义元素至少差 3 级**（0x44 = 68/255 ≈ 4 级）——`onSurface` 与
 *    `surface` 是 15 级对 0 级，正文对比拉满；次要文字用 0x44（差 11 级）。
 * 3. **层级用 `outline` 描边表达，不用 tonal 渐变**：五个 container 统一为
 *    0xFF / 0x00，靠 1px 描边（`outline` 0x88、`outlineVariant` 0xCC）区分边界。
 *    Material3 的 elevation 主要是 tonal 的（无阴影），低 elevation 阴影量化后
 *    接近背景、脏块风险低，所以不必去改各组件的 `cardElevation`。
 * 4. **选中态用明暗反转**（黑底白字），不用彩色强调色 —— 彩色量化到 16 级后
 *    可能落进中灰，"选中/未选中"就分辨不出。
 * 5. **背景不用纯白**：`0xEE`（14 级）。纯白在墨水屏上因纸张反光率有限而显得
 *    发灰且更留残影；卡片用 `0xFF` 纯白，靠描边与背景分开。
 * 6. 错误态也走反色（`error` = 0x00），不引入红色 —— 语义靠图标与文案承担。
 *
 * 附带效果：E-Ink 模式会强制浅色（反射式屏在环境光下白底更清楚）并禁用 AMOLED
 * （墨水屏没有 OLED 的省电优势，AMOLED 黑底反而更像"脏灰"）。本类仍提供
 * [darkScheme]，以便系统在深色偏好下也给出可读的高对比反相配色。
 */
internal object EinkColorScheme : BaseColorScheme() {

    private val black = Color(0xFF000000)
    private val white = Color(0xFFFFFFFF)

    /** 12 级 —— 浅描边、分隔线。 */
    private val gray12 = Color(0xFFCCCCCC)

    /** 8 级 —— 描边、禁用态前景。 */
    private val gray8 = Color(0xFF888888)

    /** 4 级 —— 次要文字。 */
    private val gray4 = Color(0xFF444444)

    /** 14 级 —— 页面背景（不用纯白，见规则 5）。 */
    private val gray14 = Color(0xFFEEEEEE)

    override val lightScheme: ColorScheme = lightColorScheme(
        primary = black,
        onPrimary = white,
        primaryContainer = black,
        onPrimaryContainer = white,
        inversePrimary = white,

        secondary = gray4,
        onSecondary = white,
        secondaryContainer = gray12,
        onSecondaryContainer = black,

        tertiary = gray8,
        onTertiary = white,
        tertiaryContainer = gray12,
        onTertiaryContainer = black,

        error = black,
        onError = white,
        errorContainer = white,
        onErrorContainer = black,

        background = gray14,
        onBackground = black,
        surface = white,
        onSurface = black,
        surfaceVariant = gray14,
        onSurfaceVariant = gray4,

        // 规则 3：层级靠描边，不靠 tonal 渐变 —— 五个 container 全部同色。
        surfaceContainerLowest = white,
        surfaceContainerLow = white,
        surfaceContainer = white,
        surfaceContainerHigh = white,
        surfaceContainerHighest = white,

        outline = gray8,
        outlineVariant = gray12,

        scrim = black,
        inverseSurface = black,
        inverseOnSurface = white,

        surfaceDim = gray12,
        surfaceBright = white,
    )

    override val darkScheme: ColorScheme = darkColorScheme(
        primary = white,
        onPrimary = black,
        primaryContainer = white,
        onPrimaryContainer = black,
        inversePrimary = black,

        secondary = gray12,
        onSecondary = black,
        secondaryContainer = gray8,
        onSecondaryContainer = white,

        tertiary = gray8,
        onTertiary = black,
        tertiaryContainer = gray4,
        onTertiaryContainer = white,

        error = white,
        onError = black,
        errorContainer = white,
        onErrorContainer = black,

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

        outline = gray8,
        outlineVariant = gray4,

        scrim = black,
        inverseSurface = white,
        inverseOnSurface = black,

        surfaceDim = black,
        surfaceBright = gray4,
    )
}
