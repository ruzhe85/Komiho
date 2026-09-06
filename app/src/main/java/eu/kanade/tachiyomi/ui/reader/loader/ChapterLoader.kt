package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import java.io.File
import java.io.IOException
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import app.mihonsy.komga.data.download.KomgaDownloadStore
import app.mihonsy.komga.data.webdav.WebDavConnectionStore
import app.mihonsy.komga.data.webdav.WebDavCoverCache
import app.mihonsy.komga.data.remote.CachingArchiveHandle
import app.mihonsy.komga.data.remote.RemotePageCache
import app.mihonsy.komga.data.smb.SmbConnectionStore
import app.mihonsy.komga.data.smb.SmbCoverCache
import app.mihonsy.komga.data.smb.SmbRandomAccessSource
import app.mihonsy.komga.source.KomgaSource
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import mihon.core.common.archive.ArchiveHandle
import mihon.core.common.archive.ArchiveReader
import mihon.core.common.archive.RemoteScheme
import mihon.core.common.archive.RemoteZipReader
import mihon.core.common.archive.WebDavRandomAccessSource
import mihon.core.common.archive.archiveReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
                            // SY --> Komiho Phase3/Phase7: 远程随机访问（WebDAV=HTTP Range，SMB=原生 offset 读）
                            // SY: SMB 且 URL 以 / 结尾 = 散图目录章节（点目录内图片打开）。
                            is Format.RemoteArchive -> when {
                                smbIsDirectoryChapter(format.remoteUrl) -> smbDirectoryLoader(format.remoteUrl)
                                webDavIsDirectoryChapter(format.remoteUrl) -> webDavDirectoryLoader(format.remoteUrl)
                                else -> ArchivePageLoader(remoteArchiveHandle(format.remoteUrl))
                            }
                            // SY <--
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
                    // SY --> Komiho Phase3/Phase7: 远程随机访问（WebDAV=HTTP Range，SMB=原生 offset 读）
                    // SY: SMB 且 URL 以 / 结尾 = 散图目录章节（点目录内图片打开）。
                    is Format.RemoteArchive -> when {
                        smbIsDirectoryChapter(format.remoteUrl) -> smbDirectoryLoader(format.remoteUrl)
                        webDavIsDirectoryChapter(format.remoteUrl) -> webDavDirectoryLoader(format.remoteUrl)
                        else -> ArchivePageLoader(remoteArchiveHandle(format.remoteUrl))
                    }
                    // SY <--
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

    // SY --> Komiho Phase4: 由 `webdav:` 章节 url 构造远程随机访问源。
    // 凭据按章节 URL 双格式解析（D2.A）：新格式 `webdav://<connId>/<URL>` 按 connId 精确取，
    // 旧格式 `webdav:<URL>` 回落 baseUrl 最长前缀匹配的连接（历史章节不改 DB）。
    // 服务器不支持 Range 时整本缓存回退目录 cacheDir/webdav_fallback；
    // 磁盘上限设置-存储可调（webdavCacheMaxBytes，默认 1GB），超限 LRU 淘汰。
    private fun webDavSource(remoteUrl: String): WebDavRandomAccessSource {
        val credentials = WebDavConnectionStore.credentialsFor(remoteUrl)
        return WebDavRandomAccessSource(
            url = WebDavConnectionStore.extractFullUrl(remoteUrl),
            username = credentials?.first?.ifBlank { null },
            password = credentials?.second?.ifBlank { null },
            fallbackCacheDir = File(context.cacheDir, "webdav_fallback"),
            cacheMaxBytes = Injekt.get<StoragePreferences>().webdavCacheMaxBytes.get(),
        )
    }

    // SY --> Komiho Phase7: 散图目录章节——URL 约定 `smb://<connId>/<dirRel>/`（尾斜杠）。
    private fun smbIsDirectoryChapter(remoteUrl: String): Boolean =
        remoteUrl.startsWith("smb://") && remoteUrl.endsWith("/")

    private fun smbDirectoryLoader(remoteUrl: String): SmbDirectoryPageLoader {
        val target = SmbConnectionStore.resolve(remoteUrl)
            ?: throw IOException("SMB 连接不存在（可能已删除）: $remoteUrl")
        val dirRel = SmbConnectionStore.extractRelPath(remoteUrl).trim('/')
        return SmbDirectoryPageLoader(target.conn, target.password, dirRel)
    }
    // SY <--

    // SY --> Komiho Phase7: WebDAV 散图目录章节——URL 约定 `webdav://<connId>/<dirUrl>/`（尾斜杠）。
    private fun webDavIsDirectoryChapter(remoteUrl: String): Boolean =
        remoteUrl.startsWith("webdav:") && remoteUrl.endsWith("/")

    private fun webDavDirectoryLoader(remoteUrl: String): WebDavDirectoryPageLoader {
        val connId = remoteUrl.removePrefix("webdav://").substringBefore('/')
        val conn = WebDavConnectionStore.all().firstOrNull { it.id == connId }
            ?: throw IOException("WebDAV 连接不存在（可能已删除）: $remoteUrl")
        val dirUrl = WebDavConnectionStore.extractFullUrl(remoteUrl)
            .let { if (it.endsWith('/')) it else "$it/" }
        return WebDavDirectoryPageLoader(conn, dirUrl)
    }
    // SY <--

    // SY --> Komiho Phase7: 远程归档按 URL 方案分派（webdav: / smb://）。
    private fun remoteArchiveHandle(remoteUrl: String): ArchiveHandle =
        if (RemoteScheme.isSmb(remoteUrl)) smbArchiveHandle(remoteUrl) else webDavArchiveHandle(remoteUrl)

    // SY --> Komiho Phase7: 由 `smb://<connId>/<relPath>` 章节 url 构造 SMB 随机访问源。
    // 与 WebDAV 的差异：SMB 原生支持按偏移读，无 Range 探测、无 rar/7z 整本缓存回退；
    // 代价是会话有状态——由 SmbSessionManager 池化 + 断线重连（读写失败作废会话）。
    // 页缓存与 WebDAV 共用同一个 RemotePageCache（键为 smb 定位串 + 指纹，天然不冲突）。
    private fun smbArchiveHandle(remoteUrl: String): ArchiveHandle {
        val target = SmbConnectionStore.resolve(remoteUrl)
            ?: throw IOException("SMB 连接不存在（可能已删除）: $remoteUrl")
        // SY --> Komiho Phase7: 打开章节时「顺便」生成历史/书签封面（缺缓存才拉，失败静默）。
        SmbCoverCache.generateAsync(context, remoteUrl)
        // SY <--
        val source = SmbRandomAccessSource(target.conn, target.password, target.relPath)
        val delegate: ArchiveHandle = try {
            RemoteZipReader(source)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "RemoteZipReader 解析失败，回落 libarchive 路径: ${e.message}" }
            ArchiveReader(source)
        }
        return CachingArchiveHandle(
            delegate = delegate,
            cache = RemotePageCache(
                root = File(context.cacheDir, "remote_pages"),
                maxBytes = Injekt.get<StoragePreferences>().webdavCacheMaxBytes.get(),
            ),
            metaKey = {
                source.remoteFingerprint?.let { fp -> "${source.normalizedUrl}|$fp" }
            },
        )
    }
    // SY <--

    // SY --> Komiho Phase3（方案 A+C）：远程 ZIP 走纯 Kotlin 中央目录直读（每页流量=条目本身，
    // 跳页 O(1)），彻底绕开 libarchive 逐条目迭代 × 256KB 放大（曾致每页流量 ≥ 整个文件）。
    // 加密（ZipCrypto / WinZip AES）内建解密，密码复用 CbzCrypto 全局密码，走既有弹窗流程。
    // 构造期解析失败（非 ZIP / 不支持的压缩方法等）→ 回落 libarchive 回调路径，行为不回退。
    private fun webDavArchiveHandle(remoteUrl: String): ArchiveHandle {
        val source = webDavSource(remoteUrl)
        // SY --> Komiho Phase4: 顺便生成历史/书签封面——缓存缺失时后台拉首图落盘
        // （独立连接、失败静默、成功后历史/书签行零请求显示，见 WebDavCoverCache）。
        WebDavCoverCache.generateAsync(context, remoteUrl)
        // SY <--
        val delegate: ArchiveHandle = try {
            RemoteZipReader(source)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "RemoteZipReader 解析失败，回落 libarchive 路径: ${e.message}" }
            ArchiveReader(source)
        }
        // SY --> Komiho Phase5: 页级磁盘缓存装饰器——回翻/重开章节零网络；
        // rar/7z 强制整本回退时 source.remoteFingerprint 指向整本文件，页缓存自动停用。
        return CachingArchiveHandle(
            delegate = delegate,
            cache = RemotePageCache(
                root = File(context.cacheDir, "remote_pages"),
                maxBytes = Injekt.get<StoragePreferences>().webdavCacheMaxBytes.get(),
            ),
            metaKey = {
                source.remoteFingerprint?.let { fp -> "${source.normalizedUrl}|$fp" }
            },
        )
        // SY <--
    }
    // SY <--
}
