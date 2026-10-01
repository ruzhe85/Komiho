package exh.log

import android.util.Log
import logcat.LogPriority

/**
 * Komiho (2026-10-01): 诊断日志 —— 同时写 [android.util.Log] 与进程内 [DiagnosticLogBuffer]。
 *
 * 为什么需要：阅读器里那批为了**绕过 Release 下 XLog 的 WARN 级别**而直接写 `android.util.Log`
 * 的诊断（`Waifu2xTiming` / `Waifu2xHolder` / `Waifu2xPrewarm` / `Waifu2xRebuild` /
 * `Waifu2xAutoWebtoon` / 条漫 bind-render 等）在 logcat 里看得见，却**不进**「导出诊断日志」——
 * 因为导出的数据源 [DiagnosticLogBuffer] 只挂在 `logcat()` 上。
 *
 * 后果：无法使用 adb 的设备（鸿蒙等）只剩这一条排查路径，却恰好丢掉了最关键的那批数
 * （`wait=` / `pure=` / `queue2=` 是判断瓶颈在排队还是纯计算的全部依据）。
 *
 * 所以两处都写。签名与 `android.util.Log` 完全一致，调用点原地替换即可；[DiagnosticLogBuffer]
 * 独立于 XLog 级别，DEBUG 也能留住。
 */
object DiagLog {

    fun d(tag: String, msg: String) = write(LogPriority.DEBUG, tag, msg) { Log.d(tag, msg) }

    fun i(tag: String, msg: String) = write(LogPriority.INFO, tag, msg) { Log.i(tag, msg) }

    fun w(tag: String, msg: String) = write(LogPriority.WARN, tag, msg) { Log.w(tag, msg) }

    fun e(tag: String, msg: String) = write(LogPriority.ERROR, tag, msg) { Log.e(tag, msg) }

    fun e(tag: String, msg: String, throwable: Throwable) =
        write(LogPriority.ERROR, tag, msg) { Log.e(tag, msg, throwable) }

    private inline fun write(priority: LogPriority, tag: String, msg: String, log: () -> Unit) {
        log()
        DiagnosticLogBuffer.record(priority, tag, msg)
    }
}
