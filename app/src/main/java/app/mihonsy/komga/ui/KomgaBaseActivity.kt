package app.mihonsy.komga.ui

import android.content.Context
import androidx.activity.ComponentActivity
import app.mihonsy.komga.data.withAppLanguage

/**
 * Komiho 全部界面 Activity 的基类。
 *
 * 在 attachBaseContext 时按「应用语言」（KomgaPreferences.appLanguage）包装
 * base context，使语言切换对 ComponentActivity 即时生效——ComponentActivity
 * 不像 AppCompatActivity 那样自动应用 AppCompatDelegate 的 per-app locale，
 * 这是此前「语言选项没生效」的根因。
 *
 * 空语言（""）表示跟随系统，不包装。
 *
 * 注：[withAppLanguage] 已下沉到 `komga-data`，这样 source / 数据层也能用它
 * 本地化自己的提示（否则那些提示只会跟随系统语言）。
 */
open class KomgaBaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage())
    }
}
