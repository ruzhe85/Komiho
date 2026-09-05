package app.mihonsy.komga.data.webdav

import logcat.LogPriority
import logcat.logcat
import mihon.core.common.archive.ArchiveHandle
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// SY --> Komiho Phase5：WebDAV 页级磁盘缓存。
//
// 背景：WebDAV 的 zip/cbz 走 WebDavZipReader 每页一次 Range 按需拉取（无磁盘缓存，
// 重看重拉）；rar/7z 虽有整本回退缓存，但那是 RandomAccessSource 层的字节段文件。
// 本缓存把「每一页解压/解密后的原始图片字节」按书落盘，收益：
//  - 回翻 / 重开章节 / 历史跳转 → 零网络、零解压；
//  - Pager 的 A+B+C 预热照常工作（stream 层透明），命中时预热变纯磁盘读。
//
// 设计要点：
//  - 插入点 = ArchiveHandle 窄接口的装饰器（[CachingArchiveHandle]），
//    WebDavZipReader 快路径与 libarchive 回落路径统一覆盖，阅读链路零改动；
//  - 失效键 = normalizedUrl + 探测指纹(size:Last-Modified)，远程文件被替换 → 整书目录作废；
//  - 只缓存「可安全产出内容」的读取：加密包在密码未验证通过前不落盘；
//  - rar/7z 强制整本回退（isForcedFallback）时不启用——整本已落盘，页缓存只会双份占空间；
//  - 并发去重：同条目并发读取（Pager 预热 × holder）只在第一个线程拉取，其余等待共享；
//  - 淘汰：文件 lastModified LRU，上限复用设置-存储的 webdavCacheMaxBytes（与整本缓存
//    共用同一个滑条值，各自目录独立执行预算）。

/**
 * 页级磁盘缓存。目录结构：
 * ```
 * root/
 *   <sha1(metaKey)>/          ← 每本书一个目录（metaKey = normalizedUrl|fingerprint）
 *     .meta                   ← 记录 metaKey，内容不符 = 远程文件已变 → 整目录作废
 *     <sha1(entryName)>       ← 该页的原始图片字节
 * ```
 * 线程安全：所有方法可在任意线程调用；跨实例写入同一目录由 .meta 幂等化兜底。
 */
class WebDavPageCache(private val root: File, private val maxBytes: Long) {

    private val putCounter = AtomicLong()

    /** 返回该书可用的缓存目录；metaKey 不可得（未探测/强制回退）返回 null = 不缓存。 */
    fun bookDir(metaKey: String?): File? {
        if (metaKey.isNullOrBlank()) return null
        val dir = File(root, sha1(metaKey).take(24))
        val meta = File(dir, META_NAME)
        val existing = runCatching { meta.readText() }.getOrNull()
        if (existing != metaKey) {
            // 远程文件被替换（大小/Last-Modified 变了）→ 旧页全部作废
            dir.deleteRecursively()
        }
        dir.mkdirs()
        if (!meta.exists()) {
            runCatching { meta.writeText(metaKey) }
                .onFailure { logcat(LogPriority.WARN) { "[WebDavPageCache] meta 写入失败: ${it.message}" } }
        }
        return if (dir.isDirectory) dir else null
    }

    /** 读一页缓存；命中时顺带刷新 lastModified 作 LRU 依据。 */
    fun get(dir: File, entryName: String): ByteArray? {
        val file = pageFile(dir, entryName) ?: return null
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return runCatching { file.readBytes() }
            .onFailure { logcat(LogPriority.WARN) { "[WebDavPageCache] 缓存读取失败，按未命中处理: ${it.message}" } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** 写一页缓存（.part 临时文件 + 原子改名，避免中断留下半包）；周期性执行预算淘汰。 */
    fun put(dir: File, entryName: String, bytes: ByteArray) {
        val file = pageFile(dir, entryName) ?: return
        runCatching {
            val tmp = File(dir, file.name + ".part")
            tmp.writeBytes(bytes)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.onFailure { logcat(LogPriority.WARN) { "[WebDavPageCache] 缓存写入失败: ${it.message}" } }
        // 每 32 页淘汰一次（列目录有成本），首次打开书时也会兜底执行
        if (putCounter.incrementAndGet() % EVICT_INTERVAL == 0L) evict()
    }

    /** 超预算时按 lastModified 删最旧页文件（.meta 不参与）。 */
    fun evict() {
        if (maxBytes <= 0) return
        runCatching {
            val files = root.listFiles()
                ?.flatMap { book -> book.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: emptyList() }
                ?.filter { it.name != META_NAME }
                ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= maxBytes) return
                val size = file.length()
                if (file.delete()) total -= size
            }
        }.onFailure { logcat(LogPriority.WARN) { "[WebDavPageCache] 淘汰失败: ${it.message}" } }
    }

    /** 清空全部页缓存，返回释放的字节数（设置-存储「清除缓存」调用）。 */
    fun clearAll(): Long {
        val freed = root.listFiles()?.sumOf { it.length() } ?: 0L
        root.deleteRecursively()
        root.mkdirs()
        return freed
    }

    fun usageBytes(): Long =
        root.listFiles()?.flatMap { it.listFiles()?.toList() ?: emptyList() }?.sumOf { it.length() } ?: 0L

    private fun pageFile(dir: File, entryName: String): File? {
        val name = sha1(entryName)
        return if (name.isBlank()) null else File(dir, name)
    }

    private fun sha1(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val META_NAME = ".meta"
        const val EVICT_INTERVAL = 32L
    }
}

/**
 * [ArchiveHandle] 缓存装饰器：getInputStream 走「磁盘 → 网络 → 回填」，
 * 其余成员（条目枚举 / 加密状态 / 关闭）全部透传 delegate。
 *
 * [metaKey] 惰性求值：探测完成后才有值；返回 null 时全部直连 delegate（不缓存）。
 */
class CachingArchiveHandle(
    private val delegate: ArchiveHandle,
    private val cache: WebDavPageCache,
    private val metaKey: () -> String?,
) : ArchiveHandle by delegate {

    /** 同条目并发拉取去重（Pager 预热与 holder 会同时读同一页）。 */
    private val inFlight = ConcurrentHashMap<String, Any>()

    override fun getInputStream(entryName: String): InputStream? {
        // 加密包密码未验证通过（encrypted 且 wrongPassword 未确认 false）时不落盘，
        // 避免把解密失败的垃圾字节缓存下来反复投毒。
        val cacheable = !delegate.encrypted || delegate.wrongPassword == false
        val dir = if (cacheable) cache.bookDir(metaKey()) else null
        if (dir != null) {
            cache.get(dir, entryName)?.let { return it.inputStream() }
        }
        if (dir == null) return delegate.getInputStream(entryName)

        val lock = inFlight.computeIfAbsent(entryName) { Any() }
        synchronized(lock) {
            cache.get(dir, entryName)?.let { return it.inputStream() }
            val bytes = delegate.getInputStream(entryName)?.use { it.readBytes() } ?: return null
            if (bytes.isNotEmpty()) cache.put(dir, entryName, bytes)
            return bytes.inputStream()
        }
    }
}
