package app.mihonsy.komga.source

import android.content.Context
import app.mihonsy.komga.data.KomgaApiClient
import app.mihonsy.komga.data.KomgaAuthType
import app.mihonsy.komga.data.KomgaPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.Headers
import rx.Observable

/**
 * Komiho V2 (R-1): app-internal Komga data source.
 *
 * Bridges the native MihonSY reader to Komga:
 * - series -> manga (url = komga://series/{id})
 * - books -> chapters (url = komga://book/{id})
 * - book pages -> reader pages (imageUrl = Komga page endpoint)
 *
 * Not a plugin: registered directly in AndroidSourceManager alongside the
 * built-in SY sources, so the whole extension machinery stays untouched.
 */
class KomgaSource(private val context: Context) : HttpSource() {

    companion object {
        const val ID = 1_000_001L
        const val SERIES_URL_PREFIX = "komga://series/"
        const val BOOK_URL_PREFIX = "komga://book/"
    }

    private val prefs: KomgaPreferences by lazy {
        KomgaPreferences(context.applicationContext)
    }

    override val id: Long = ID
    override val name: String = "Komga"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false
    override val baseUrl: String
        get() = prefs.baseUrl

    // Komga API key / basic auth must ride along on every image request the
    // reader makes through HttpPageLoader.
    override fun headersBuilder(): Headers.Builder = super.headersBuilder().apply {
        val conn = prefs.connection()
        when (conn.authType) {
            KomgaAuthType.API_KEY -> add("X-API-Key", conn.apiKey)
            KomgaAuthType.BASIC -> add("Authorization", Credentials.basic(conn.username, conn.password))
        }
    }

    private fun client(): KomgaApiClient = KomgaApiClient(prefs.connection())

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        val seriesId = manga.url.removePrefix(SERIES_URL_PREFIX)
        val series = runBlocking { client().getSeriesDetail(seriesId) }
        SManga(
            url = manga.url,
            title = series.metadata.title ?: series.name,
            artist = series.metadata.authors.firstOrNull { it.role == "ARTIST" }?.name,
            author = series.metadata.authors.firstOrNull { it.role == "WRITER" }?.name,
            description = series.metadata.summary,
            genre = series.metadata.genres.joinToString(","),
            initialized = true,
        )
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val seriesId = manga.url.removePrefix(SERIES_URL_PREFIX)
        val books = runBlocking { client().getSeriesBooks(seriesId) }.content
        books.sortedBy { it.number ?: Int.MAX_VALUE }.mapIndexed { i, book ->
            SChapter(
                name = book.name,
                url = BOOK_URL_PREFIX + book.id,
                chapter_number = (book.number ?: i + 1).toFloat(),
            )
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val bookId = chapter.url.removePrefix(BOOK_URL_PREFIX)
        val client = client()
        val pageDtos = runBlocking { client.getBookPages(bookId) }
        pageDtos.mapIndexed { index, p ->
            val imageUrl = client.pageImageUrl(bookId, p.number)
            Page(index, url = imageUrl, imageUrl = imageUrl)
        }
    }

    // Page.imageUrl is already the final Komga image URL — never resolve via network.
    override fun fetchImageUrl(page: Page): Observable<String> =
        Observable.just(page.imageUrl ?: page.url)

    // Komga 的 manga/chapter url 使用内部 scheme `komga://series|book/{id}`，并非真实
    // 网页 URL。Mihon 的默认 getChapterUrl()/getMangaUrl() 会做 `baseUrl + url`，拼出
    // `http://host:portkomga://...` 这种非法 URL，在 HttpUrl 解析端口时抛
    // IllegalArgumentException（Invalid URL port: "10007komga:"）。
    // 这两个方法仅用于「在浏览器打开」类的辅助功能，对 Komga 无意义，直接返回
    // 原始 scheme url（或 null 让调用方安全降级），避免崩溃。
    override fun getChapterUrl(chapter: SChapter): String = chapter.url
    override fun getMangaUrl(manga: SManga): String = manga.url
}
