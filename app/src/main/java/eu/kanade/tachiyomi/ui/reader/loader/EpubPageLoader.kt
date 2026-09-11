package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.storage.EpubFile
import mihon.core.common.archive.ArchiveReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Loader used to load a chapter from a .epub file.
 *
 * Komiho: 本地 EPUB 走的是「抽取图片页」路径（[EpubFile] 只解析 OPF/spine 里的
 * `<img>`/`<image xlink:href>`），因此纯文字书（小说）会得到 0 页 —— 这是设计限制，
 * 不是文件损坏。这里给出明确提示，避免上层用通用的「No pages found」含糊带过。
 */
internal class EpubPageLoader(
    reader: ArchiveReader,
    private val context: Context,
) : PageLoader() {

    private val epub = EpubFile(reader)

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val images = epub.getImagesFromPages()
        if (images.isEmpty()) {
            throw Exception(context.stringResource(MR.strings.loader_epub_no_images_error))
        }
        return images.mapIndexed { i, path ->
            val streamFn = { epub.getInputStream(path)!! }
            ReaderPage(i).apply {
                stream = streamFn
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        epub.close()
    }
}
