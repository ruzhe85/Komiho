package app.mihonsy.komga.source

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import tachiyomi.source.local.io.LocalSourceFileSystem
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import tachiyomi.source.local.io.Format

/**
 * Komiho P0（重做）：本地文件源 —— **文件管理器语义，不是图源**。
 *
 * 与 Mihon 的 [tachiyomi.source.local.LocalSource] 的区别：
 *  - LocalSource 是「图源」模型：base/<系列目录>/<章节文件>，只认两层、按名称查找，
 *    且把系列当漫画、文件当章节（必须先整理成这种结构才能看到东西）。
 *  - 本源只做一件事：把 chapter.url 里存的文件 URI 还原成 [UniFile]，交给
 *    ChapterLoader 分发到 DirectoryPageLoader / ArchivePageLoader / EpubPageLoader。
 *    目录层级、什么算「一本」全部由 UI 层（文件浏览器）决定，与源无关。
 *
 * 因此这里不提供任何目录浏览能力（getSearchManga 等返回空），
 * 页面也不是通过 getPageList 拿的——阅读走 getFormat，与 LocalSource 一致。
 */
class KomihoFileSource(
    private val context: Context,
    private val localSourceFs: LocalSourceFileSystem,
) : Source, UnmeteredSource {

    companion object {
        const val ID = 1_000_002L

        /** chapter.url 前缀，后面跟 encode 过的「相对本地根目录的路径」。 */
        const val FILE_URL_PREFIX = "komihofile://"

        /** 把相对路径（相对本地根目录）编码成 chapter.url。 */
        fun encodeUrl(relativePath: String): String =
            FILE_URL_PREFIX + Uri.encode(relativePath)
    }

    override val id: Long = ID
    override val name: String = "本地文件"
    override val lang: String = "other"
    override val supportsLatest: Boolean = false

    override fun toString() = name

    // 不走图源浏览：这些接口对本源没有意义，返回空即可（不抛异常，避免被其它
    // 通用逻辑（如全局搜索、库更新）调用时崩溃）。
    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = MangasPage(emptyList(), false)

    // 详情/章节由文件浏览器直接落库，无需再从源拉取。
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = SMangaUpdate(manga, chapters)

    // 页面由 ChapterLoader 经 getFormat() 分发，不走这里（同 LocalSource）。
    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw UnsupportedOperationException("Unused")

    /**
     * 把 chapter.url（相对路径）还原为文件，并判定其格式。
     *
     * - 目录（内含散图）→ [Format.Directory]
     * - 归档（cbz/zip/rar/cbr/7z/cb7/tar/cbt）→ [Format.Archive]
     * - epub → [Format.Epub]
     *
     * 关键：必须用「tree-backed 的 base.findFile(相对路径)」重建文件。
     * 若直接用子项的单文档 content URI 调 [UniFile.fromUri]，会得到
     * SingleDocumentFile，其 listFiles() 恒为空——这正是散图目录读不出图的根因。
     * base 来自 [LocalSourceFileSystem.getBaseDirectory]，已是 tree-backed。
     *
     * @throws Exception 目录未设置、文件不存在，或格式不受支持。
     */
    fun getFormat(chapter: SChapter): Format {
        val relativePath = Uri.decode(chapter.url.removePrefix(FILE_URL_PREFIX))
        val base = localSourceFs.getBaseDirectory()
            ?: throw Exception("本地漫画目录未设置或无法访问")
        val file = if (relativePath.isEmpty()) {
            base
        } else {
            // findFile 返回可空；任意一级找不到即抛错，确保最终类型为非空 UniFile。
            relativePath.split("/").fold(base) { dir, seg ->
                dir.findFile(seg) ?: throw Exception("找不到文件：$relativePath")
            }
        }
        if (!file.exists()) {
            throw Exception("文件不存在：$relativePath")
        }
        return try {
            Format.valueOf(file)
        } catch (e: Format.UnknownFormatException) {
            throw Exception("不支持的文件格式：$relativePath")
        }
    }
}
