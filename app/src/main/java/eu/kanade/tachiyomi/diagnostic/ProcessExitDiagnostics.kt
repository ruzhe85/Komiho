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
 * Komiho (2026-11-06) 两条收缩（原先每次启动把 8 条历史记录 + 各自最多 1500 行 trace 全打
 * 一遍，导出文件里 6 万行 / 7MB 有 99% 是重复 dump）：
 *  1. 只汇报**上次启动之后新增**的退出记录（时间戳记在 SharedPreferences 里）；
 *  2. trace 只留关键行（异常信息 / 信号 / 带 .so 的帧 + 前 40 帧），单条上限 120 行。
 *
 * 只读、只打日志；任何失败都吞掉（老系统 / 厂商裁剪 / 无权限）。
 */
object ProcessExitDiagnostics {

    private const val TAG = "ProcessExit"

    /** 一次最多列几条（系统上限约 16）。 */
    private const val MAX_ENTRIES = 8

    private const val PREFS = "komiho_diag"
    private const val KEY_LAST_REPORTED_TS = "last_exit_ts"

    /** 单条 trace 最多写这么多行/帧（防止把导出文件刷成几 MB）。 */
    private const val MAX_TRACE_LINES = 120
    private const val MAX_TRACE_FRAMES = 40

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

        val prefs = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull()
        val lastReported = prefs?.getLong(KEY_LAST_REPORTED_TS, 0L) ?: 0L

        // 系统会长期保留最近 8 条，每次都全量打印 → 日志被历史 dump 淹没。
        // 只报「上次汇报之后新增的」，同时仍把总数说出来，方便判断历史里有没有崩溃。
        val fresh = list.filter { it.timestamp > lastReported }
        if (fresh.isEmpty()) {
            DiagLog.d(TAG, "无新的进程退出记录（系统里共 ${list.size} 条，均已汇报过）")
            return
        }

        DiagLog.d(TAG, "新增进程退出记录 ${fresh.size} 条（系统共 ${list.size} 条，均在本次启动之前）:")
        var maxTs = lastReported
        for (info in fresh) {
            if (info.timestamp > maxTs) maxTs = info.timestamp
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
            if (isAbnormal(info.reason)) logTrace(info)
        }
        // 用 commit：这条记录的意义就是「下次崩溃后别再重播」，若用 apply（异步落盘）
        // 而进程紧接着又死，下次启动会重复打印一遍，等于白改。
        prefs?.edit()?.putLong(KEY_LAST_REPORTED_TS, maxTs)?.commit()

        // 自解释：免得看导出文件的人（和后来接手的人）要回代码里查这张表。
        DiagLog.d(
            TAG,
            "判读: CRASH_NATIVE/SIGNALED=应用 native 层崩(库或驱动，非系统设置问题); " +
                "LOW_MEMORY=被系统内存回收; ANR=卡死被杀(常见于解析过慢); " +
                "USER_REQUESTED=用户自己划掉(日志断掉≠崩溃); EXIT_SELF/OTHER=正常或未知",
        )
    }

    /**
     * Komiho: 把系统为这次退出保存的原始 trace 落进诊断日志。
     *
     * 这是唯一能拿到 **native 栈**的地方（`#00 pc 0000xxxx  /data/app/…/libwaifu2x-jni.so`）：
     * [ApplicationExitInfo] 的 reason/status 只能告诉我们「SIGSEGV」或「SIGABRT」，指不到具体
     * 哪个 .so、哪个函数；而 adb 在不少设备上拿不到（鸿蒙、家长控制/企业管控机型）。
     *
     * 只保留判读需要的行：异常信息 / 信号 / 前 [MAX_TRACE_FRAMES] 帧 / 任何带 `.so` 的帧。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun logTrace(info: ApplicationExitInfo) {
        val text = runCatching {
            info.traceInputStream?.use { input ->
                val buf = ByteArray(MAX_TRACE_BYTES)
                var n = 0
                while (n < buf.size) {
                    val read = input.read(buf, n, buf.size - n)
                    if (read <= 0) break
                    n += read
                }
                String(buf, 0, n, Charsets.UTF_8)
            }
        }.getOrNull()
        if (text.isNullOrBlank()) return

        val lines = trimTrace(text)
        if (lines.isEmpty()) return
        DiagLog.w(
            TAG,
            "退出 trace(${reasonName(info.reason)}, time=${TIME_FORMAT.format(Date(info.timestamp))}) " +
                "保留 ${lines.size} 行:",
        )
        lines.forEach { DiagLog.w(TAG, it) }
    }

    /**
     * 从完整 trace 里挑出关键行。
     *
     * 原始 trace 是一份几万行的 tombstone（含所有线程的寄存器 dump），全量落盘既慢又难读；
     * 判读真正用得到的是「怎么死的 + 崩在哪个 .so」。所以只留：
     *  - `Abort message` / `signal` / `pid:` 这类头部；
     *  - 崩溃线程的前 [MAX_TRACE_FRAMES] 个栈帧；
     *  - 任何带 `.so` 的帧（不限于前 40 帧，app 自己的库往往在中间）。
     */
    private fun trimTrace(text: String): List<String> {
        val out = ArrayList<String>(MAX_TRACE_LINES)
        var frames = 0
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            // tombstone 里有一大段 `x0 … x29` 寄存器 dump 和二进制块：对判读没用，
            // 而且按 UTF-8 解码出来是乱码（导出文件里那一串 `x0������`），直接丢。
            if (!looksTextual(line)) continue
            val trimmed = line.trimStart()
            val header = trimmed.startsWith("pid:") ||
                trimmed.startsWith("signal ") ||
                trimmed.startsWith("Abort message") ||
                trimmed.startsWith("backtrace:")
            val withLib = trimmed.startsWith("#") && line.contains(".so")
            if (header || withLib) {
                out += line
            } else if (trimmed.startsWith("#")) {
                frames++
                if (frames <= MAX_TRACE_FRAMES) out += line
            }
            if (out.size >= MAX_TRACE_LINES) break
        }
        if (out.isEmpty()) {
            // ANR 的 trace 是 Java 线程 dump（`"main" prio=5 …`，没有 `#` 帧），上面的规则
            // 一条都匹配不到 —— 退回前 40 行，至少能看到卡在哪个方法。
            return text.lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() && looksTextual(it) }
                .take(MAX_TRACE_FRAMES)
                .toList()
        }
        return out
    }

    /** 非 ASCII / 控制字符占比过高的行视为二进制垃圾（trace 里的寄存器 dump 就是这种）。 */
    private fun looksTextual(line: String): Boolean {
        var bad = 0
        for (ch in line) {
            if (ch == '\t') continue
            if (ch.code < 0x20 || ch.code > 0x7E) bad++
        }
        return bad * 4 <= line.length
    }

    /** 单条 trace 最多读这么多字节：native 栈通常几 KB，ANR trace 会到几十 KB。 */
    private const val MAX_TRACE_BYTES = 64 * 1024

    /** 需要特别标红的退出原因（用 WARN 级别写，便于在导出文件里一眼看到）。 */
    private fun isAbnormal(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
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
