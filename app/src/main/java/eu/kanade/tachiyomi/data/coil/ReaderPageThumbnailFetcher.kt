package eu.kanade.tachiyomi.data.coil

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.tachiyomi.diagnostic.DiagLog
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.readAllBytesCompact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 进度条缩略图：复用章节 [PageLoader] 加载某一页，子采样解码为小尺寸 JPEG 后交给 Coil。
 *
 * 用于 Komga 之外的来源（本地 / SMB / WebDAV / 远程 HttpSource 等）——这些来源没有服务端
 * 缩图接口，本取图器自行做「两次 decode」子采样（与 LocalCoverFetcher.decodeSampled 同策略）：
 * 先 inJustDecodeBounds 探尺寸 → 算 inSampleSize（目标 ~THUMB_PX）→ 再按采样比解码，
 * 保证**绝不先解整张原图再缩小**（原图常 1000–3000px，整解会卡顿、GC 抖动）。
 *
 * 三级缓存（issue #17，SMB 拖动进度条 OOM）：
 * - L1 进程内 LRU（章节+页码 → 压缩后小图字节），重复拖动直接命中；
 * - L2 磁盘（`cacheDir/reader_thumbs`），跨进程/跨次打开仍在，**同一页只拉一次原图**；
 * - L3 现读现解码：全局最多 [MAX_CONCURRENT] 个并发（旧实现没有闸门，8 路 Coil 并发 ×
 *   整页十几 MB × 取消不掉的阻塞读，拖动进度条就能顶爆 512MB heap）。
 *
 * 两道准入闸门：
 * - 取消语义：阻塞式网络读**不可中断**，所以必须在「真的开始读」之前就把过期请求丢掉 ——
 *   拿到信号量之后先 `ensureActive()`；
 * - 内存准入：按「这一页的字节数 + [HEAP_RESERVE_BYTES]」要求剩余堆，不够就这次不出图。
 *   解码走 [decodeCoverBitmapBytes]（字节直解、零复制），单路峰值只有「整页字节 + 位图」，
 *   所以几十 MB 的巨页在堆宽裕时也能出缩略图。
 */
class ReaderPageThumbnailFetcher(
    private val pageLoader: PageLoader,
    private val readerPage: ReaderPage,
    private val cacheKey: String,
    private val options: Options,
    /** 磁盘二级缓存目录（cacheDir 下）；null = 不落盘。 */
    private val diskDir: File?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        ThumbnailCache[cacheKey]?.let { return bytesResult(it) }
        readDisk()?.let { return bytesResult(it) }

        val bytes = generate()
        ThumbnailCache.put(cacheKey, bytes)
        writeDisk(bytes)
        return bytesResult(bytes)
    }

    /**
     * 真正生成缩略图：全局信号量 + 取消检查 + 体积上限。
     * 被取消的请求若还在排队，将直接放弃（CancellationException），一次网络读都不会发出去。
     */
    private suspend fun generate(): ByteArray = thumbnailGate.withPermit {
        currentCoroutineContext().ensureActive()
        // 排队期间可能已有同页请求生成完毕（或已落盘）——先命中再决定是否读原图。
        ThumbnailCache[cacheKey]?.let { return@withPermit it }
        readDisk()?.let { return@withPermit it }

        // 堆余量闸门：缩略图是「可有可无」的装饰，不能用崩溃换。
        // 阅读器本身（解码位图 + 增强）常常占掉大半 heap，此时再读一整页原图就会
        // 在 smbj 的读线程里抛 OOM（issue #17 现场），直接放弃出图。
        // 阈值按「这一页有多大」算，而不是一刀切：小页只需保底余量，20MB 的页才需要更多。
        val size = readerPage.sourceSize
        if (size > MAX_SOURCE_BYTES) {
            DiagLog.w("ReaderThumb", "skip page=${readerPage.index} size=${size}B 超过上限")
            throw IOException("page ${readerPage.index} 体积 ${size}B 超过缩略图读取上限")
        }
        // 多路并行时必须记账：三路各自「看到足够余量」再一起分配，等于谁都没看到。
        val required = maxOf(size, UNKNOWN_SIZE_ASSUMPTION) + HEAP_RESERVE_BYTES
        if (!HeapBudget.tryReserve(required)) {
            DiagLog.w(
                "ReaderThumb",
                "skip page=${readerPage.index} size=${size}B 堆余量不足" +
                    "（需要 ${required / 1024 / 1024}MB，已被缩略图占用 ${HeapBudget.reservedMB()}MB）",
            )
            throw IOException("page ${readerPage.index} 堆余量不足，跳过缩略图")
        }
        try {
            return@withPermit produce(size)
        } finally {
            HeapBudget.release(required)
        }
    }

    /** 读页 → 解码 → 压缩成小图字节。调用方已持有信号量名额与堆预算。 */
    private suspend fun produce(size: Long): ByteArray {
        // 触发加载（fire-and-forget）：
        // - 本地 / SMB / WebDAV 的页在 getPages() 阶段已是 Ready，loadPage 是 no-op，立即返回；
        // - 远程 HttpSource 的页初始为 Queue，loadPage 会入队异步加载；
        // 注意 HttpPageLoader.loadPage 对「已 Ready」页会挂起（其 continuation 不在本处 resume），
        // 故只用 detached scope fire-and-forget，真正等待交给下方 statusFlow，绝不 await loadPage 本身。
        val trigger = if (readerPage.status !is Page.State.Ready) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { pageLoader.loadPage(readerPage) }
            }
        } else {
            null
        }
        try {
            // 等到终态（已就绪则立即返回，不会挂起）。
            // 超时兜底：远程源的页可能排队后一直不进入终态，若不设上限，这三个信号量名额
            // 会被永久占住，整个阅读器的缩略图都停摆（超时后按失败处理，走占位）。
            val settled = withTimeoutOrNull(PAGE_WAIT_TIMEOUT_MS) {
                readerPage.statusFlow.first { it is Page.State.Ready || it is Page.State.Error }
            }
            if (settled == null) {
                DiagLog.w("ReaderThumb", "page=${readerPage.index} 等待页就绪超时")
            }
        } finally {
            trigger?.cancel()
        }

        if (readerPage.status !is Page.State.Ready) {
            throw IOException("page ${readerPage.index} failed to load for thumbnail")
        }

        val bitmap = readThumbnailBitmap(size)
        return runCatching {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }.getOrNull().also { bitmap.recycle() }
            ?: throw IOException("page ${readerPage.index} compress failed for thumbnail")
    }

    /**
     * 读整页字节 → 采样解码 → 返回位图。
     *
     * 刻意放在独立函数里：返回后整页字节立即不可达，GC 可以在压缩/落盘之前就回收这十几 MB，
     * 而不是陪着请求活到 `generate()` 结束。解码走 [decodeCoverBitmapBytes]（零复制），
     * 峰值 = 整页字节 + 位图，不再叠加一份等长的副本。
     */
    private fun readThumbnailBitmap(size: Long): Bitmap {
        // 来源能直通字节就直通（SMB / WebDAV / 归档本来就先攒出一个 ByteArray），
        // 省掉「stream → 再读一份」的等长复制；其余来源（本地文件、远程 HttpSource）只能从流读。
        val raw = runCatching { readerPage.rawBytes?.invoke() }.getOrNull()
            ?: readerPage.stream?.invoke()?.use {
                // 已知大小按精确长度读取（峰值 1 倍）；未知走分块收集（峰值 2 倍）。
                it.readAllBytesCompact(size)
            }
            ?: throw IOException("page ${readerPage.index} stream unavailable for thumbnail")
        if (raw.isEmpty()) {
            throw IOException("page ${readerPage.index} empty bytes for thumbnail")
        }
        if (raw.size > MAX_SOURCE_BYTES) {
            DiagLog.w("ReaderThumb", "skip page=${readerPage.index} actual=${raw.size}B 超过上限")
            throw IOException("page ${readerPage.index} 实际 ${raw.size}B 超过缩略图读取上限")
        }
        return decodeCoverBitmapBytes("reader page=${readerPage.index}", THUMB_PX, raw)
            ?: throw IOException("page ${readerPage.index} decode failed for thumbnail")
    }

    /** 读磁盘二级缓存（命中即回填 L1）。读失败按未命中处理，不打断流程。 */
    private fun readDisk(): ByteArray? {
        val dir = diskDir ?: return null
        val file = File(dir, diskName(cacheKey))
        if (file.length() <= 0) return null
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return null
        ThumbnailCache.put(cacheKey, bytes)
        return bytes
    }

    /** 写磁盘二级缓存：先写临时文件再改名，避免中断留下半张图。失败静默。 */
    private fun writeDisk(bytes: ByteArray) {
        val dir = diskDir ?: return
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val target = File(dir, diskName(cacheKey))
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) tmp.delete()
            ThumbnailDiskPrune.maybe(dir)
        }
    }

    /** 把已压缩的小图字节包成 SourceFetchResult（标注 MEMORY，避免 Coil 落盘缓存）。 */
    private fun bytesResult(bytes: ByteArray): SourceFetchResult {
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(bytes),
                fileSystem = options.fileSystem,
            ),
            mimeType = "image/jpeg",
            dataSource = DataSource.MEMORY,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<ReaderPageThumbnailRequest> {
        private val diskDir = File(context.cacheDir, DIR_THUMBS)

        override fun create(
            data: ReaderPageThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return ReaderPageThumbnailFetcher(
                pageLoader = data.pageLoader,
                readerPage = data.readerPage,
                cacheKey = data.key,
                options = options,
                diskDir = diskDir,
            )
        }
    }
}

/**
 * 进度条缩略图的 Coil 内存缓存键。
 *
 * 不注册的话 Coil 会退回 `data.toString()`（含对象身份 hash），重组时虽然值相同但语义脆弱；
 * 显式给「章节+页码」既能稳定去重（同一页的重复请求只跑一次），也避免与其它来源的字符串键撞车。
 */
class ReaderPageThumbnailKeyer : Keyer<ReaderPageThumbnailRequest> {
    // 带上请求尺寸：同一页在竖屏（3 张、更小）与横屏（4 张、更大）下需要的位图尺寸不同，
    // 键里不带尺寸会直接把竖屏解好的小图拿去横屏放大显示（发虚）。
    // 真正的「同一页只拉一次原图」由取图器自己的 L1/L2 缓存保证，与这个键无关。
    override fun key(data: ReaderPageThumbnailRequest, options: Options): String =
        "reader-thumb:${data.key}@${options.size}"
}

/**
 * 进程内缩略图 LRU：key = 章节+页码（[ReaderPageThumbnailRequest.key]），value = 压缩后的小图字节。
 * 上限 ~8MB：单张缩图（THUMB_PX=480, q85）约 30–80KB，可容纳上百张；LRU 淘汰最旧。
 */
private object ThumbnailCache {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val cache = object : LruCache<String, ByteArray>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    operator fun get(key: String): ByteArray? = cache.get(key)
    fun put(key: String, bytes: ByteArray) = cache.put(key, bytes)
}

/**
 * 缩略图链路的内存记账。
 *
 * 多路并行时，如果每路都只看「当前堆还够」就各分配一份整页字节，结果是谁都看到了余量、
 * 谁都没真正留出余量（issue #17 的乘数效应）。这里在准入时先把「将要占用的字节数」记上账，
 * 后来的请求看到的是扣掉在途请求之后的余量。
 */
private object HeapBudget {
    private val reserved = AtomicLong(0L)

    fun tryReserve(bytes: Long): Boolean = synchronized(this) {
        val free = Runtime.getRuntime().let { it.maxMemory() - (it.totalMemory() - it.freeMemory()) }
        if (free - reserved.get() < bytes) return@synchronized false
        reserved.addAndGet(bytes)
        true
    }

    fun release(bytes: Long) {
        reserved.addAndGet(-bytes)
    }

    fun reservedMB(): Long = reserved.get() / 1024 / 1024
}

/** 磁盘缩略图缓存的容量回收：超过上限时按最久未用删除（最多每 30s 扫一次目录）。 */
private object ThumbnailDiskPrune {
    private const val MAX_BYTES = 48L * 1024 * 1024
    private const val MIN_INTERVAL_MS = 30_000L
    private val lastRun = AtomicLong(0L)

    fun maybe(dir: File) {
        val now = System.currentTimeMillis()
        val prev = lastRun.get()
        if (now - prev < MIN_INTERVAL_MS || !lastRun.compareAndSet(prev, now)) return
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile } ?: return@runCatching
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return@runCatching
            val target = MAX_BYTES * 3 / 4
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= target) return@forEach
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
    }
}

/**
 * 进度条缩略图的 Coil 模型：携带章节 [PageLoader] 与目标 [ReaderPage]。
 * [key] 用于进程内 LRU 与 Coil 内存缓存键（按 章节+页码 唯一），避免跨章同名页串图。
 */
data class ReaderPageThumbnailRequest(
    val key: String,
    val pageLoader: PageLoader,
    val readerPage: ReaderPage,
)

/** 磁盘缓存文件名：key 的 SHA-256（含 `#`、`/` 等路径字符，不能直接当文件名）。 */
private fun diskName(key: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
    return digest.joinToString("") { "%02x".format(it) } + ".jpg"
}

// SY --> Komiho: 进度气泡缩略图子采样相关常量
private const val THUMB_PX = 480
private const val JPEG_QUALITY = 85

/**
 * 同时进行「现读现解码」的缩略图数量。
 *
 * 旧实现没有闸门：8 路 Coil 并发 × 整页十几 MB × 「取消不掉」的阻塞读，拖动进度条
 * 足够把 512MB heap 撑爆（issue #17 现场）。解码改成零复制后单路峰值只剩「整页字节 + 位图」，
 * 3 路正好覆盖竖屏三张（横屏 4 张则 3+1），不再为串行多付一轮等待。
 */
private const val MAX_CONCURRENT = 3
private val thumbnailGate = Semaphore(MAX_CONCURRENT)

/**
 * 单页允许读取的硬上限（仅作异常兜底，与 ImageUtil 的解码上限同口径）。
 *
 * 这里**不是**体验阀值：真正的准入判断是按「这一页多大 + 还剩多少堆」算的
 * （见 [HEAP_RESERVE_BYTES]），几十 MB 的巨页在堆宽裕时照样出图。
 */
private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024

/** 未知页大小时的保守假设：按它来要求堆余量。 */
private const val UNKNOWN_SIZE_ASSUMPTION = 16L * 1024 * 1024

/**
 * 除整页字节外必须留出的余量：解码位图（采样到 480px，1MB 内）+ 压缩缓冲 + 给阅读器本身的
 * 正常周转。低于它就不生成缩略图——装饰性小图不值得把进程撑爆。
 */
private const val HEAP_RESERVE_BYTES = 48L * 1024 * 1024

/**
 * 等待目标页进入终态的上限。SMB 请求本身有 8s 超时，这里留出余量；
 * 超时只影响这一张缩略图（显示占位），不会占着信号量名额拖垮整条链路。
 */
private const val PAGE_WAIT_TIMEOUT_MS = 15_000L

/** 磁盘缓存的子目录名（cacheDir 下）。 */
private const val DIR_THUMBS = "reader_thumbs"
// SY <--
