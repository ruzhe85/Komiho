package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.diagnostic.DiagLog
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.pdf.PdfPasswordException
import eu.kanade.tachiyomi.util.pdf.PdfPasswordHolder
import eu.kanade.tachiyomi.util.pdf.PdfParser
import eu.kanade.tachiyomi.util.pdf.PdfRenderFallback
import eu.kanade.tachiyomi.util.pdf.remotePdfSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.min
import android.os.Build
import app.mihonsy.komga.data.withAppLanguage
import mihon.core.common.archive.RandomAccessSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR

/**
 * 本地 / 远程 PDF 页加载器（方案 A：自写提取 + 系统渲染兜底）。
 *
 * 每页交付「编码字节流」给既有解码+增强管线：
 *  - DCTDecode(JPEG) 内嵌图：直通原始字节，Lanczos3 / AI / NPU 增强无损生效（网点细节保住）。
 *  - 其他（CCITT/JPX/Flate/矢量/无内嵌图/解析失败）：[PdfRenderFallback] 渲染成 PNG 字节流。
 *
 * 健壮性（修复「打开 PDF 退回文件列表」）：
 *  - 不依赖 [UniFile.filePath]：Android 11+ 本地源常走 SAF，filePath 为 null 或直开 EACCES；
 *    此时整本经 [UniFile.openInputStream] 落到缓存再当本地文件解析。
 *  - 自写解析抛异常 / 0 页 / 加密识别失败 → 自动降级为「整本系统渲染」，保证任何 PDF 都能打开。
 *
 * 来源：
 *  - 本地：`PdfPageLoader(file, context)`。
 *  - 远程（WebDAV/SMB）：`PdfPageLoader(remoteUrl, context)` —— 首次取页时整本落本地缓存。
 */
internal class PdfPageLoader private constructor(
    private val context: Context,
    private val localFile: UniFile?,
    private val remoteUrl: String?,
) : PageLoader() {

    private lateinit var path: String
    private var parser: PdfParser? = null
    private var renderOnly = false
    /** 本次打开需带密码渲染时使用的密码（加密 PDF 走系统渲染兜底）。 */
    private var currentPassword: String? = null

    /** 本地 PDF：传 UniFile。 */
    constructor(file: UniFile, context: Context) : this(context, file, null)

    /** 远程 PDF（WebDAV/SMB）：传章节 url，首次取页时整本落本地缓存再复用本地解析。 */
    constructor(remoteUrl: String, context: Context) : this(context, null, remoteUrl)

    override var isLocal: Boolean = remoteUrl == null

    init {
        if (localFile != null && remoteUrl != null) {
            error("PdfPageLoader 不能同时持本地文件与远程 url")
        }
    }

    override suspend fun getPages(): List<ReaderPage> = withIOContext {
        path = resolvePath()
        // impl= 是本版实现标记：用来确认装到的包到底有没有「xref 扫描重建」这条路径。
        DiagLog.d(TAG, "PDF open: path=$path impl=$IMPL_MARK")

        // 超大 PDF（>200MB）不整文件载入内存解析（避免 OOM），直接走系统渲染兜底；
        // 仍用运行时探测判定加密，保证加密 PDF 弹密码框。
        val openStartedAt = android.os.SystemClock.elapsedRealtime()
        val fileSize = File(path).length()
        val probe = if (fileSize <= MAX_PARSER_FILE_BYTES) runCatching { PdfParser(path) }.getOrNull() else null
        DiagLog.d(
            TAG,
            "PDF probe: ${fileSize / 1024 / 1024}MB parser=${probe?.parseOk == true} pages=${probe?.pageCount} " +
                "耗时=${android.os.SystemClock.elapsedRealtime() - openStartedAt}ms",
        )
        // 文档里有没有 /Encrypt（离线判据，解析器偶发漏判）。
        val hasEncrypt = probe?.parseOk == true && probe.isEncrypted()
        // Komiho:「有 /Encrypt」≠「必须输密码」。权限型加密（只有所有者密码、**用户密码为空**，
        // 出版方用来禁复制/打印）在正常阅读器里直接打得开，系统 PDFium 无密码构造也成功 ——
        // 以前只看 /Encrypt 就弹框，会问一个根本不存在的密码，而同类书在别的阅读器都能开。
        // 「无密码构造必抛 SecurityException」是 PDFium 的既定行为（现有 isEncryptedPdf 就是这么用的），
        // 反过来正好当「是否真的要密码」的判据：
        //   hasEncrypt=false + 探测=true  → 解析器漏判，仍按加密处理（弹框）
        //   hasEncrypt=true  + 探测=false → 权限型，不弹框、直接整页渲染
        // 探测本身失败（非 SecurityException）时沿用旧口径 = hasEncrypt。
        // Komiho (2026-11-03): 解析器已经确认「结构能解析 + trailer 里没有 /Encrypt」时，不再让
        // PDFium 的 SecurityException 推翻它。有一类 PDF（「解锁」工具处理过的、加密字典残留的、
        // 或 PDFium 拒绝解析的）会让 PdfRenderer 一律抛 SecurityException，但文档其实一个密码都没有
        // —— 以前据此弹密码框，输什么都会失败，于是「没密码却反复要密码」。
        val parserTrusted = probe != null && probe.parseOk && probe.pageCount > 0
        // Komiho (2026-11-03 之二): 内容判据优先于「加密标志」。有的 PDF 带着 /Encrypt（解锁工具
        // 改坏、或加密字典残留），但流其实是明文 —— PDFium 一律报「需要密码」，而同类文件在别的
        // 阅读器里直接能开。判据：抽查前几页的 DCT 流，头是 FF D8（JPEG SOI）就说明没加密
        // （RC4/AES 处理过的流不可能正好以 FFD8 开头）。是明文就完全不问密码，直接走内嵌图直通。
        val streamsLookPlain = probe != null && parserTrusted && looksLikePlainJpeg(probe)
        val pdfiumNeedsPassword = if (parserTrusted && (!hasEncrypt || streamsLookPlain)) {
            false
        } else {
            runCatching { PdfRenderFallback.isEncryptedPdf(path) }.getOrElse { hasEncrypt }
        }
        // Komiho (2026-11-04): 裸 PdfRenderer 报「需要密码」时，先用「空密码 + LoadParams」再确认一次。
        // xref 损坏的文档走 LoadParams 路径往往能直接打开（实际一个密码都没有），
        // 之前一律弹框，用户只能靠乱猜 —— 猜中了才「可读」。
        val readableWithEmptyPassword = pdfiumNeedsPassword &&
            runCatching { PdfRenderFallback.isReadableWithEmptyPassword(path) }.getOrDefault(false)
        val needsPassword = pdfiumNeedsPassword && !readableWithEmptyPassword
        DiagLog.d(
            TAG,
            "PDF encrypt: parserEncrypt=$hasEncrypt parserTrusted=$parserTrusted " +
                "streamsLookPlain=$streamsLookPlain pdfiumNeedsPassword=$pdfiumNeedsPassword " +
                "readableWithEmptyPassword=$readableWithEmptyPassword needsPassword=$needsPassword",
        )
        // 是否按加密文档处理 —— 决定能不能走「内嵌图直通」：加密文档的流是 RC4/AES 密文，
        // 直通只会把密文当 JPEG 交下去，解出乱码。
        val encrypted = !streamsLookPlain && (hasEncrypt || needsPassword)

        var effectivePassword: String? = null

        if (encrypted) {
            // 证书型 DRM（/Encrypt 的 /Filter 非 Standard，如 Adobe.PubSec / Adobe Content Server）：
            // 要的是证书私钥而不是密码，弹密码框只会让用户反复「密码错误」又退不出去 → 直接给专门提示。
            val encryptFilter = if (hasEncrypt) probe?.encryptFilter() else null
            if (encryptFilter != null && !encryptFilter.equals("Standard", ignoreCase = true)) {
                DiagLog.w(TAG, "PDF certificate DRM, filter=$encryptFilter")
                throw Exception(context.withAppLanguage().stringResource(MR.strings.pdf_drm_certificate_error))
            }
            if (needsPassword) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    // 低于 Android 15 无 setPassword 入口，无法渲染需要密码的 PDF。
                    throw PdfPasswordException(unsupported = true)
                }
                val pw = PdfPasswordHolder.current
                if (pw == null) {
                    // 缺密码 → 弹框输入。
                    throw PdfPasswordException()
                }
                // 校验密码：错误则抛 wrongPassword。
                val n = runCatching { PdfRenderFallback.getPageCount(path, pw) }
                    .getOrElse { throw PdfPasswordException(wrongPassword = true) }
                if (n <= 0) throw PdfPasswordException(wrongPassword = true)
                effectivePassword = pw
                DiagLog.d(TAG, "PDF encrypted, render-only with password, pages=$n")
            } else {
                // 权限型加密：无密码渲染即可。currentPassword 保持 null —— 下面「SecurityException
                // 转 wrongPassword」只在真给过密码时才成立，权限型的不该被报成「密码错误」。
                // 但若裸构造打不开、只有 LoadParams 能开，就把空密码带上（否则渲染页会再抛 SecurityException）。
                if (readableWithEmptyPassword) effectivePassword = ""
                DiagLog.d(TAG, "PDF permission-only encryption, render-only without password")
            }
            renderOnly = true
        } else if (probe != null && probe.parseOk && probe.pageCount > 0) {
            parser = probe
            renderOnly = false
            DiagLog.d(TAG, "PDF extract mode, pages=${probe.pageCount}")
        } else {
            renderOnly = true
            // 同上：解析器吃不下、但 LoadParams 能用空密码打开 → 不弹框，带上空密码去渲染。
            if (readableWithEmptyPassword) effectivePassword = ""
            DiagLog.d(TAG, "PDF parse incomplete / 超大文件, fallback to render-only")
        }

        currentPassword = effectivePassword

        var dctPassthroughPages = 0
        val pages = if (renderOnly) {
            val n = PdfRenderFallback.getPageCount(path, currentPassword)
            DiagLog.d(TAG, "PDF render-only mode, pages=$n")
            if (n <= 0) throw Exception("PDF 无法解析（可能已损坏或已加密）")
            List(n) { i ->
                ReaderPage(i).apply {
                    stream = { openStream(i) }
                    status = Page.State.Ready
                    // Komiho (2026-11-04): 渲染页同样允许走 AI 增强 —— 之前把它们标成
                    // skipEnhance 是为了省时间，但用户期望 PDF 也能用 NPU 模型（扫描本正是
                    // 最需要放大的场景），所以这里恢复默认管线。
                }
            }
        } else {
            List(parser!!.pageCount) { i ->
                if (parser!!.bestImageForPage(i)?.filter == "DCTDecode") dctPassthroughPages++
                ReaderPage(i).apply {
                    stream = { openStream(i) }
                    status = Page.State.Ready
                }
            }
        }
        DiagLog.d(
            TAG,
            "PDF open done: mode=${if (renderOnly) "render" else "extract"} pages=${pages.size} " +
                "dct直通=$dctPassthroughPages " +
                "耗时=${android.os.SystemClock.elapsedRealtime() - openStartedAt}ms",
        )
        pages
    }

    private suspend fun resolvePath(): String = withIOContext {
        if (localFile != null) {
            // 优先用真实文件路径（最快）；SAF / 无 filePath / 直开会 EACCES 时整本落缓存。
            val fp = localFile.filePath
            if (fp != null && runCatching { java.io.RandomAccessFile(fp, "r").use { it.length() } }.isSuccess) {
                return@withIOContext fp
            }
            val cache = localCacheFile(localFile.uri.toString())
            if (!cache.exists() || cache.length() != localFile.length()) {
                localFile.openInputStream().use { input ->
                    FileOutputStream(cache).use { input.copyTo(it) }
                }
            }
            return@withIOContext cache.absolutePath
        }
        // 远程：整本下载到 cache 后当本地文件处理（渲染兜底也需本地文件）。
        val source = remotePdfSource(remoteUrl!!, context)
        try {
            val cacheFile = remoteCacheFile(remoteUrl!!)
            if (!cacheFile.exists() || cacheFile.length() != source.size) {
                downloadTo(source, cacheFile)
            }
            cacheFile.absolutePath
        } finally {
            source.close()
        }
    }

    private fun localCacheFile(key: String): File {
        val dir = File(context.cacheDir, "remote_pdf")
        dir.mkdirs()
        val name = "pdf_local_${key.hashCode().toLong().and(0xffffffffL).toString(16)}"
        return File(dir, name)
    }

    private fun remoteCacheFile(url: String): File {
        val dir = File(context.cacheDir, "remote_pdf")
        dir.mkdirs()
        val name = "pdf_${url.hashCode().toLong().and(0xffffffffL).toString(16)}"
        return File(dir, name)
    }

    private fun downloadTo(source: RandomAccessSource, out: File) {
        val tmp = File(out.parentFile, out.name + ".part")
        FileOutputStream(tmp).use { fos ->
            val size = source.size
            var offset = 0L
            // SY --> Komiho: 远程整本下载带进度（size 已知时上报百分比，否则仅保持转圈）。
            if (size > 0L) progressReporter?.invoke(0f)
            while (offset < size) {
                val len = min(1 shl 20, (size - offset).toInt())
                val buf = source.read(offset, len)
                if (buf.isEmpty()) break
                fos.write(buf)
                offset += buf.size
                if (size > 0L) progressReporter?.invoke((offset.toFloat() / size).coerceIn(0f, 1f))
            }
            if (size > 0L) progressReporter?.invoke(1f)
        }
        if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    private fun openStream(index: Int): InputStream {
        check(!isRecycled) { "PDF 页面读取时加载器已被回收——重载即恢复" }
        if (!renderOnly) {
            try {
                val img = parser!!.bestImageForPage(index)
                if (img != null && img.filter == "DCTDecode") {
                    val raw = parser!!.rawStreamBytes(img.objNum)
                    if (raw != null && raw.isNotEmpty()) {
                        // 直通即「拆原图」：落到这里的字节就是 PDF 内嵌 JPEG 的原样，
                        // 另存图片/增强拿到的都是这个尺寸，不再经过栅格化。
                        DiagLog.d(TAG, "page ${index + 1}: DCT passthrough ${img.width}x${img.height} (${raw.size}B)")
                        return ByteArrayInputStream(raw)
                    }
                }
                DiagLog.d(TAG, "page ${index + 1}: no passable DCT image (filter=${img?.filter}), render fallback")
            } catch (e: Exception) {
                DiagLog.w(TAG, "PDF extract page ${index + 1} failed, render fallback: ${e.message}")
            }
        }
        // 兜底：系统渲染（含加密带密码；密码错误时系统抛 SecurityException → 转 wrongPassword）
        val rendered = try {
            PdfRenderFallback.renderPage(path, index, currentPassword)
        } catch (e: SecurityException) {
            if (currentPassword != null) throw PdfPasswordException(wrongPassword = true)
            throw e
        } ?: throw IllegalStateException("PDF 第 ${index + 1} 页无法渲染（可能已损坏或加密）")
        return ByteArrayInputStream(rendered)
    }

    /**
     * 抽查前几页的 DCT 流是不是明文 JPEG。
     *
     * RC4/AES 加密后的流头是随机字节，不可能正好是 `FF D8`；能读出 SOI 就说明这本文档的
     * 图像流没有被加密（只是带着 /Encrypt 标记），可以放心走内嵌图直通、不必问密码。
     */
    private fun looksLikePlainJpeg(parser: PdfParser): Boolean = runCatching {
        for (i in 0 until min(parser.pageCount, PLAINTEXT_PROBE_PAGES)) {
            val img = parser.bestImageForPage(i) ?: continue
            if (img.filter != "DCTDecode") continue
            val raw = parser.rawStreamBytes(img.objNum) ?: continue
            if (raw.size < 3) continue
            val soi = raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()
            DiagLog.d(
                TAG,
                "plaintext probe page ${i + 1}: ${img.width}x${img.height} head=${hexHead(raw)} soi=$soi",
            )
            if (soi) return@runCatching true
        }
        false
    }.getOrDefault(false)

    private fun hexHead(bytes: ByteArray, count: Int = 4): String =
        bytes.take(count).joinToString("") { "%02X".format(it) }

    override fun recycle() {
        // Komiho: 释放复用的 PdfRenderer（否则整份文档会一直被 PDFium 持有到进程结束）。
        runCatching { PdfRenderFallback.releaseCachedRenderer() }
        super.recycle()
    }

    companion object {
        private const val TAG = "KomihoPdfLoader"

        /**
         * 解析器整文件载入内存的阈值：超过则跳过内存解析、直接走系统渲染兜底，避免大文件 OOM。
         *
         * Komiho (2026-11-04): 200MB → 320MB。清单里 `android:largeHeap="true"`，实测 171MB
         * 的扫描本完全可以解析（只是以前解析器自身有初始化顺序 bug，一进来就 NPE）。放宽后
         * 「200MB 出头的画集」也能走内嵌图直通，而不是整本栅格化。
         * 解析异常（含 OOM）由上层 runCatching 兜住，失败仍自动回落渲染，不会崩。
         */
        private const val MAX_PARSER_FILE_BYTES = 320L * 1024 * 1024 // 320MB

        /** 「流是否明文」抽查页数：前几页都是明文就够了，不必整本扫。 */
        private const val PLAINTEXT_PROBE_PAGES = 3

        /** 实现标记：出现在 `PDF open` 日志里，用来判断装到的包是否包含最新解析逻辑。 */
        private const val IMPL_MARK = "20261104d"
    }
}
