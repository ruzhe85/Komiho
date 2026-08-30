package app.mihonsy.komga.data

import android.content.Context
import android.content.Intent
import app.mihonsy.komga.source.KomihoFileSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import mihon.core.common.extensions.EMPTY
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho P0（重做）：把本地文件浏览器的一个条目（归档文件，或含散图的目录）
 * 送入 MihonSY 阅读器。
 *
 * Mihon 阅读器是按 DB 里的 mangaId / chapterId 启动的，因此这里仍要落库
 * （与 [KomgaDbBridge] 同套路）——但**只落库，不做任何图源语义**：
 * 一个条目 = 一本（一个 manga + 一个 chapter），标题即文件名/目录名，
 * 目录层级、什么算一本完全由文件浏览器在调用处决定。
 */
object KomihoFileLauncher {

    private val mangaRepository: MangaRepository by lazy { Injekt.get() }
    private val chapterRepository: ChapterRepository by lazy { Injekt.get() }

    /**
     * 以「一本」的方式打开 [relativePath]（相对本地根目录的路径）。
     *
     * [relativePath] 指向归档（cbz/zip/rar/…）、epub，或内含图片的目录；
     * 直接传一张散图会在阅读器侧因格式不受支持而报错。
     */
    suspend fun open(context: Context, relativePath: String) {
        val title = relativePath.substringAfterLast("/").ifBlank { "未命名" }
        val url = KomihoFileSource.encodeUrl(relativePath)

        val manga = mangaRepository.getMangaByUrlAndSourceId(url, KomihoFileSource.ID)
            ?: mangaRepository.insertNetworkManga(
                listOf(
                    Manga(
                        id = 0,
                        source = KomihoFileSource.ID,
                        favorite = false,
                        lastUpdate = 0,
                        nextUpdate = 0,
                        fetchInterval = -1,
                        dateAdded = System.currentTimeMillis(),
                        viewerFlags = 0,
                        chapterFlags = 0,
                        coverLastModified = 0,
                        url = url,
                        ogTitle = title,
                        ogArtist = null,
                        ogAuthor = null,
                        ogThumbnailUrl = null,
                        ogDescription = null,
                        ogGenre = null,
                        ogStatus = 0,
                        updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
                        initialized = true,
                        lastModifiedAt = 0,
                        favoriteModifiedAt = null,
                        version = 1,
                        notes = "",
                        memo = JsonObject.EMPTY,
                    ),
                ),
            ).first()

        val chapter = chapterRepository.getChapterByUrlAndMangaId(url, manga.id)
            ?: chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = url,
                        name = title,
                        dateFetch = System.currentTimeMillis(),
                    ),
                ),
            ).first()

        context.startActivity(
            Intent(context, ReaderActivity::class.java)
                .putExtra("manga", manga.id)
                .putExtra("chapter", chapter.id),
        )
    }
}
