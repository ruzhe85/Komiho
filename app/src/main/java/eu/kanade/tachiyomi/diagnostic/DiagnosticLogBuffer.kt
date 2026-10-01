package eu.kanade.tachiyomi.diagnostic

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
 *
 * Komiho (2026-10-01): 数据源扩到**三处** —— 目标是让「导出诊断日志」在无法使用 adb 的设备
 * （鸿蒙等）上也能拿到足以定性的证据，而不只是有没有成功：
 *
 *  1. `logcat()` —— [Logger]（原有）；
 *  2. `android.util.Log` 那批诊断行 —— 经 [DiagLog] 双写进来（`Waifu2xTiming` / Holder /
 *     Prewarm / Rebuild / 自动条漫切换 …）。它们此前**完全不在**导出里；
 *  3. 原生 C++ 的 `Waifu2xNative` / `Waifu2xJNI` —— 经 [getLogsMerged] 解析
 *     `<epochMillis>|<level>|<tag>|<message>` 行（见 `app/src/main/cpp/native_log.h`）。
 *     原生侧此前只在 logcat 里，进程内缓冲读不到。
 *
 * 三者的时间戳**都是 epoch 毫秒**（Kotlin 用 `System.currentTimeMillis()`，原生用
 * `clock_gettime(CLOCK_REALTIME)`），所以 [getLogsMerged] 能按时间升序真实合并 —— 这一点很关键：
 * 只有排到一起，才能对齐「Gray mask 起点 → 原生调度/批次 → 纯耗时 pure=」这条因果链。
 */
object DiagnosticLogBuffer {

    /**
     * 40k 条上限。比原先的 20k 大一倍：接入 [DiagLog] 后每条增强会多出若干行
     * （holder 生命周期 / prewarm 决策 / `adaptive batch` 每批一行），一话的量级仍远在其内。
     */
    private const val MAX_ENTRIES = 40_000

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** 一条日志。时间戳为 epoch 毫秒（与原生 `clock_gettime(CLOCK_REALTIME)` 同基准）。 */
    data class Entry(
        val timeMillis: Long,
        val priority: LogPriority,
        val tag: String,
        val message: String,
    )

    fun install() {
        LogcatLogger.loggers += Logger
    }

    /** 供 [DiagLog] 把 `android.util.Log` 那批诊断行也记进来。 */
    fun record(priority: LogPriority, tag: String, message: String) {
        Logger.log(priority, tag, message)
    }

    /** 缓冲快照（副本，保持记录顺序）。 */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /** 把缓冲的日志拼成可读文本（按时间升序）。 */
    fun getLogs(): String = format(snapshot())

    /**
     * 合并「进程内缓冲 + 原生日志环」后输出，按时间升序。
     *
     * [nativeLogs] 每行格式 `<epochMillis>|<V/D/I/W/E>|<tag>|<message>`，由 `Waifu2x.nativeLogs()`
     * 提供。**无法解析的行直接忽略**：原生环只是增量信息源，绝不能因为它格式异常就把导出整段搞空。
     */
    fun getLogsMerged(nativeLogs: String?): String {
        val all = ArrayList<Entry>(entries.size + 256)
        all += snapshot()
        if (!nativeLogs.isNullOrEmpty()) {
            for (line in nativeLogs.lineSequence()) {
                if (line.isEmpty()) continue
                val parts = line.split('|', limit = 4)
                if (parts.size < 4) continue
                val time = parts[0].toLongOrNull() ?: continue
                all += Entry(time, priorityOf(parts[1]), parts[2], parts[3])
            }
        }
        all.sortBy { it.timeMillis }
        return format(all)
    }

    /** 按 [getLogs] 的格式（`时间 级别/标签: 内容`）把 [list] 拼成文本。 */
    fun format(list: List<Entry>): String = buildString {
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
        synchronized(lock) { entries.clear() }
    }

    /** 原生侧的单字母级别 → [LogPriority]（首字母口径与 [format] 输出的那一列一致）。 */
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
            val entry = Entry(System.currentTimeMillis(), priority, tag.orEmpty(), message)
            synchronized(lock) {
                entries.addLast(entry)
                if (entries.size > MAX_ENTRIES) entries.removeFirst()
            }
        }
    }
}
