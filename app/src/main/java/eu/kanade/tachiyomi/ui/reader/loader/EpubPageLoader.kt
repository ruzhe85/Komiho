package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.os.SystemClock
import app.mihonsy.komga.data.withAppLanguage
import eu.kanade.tachiyomi.diagnostic.DiagLog
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.storage.EpubFile
import mihon.core.common.archive.ArchiveHandle
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException

/**
 * Loader used to load a chapter from a .epub file.
 *
 * Komiho: 本地 / WebDAV / SMB 的 EPUB 都走这里（参数为窄接口 [ArchiveHandle]，
 * 本地是 ArchiveReader，远程是 RemoteZipReader / CachingArchiveHandle）。
 * 阅读方式是「抽取图片页」—— [EpubFile] 只解析 OPF/spine 里的 `<img>`/`<image xlink:href>`，
 * 因此纯文字书（小说）会得到 0 页，这是设计限制，不是文件损坏；
 * 这里给出明确提示，避免上层用通用的「No pages found」含糊带过。
 */
internal class EpubPageLoader(
    reader: ArchiveHandle,
    private val context: Context,
) : PageLoader() {

    private val epub = EpubFile(reader)

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val startedAt = SystemClock.elapsedRealtime()
        // Komiho: DRM 的书要和「纯文字书没有图片页」分开报 —— 两者都是抽到 0 张图，但 DRM 是
        // 「读不了」：原来的提示让人「改用 Komga 服务器」也是错的（服务端同样解不开 DRM）。
        if (epub.isDrmProtected()) {
            throw Exception(context.withAppLanguage().stringResource(MR.strings.loader_epub_drm_error))
        }
        // Komiho (2026-10-04): 分阶段计时。「打不开」的导出日志原来只能看出「日志断了」，
        // 加上这几行才能看出断在哪一步（DRM 检查 / 图片抽取 / 页列表构建 / 首张图渲染）。
        DiagLog.d(TAG, "DRM 检查完成(未加密) 耗时=${SystemClock.elapsedRealtime() - startedAt}ms")
        val images = epub.getImagesFromPages()
        DiagLog.d(TAG, "图片路径抽取完成: ${images.size} 张, 耗时=${SystemClock.elapsedRealtime() - startedAt}ms")
        if (images.isEmpty()) {
            // withAppLanguage()：reader 的 context 来自 ReaderActivity（Mihon 的 BaseActivity），
            // 不经过 Komiho 的语言包装，直接取会落到系统语言。
            throw Exception(context.withAppLanguage().stringResource(MR.strings.loader_epub_no_images_error))
        }
        val pages = images.mapIndexed { i, path ->
            ReaderPage(i).apply {
                // Komiho (2026-10-04): 图片流是**渲染时**才打开的（这里只挂一个 lambda），
                // 前几页各记一条、取不到时记一条告警 —— 「页列表建好了但渲染时打不开」
                // 这一层原来完全没痕迹。原来这里是 `!!`，抛出的 NPE 没有 message，
                // 日志里只剩一个 null；换成带路径的 IOException 才可定位。
                stream = {
                    val openedAt = SystemClock.elapsedRealtime()
                    val opened = epub.getInputStream(path)
                    if (opened == null) {
                        DiagLog.w(TAG, "图片条目缺失(第 $i 页): $path")
                        throw IOException("EPUB 图片条目缺失: $path (page=$i)")
                    }
                    if (i < PAGE_OPEN_LOG_LIMIT) {
                        DiagLog.d(
                            TAG,
                            "打开第 $i 页图片: $path 耗时=${SystemClock.elapsedRealtime() - openedAt}ms",
                        )
                    }
                    opened
                }
                status = Page.State.Ready
            }
        }
        DiagLog.d(
            TAG,
            "页列表构建完成: ${pages.size} 页, 总耗时=${SystemClock.elapsedRealtime() - startedAt}ms",
        )
        return pages
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        epub.close()
    }

    private companion object {
        /** 诊断日志 tag（导出文件里按它 grep 整条 EPUB 解析链）。 */
        const val TAG = "EpubParse"

        /** 只记前 N 页「渲染时打开图片流」的耗时，避免页多时刷屏。 */
        const val PAGE_OPEN_LOG_LIMIT = 3
    }
}
