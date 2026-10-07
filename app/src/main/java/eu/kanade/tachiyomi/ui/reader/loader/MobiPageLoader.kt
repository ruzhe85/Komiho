package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.util.Log
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.mobi.MobiDrmException
import eu.kanade.tachiyomi.util.mobi.MobiExtractor
import eu.kanade.tachiyomi.util.pdf.remotePdfSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.min
import app.mihonsy.komga.data.withAppLanguage
import mihon.core.common.archive.RandomAccessSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR

/**
 * 本地 / 远程 MOBI/AZW3/AZW 页加载器（libmobi 纯抽图，语义对齐 EpubPageLoader）。
 *
 * 每页交付「编码字节流」（libmobi 落盘的原始图片文件）给既有解码+增强管线：
 * Lanczos / AI / NPU 增强无损生效。不做文字渲染 —— 无图片的纯文字书报错，
 * 与 EPUB 纯文字书同口径（mobi_no_images_error）。
 *
 * 文件获取与 PdfPageLoader 同套路：
 *  - 不依赖 [UniFile.filePath]：SAF 下 filePath 可能为 null 或直开 EACCES，
 *    此时整本经 openInputStream 落到缓存再解析。
 *  - 远程（WebDAV/SMB）：首次取页时整本落本地缓存。
 *
 * 来源：
 *  - 本地：`MobiPageLoader(file, context)`。
 *  - 远程：`MobiPageLoader(remoteUrl, context)` —— 首次取页时整本落本地缓存。
 */
internal class MobiPageLoader private constructor(
    private val context: Context,
    private val localFile: UniFile?,
    private val remoteUrl: String?,
) : PageLoader() {

    private lateinit var path: String

    /** 本地 MOBI：传 UniFile。 */
    constructor(file: UniFile, context: Context) : this(context, file, null)

    /** 远程 MOBI（WebDAV/SMB）：传章节 url，首次取页时整本落本地缓存再复用本地解析。 */
    constructor(remoteUrl: String, context: Context) : this(context, null, remoteUrl)

    override var isLocal: Boolean = remoteUrl == null

    init {
        if (localFile != null && remoteUrl != null) {
            error("MobiPageLoader 不能同时持本地文件与远程 url")
        }
    }

    override suspend fun getPages(): List<ReaderPage> = withIOContext {
        path = resolvePath()
        Log.d(TAG, "MOBI open: path=$path")

        val book = try {
            MobiExtractor.extractImages(File(path), extractDir())
        } catch (e: MobiDrmException) {
            throw Exception(context.withAppLanguage().stringResource(MR.strings.mobi_drm_error))
        }

        if (book.imageFiles.isEmpty()) {
            throw Exception(context.withAppLanguage().stringResource(MR.strings.mobi_no_images_error))
        }
        Log.d(TAG, "MOBI extracted, images=${book.imageFiles.size}, title=${book.title}")

        book.imageFiles.mapIndexed { index, file ->
            ReaderPage(index).apply {
                stream = { FileInputStream(file) }
                status = Page.State.Ready
            }
        }
    }

    private suspend fun resolvePath(): String = withIOContext {
        if (localFile != null) {
            // 优先用真实文件路径（最快）；SAF / 无 filePath / 直开会 EACCES 时整本落缓存。
            // （RandomAccessFile 探测与 PdfPageLoader 同口径：content:// 的 filePath 可能解码出
            // 真实路径但直开 EACCES，canRead 判不出来，必须实际开一次。）
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
        // 远程：整本下载到 cache 后当本地文件处理。
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

    private fun extractDir(): File {
        val dir = File(File(context.cacheDir, "remote_mobi"), "pages_${path.hashCode().toLong().and(0xffffffffL).toString(16)}")
        dir.mkdirs()
        return dir
    }

    private fun localCacheFile(key: String): File {
        val dir = File(context.cacheDir, "remote_mobi")
        dir.mkdirs()
        val name = "mobi_local_${key.hashCode().toLong().and(0xffffffffL).toString(16)}"
        return File(dir, name)
    }

    private fun remoteCacheFile(url: String): File {
        val dir = File(context.cacheDir, "remote_mobi")
        dir.mkdirs()
        val name = "mobi_${url.hashCode().toLong().and(0xffffffffL).toString(16)}"
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

    companion object {
        private const val TAG = "KomihoMobiLoader"
    }
}
