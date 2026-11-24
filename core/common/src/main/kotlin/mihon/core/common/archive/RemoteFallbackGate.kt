package mihon.core.common.archive

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

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
     * 请求批准整本缓存。返回 true = 允许下载。
     *
     * @param fileName 仅用于提示文案
     * @param sizeBytes 文件大小；未知（<= 0）时不拦
     */
    fun requestApproval(fileName: String, sizeBytes: Long): Boolean {
        if (sizeBytes <= 0L) return true
        val threshold = thresholdBytes
        if (threshold <= 0L || sizeBytes <= threshold) return true

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
        val result = try {
            val posted = runCatching {
                handler(fileName, sizeBytes) { ok ->
                    approved.set(ok)
                    latch.countDown()
                }
            }.isSuccess
            if (!posted) latch.countDown()
            val done = runCatching { latch.await(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault(false)
            done && approved.get()
        } finally {
            dialogShowing = false
        }
        logcat(LogPriority.DEBUG) {
            "[RemoteCache] 大文件缓存确认 name=$fileName size=$sizeBytes " +
                "threshold=$threshold result=$result timeout=${!done}"
        }
        return result
    }
}
