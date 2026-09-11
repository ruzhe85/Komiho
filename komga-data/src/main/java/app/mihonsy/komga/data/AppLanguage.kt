package app.mihonsy.komga.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 按「应用语言」（[KomgaPreferences.appLanguage]）包装 context；空 = 跟随系统，原样返回。
 *
 * 为什么需要：Komiho 的界面大多走 ComponentActivity（不像 AppCompatActivity 那样由
 * AppCompatDelegate 自动套用 per-app locale），而数据层 / source 拿到的是**未包装的
 * application context** —— 直接 `context.getString(...)` / `stringResource(...)` 会取到
 * **系统语言**的资源。表现：应用内把语言切成中文，来自 source 的提示仍是英文。
 */
fun Context.withAppLanguage(): Context {
    val tag = KomgaPreferences(this).appLanguage
    if (tag.isEmpty()) return this
    val config = Configuration(resources.configuration)
    config.setLocale(Locale.forLanguageTag(tag))
    return createConfigurationContext(config)
}
