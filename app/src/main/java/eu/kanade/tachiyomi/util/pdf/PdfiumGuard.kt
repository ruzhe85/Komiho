package eu.kanade.tachiyomi.util.pdf

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.diagnostic.DiagLog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho (2026-11-05): PDFium 抽取的**崩溃熔断**。
 *
 * native 崩溃（SIGSEGV/SIGTRAP）在 Kotlin 侧完全不可捕获 —— 一旦某个文件的某个页面
 * 让 libpdfium 崩了，表现就是「每次打开都闪退」，用户直接被锁死（2026-11-05 的现场
 * 就是这样：打开 PDF → 0.3s 后进程没了 → 回到书架 → 再试 → 再崩）。
 *
 * 做法：进入 native 前先把 `inflight` 落盘（`commit()` 同步写），调用正常返回后清掉。
 * 下次启动时如果发现 `inflight` 还是 true，说明**上次进程死在 native 调用里**，于是
 * 永久关闭抽取（`disabled`），整条链路回落到「自写解析器 → PDFium 渲染」——
 * 慢一点，但至少能读。清除应用数据可恢复。
 */
internal object PdfiumGuard {

    private const val TAG = "KomihoPdfiumX"
    private const val PREFS = "komiho_pdfium_extract"
    private const val KEY_INFLIGHT = "inflight"
    private const val KEY_DISABLED = "disabled"
    private const val KEY_DISABLED_STAMP = "disabled_stamp"

    /** 当前构建的标识：换包（重新 CI 构建）后自动解除熔断，给修复一次机会。 */
    private val buildStamp: String by lazy {
        runCatching { BuildConfig.BUILD_TIME }.getOrDefault("")
    }

    private fun prefs(): SharedPreferences? = runCatching {
        Injekt.get<Application>().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }.getOrNull()

    /** 抽取是否可用；检测到上次崩溃则关闭（同一次构建内）。 */
    fun available(): Boolean {
        val p = prefs() ?: return false
        if (p.getBoolean(KEY_DISABLED, false)) {
            if (p.getString(KEY_DISABLED_STAMP, "") == buildStamp) return false
            runCatching {
                p.edit().putBoolean(KEY_DISABLED, false).remove(KEY_DISABLED_STAMP).commit()
            }
            DiagLog.d(TAG, "检测到新构建，PDFium 抽取熔断已自动解除")
        }
        if (p.getBoolean(KEY_INFLIGHT, false)) {
            runCatching {
                p.edit()
                    .putBoolean(KEY_DISABLED, true)
                    .putString(KEY_DISABLED_STAMP, buildStamp)
                    .putBoolean(KEY_INFLIGHT, false)
                    .commit()
            }
            DiagLog.w(TAG, "上次进程死在 PDFium 抽取调用里，已熔断：直通关闭，回落渲染")
            return false
        }
        return true
    }

    /** 进入 native 前落盘（同步 commit，崩溃前必须写完）。 */
    fun arm() {
        runCatching { prefs()?.edit()?.putBoolean(KEY_INFLIGHT, true)?.commit() }
    }

    /** native 正常返回后清除。 */
    fun disarm() {
        runCatching { prefs()?.edit()?.putBoolean(KEY_INFLIGHT, false)?.commit() }
    }
}
