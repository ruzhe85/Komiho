package eu.kanade.tachiyomi.diagnostic

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Komiho (2026-10-04): 把「上一次进程是怎么结束的」写进诊断日志。
 *
 * 背景：EPUB 打不开的报告里，日志停在 `PageLoader=EpubPageLoader` 之后、没有任何 Java
 * 异常，随后是新进程 —— 分不清是「解析卡住没走完」「被系统杀」还是「native 崩溃」。
 * Java 崩溃有 [eu.kanade.tachiyomi.crash.GlobalExceptionHandler] 落盘，但 **native 崩溃
 * （SIGSEGV/SIGABRT）与低内存回收（LMK）根本不经过 Java**，日志里只会「凭空断掉」。
 *
 * Android 11（API 30）起系统自己记着最近若干次进程退出原因，
 * [ActivityManager.getHistoricalProcessExitReasons] 就能读出来：
 *
 * - `CRASH_NATIVE` / `SIGNALED` + 信号号 → native 层崩（库 / 显卡驱动 / 越界），与系统设置无关；
 * - `LOW_MEMORY` → 被系统低内存回收（典型「设备/系统」原因）；
 * - `ANR` → 卡死被系统杀掉（正好对应「解析慢到超时」）；
 * - `USER_REQUESTED` → 用户自己划掉的，此时日志「断掉」**不代表崩溃**。
 *
 * 只读、只打日志；任何失败都吞掉（老系统 / 厂商裁剪 / 无权限）。
 */
object ProcessExitDiagnostics {

    private const val TAG = "ProcessExit"

    /** 一次最多列几条（系统上限约 16）。 */
    private const val MAX_ENTRIES = 8

    private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 在冷启动早期调用（必须在 [DiagnosticLogBuffer.install] 之后，否则写不进落盘缓冲）。
     * 内部是 binder 调用，调用方请放在后台线程。
     */
    fun logRecentExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            DiagLog.d(
                TAG,
                "跳过进程退出记录: Android ${Build.VERSION.RELEASE}(SDK ${Build.VERSION.SDK_INT}) " +
                    "< 30，系统不提供该接口（此机型无法区分崩溃/被杀）",
            )
            return
        }
        runCatching { logApi30(context) }
            .onFailure { DiagLog.w(TAG, "读取进程退出记录失败: ${it.javaClass.simpleName}: ${it.message}") }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun logApi30(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val list = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_ENTRIES)
        if (list.isNullOrEmpty()) {
            DiagLog.d(TAG, "无进程退出记录（首次启动，或已被系统清理）")
            return
        }
        DiagLog.d(TAG, "上次会话之后能查到的进程退出记录 ${list.size} 条（**本次启动之前**发生）:")
        for (info in list) {
            val line = buildString {
                append("reason=").append(reasonName(info.reason))
                append(", time=").append(TIME_FORMAT.format(Date(info.timestamp)))
                if (info.status != 0) {
                    append(", status=").append(info.status)
                    if (
                        info.reason == ApplicationExitInfo.REASON_SIGNALED ||
                        info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    ) {
                        append('(').append(signalName(info.status)).append(')')
                    }
                }
                append(", pss=").append(info.pss / 1024).append("MB")
                append(", rss=").append(info.rss / 1024).append("MB")
                append(", importance=").append(importanceName(info.importance))
                info.description?.takeIf { it.isNotBlank() }?.let { append(", desc=").append(it) }
            }
            if (isAbnormal(info.reason)) {
                DiagLog.w(TAG, "进程退出(异常): $line")
            } else {
                DiagLog.d(TAG, "进程退出: $line")
            }
        }
        // 自解释：免得看导出文件的人（和后来接手的人）要回代码里查这张表。
        DiagLog.d(
            TAG,
            "判读: CRASH_NATIVE/SIGNALED=应用 native 层崩(库或驱动，非系统设置问题); " +
                "LOW_MEMORY=被系统内存回收; ANR=卡死被杀(常见于解析过慢); " +
                "USER_REQUESTED=用户自己划掉(日志断掉≠崩溃); EXIT_SELF/OTHER=正常或未知",
        )
    }

    /** 需要特别标红的退出原因（用 WARN 级别写，便于在导出文件里一眼看到）。 */
    private fun isAbnormal(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_DEADLOCK,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        -> true
        else -> false
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR(卡死被杀)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH(Java 未捕获异常)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE(native 崩溃)"
        ApplicationExitInfo.REASON_DEADLOCK -> "DEADLOCK(死锁)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE(资源超限)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF(正常退出)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE(启动失败)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY(被系统低内存回收)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED(被信号杀死)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED(用户主动划掉)"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER(被冻结)"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        else -> "UNKNOWN($reason)"
    }

    /** 信号号 → 名称（只列常见几个；`status` 就是信号号）。 */
    private fun signalName(signal: Int): String = when (signal) {
        3 -> "SIGQUIT"
        4 -> "SIGILL"
        5 -> "SIGTRAP"
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        8 -> "SIGFPE"
        9 -> "SIGKILL"
        11 -> "SIGSEGV"
        13 -> "SIGPIPE"
        15 -> "SIGTERM"
        else -> "SIG$signal"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING_PRE_28 -> "TOP_SLEEPING"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE_PRE_26 -> "PERCEPTIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        else -> importance.toString()
    }
}
