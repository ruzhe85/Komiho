package mihon.core.common.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import logcat.LogPriority
import me.zhanghai.android.libarchive.ArchiveException
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.openFileDescriptor
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile

// SY --> Phase3: 实现 ArchiveHandle 窄接口，与 RemoteZipReader（远程 ZIP 直读路径）共用 ArchivePageLoader
class ArchiveReader : ArchiveHandle {

    val size: Long
    private val address: Long?
    private val source: RandomAccessSource?

    // 本地 mmap 构造器（SAF / content uri 走这条，保持原有行为）
    constructor(pfd: ParcelFileDescriptor) {
        size = pfd.statSize
        address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)
        source = null
        checkEncryptionStatus()
    }

    // 回调式构造器（Local / WebDAV / SMB 走这条，真正随机访问，不整本 mmap）
    constructor(source: RandomAccessSource) {
        this.source = source
        size = source.size
        address = null
        checkEncryptionStatus()
    }

    // SY -->
    override var encrypted: Boolean = false
        private set
    override var wrongPassword: Boolean? = null
        private set
    // 每 reader 实例唯一（用于 ArchivePageLoader 建临时目录）；mmap 路径取 mmap 地址，回调路径取 source 实例哈希
    override val archiveHashCode: Int
        get() = address?.hashCode() ?: source!!.hashCode()
    // SY <--

    private fun newStream(encrypted: Boolean): ArchiveInputStream =
        if (address != null) ArchiveInputStream(address, size, encrypted)
        else ArchiveInputStream(source!!, encrypted)

    // SY -->
    /**
     * 构造期扫描（[checkEncryptionStatus]）顺带收集的条目列表。
     *
     * 未加密归档会把全部条目扫完 → 完整缓存，[useEntries] 直接复用，省掉 `getPages()`
     * 的第二次全量扫描（rar/7z 这类 libarchive-only 格式打开停顿的主要来源之一）；
     * 加密归档在首个加密条目处提前 break（只为尽快弹密码框），此时列表不完整、不缓存。
     */
    @Volatile
    private var cachedEntries: List<ArchiveEntry>? = null
    // SY <--

    override fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T {
        cachedEntries?.let { return block(it.asSequence()) }
        return newStream(encrypted).use { block(generateSequence { it.getNextEntry() }) }
    }

    override fun getInputStream(entryName: String): InputStream? {
        val startedAt = SystemClock.elapsedRealtime()
        var scanned = 0L
        var hit = false
        val archive = newStream(encrypted)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                scanned++
                if (entry.name == entryName) {
                    hit = true
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        } finally {
            // Komiho (2026-10-04): 缓存本次查找成本。每次都从头扫 → 整本书解析是
            // O(页数 × 条目数)，小样本无感、整卷在大屏低端机上可能几十秒。
            // [recordLookup] 只打日志、不改行为。
            recordLookup(entryName, scanned, hit, SystemClock.elapsedRealtime() - startedAt)
        }
        archive.close()
        return null
    }

    // SY -->
    private fun checkEncryptionStatus() {
        val startedAt = SystemClock.elapsedRealtime()
        var scanned = 0L
        // SY --> 顺带收集条目列表，供 useEntries 复用（省掉第二次全量扫描）
        val collected = ArrayList<ArchiveEntry>()
        // SY <--
        val archive = newStream(false)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                scanned++
                // SY -->
                collected.add(entry)
                // SY <--
                if (entry.isEncrypted) {
                    encrypted = true
                    // SY: 仅在已设置全局密码时才校验对错，否则交由上层弹密码框（避免无密码时空抛）
                    if (CbzCrypto.isPasswordSet()) {
                        isPasswordIncorrect(entry.name)
                    }
                    break
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        } finally {
            // Komiho (2026-10-04): 构造 ArchiveReader 时的整包初扫 —— 打开本地大
            // epub/cbz 卡顿的第一站，先把它的代价量出来。
            logcat(tag = TAG) {
                "初始化扫描: 已读 $scanned 条, 耗时=${SystemClock.elapsedRealtime() - startedAt}ms"
            }
        }
        archive.close()
        // SY --> 未加密 = 已扫完全部条目，列表完整可复用；加密时上面提前 break，不缓存。
        if (!encrypted) cachedEntries = collected
        // SY <--
    }

    private fun isPasswordIncorrect(entryName: String) {
        try {
            getInputStream(entryName).use { stream ->
                stream!!.read()
            }
        } catch (e: ArchiveException) {
            if (e.message == "Incorrect passphrase") {
                wrongPassword = true
                return
            }
            throw e
        }
        wrongPassword = false
    }
    // SY <--

    override fun close() {
        if (address != null) Os.munmap(address, size)
        source?.close()
    }

    // SY --> Komiho (2026-10-04): 条目查找成本统计（仅诊断，不影响行为）
    /** 查找次数 / 累计扫描条目数 / 累计耗时 / 慢查数 / 未命中数。 */
    private val lookupCount = AtomicLong()
    private val lookupScanned = AtomicLong()
    private val lookupMillis = AtomicLong()
    private val lookupSlow = AtomicLong()
    private val lookupMiss = AtomicLong()

    /**
     * 记录一次条目查找的成本（只打日志、零行为影响）。
     *
     * 动机：[getInputStream] 每次查找都要新建归档流并**从第一个条目扫到目标**，
     * 整本书解析因此是 O(页数 × 条目数)。累计扫描条目数是这条曲线的直接证据：
     * 它会随查找次数近似平方增长 —— 拿真机数据就能证明/排除「解析太慢」这个假设。
     *
     * 未命中（扫全包）与单次慢查必记；常规查找每 [LOOKUP_LOG_EVERY] 次记一条累计。
     */
    private fun recordLookup(entryName: String, scanned: Long, hit: Boolean, elapsedMs: Long) {
        val count = lookupCount.incrementAndGet()
        val totalScanned = lookupScanned.addAndGet(scanned)
        val totalMs = lookupMillis.addAndGet(elapsedMs)
        if (!hit) lookupMiss.incrementAndGet()
        val slow = elapsedMs >= SLOW_LOOKUP_MS
        if (slow) lookupSlow.incrementAndGet()
        if (hit && !slow && count % LOOKUP_LOG_EVERY != 0L) return
        logcat(priority = if (hit) LogPriority.DEBUG else LogPriority.WARN, tag = TAG) {
            "条目查找 #$count ${if (hit) "命中" else "未命中(扫全包)"} entry=$entryName " +
                "本次扫描=$scanned 条/耗时=${elapsedMs}ms | 累计: 查找=$count 次, 扫描=$totalScanned 条, " +
                "耗时=${totalMs}ms, 慢查(≥${SLOW_LOOKUP_MS}ms)=${lookupSlow.get()}, 未命中=${lookupMiss.get()}"
        }
    }

    private companion object {
        private const val TAG = "ArchiveLookup"

        /** 单次查找达到它就单独记一条（看清单次代价）。 */
        private const val SLOW_LOOKUP_MS = 300L

        /** 常规查找每 N 次记一条累计（避免日志过载）。 */
        private const val LOOKUP_LOG_EVERY = 25L
    }
    // SY <--
}

fun UniFile.archiveReader(context: Context): ArchiveReader {
    val path = filePath
    // SY --> Phase2: 真实文件（uri.scheme == "file"）走 LocalRandomAccessSource 回调路径，
    // 真机验证 libarchive seek 架构；content uri（SAF / 系统文件选择器）一律走原 mmap 路径。
    // 关键：SAF 在 scoped storage 下只能经 content resolver 访问，filePath 虽能解码出真实路径，
    // 但直接 RandomAccessFile(File) 打开会 EACCES；不可仅凭 filePath 非空就走回调路径。
    if (uri?.scheme == "file" && !path.isNullOrEmpty()) {
        return ArchiveReader(LocalRandomAccessSource(File(path)))
    }
    return openFileDescriptor(context, "r").use { ArchiveReader(it) }
}

// SY --> issue #15：本地 ZIP 中央目录索引路径
/** [RemoteZipReader] 只支持 ZIP 家族扩展名；rar/7z/tar 直接走 libarchive。 */
private val ZIP_FAMILY_EXTENSIONS = setOf("zip", "cbz")

/**
 * 本地归档句柄（issue #15）：zip/cbz 优先进中央目录索引（[RemoteZipReader]）。
 *
 * 打开时只读尾部 EOCD + 中央目录建索引，之后每页直接定位读取（O(1)）；
 * 不再像 libarchive 路径那样「每次找条目从头扫一遍、打开时还要扫两次全包」。
 *
 * 其余格式（rar/cbr/7z/cb7/tar/cbt）、索引不支持或解析失败，一律回落 libarchive，行为不变。
 * SAF / content-uri 没有真实路径，但可通过 [PfdRandomAccessSource] 同样走索引。
 */
fun UniFile.indexedArchiveHandle(context: Context): ArchiveHandle {
    // 非 zip 家族保持原行为：真实文件走定位读、SAF 走 mmap。
    if (extension?.lowercase() !in ZIP_FAMILY_EXTENSIONS) return archiveReader(context)

    val path = filePath
    val isRealFile = uri?.scheme == "file" && !path.isNullOrEmpty()
    val source: RandomAccessSource = if (isRealFile) {
        LocalRandomAccessSource(File(path))
    } else {
        // SAF / content-uri：用 PFD 做定位读；所有权转交 source，close() 时一并关闭。
        PfdRandomAccessSource(openFileDescriptor(context, "r"))
    }
    val label = path ?: uri?.toString().orEmpty()

    try {
        val indexed = RemoteZipReader(source)
        val count = indexed.useEntries { it.count() }
        // 条目名一律按 UTF-8 解码：传统编码（GBK / Shift-JIS 等且未置 UTF-8 标志）会解成
        // U+FFFD，不同条目可能撞成同一个 key → 丢页。这种情况坚决回落 libarchive。
        val badNames = count > 0 &&
            indexed.useEntries { seq -> seq.any { it.name.contains('\uFFFD') } }
        if (count > 0 && !badNames) {
            logcat(LogPriority.INFO) { "[Archive] 索引化打开 zip=$label 条目=$count" }
            return indexed
        }
        // 非 ZIP 的尾部偶然命中 EOCD 时会解析出空索引 → 回落 libarchive。
        logcat(LogPriority.WARN) {
            "[Archive] 索引不可用（空索引或文件名非 UTF-8），回落 libarchive: $label"
        }
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) {
            "[Archive] RemoteZipReader 解析失败，回落 libarchive: ${e.message} path=$label"
        }
    }

    // 真实文件复用同一 source（定位读无游标）；SAF 关掉索引用的 PFD 后走原来的 mmap 路径。
    if (isRealFile) return ArchiveReader(source)
    runCatching { source.close() }
    return openFileDescriptor(context, "r").use { ArchiveReader(it) }
}
// SY <--

// SY -->
/**
 * 加密归档缺少密码 / 密码错误时抛出，上层据此在阅读器内弹密码输入对话框。
 * [wrongPassword]=true 表示已输入过但密码不正确。
 */
class ArchivePasswordException(val wrongPassword: Boolean = false) :
    Exception(if (wrongPassword) "密码错误，请重新输入" else "此压缩包已加密，请输入密码")
// SY <--
