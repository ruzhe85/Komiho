package app.mihonsy.komga

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.mihonsy.komga.data.KomgaPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Komiho: 把「应用语言」（[KomgaPreferences.appLanguage]）同步到平台的 per-app locale。
 *
 * ## 为什么必须对齐
 *
 * [app.mihonsy.komga.data.withAppLanguage] 的 Context 包装只在 **ComponentActivity**
 * （[app.mihonsy.komga.ui.KomgaBaseActivity]）上生效。阅读器
 * `eu.kanade.tachiyomi.ui.reader.ReaderActivity` 继承的是 AppCompatActivity：它的
 * `attachBaseContext` 跑完之后，**AppCompat 会再按 per-app locale 重写一次 configuration**，
 * 把我们的包装冲掉。API 33+ 时 AppCompat 直接读框架 `LocaleManager` 的值，读不到就写入
 * **空 locale 列表**，资源于是回退**系统语言**——中文机上就是「锁死中文」。
 *
 * 症状签名（就是这次线上反馈）：应用语言设成英文后，设置 → 阅读器
 * （ComponentActivity，走 [app.mihonsy.komga.data.withAppLanguage]）显示正常英文，
 * 而阅读器里（AppCompatActivity，走 AppCompat 的 per-app locale）全是中文。
 *
 * ## 为什么要在进程启动时无条件对齐
 *
 * 这个偏好是 Komiho 自己维护的，与平台 per-app locale 是**两份状态**，会分叉：偏好可能由
 * 旧版本写入（那时还没有 `setApplicationLocales` 那一行），或者用户根本没在设置里点过语言项，
 * 于是 `setApplicationLocales` 从未被调用过。
 *
 * 修复前实测（设备 API 36，系统语言 zh-Hans-CN，偏好 "en"）：
 * ```
 * adb shell cmd locale get-app-locales cn.ruzhe.komiho   →  []
 * ```
 * 只把平台值设成 `en` 后，阅读器立刻变英文——据此确认根因。所以这里每次进程启动对齐一次。
 *
 * ## 实现选择
 *
 * - **API 33+**：直接写框架 [LocaleManager]。AppCompat 的 configuration 本来以它为准，且绕开了
 *   AppCompat 内部状态是否就绪的问题。
 * - **API < 33**：走 AppCompat 自己的存储（`AppCompatDelegate.setApplicationLocales`）。
 *
 * 空串 = 跟随系统，写空 locale 列表即清除覆盖（与设置页里选择「跟随系统」语义一致）。
 *
 * 失败只记日志不抛出：文案语言不对远好过启动即崩。
 */
fun Context.applyAppLanguageToPlatform() {
    val tag = KomgaPreferences(this).appLanguage
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(tag)
                }
        } else {
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tag)
                },
            )
        }
    }.onFailure {
        logcat(LogPriority.WARN, it) { "applyAppLanguageToPlatform failed for appLanguage='$tag'" }
    }
}
