package eu.kanade.tachiyomi.diagnostic

import android.content.Context
import logcat.LogPriority
import logcat.LogcatLogger
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志缓冲：从冷启动起记录所有 [logcat] 调用（不受 Release 包下 XLog 级别过滤影响，
 * 因此 DEBUG/INFO 也能被保留），供「导出诊断日志」导出为 txt 分析。
 *
 * 在 [eu.kanade.tachiyomi.App] 的 onCreate 中调用 [install] 注册为 logcat 的 Logger。
 *
 * Komiho (2026-10-02): 改为**落盘**而非仅存内存。原因——有人反馈「闪退后自动重启」时导出
 * 内容为空：闪退会让进程死亡，内存里的缓冲随之中断清空，新进程启动后 [DiagnosticLogBuffer]
 * 是空的，导出的自然不含崩溃原因。落盘后文件在进程死亡后依然存在，重启也能导出上一会话的
 * 崩溃证据。
 *
 * 数据源（与 2026-10-01 一致，三处合并）：
 *
 *  1. `logcat()` —— [Logger]（原有）；
 *  2. `android.util.Log` 那批诊断行 —— 经 [DiagLog] 双写进来；
 *  3. 原生 C++ 的 `Waifu2xNative` / `Waifu2xJNI` —— 经 [getLogsMerged] 解析
 *     `<epochMillis>|<level>|<tag>|<message>` 行（见 `app/src/main/cpp/native_log.h`）。
 *
 * 落盘格式与原生环**完全相同**（`<epochMillis>|<V/D/I/W/E/A>|<tag>|<message>`，一行一条），
 * 所以 [getLogsMerged] 把磁盘文件与原生环文本直接拼起来、按时间升序合并即可对齐因果链。
 *
 * 注意：[install] 故意**不清空**旧文件——崩溃导致的自动重启是「新进程」，清空会让上一会话
 * 的崩溃证据丢失（正是本功能要修的问题）。文件靠 [MAX_FILE_BYTES] 做环形裁剪。
 */
object DiagnosticLogBuffer {

    /** 磁盘缓冲上限：超过则保留最近 75%。8MB 约可容纳数万行，足够覆盖一次崩溃前后。 */
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024

    private val lock = Any()
    private lateinit var logFile: File
    private var writer: BufferedWriter? = null

    fun install(context: Context) {
        logFile = File(context.filesDir, "komiho_diagnostic_log.txt")
        // 不清空旧文件：崩溃自动重启后仍需上一会话的日志（见类注释）。
        openWriter()
        // 注册为 logcat 的 Logger —— 这是 logcat() 调用进入本缓冲的唯一入口
        // （DiagLog 走 record() 直接进来，不依赖这里）。漏注册会让导出整段为空。
        LogcatLogger.loggers += Logger
    }

    private fun openWriter() {
        synchronized(lock) {
            try {
                writer?.close()
            } catch (_: Throwable) {
            }
            // 追加模式：绝不能截断，否则冷启动会清掉上一会话（崩溃前）的日志。
            writer = FileOutputStream(logFile, /* append = */ true).bufferedWriter()
        }
    }

    /** 供 [DiagLog] 把 `android.util.Log` 那批诊断行也记进来。 */
    fun record(priority: LogPriority, tag: String, message: String) {
        Logger.log(priority, tag, message)
    }

    /**
     * 合并「磁盘缓冲 + 原生日志环」后输出，按时间升序。
     *
     * 磁盘缓冲在进程死亡（闪退/自动重启）后依然存在，因此崩溃证据可跨重启导出。
     */
    fun getLogsMerged(nativeLogs: String?): String {
        synchronized(lock) {
            try {
                writer?.flush()
            } catch (_: Throwable) {
            }
        }
        val sb = StringBuilder()
        if (this::logFile.isInitialized && logFile.exists()) {
            sb.append(logFile.readText())
        }
        if (!nativeLogs.isNullOrEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(nativeLogs)
        }
        return formatParsed(parseLines(sb.toString()))
    }

    /** 合并前的解析：每行 `<epochMillis>|<level>|<tag>|<message>`（与原生环同格式）。 */
    private fun parseLines(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) continue
            val time = parts[0].toLongOrNull() ?: continue
            out += Entry(time, priorityOf(parts[1]), parts[2], parts[3])
        }
        return out
    }

    private fun formatParsed(list: List<Entry>): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        for (e in list) {
            append(fmt.format(Date(e.timeMillis)))
            append(' ')
            append(e.priority.name.first())
            if (e.tag.isNotBlank()) {
                append('/')
                append(e.tag)
            }
            append(": ")
            append(e.message)
            append('\n')
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                writer?.close()
            } catch (_: Throwable) {
            }
            writer = null
            if (this::logFile.isInitialized && logFile.exists()) logFile.delete()
            openWriter()
        }
    }

    /** 原生侧的单字母级别 → [LogPriority]（首字母口径与落盘/输出那一列一致）。 */
    private fun priorityOf(level: String): LogPriority = when (level.firstOrNull()) {
        'V' -> LogPriority.VERBOSE
        'I' -> LogPriority.INFO
        'W' -> LogPriority.WARN
        'E' -> LogPriority.ERROR
        'A' -> LogPriority.ASSERT
        else -> LogPriority.DEBUG
    }

    private object Logger : LogcatLogger {
        override fun log(priority: LogPriority, tag: String, message: String) {
            // 写入失败绝不应影响业务（包括让 app 再崩一次）。
            try {
                val line = buildString {
                    append(System.currentTimeMillis())
                    append('|')
                    append(priority.name.first())
                    append('|')
                    append(tag.orEmpty())
                    append('|')
                    // 消息里可能含换行，压成单行保证「一行一条」便于按行解析
                    append(message.replace('\n', ' ').replace('\r', ' '))
                }
                synchronized(lock) {
                    writer?.append(line)?.append('\n')?.flush()
                    if (logFile.length() > MAX_FILE_BYTES) trimLocked()
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** 环形裁剪：保留最近 75%（须在 [lock] 内调用，且会安全地重开 writer）。 */
    private fun trimLocked() {
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
        val lines = logFile.readLines()
        if (lines.size <= 1) {
            openWriter()
            return
        }
        val keep = lines.takeLast((lines.size * 3) / 4)
        logFile.writeText(keep.joinToString("\n") + "\n")
        openWriter()
    }

    data class Entry(
        val timeMillis: Long,
        val priority: LogPriority,
        val tag: String,
        val message: String,
    )
}
