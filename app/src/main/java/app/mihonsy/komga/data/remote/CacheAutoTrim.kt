package app.mihonsy.komga.data.remote

import android.content.Context
import app.mihonsy.komga.data.KomgaPreferences
import logcat.LogPriority
import logcat.logcat
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Komiho (2026-11-04): 远程缓存的「总量兜底」。
 *
 * 和「设置-存储」里那条 200MB~4GB 滑条不是一回事：
 * - 滑条（StoragePreferences.webdavCacheMaxBytes）= 各缓存目录**写入时**的分预算；
 * - 本对象 = **App 启动时**的一次总检查：把三个目录合起来算总量，超过用户填的 MB 就从
 *   最旧的开始删。
 *
 * 覆盖目录：
 * - `cacheDir/webdav_fallback`：不支持 Range 时的整本回退缓存
 * - `cacheDir/remote_pages`：WebDAV/SMB 页级缓存（每书一目录）
 * - `cacheDir/remote_pdf`：远程/本地 PDF 整本缓存
 *
 * 细节：
 * - 目标是上限的 90%，留 10% 余量，避免每次启动都刚好踩线、刚清完又被写满；
 * - `.part`（正在写）与 `.meta`（书籍身份，删了会让整书缓存变成孤儿）不参与删除；
 * - 只在后台线程调用，遍历目录有 IO 成本。
 */
object CacheAutoTrim {

    private val DIRS = listOf("webdav_fallback", "remote_pages", "remote_pdf")

    /**
     * 超限则按 lastModified 从最旧开始删，直到降到上限的 90%。
     *
     * @param limitMb 上限（MB）；<= 0 表示不限制，直接返回。
     * @return 实际释放的字节数（未触发清理时为 0）。
     */
    fun trimIfNeeded(context: Context, limitMb: Int): Long {
        if (limitMb <= 0) return 0L
        val limit = limitMb.toLong() * 1024L * 1024L
        val target = limit / 10L * 9L

        return runCatching {
            val dirs = DIRS.map { File(context.cacheDir, it) }.filter { it.isDirectory }
            if (dirs.isEmpty()) return@runCatching 0L

            val files = dirs.flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && !it.name.endsWith(".part") && it.name != ".meta" }
                    .toList()
            }
            var total = files.sumOf { it.length() }
            if (total <= limit) return@runCatching 0L

            var freed = 0L
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= target) break
                val size = file.length()
                if (file.delete()) {
                    total -= size
                    freed += size
                }
            }

            // 页缓存按「每书一目录」组织，删空后顺手把空目录也收掉，避免目录数无限增长。
            dirs.forEach { dir ->
                dir.listFiles()
                    ?.filter { it.isDirectory && it.list().isNullOrEmpty() }
                    ?.forEach { runCatching { it.delete() } }
            }

            logcat(LogPriority.INFO) {
                "[CacheAutoTrim] 上限=${limitMb}MB 清理=${freed / 1024L / 1024L}MB 剩余=${total / 1024L / 1024L}MB"
            }
            freed
        }.getOrDefault(0L)
    }
}

/**
 * Komiho (2026-11-04): 缓存预算的**单一来源**。
 *
 * 「设置-存储」里那条 200MB~4GB 的滑条已撤掉（UI 无入口），`StoragePreferences.webdavCacheMaxBytes`
 * 只剩默认值 1GB 在写入时使用 —— 于是会出现「用户设了 300MB 总上限，单个目录却能涨到 1GB」的错位。
 * 这里收敛成一处判断：
 * - 用户填了「缓存上限（MB）」（> 0）→ 写入预算 = 该上限（各目录独立执行），启动时再做总量兜底；
 * - 没填（0 = 不限制）→ 写入预算回落到历史默认 1GB，启动时不做清理。
 */
object CacheLimits {
    fun maxBytes(context: Context): Long {
        val limitMb = runCatching { KomgaPreferences(context.applicationContext).cacheLimitMb }.getOrDefault(0)
        return if (limitMb > 0) {
            limitMb.toLong() * 1024L * 1024L
        } else {
            Injekt.get<StoragePreferences>().webdavCacheMaxBytes.get()
        }
    }
}
