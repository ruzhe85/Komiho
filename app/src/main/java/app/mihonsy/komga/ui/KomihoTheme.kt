package app.mihonsy.komga.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.mihonsy.komga.data.KomgaPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.CatppuccinColorScheme
import eu.kanade.presentation.theme.colorscheme.GreenAppleColorScheme
import eu.kanade.presentation.theme.colorscheme.LavenderColorScheme
import eu.kanade.presentation.theme.colorscheme.MidnightDuskColorScheme
import eu.kanade.presentation.theme.colorscheme.MonetColorScheme
import eu.kanade.presentation.theme.colorscheme.MonochromeColorScheme
import eu.kanade.presentation.theme.colorscheme.NordColorScheme
import eu.kanade.presentation.theme.colorscheme.StrawberryColorScheme
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme
import eu.kanade.presentation.theme.colorscheme.TakoColorScheme
import eu.kanade.presentation.theme.colorscheme.TealTurqoiseColorScheme
import eu.kanade.presentation.theme.colorscheme.TidalWaveColorScheme
import eu.kanade.presentation.theme.colorscheme.YinYangColorScheme
import eu.kanade.presentation.theme.colorscheme.YotsubaColorScheme

/**
 * Komiho 全局主题：启用 Mihon 皮肤体系（AppTheme × 明暗模式 × AMOLED）。
 *
 * 设置页（SettingsTab 外观分组）修改后写入 KomgaPreferences 并 recreate Activity，
 * 本组件随 Activity 重建重组、读取最新值 → 全局即时生效（无需重启应用）。
 * 明暗模式：SYSTEM 跟随系统；LIGHT/DARK 强制切换（isSystemInDarkTheme 对应修正）。
 */
@Composable
fun KomihoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { KomgaPreferences(context.applicationContext) }
    val themeMode = prefs.themeMode // "SYSTEM" / "LIGHT" / "DARK"
    val appThemeName = prefs.appTheme // AppTheme 枚举名
    val amoled = prefs.themeDarkAmoled
    val appTheme = runCatching { AppTheme.valueOf(appThemeName) }.getOrDefault(AppTheme.DEFAULT)
    val isDark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = remember(appTheme, isDark, amoled) {
        colorSchemeFor(context, appTheme, isDark, amoled)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** AppTheme → Mihon colorscheme 映射（与 TachiyomiTheme.kt 的 colorSchemes 表一致）。 */
private fun colorSchemeFor(
    context: Context,
    appTheme: AppTheme,
    isDark: Boolean,
    isAmoled: Boolean,
): ColorScheme {
    val scheme: BaseColorScheme = when (appTheme) {
        AppTheme.MONET -> MonetColorScheme(context)
        AppTheme.CATPPUCCIN -> CatppuccinColorScheme
        AppTheme.GREEN_APPLE -> GreenAppleColorScheme
        AppTheme.LAVENDER -> LavenderColorScheme
        AppTheme.MIDNIGHT_DUSK -> MidnightDuskColorScheme
        AppTheme.MONOCHROME -> MonochromeColorScheme
        AppTheme.NORD -> NordColorScheme
        AppTheme.STRAWBERRY_DAIQUIRI -> StrawberryColorScheme
        AppTheme.TAKO -> TakoColorScheme
        AppTheme.TEALTURQUOISE -> TealTurqoiseColorScheme
        AppTheme.TIDAL_WAVE -> TidalWaveColorScheme
        AppTheme.YINYANG -> YinYangColorScheme
        AppTheme.YOTSUBA -> YotsubaColorScheme
        // DEFAULT / 废弃项（DARK_BLUE、HOT_PINK、BLUE）/ SY 的 PURE_RED：回退默认蓝。
        else -> TachiyomiColorScheme
    }
    return scheme.getColorScheme(
        isDark = isDark,
        isAmoled = isAmoled,
        overrideDarkSurfaceContainers = appTheme != AppTheme.MONET,
    )
}
