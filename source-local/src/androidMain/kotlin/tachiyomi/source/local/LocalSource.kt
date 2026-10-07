package tachiyomi.source.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.lang.naturalPinyinComparator
import eu.kanade.tachiyomi.util.storage.EpubFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import mihon.core.common.archive.RemoteScheme
import mihon.core.common.archive.ZipWriter
import mihon.core.common.archive.archiveReader
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.chapter.service.ChapterNumbering
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.metadata.fillMetadata
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.days
import tachiyomi.domain.source.model.Source as DomainSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

actual class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    // SY -->
    private val allowHiddenFiles: () -> Boolean,
    // SY <--
) : Source, UnmeteredSource {

    private val json: Json by injectLazy()
    private val xml: XML by injectLazy()

    @Suppress("PrivatePropertyName")
    private val PopularFilters = FilterList(OrderBy.Popular(context))

    @Suppress("PrivatePropertyName")
    private val LatestFilters = FilterList(OrderBy.Latest(context))

    override val name: String = context.stringResource(MR.strings.local_source)

    override val id: Long = ID

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = true

    // Browse related
    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", PopularFilters)

    override suspend fun getLatestUpdates(page: Int) = getSearchManga(page, "", LatestFilters)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val lastModifiedLimit = if (filters === LatestFilters) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }
        // SY -->
        val allowLocalSourceHiddenFolders = allowHiddenFiles()
        // SY <--

        var mangaDirs = fileSystem.getFilesInBaseDirectory()
            // Filter out files that are hidden and is not a folder
            .filter {
                it.isDirectory &&
                    /* SY --> */ (
                        !it.name.orEmpty().startsWith('.') ||
                            allowLocalSourceHiddenFolders
                        ) /* SY <-- */
            }
            .distinctBy { it.name }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true)
                } else {
                    it.lastModified() >= lastModifiedLimit
                }
            }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedWith(compareBy(naturalPinyinComparator) { it.name.orEmpty() })
                    } else {
                        mangaDirs.sortedWith(compareByDescending(naturalPinyinComparator) { it.name.orEmpty() })
                    }
                }
                is OrderBy.Latest -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedBy(UniFile::lastModified)
                    } else {
                        mangaDirs.sortedByDescending(UniFile::lastModified)
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

        val mangas = mangaDirs
            .map { mangaDir ->
                async {
                    SManga.create().apply {
                        // SY --> Komiho: url 统一为真实绝对路径，使 SAF / 全权限两模式互认。
                        val url = fileSystem.realPathOf(mangaDir) ?: mangaDir.name.orEmpty()
                        this.url = url
                        title = mangaDir.name.orEmpty()

                        // Try to find the cover
                        coverManager.find(url)?.let {
                            thumbnail_url = it.uri.toString()
                        }
                    }
                }
            }
            .awaitAll()

        MangasPage(mangas, false)
    }

    // SY -->
    fun updateMangaInfo(manga: SManga) {
        val mangaDirFiles = fileSystem.getFilesInMangaDirectory(manga.url)
        val existingFile = mangaDirFiles
            .firstOrNull { it.name == COMIC_INFO_FILE }
        val comicInfoArchiveFile = mangaDirFiles.firstOrNull { it.name == COMIC_INFO_ARCHIVE }
        val comicInfoArchiveReader = comicInfoArchiveFile?.archiveReader(context)
        val existingComicInfo =
            (existingFile?.openInputStream() ?: comicInfoArchiveReader?.getInputStream(COMIC_INFO_FILE))?.use {
                AndroidXmlReader(it, StandardCharsets.UTF_8.name()).use { xmlReader ->
                    xml.decodeFromReader<ComicInfo>(xmlReader)
                }
            }
        val newComicInfo = if (existingComicInfo != null) {
            manga.run {
                existingComicInfo.copy(
                    series = ComicInfo.Series(title),
                    summary = description?.let { ComicInfo.Summary(it) },
                    writer = author?.let { ComicInfo.Writer(it) },
                    penciller = artist?.let { ComicInfo.Penciller(it) },
                    genre = genre?.let { ComicInfo.Genre(it) },
                    publishingStatus = ComicInfo.PublishingStatusTachiyomi(
                        ComicInfoPublishingStatus.toComicInfoValue(status.toLong()),
                    ),
                )
            }
        } else {
            manga.getComicInfo()
        }

        fileSystem.getMangaDirectory(manga.url)?.let {
            copyComicInfoFile(
                xml.encodeToString(ComicInfo.serializer(), newComicInfo).byteInputStream(),
                it,
                comicInfoArchiveReader?.encrypted ?: false,
            )
        }
    }
    // SY <--

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val asyncManga = if (fetchDetails) async { getMangaDetails(manga) } else null
        val asyncChapters = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    // Manga details related
    private suspend fun getMangaDetails(manga: SManga): SManga = withIOContext {
        coverManager.find(manga.url)?.let {
            manga.thumbnail_url = it.uri.toString()
        }

        // Augment manga details based on metadata files
        try {
            val mangaDir = fileSystem.getMangaDirectory(manga.url) ?: error("${manga.url} is not a valid directory")
            val mangaDirFiles = mangaDir.listFiles().orEmpty()

            val comicInfoFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_FILE }
            val noXmlFile = mangaDirFiles
                .firstOrNull { it.name == ".noxml" }
            val legacyJsonDetailsFile = mangaDirFiles
                .firstOrNull { it.extension == "json" }
            // SY -->
            val comicInfoArchiveFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_ARCHIVE }
            // SY <--

            when {
                // Top level ComicInfo.xml
                comicInfoFile != null -> {
                    noXmlFile?.delete()
                    setMangaDetailsFromComicInfoFile(comicInfoFile.openInputStream(), manga)
                }
                // SY -->
                comicInfoArchiveFile != null -> {
                    noXmlFile?.delete()

                    comicInfoArchiveFile.archiveReader(context).getInputStream(COMIC_INFO_FILE)
                        ?.let { setMangaDetailsFromComicInfoFile(it, manga) }
                }

                // SY <--

                // Old custom JSON format
                // TODO: remove support for this entirely after a while
                legacyJsonDetailsFile != null -> {
                    json.decodeFromStream<MangaDetails>(legacyJsonDetailsFile.openInputStream()).run {
                        title?.let { manga.title = it }
                        author?.let { manga.author = it }
                        artist?.let { manga.artist = it }
                        description?.let { manga.description = it }
                        genre?.let { manga.genre = it.joinToString() }
                        status?.let { manga.status = it }
                    }
                    // Replace with ComicInfo.xml file
                    val comicInfo = manga.getComicInfo()
                    mangaDir
                        .createFile(COMIC_INFO_FILE)
                        ?.openOutputStream()
                        ?.use {
                            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
                            it.write(comicInfoString.toByteArray())
                            legacyJsonDetailsFile.delete()
                        }
                }

                // Copy ComicInfo.xml from chapter archive to top level if found
                noXmlFile == null -> {
                    val chapterArchives = mangaDirFiles.filter(Archive::isSupported)

                    val copiedFile = copyComicInfoFileFromChapters(chapterArchives, mangaDir)

                    // SY -->
                    if (copiedFile != null && copiedFile.name != COMIC_INFO_ARCHIVE) {
                        setMangaDetailsFromComicInfoFile(copiedFile.openInputStream(), manga)
                    } else if (copiedFile != null && copiedFile.name == COMIC_INFO_ARCHIVE) {
                        copiedFile.archiveReader(context).getInputStream(COMIC_INFO_FILE)
                            ?.let { setMangaDetailsFromComicInfoFile(it, manga) }
                    } // SY <--
                    else {
                        // Avoid re-scanning
                        mangaDir.createFile(".noxml")
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error setting manga details from local metadata for ${manga.title}" }
        }

        return@withIOContext manga
    }

    private fun <T> getComicInfoForChapter(chapter: UniFile, block: (InputStream, /* SY --> */ Boolean /* <-- SY */) -> T): T? {
        return if (chapter.isDirectory) {
            chapter.findFile(COMIC_INFO_FILE)?.let { file ->
                file.openInputStream().use {
                    block(it, /* SY --> */ false /* SY <-- */)
                }
            }
        } else {
            chapter.archiveReader(context).use { reader ->
                reader.getInputStream(COMIC_INFO_FILE)?.use {
                    block(it, /* SY --> */ reader.encrypted /* SY <-- */)
                }
            }
        }
    }

    private fun copyComicInfoFileFromChapters(chapterArchives: List<UniFile>, folder: UniFile): UniFile? {
        for (chapter in chapterArchives) {
            val file = getComicInfoForChapter(chapter) f@{ stream, /* SY --> */ encrypted /* SY <-- */ ->
                return@f copyComicInfoFile(stream, folder, /* SY --> */ encrypted /* SY <-- */)
            }
            if (file != null) return file
        }
        return null
    }

    private fun copyComicInfoFile(
        comicInfoFileStream: InputStream,
        folder: UniFile,
        // SY -->
        encrypt: Boolean,
        // SY <--
    ): UniFile? {
        // SY -->
        if (encrypt) {
            val comicInfoArchiveFile = folder.createFile(COMIC_INFO_ARCHIVE)
            comicInfoArchiveFile?.let { archive ->
                ZipWriter(context, archive, encrypt = true).use { writer ->
                    writer.write(comicInfoFileStream.use { it.readBytes() }, COMIC_INFO_FILE)
                }
            }
            return comicInfoArchiveFile
        } else {
            // SY <--
            return folder.createFile(COMIC_INFO_FILE)?.apply {
                openOutputStream().use { outputStream ->
                    comicInfoFileStream.use { it.copyTo(outputStream) }
                }
            }
        }
    }

    private fun parseComicInfo(stream: InputStream): ComicInfo {
        return AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
            xml.decodeFromReader<ComicInfo>(it)
        }
    }

    private fun setMangaDetailsFromComicInfoFile(stream: InputStream, manga: SManga) {
        manga.copyFromComicInfo(parseComicInfo(stream))
    }

    private fun setChapterDetailsFromComicInfoFile(stream: InputStream, chapter: SChapter) {
        val comicInfo = parseComicInfo(stream)

        comicInfo.title?.let { chapter.name = it.value }
        comicInfo.number?.value?.toFloatOrNull()?.let { chapter.chapter_number = it }
        comicInfo.translator?.let { chapter.scanlator = it.value }
    }

    // Chapters
    private suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        val chapterFiles = fileSystem.getFilesInMangaDirectory(manga.url)
            // Only keep supported formats
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter { it.isDirectory || Archive.isSupported(it) || it.extension.equals("epub", true) || it.extension.equals("pdf", true) || it.extension?.lowercase() in Format.MOBI_EXTENSIONS }
            // Komiho: 先按文件名自然序排好，章节号才能按这个顺序兜底（见 ChapterNumbering）。
            .sortedWith { a, b ->
                a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty())
            }
        // Komiho: 文件名里的编号优先，但一批编号随自然序不再严格递增时（s1_01/s2_01 会被
        // ChapterRecognition 抹掉季标记、都解析成 1.0）改用自然序编号 —— 否则阅读器「下一章」错序。
        val chapterNumbers = ChapterNumbering.assign(chapterFiles.map { it.name.orEmpty() }, manga.title)
        val chapters = chapterFiles
            .mapIndexed { index, chapterFile ->
                SChapter.create().apply {
                    // SY --> Komiho: chapter.url 也用真实绝对路径（与 manga.url 同源），
                    // 跨模式续读才能定位到同一文件。
                    url = fileSystem.realPathOf(chapterFile) ?: "${manga.url}/${chapterFile.name}"
                    name = if (chapterFile.isDirectory) {
                        chapterFile.name
                    } else {
                        chapterFile.nameWithoutExtension
                    }.orEmpty()
                    date_upload = chapterFile.lastModified()
                    // ComicInfo / EPUB 里的显式编号在这之后覆盖，仍然优先。
                    chapter_number = chapterNumbers[index].toFloat()

                    val format = Format.valueOf(chapterFile)
                    when (format) {
                        is Format.Epub -> {
                            EpubFile(format.file.archiveReader(context)).use { epub ->
                                epub.fillMetadata(manga, this)
                            }
                        }
                        // SY --> Komiho: PDF 骨架阶段不解析内部 ComicInfo（避免把 pdf 当归档打开崩溃）。
                        is Format.Pdf -> { /* 后续补齐步再读 PDF 元数据 */ }
                        // SY <--
                        else -> {
                            getComicInfoForChapter(chapterFile) { stream, encrypted ->
                                setChapterDetailsFromComicInfoFile(stream, this)
                            }
                        }
                    }
                }
            }
            .sortedWith { c1, c2 ->
                c2.name.compareToCaseInsensitiveNaturalOrder(c1.name)
            }

        // Copy the cover from the first chapter found if not available
        if (manga.thumbnail_url.isNullOrBlank()) {
            chapters.lastOrNull()?.let { chapter ->
                updateCover(chapter, manga)
            }
        }

        chapters
    }

    // Filters
    override fun getFilterList() = FilterList(OrderBy.Popular(context))

    // Unused stuff
    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException("Unused")

    fun getFormat(chapter: SChapter): Format {
        // SY --> Komiho Phase3/Phase7: 远程归档章节（`webdav:` / `smb://` 前缀）不进本地
        // 文件系统解析，交由 ChapterLoader 构造对应的 RandomAccessSource 随机访问。
        // （前缀判定收口在 core.common 的 RemoteScheme：source-local 看不到 app 层的
        //  SMB 连接存储，但两边都依赖 core.common。）
        if (RemoteScheme.isRemote(chapter.url)) {
            // SY --> Komiho Phase3/Phase7: 远程 PDF 走专用变体（整本落缓存后复用本地解析），
            // 不能进 RemoteArchive，否则被当 zip 解析失败。
            if (chapter.url.substringBefore('?').lowercase().endsWith(".pdf")) {
                return Format.RemotePdf(chapter.url)
            }
            // SY --> Komiho: 远程 MOBI/AZW3/AZW 同走专用变体（libmobi 不认 zip，必须落缓存单独解析）。
            val ext = chapter.url.substringBefore('?').substringAfterLast('.').lowercase()
            if (ext in Format.MOBI_EXTENSIONS) {
                return Format.RemoteMobi(chapter.url)
            }
            // SY <--
            return Format.RemoteArchive(chapter.url)
        }
        // SY <--
        try {
            // SY --> Komiho: chapter.url 已是真实绝对路径，直接映射到当前根下的 UniFile。
            val file = fileSystem.resolveUnderBase(chapter.url)
                ?: throw Exception(
                    context.stringResource(MR.strings.chapter_not_found) + " " + describeResolveFailure(chapter.url),
                )
            return Format.valueOf(file)
        } catch (e: Format.UnknownFormatException) {
            throw Exception(context.stringResource(MR.strings.local_invalid_format))
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * Komiho: 把「找不到该章节」的全部判据压成一行。起因是「打开 EPUB 闪退回浏览器」——
     * 阅读器 init 失败只留一句 chapter_not_found，而下面四种成因的修法完全不同，用这四个值
     * 就能一次性区分（以后不必再让对方导出诊断日志）：
     *
     *  - manage=false：没拿到「所有文件访问」，浏览根退化成 SAF tree。MIUI 在系统更新 / 关过
     *    MIUI 优化之后会重置该权限，是「同一台机器昨天能开今天不能」最常见的原因。
     *  - base=null：浏览根本身就解不出真实路径 → 授权用的 provider 的 doc id 不是 <卷>:<相对路径>。
     *  - exists=false：记录的绝对路径在文件系统上不存在 → 那个 doc id 解码出的路径是拼出来的。
     *  - 三项都正常仍解析不到 → 前缀不匹配（换过卷根 / 挂载点 canonicalPath 差异）。
     *
     * 这一整行会随异常 message 同时进入 toast 与诊断日志（ReaderInit 的 init 失败堆栈）。
     */
    private fun describeResolveFailure(url: String): String {
        val manage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        val base = runCatching { fileSystem.getBaseDirectory()?.let { fileSystem.realPathOf(it) } }.getOrNull()
        val baseUri = runCatching { fileSystem.getBaseDirectory()?.uri?.toString() }.getOrNull()
        val exists = runCatching { File(url).exists() }.getOrDefault(false)
        return "(url=$url base=$base baseUri=$baseUri manage=$manage exists=$exists)"
    }

    private fun updateCover(chapter: SChapter, manga: SManga): UniFile? {
        return try {
            when (val format = getFormat(chapter)) {
                is Format.Directory -> {
                    val entry = format.file.listFiles()
                        ?.sortedWith { f1, f2 ->
                            f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(
                                f2.name.orEmpty(),
                            )
                        }
                        ?.find {
                            !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() }
                        }

                    entry?.let { coverManager.update(manga, it.openInputStream()) }
                }
                is Format.Archive -> {
                    format.file.archiveReader(context).use { reader ->
                        val entry = reader.useEntries { entries ->
                            entries
                                .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                                .find { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        }

                        entry?.let { coverManager.update(manga, reader.getInputStream(it.name)!!, reader.encrypted) }
                    }
                }
                is Format.Epub -> {
                    EpubFile(format.file.archiveReader(context)).use { epub ->
                        val entry = epub.getImagesFromPages().firstOrNull()

                        entry?.let { coverManager.update(manga, epub.getInputStream(it)!!) }
                    }
                }
                // SY --> Komiho: 本地 PDF 封面 = 系统 PdfRenderer 渲第 0 页（source-local 看不到
                // app 模块的 PdfRenderFallback，此处自持一份最小渲染逻辑）。
                is Format.Pdf -> {
                    pdfCoverStream(context, format.file)?.let { coverManager.update(manga, it, false) }
                }
                // SY <--
                // SY --> Komiho: 本地 MOBI/AZW3/AZW 封面 = 最小 PDB/MOBI/EXTH 头读取（source-local
                // 看不到 app 模块的 libmobi 抽图，此处自持一份只读封面的轻量解析，同 pdfCoverStream 先例）。
                is Format.Mobi -> {
                    mobiCoverStream(context, format.file)?.let { coverManager.update(manga, it, false) }
                }
                // SY <--
                // SY --> Komiho Phase3: 远程封面 Phase 4 再做（不为封面拉远程数据）
                is Format.RemoteArchive -> null
                // SY --> Komiho Phase3/Phase7: 远程 PDF 封面同样不拉远程数据（与 RemoteArchive 同口径）。
                is Format.RemotePdf -> null
                // SY --> Komiho: 远程 MOBI 封面同口径（打开章节时由 WebDav/SmbCoverCache 顺便生成）。
                is Format.RemoteMobi -> null
                // SY <--
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error updating cover for ${manga.title}" }
            null
        }
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://mihon.app/docs/guides/local-source/"

        // SY -->
        const val COMIC_INFO_ARCHIVE = "ComicInfo.cbm"
        // SY <--

        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds
    }
}

fun Manga.isLocal(): Boolean = source == LocalSource.ID

fun Source.isLocal(): Boolean = id == LocalSource.ID

fun DomainSource.isLocal(): Boolean = id == LocalSource.ID

// SY --> Komiho: 用系统 PdfRenderer 渲 PDF 第 0 页作封面字节流（source-local 看不到 app 模块的
// PdfRenderFallback，此处自持一份最小渲染逻辑；封面只需缩略，长边限 1600 防 OOM）。
private fun pdfCoverStream(context: Context, file: UniFile): InputStream? {
    // Komiho (2026-10-01): SAF 模式不能拿 filePath 判定 —— content:// 的 filePath 可能解码出
    // 真实路径但直开 EACCES（与 archiveReader 同一坑）。按 uri scheme 走：file:// 才用路径，
    // content:// 一律 contentResolver 直开描述符（不落临时文件）。与阅读器 PdfPageLoader 同口径。
    val pfd = if (file.uri?.scheme == "file" && file.filePath != null) {
        ParcelFileDescriptor.open(File(file.filePath!!), ParcelFileDescriptor.MODE_READ_ONLY)
    } else {
        context.contentResolver.openFileDescriptor(file.uri, "r") ?: return null
    }
    val renderer = PdfRenderer(pfd)
    try {
        if (renderer.pageCount <= 0) return null
        val page = renderer.openPage(0)
        val longSide = maxOf(page.width, page.height)
        val scale = if (longSide > 1600) 1600f / longSide else 1f
        val bmp = Bitmap.createBitmap(
            (page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return ByteArrayInputStream(out.toByteArray())
    } finally {
        renderer.close()
        pfd.close()
    }
}
// SY <--

// SY --> Komiho: 本地 MOBI/AZW3/AZW 封面 —— source-local 看不到 app 模块的 libmobi 抽图，
// 此处自持一份只读封面的最小 PDB/MOBI/EXTH 头解析（同 pdfCoverStream 先例）。图片记录
// 本身不压缩，无需 PalmDOC 解压：PDB 记录表 → record0 的 MOBI 头取 firstImageIndex、
// EXTH 201 取 coveroffset → 封面记录 = firstImageIndex + coveroffset，读出后按魔数校验
// （与 libmobi mobi_determine_resource_type 同口径），任何一步不合预期就返回 null 无封面。
private fun mobiCoverStream(context: Context, file: UniFile): InputStream? {
    val pfd = if (file.uri?.scheme == "file" && file.filePath != null) {
        ParcelFileDescriptor.open(File(file.filePath!!), ParcelFileDescriptor.MODE_READ_ONLY)
    } else {
        context.contentResolver.openFileDescriptor(file.uri, "r") ?: return null
    }
    pfd.use { fd ->
        val fileSize = fd.statSize
        if (fileSize < 78L + 8L) return null

        fun readAt(offset: Long, len: Int): ByteArray? {
            val bb = java.nio.ByteBuffer.allocate(len)
            var pos = offset
            while (bb.hasRemaining()) {
                val n = try {
                    Os.pread(fd.fileDescriptor, bb, pos)
                } catch (e: ErrnoException) {
                    return null
                }
                if (n <= 0) return null
                pos += n
            }
            return bb.array()
        }

        fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        fun u32(b: ByteArray, off: Int) =
            ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

        val head = readAt(0, 78) ?: return null
        val numRecords = u16(head, 76)
        if (numRecords !in 1..16384) return null
        // PDB attributes 的加密位 —— 置位即 DRM，读出的封面记录也是密文。
        if (u16(head, 32) and 0x0002 != 0) return null

        val list = readAt(78, numRecords * 8) ?: return null
        val offsets = IntArray(numRecords) { u32(list, it * 8).toInt() }

        val r0Len = (if (numRecords > 1) offsets[1] else fileSize.toInt()) - offsets[0]
        if (r0Len <= 0) return null
        val r0 = readAt(offsets[0].toLong(), minOf(r0Len, 8192)) ?: return null
        // PalmDOC 头的 encryption type（0 = 无加密）+ "MOBI" 魔数。
        if (u16(r0, 12) != 0) return null
        if (r0.size < 112 || r0[16] != 'M'.code.toByte() || r0[17] != 'O'.code.toByte() ||
            r0[18] != 'B'.code.toByte() || r0[19] != 'I'.code.toByte()
        ) {
            return null
        }
        val firstImageIndex = u32(r0, 108)
        if (firstImageIndex <= 0L || firstImageIndex >= numRecords) return null

        var coverOffset = -1L
        val mobiHeaderLen = u32(r0, 20).toInt()
        val exthOff = 16 + mobiHeaderLen
        if (mobiHeaderLen in 16..r0.size - 12 && r0[exthOff] == 'E'.code.toByte() &&
            r0[exthOff + 1] == 'X'.code.toByte() && r0[exthOff + 2] == 'T'.code.toByte() &&
            r0[exthOff + 3] == 'H'.code.toByte()
        ) {
            val exthEnd = exthOff + u32(r0, exthOff + 4).toInt()
            var p = exthOff + 12
            while (p + 8 <= exthEnd && p + 8 <= r0.size) {
                val type = u32(r0, p)
                val len = u32(r0, p + 4).toInt()
                if (len < 8) break
                if (type == 201L && len >= 12) { // EXTH_COVEROFFSET
                    coverOffset = u32(r0, p + 8)
                    break
                }
                p += len
            }
        }
        if (coverOffset < 0) return null

        val coverIndex = firstImageIndex + coverOffset
        if (coverIndex >= numRecords) return null
        val coverLen =
            ((if (coverIndex + 1 < numRecords) offsets[(coverIndex + 1).toInt()] else fileSize.toInt()) - offsets[coverIndex.toInt()])
        if (coverLen <= 0 || coverLen > 20_000_000) return null
        val img = readAt(offsets[coverIndex.toInt()].toLong(), coverLen) ?: return null

        val isJpeg = img.size >= 3 && img[0] == 0xFF.toByte() && img[1] == 0xD8.toByte() && img[2] == 0xFF.toByte()
        val isGif = img.size >= 4 && img[0] == 0x47.toByte() && img[1] == 0x49.toByte() && img[2] == 0x46.toByte() && img[3] == 0x38.toByte()
        val isPng = img.size >= 8 && img[0] == 0x89.toByte() && img[1] == 0x50.toByte() && img[2] == 0x4E.toByte() && img[3] == 0x47.toByte()
        val isBmp = img.size >= 6 && img[0] == 0x42.toByte() && img[1] == 0x4D.toByte()
        if (!isJpeg && !isGif && !isPng && !isBmp) return null
        return ByteArrayInputStream(img)
    }
}
// SY <--
