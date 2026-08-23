package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import java.io.File
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import app.mihonsy.komga.data.download.KomgaDownloadStore
import app.mihonsy.komga.source.KomgaSource
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.core.common.archive.archiveReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val manga: Manga,
    private val source: Source,
    // SY -->
    private val sourceManager: SourceManager,
    private val readerPrefs: ReaderPreferences,
    private val mergedReferences: List<MergedMangaReference>,
    private val mergedManga: Map<Long, Manga>,
    // SY <--
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter /* SY --> */, page: Int? = null/* SY <-- */) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read /* --> EH */ ||
                    readerPrefs
                        .preserveReadingPosition
                        .get() ||
                    page != null // <-- EH
                ) {
                    chapter.requestedPage = /* SY --> */ page ?: /* SY <-- */ chapter.chapter.last_page_read
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloaded(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            /* SY --> */ manga.ogTitle, /* SY <-- */
            manga.source,
            skipCache = true,
        )
        return when {
            // SY -->
            source is MergedSource -> {
                val mangaReference = mergedReferences.firstOrNull {
                    it.mangaId == chapter.chapter.manga_id
                } ?: error("Merge reference null")
                val source = sourceManager.get(mangaReference.mangaSourceId)
                    ?: error("Source ${mangaReference.mangaSourceId} was null")
                val manga = mergedManga[chapter.chapter.manga_id] ?: error("Manga for merged chapter was null")
                val isMergedMangaDownloaded = downloadManager.isChapterDownloaded(
                    chapterName = chapter.chapter.name,
                    chapterScanlator = chapter.chapter.scanlator,
                    chapterUrl = chapter.chapter.url,
                    mangaTitle = manga.ogTitle,
                    sourceId = manga.source,
                    skipCache = true,
                )
                when {
                    isMergedMangaDownloaded -> DownloadPageLoader(
                        chapter = chapter,
                        manga = manga,
                        source = source,
                        downloadManager = downloadManager,
                        downloadProvider = downloadProvider,
                    )
                    source is HttpSource -> HttpPageLoader(chapter, source)
                    source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                        when (format) {
                            is Format.Directory -> DirectoryPageLoader(format.file)
                            is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                            is Format.Epub -> EpubPageLoader(format.file.archiveReader(context))
                        }
                    }
                    else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
                }
            }
            // SY <--
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
            )
            source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                    is Format.Epub -> EpubPageLoader(format.file.archiveReader(context))
                }
            }
            // SY --> KomihoV2: 整本 CBZ 已下载时本地优先
            // Komga 下载以整本 CBZ 落盘（非 Mihon 的按章目录），因此
            // downloadManager.isChapterDownloaded 检测不到。此处显式查
            // KomgaDownloadStore：有本地 CBZ 则走 ArchivePageLoader 直接读
            // 本地 zip（复用既有的互斥锁解码管线），否则回退远程 HttpPageLoader。
            // 必须放在 HttpSource 分支之前，因为 KomgaSource 继承自 HttpSource。
            source is KomgaSource -> {
                val bookId = dbChapter.url.removePrefix(KomgaSource.BOOK_URL_PREFIX)
                val cbzPath = KomgaDownloadStore(context).getPath(bookId)
                if (cbzPath != null && File(cbzPath).exists()) {
                    ArchivePageLoader(UniFile.fromFile(File(cbzPath))!!.archiveReader(context.applicationContext))
                } else {
                    HttpPageLoader(chapter, source)
                }
            }
            // SY <--
            source is HttpSource -> HttpPageLoader(chapter, source)
            source is StubSource -> error(context.stringResource(MR.strings.source_not_installed, source.toString()))
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
    }
}
