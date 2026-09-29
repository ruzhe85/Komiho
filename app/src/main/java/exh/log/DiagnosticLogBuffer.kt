package exh.log

import logcat.LogPriority
import logcat.LogcatLogger
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 进程内内存日志缓冲：从冷启动起记录所有 [logcat] 调用（不受 Release 包下 XLog 级别过滤影响，
 * 因此 DEBUG/INFO 也能被保留），供「导出诊断日志」把本次冷启动后的全部日志导出为 txt 分析。
 *
 * 在 [eu.kanade.tachiyomi.App] 的 onCreate 中调用 [install] 注册为 logcat 的 Logger。
 */
object DiagnosticLogBuffer {

    private const val MAX_ENTRIES = 20_000

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    private data class Entry(
        val timeMillis: Long,
        val priority: LogPriority,
        val tag: String,
        val message: String,
    )

    fun install() {
        LogcatLogger.loggers += Logger
    }

    /** 把缓冲的日志拼成可读文本（按时间升序）。 */
    fun getLogs(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        synchronized(lock) {
            for (e in entries) {
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
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private object Logger : LogcatLogger {
        override fun log(priority: LogPriority, tag: String, message: String) {
            val entry = Entry(System.currentTimeMillis(), priority, tag.orEmpty(), message)
            synchronized(lock) {
                entries.addLast(entry)
                if (entries.size > MAX_ENTRIES) entries.removeFirst()
            }
        }
    }
}
