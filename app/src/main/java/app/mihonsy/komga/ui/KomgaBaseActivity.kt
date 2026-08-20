package app.mihonsy.komga.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import app.mihonsy.komga.data.KomgaPreferences
import java.util.Locale

/**
 * Komiho 全部界面 Activity 的基类。
 *
 * 在 attachBaseContext 时按「应用语言」（KomgaPreferences.appLanguage）包装
 * base context，使语言切换对 ComponentActivity 即时生效——ComponentActivity
 * 不像 AppCompatActivity 那样自动应用 AppCompatDelegate 的 per-app locale，
 * 这是此前「语言选项没生效」的根因。
 *
 * 空语言（""）表示跟随系统，不包装。
 */
open class KomgaBaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage())
    }
}

/** 按 KomgaPreferences.appLanguage 包装 context；空 = 跟随系统，原样返回。 */
fun Context.withAppLanguage(): Context {
    val tag = KomgaPreferences(this).appLanguage
    if (tag.isEmpty()) return this
    val config = Configuration(resources.configuration)
    config.setLocale(Locale.forLanguageTag(tag))
    return createConfigurationContext(config)
}
