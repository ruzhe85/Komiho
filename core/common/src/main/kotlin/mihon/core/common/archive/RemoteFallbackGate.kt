package mihon.core.common.archive

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * 用户在「大文件整本缓存确认」里点了取消（或超时没回应）。
 *
 * 单独成型是给上层一个**明确的停止信号**：阅读链路里 `RemoteZipReader` 失败后会回落
 * `ArchiveReader` 再试一次，用户不区分类型就会看到同一个文件弹两次确认框。
 * 上层见到它就应当直接失败，不要再换解析路径重试。
 */
class RemoteFallbackDeclined(fileName: String) :
    IOException("已取消整本缓存：$fileName")

/**
 * Komiho：远程大文件「整本缓存」前的确认闸门。
 *
 * 背景：WebDAV / 115 遇到 rar、7z（以及不支持 Range 的服务器）必须**整本下载**才能翻页，
 * 而 SMB 是原生按偏移读、不需要缓存。用户点错一个大压缩包（比如 2.4GB）时，旧行为会
 * 闷头下完，流量和磁盘都白烧。
 *
 * 这里只做两件事：
 *  1. 超过 [thresholdBytes] 才拦（默认 300MB，设置-存储可改，<= 0 = 从不确认）；
 *  2. 有界面时问一句，没界面时**直接拒绝**（宁可不打开，也不在后台偷偷下几十 GB）。
 *
 * 调用点在 IO 线程（`RandomAccessSource.ensureProbed` 里），所以是阻塞式；
 * UI 实现由 [confirmHandler] 注入，必须自己在主线程弹窗并回调结果。
 */
object RemoteFallbackGate {

    /** 阈值（字节）。<= 0 表示不确认、直接缓存。由 App 启动与设置变更时写入。 */
    @Volatile
    var thresholdBytes: Long = 300L * 1024 * 1024

    /**
     * UI 侧实现：弹确认框，用户选择后回调 `onResult(true/false)`。
     * 未注入（例如没有前台 Activity）时一律拒绝。
     */
    @Volatile
    var confirmHandler: ((fileName: String, sizeBytes: Long, onResult: (Boolean) -> Unit) -> Unit)? = null

    /** 等待用户回应的上限：超时视为拒绝，避免阅读线程被长时间挂住。 */
    private const val CONFIRM_TIMEOUT_SECONDS = 30L

    /**
     * 是否已有确认框在等回应。
     *
     * 阅读器会并发取页、封面缓存也会在后台打开同一个文件 —— 不挡一下会同时弹好几个框。
     * 后到的直接按「拒绝」处理（它只是这次尝试放弃，用户点确定后重进即可）。
     */
    @Volatile
    private var dialogShowing = false

    /**
     * 最近被拒绝的文件（`文件名|字节数` → 拒绝时刻）。
     *
     * 阅读器、`ChapterLoader` 的回落分支、封面缓存会各自 new 一个 source 打开同一个文件 ——
     * 不记住「刚被拒过」的话，用户点一次取消会被连着问好几次。
     */
    private val declinedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 拒绝记忆的有效期：过了这段时间再打开会重新询问（用户可能改主意）。 */
    private const val DECLINE_MEMORY_MS = 60_000L

    /**
     * 请求批准整本缓存。返回 true = 允许下载。
     *
     * @param fileName 仅用于提示文案
     * @param sizeBytes 文件大小；未知（<= 0）时不拦
     */
    fun requestApproval(fileName: String, sizeBytes: Long): Boolean {
        if (sizeBytes <= 0L) return true
        val threshold = thresholdBytes
        if (threshold <= 0L || sizeBytes <= threshold) return true

        // 刚被拒过就直接拒绝，不再弹窗：阅读器 + 回落分支 + 封面缓存会分别 new source
        // 打开同一个文件，不记这一笔用户要点好几次取消。
        val key = "$fileName|$sizeBytes"
        val now = System.currentTimeMillis()
        val lastDecline = declinedAt[key]
        if (lastDecline != null) {
            if (now - lastDecline < DECLINE_MEMORY_MS) {
                logcat(LogPriority.DEBUG) {
                    "[RemoteCache] 刚被拒绝，跳过询问 name=$fileName"
                }
                return false
            }
            declinedAt.remove(key)
        }

        val handler = confirmHandler
        if (handler == null) {
            logcat(LogPriority.WARN) {
                "[RemoteCache] 无界面可确认，拒绝缓存大文件 name=$fileName size=$sizeBytes"
            }
            return false
        }

        if (dialogShowing) return false
        dialogShowing = true
        val latch = CountDownLatch(1)
        val approved = AtomicBoolean(false)
        var done = false
        val result = try {
            val posted = runCatching {
                handler(fileName, sizeBytes) { ok ->
                    approved.set(ok)
                    latch.countDown()
                }
            }.isSuccess
            if (!posted) latch.countDown()
            done = runCatching { latch.await(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault(false)
            done && approved.get()
        } finally {
            dialogShowing = false
        }
        logcat(LogPriority.DEBUG) {
            "[RemoteCache] 大文件缓存确认 name=$fileName size=$sizeBytes " +
                "threshold=$threshold result=$result timeout=${!done}"
        }
        if (result) {
            declinedAt.remove(key)
        } else {
            // 记「真正被拒的时刻」而不是开始询问的时刻：用户可能拖到第 29 秒才点取消，
            // 用 now 会把记忆期凭空缩短一大截。
            declinedAt[key] = System.currentTimeMillis()
        }
        return result
    }
}
