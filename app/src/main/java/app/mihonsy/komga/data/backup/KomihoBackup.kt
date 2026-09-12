package app.mihonsy.komga.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import app.mihonsy.komga.data.DashboardPreferences
import app.mihonsy.komga.data.KomgaConnection
import app.mihonsy.komga.data.KomgaCredentialCrypto
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.SourceVisibilityStore
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import app.mihonsy.komga.source.KomgaSource
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.BookmarkRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.SecureRandom
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Komiho 备份与恢复（自写轻量 JSON）。
 *
 * 备份范围（按用户确认）：
 *  - 来源列表：Komga 连接 + SMB/WebDAV 连接（含凭据）
 *  - 来源显隐 / 排序、聚合页每来源条数
 *  - Komga 个性化设置（整个 komga_connection SharedPreferences）
 *  - 非 Komga 来源（本地 / SMB / WebDAV，source != KomgaSource.ID）的
 *    书 / 章节 / 阅读历史 / 按页书签 / 收藏与分类
 *
 * 不含：Komga 服务端记录（服务器本身即真相源）、cache、下载清单。
 *
 * 凭据处理（设备内均静态加密，仅备份文件负责「出设备」那一层的保护）：
 *  - Komga 连接凭据（apiKey/username/password）设备内已由 [KomgaCredentialCrypto]
 *    静态加密（Android Keystore）；导出时先 decryptStored 还原明文入 payload，
 *    导入时若备份加密则重新 encrypt 落设备密钥（可跨设备）；
 *  - SMB/WebDAV 的 passEnc 导出时亦 decryptStored 还原明文，导入时若备份加密则重加密；
 *  - 若未设备份密码，文件不加密：Komga/SMB/WebDAV 凭据落回设备绑定密文（仅同机可恢复）。
 */
object KomihoBackup {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // ---------------------------------------------------------------- 公开 API

    /** 导出：返回完整备份文件文本（已加密或明文 JSON 包裹）。password 可空=不加密。 */
    suspend fun exportBackup(context: Context, password: String?): String {
        val payload = buildPayload(context)
        val plain = json.encodeToString(payload)
        val ver = appVersion(context)
        return if (!password.isNullOrBlank()) {
            val env = BackupCrypto.encrypt(plain, password, ver)
            json.encodeToString(env)
        } else {
            json.encodeToString(
                BackupEnvelope(
                    appVersion = ver,
                    encrypted = false,
                    data = plain,
                ),
            )
        }
    }

    /** 导入：解析备份文件并恢复。password 在文件加密时必须提供。返回恢复统计。 */
    suspend fun importBackup(context: Context, rawJson: String, password: String?): BackupSummary {
        val envelope = json.decodeFromString<BackupEnvelope>(rawJson)
        val plain = if (envelope.encrypted) {
            require(!password.isNullOrBlank()) { "该备份已加密，请输入密码" }
            BackupCrypto.decrypt(envelope.salt, envelope.iv, envelope.data, password)
        } else {
            envelope.data
        }
        val payload = json.decodeFromString<BackupPayload>(plain)
        return restorePayload(context, payload, envelope.encrypted)
    }

    /** 仅解析外层信封，判断是否加密（用于决定导入时是否弹密码框）。 */
    fun peekEncrypted(rawJson: String): Boolean =
        runCatching { json.decodeFromString<BackupEnvelope>(rawJson).encrypted }.getOrDefault(false)

    // ---------------------------------------------------------------- 导出

    private suspend fun buildPayload(context: Context): BackupPayload {
        val prefStore = Injekt.get<PreferenceStore>()

        // 1) Komga 个性化设置（整个 komga_connection SharedPreferences）
        //    先触发旧版明文凭据迁移（确保 connections 键存在且为加密形态），
        //    再取出并解密其中的敏感字段，使 payload 内为明文（由备份密码统一保护）。
        KomgaPreferences(context).connections()
        val komgaPrefs = withKomgaCredsDecrypted(readRawPrefs(context, "komga_connection"))

        // 2) SMB / WebDAV 连接（含凭据解密）
        val smbConns = readSmbConnections().map { s ->
            SmbConn(s.id, s.name, s.host, s.port, s.share, s.path, s.domain, s.user,
                password = WebDavCredentialCrypto.decryptStored(s.passEnc))
        }
        val webDavConns = readWebDavConnections().map { w ->
            WebDavConn(w.id, w.name, w.baseUrl, w.user,
                password = WebDavCredentialCrypto.decryptStored(w.passEnc))
        }

        // 3) 来源显隐 / 排序
        val sourceVisibility = SourceVisibilityBackup(
            hidden = SourceVisibilityStore.hiddenIds().toList(),
            order = SourceVisibilityStore.sourceOrder(),
        )

        // 4) 聚合页每来源条数
        val dashboard = readDashboard(prefStore)

        // 5) 非 Komga 本地数据（用 domain Repository，字段名与 domain 模型一致）
        val mangaRepo = Injekt.get<MangaRepository>()
        val chapterRepo = Injekt.get<ChapterRepository>()
        val historyRepo = Injekt.get<HistoryRepository>()
        val bookmarkRepo = Injekt.get<BookmarkRepository>()
        val categoryRepo = Injekt.get<CategoryRepository>()
        val komgaId = KomgaSource.ID
        val localMangas = mangaRepo.getAll().filter { it.source != komgaId }

        val chaptersByManga = localMangas.associateWith { m -> chapterRepo.getChapterByMangaId(m.id) }
        // chapter_id -> (mangaUrl, chapterUrl) 用于历史/书签重新关联
        val chapterRefById = mutableMapOf<Long, Pair<String, String>>()
        chaptersByManga.forEach { (m, chs) ->
            chs.forEach { c -> chapterRefById[c.id] = m.url to c.url }
        }

        val localChapters = chaptersByManga.values.flatten().mapNotNull { c ->
            val ref = chapterRefById[c.id] ?: return@mapNotNull null
            BkChapter(
                mangaUrl = ref.first,
                url = c.url, name = c.name, scanlator = c.scanlator,
                read = c.read, bookmark = c.bookmark,
                lastPageRead = c.lastPageRead, bookmarkPage = c.bookmarkPage,
                chapterNumber = c.chapterNumber, sourceOrder = c.sourceOrder,
                dateFetch = c.dateFetch, dateUpload = c.dateUpload,
                version = c.version, memo = c.memo.toString(),
            )
        }

        val localHistory = localMangas.flatMap { m ->
            historyRepo.getHistoryByMangaId(m.id).mapNotNull { h ->
                val key = chapterRefById[h.chapterId] ?: return@mapNotNull null
                BkHistory(key.first, key.second, h.readAt?.time, h.readDuration)
            }
        }

        val localBookmarks = bookmarkRepo.getBookmarksBySource(LocalSource.ID).map { b ->
            BkBookmark(b.mangaUrl, b.chapterUrl, b.page.toLong(), b.createdAt)
        }

        val categories = categoryRepo.getAll()
            .filter { it.id > 0 }
            .map { BkCategory(it.id, it.name, it.order.toInt(), it.flags.toInt()) }

        val categoryLinks = localMangas.flatMap { m ->
            categoryRepo.getCategoriesByMangaId(m.id)
                .filter { it.id > 0 }
                .map { BkCategoryLink(m.url, it.id) }
        }

        return BackupPayload(
            komgaPrefs = komgaPrefs,
            smbConnections = smbConns,
            webDavConnections = webDavConns,
            sourceVisibility = sourceVisibility,
            dashboard = dashboard,
            localMangas = localMangas.map { m ->
                BkManga(
                    source = m.source, url = m.url, title = m.ogTitle,
                    artist = m.ogArtist, author = m.ogAuthor, description = m.ogDescription,
                    genre = m.ogGenre ?: emptyList(), status = m.ogStatus,
                    thumbnailUrl = m.ogThumbnailUrl, favorite = m.favorite,
                    viewerFlags = m.viewerFlags, chapterFlags = m.chapterFlags,
                    dateAdded = m.dateAdded,
                    updateStrategy = m.updateStrategy.name,
                    initialized = m.initialized, version = m.version,
                    notes = m.notes, memo = m.memo.toString(),
                )
            },
            localChapters = localChapters,
            localHistory = localHistory,
            localBookmarks = localBookmarks,
            categories = categories,
            categoryLinks = categoryLinks,
        )
    }

    private fun readRawPrefs(context: Context, name: String): List<PrefEntry> {
        val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        return all.mapNotNull { (k, v) ->
            when (v) {
                is String -> PrefEntry(k, 0, v)
                is Int -> PrefEntry(k, 1, v.toString())
                is Long -> PrefEntry(k, 2, v.toString())
                is Float -> PrefEntry(k, 3, v.toString())
                is Boolean -> PrefEntry(k, 4, v.toString())
                is Set<*> -> PrefEntry(k, 5, json.encodeToString((v as Set<String>).toList()))
                else -> null
            }
        }
    }

    /** komga_connection 里存放连接列表的键（与 KomgaPreferences.KEY_CONNECTIONS 对应）。 */
    private const val KOMGA_CONNECTIONS_KEY = "connections"

    /** 导出：把 [KOMGA_CONNECTIONS_KEY] 条目的凭据解密为明文，使备份文件统一保护。 */
    private fun withKomgaCredsDecrypted(entries: List<PrefEntry>): List<PrefEntry> {
        return entries.map { e ->
            if (e.k != KOMGA_CONNECTIONS_KEY) return@map e
            val list = runCatching { json.decodeFromString<List<KomgaConnection>>(e.v) }.getOrNull() ?: return@map e
            val decrypted = list.map { c ->
                c.copy(
                    apiKey = KomgaCredentialCrypto.decryptStored(c.apiKey),
                    username = KomgaCredentialCrypto.decryptStored(c.username),
                    password = KomgaCredentialCrypto.decryptStored(c.password),
                )
            }
            e.copy(v = json.encodeToString(decrypted))
        }
    }

    /** 导入：备份加密时把 [KOMGA_CONNECTIONS_KEY] 凭据重新加密落设备密钥（可跨设备），
     *  否则原样写回明文（仅同机可恢复，与 SMB/WebDAV 同策略）。 */
    private fun withKomgaCredsReEncrypted(entries: List<PrefEntry>, fileEncrypted: Boolean): List<PrefEntry> {
        return entries.map { e ->
            if (e.k != KOMGA_CONNECTIONS_KEY) return@map e
            val list = runCatching { json.decodeFromString<List<KomgaConnection>>(e.v) }.getOrNull() ?: return@map e
            val processed = if (fileEncrypted) list.map { c ->
                c.copy(
                    apiKey = KomgaCredentialCrypto.encrypt(c.apiKey),
                    username = KomgaCredentialCrypto.encrypt(c.username),
                    password = KomgaCredentialCrypto.encrypt(c.password),
                )
            } else list
            e.copy(v = json.encodeToString(processed))
        }
    }

    private fun readSmbConnections(): List<SmbStored> {
        val raw = (Injekt.get<PreferenceStore>().getAll()[Preference.appStateKey("smb_connections_v1")] as? String) ?: "[]"
        return runCatching { json.decodeFromString<List<SmbStored>>(raw) }.getOrDefault(emptyList())
    }

    private fun readWebDavConnections(): List<WebDavStored> {
        val raw = (Injekt.get<PreferenceStore>().getAll()[Preference.appStateKey("webdav_connections_v1")] as? String) ?: "[]"
        return runCatching { json.decodeFromString<List<WebDavStored>>(raw) }.getOrDefault(emptyList())
    }

    private fun readDashboard(prefStore: PreferenceStore): DashboardBackup {
        val all = prefStore.getAll()
        var global = DashboardPreferences.RECENT_DEFAULT
        val perSource = mutableMapOf<String, Int>()
        for ((k, v) in all) {
            if (k == "dashboard_recent_limit") {
                global = (v as? Int) ?: global
            } else if (k.startsWith("dashboard_recent_limit_")) {
                val src = k.removePrefix("dashboard_recent_limit_")
                perSource[src] = (v as? Int) ?: 0
            }
        }
        return DashboardBackup(global, perSource)
    }

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrDefault("") ?: ""

    // ---------------------------------------------------------------- 导入

    private suspend fun restorePayload(context: Context, payload: BackupPayload, fileEncrypted: Boolean): BackupSummary {
        // 1) Komga 个性化设置
        //    fileEncrypted=true 时 payload 内 Komga 凭据为明文（导出已解密），需重新加密落设备密钥；
        //    fileEncrypted=false 时按原样写回（明文，仅同机可恢复，与 SMB/WebDAV 同策略）。
        restoreRawPrefs(context, "komga_connection", withKomgaCredsReEncrypted(payload.komgaPrefs, fileEncrypted))

        // 2) SMB / WebDAV 连接
        // fileEncrypted=true 时 password 字段是明文（导出已解密），需重新加密；
        // fileEncrypted=false 时 password 字段是设备绑定的密文，原样写回。
        restoreSmbConnections(payload.smbConnections, fileEncrypted)
        restoreWebDavConnections(payload.webDavConnections, fileEncrypted)

        // 3) 来源显隐 / 排序
        restoreSourceVisibility(payload.sourceVisibility)

        // 4) 聚合页每来源条数
        restoreDashboard(payload.dashboard)

        // 5) 非 Komga 本地数据
        return restoreLocalData(payload)
    }

    private fun restoreRawPrefs(context: Context, name: String, entries: List<PrefEntry>) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        for (e in entries) {
            when (e.t) {
                0 -> editor.putString(e.k, e.v)
                1 -> editor.putInt(e.k, e.v.toInt())
                2 -> editor.putLong(e.k, e.v.toLong())
                3 -> editor.putFloat(e.k, e.v.toFloat())
                4 -> editor.putBoolean(e.k, e.v.toBoolean())
                5 -> editor.putStringSet(e.k, json.decodeFromString<List<String>>(e.v).toSet())
                else -> Unit
            }
        }
        editor.apply()
    }

    private suspend fun restoreSmbConnections(conns: List<SmbConn>, payloadIsEncrypted: Boolean) {
        val list = conns.map { c ->
            SmbStored(
                id = c.id, name = c.name, host = c.host, port = c.port,
                share = c.share, path = c.path, domain = c.domain, user = c.user,
                passEnc = if (payloadIsEncrypted) WebDavCredentialCrypto.encrypt(c.password) else c.password,
            )
        }
        val prefStore = Injekt.get<PreferenceStore>()
        prefStore.getString(Preference.appStateKey("smb_connections_v1"), "[]")
            .set(json.encodeToString(list))
    }

    private suspend fun restoreWebDavConnections(conns: List<WebDavConn>, payloadIsEncrypted: Boolean) {
        val list = conns.map { c ->
            WebDavStored(
                id = c.id, name = c.name, baseUrl = c.baseUrl, user = c.user,
                passEnc = if (payloadIsEncrypted) WebDavCredentialCrypto.encrypt(c.password) else c.password,
            )
        }
        val prefStore = Injekt.get<PreferenceStore>()
        prefStore.getString(Preference.appStateKey("webdav_connections_v1"), "[]")
            .set(json.encodeToString(list))
    }

    private fun restoreSourceVisibility(vis: SourceVisibilityBackup?) {
        if (vis == null) return
        val currentHidden = SourceVisibilityStore.hiddenIds()
        // 先恢复显隐集合为精确备份值
        for (id in currentHidden) {
            if (id !in vis.hidden) SourceVisibilityStore.setVisible(id, true)
        }
        for (id in vis.hidden) {
            SourceVisibilityStore.setVisible(id, false)
        }
        SourceVisibilityStore.setSourceOrder(vis.order)
    }

    private suspend fun restoreDashboard(dash: DashboardBackup?) {
        if (dash == null) return
        val prefStore = Injekt.get<PreferenceStore>()
        prefStore.getInt("dashboard_recent_limit", DashboardPreferences.RECENT_DEFAULT).set(dash.global)
        for ((src, value) in dash.perSource) {
            prefStore.getInt("dashboard_recent_limit_$src", -1).set(value)
        }
        // bump 版本号，让已打开的聚合页立即刷新
        val verPref = prefStore.getInt("dashboard_recent_version", 0)
        verPref.set(verPref.get() + 1)
    }

    private suspend fun restoreLocalData(payload: BackupPayload): BackupSummary {
        val mangaRepo = Injekt.get<MangaRepository>()
        val chapterRepo = Injekt.get<ChapterRepository>()
        val db = Injekt.get<Database>()

        val mangaIdByUrl = mutableMapOf<String, Long>()
        for (b in payload.localMangas) {
            val inserted = mangaRepo.insertNetworkManga(listOf(buildManga(b))).firstOrNull() ?: continue
            mangaIdByUrl[b.url] = inserted.id
        }

        val chapterIdByKey = mutableMapOf<Pair<String, String>, Long>()
        for (b in payload.localChapters) {
            val mangaId = mangaIdByUrl[b.mangaUrl] ?: continue
            val existing = chapterRepo.getChapterByUrlAndMangaId(b.url, mangaId)
            val ch = existing ?: run {
                chapterRepo.addAll(listOf(buildChapter(mangaId, b)))
                chapterRepo.getChapterByUrlAndMangaId(b.url, mangaId)
            } ?: continue
            chapterIdByKey[b.mangaUrl to b.chapterUrl] = ch.id
        }

        var historyCount = 0
        var bookmarkCount = 0
        for (h in payload.localHistory) {
            val chId = chapterIdByKey[h.mangaUrl to h.chapterUrl] ?: continue
            db.historyQueries.upsert(chId, Date(h.lastRead ?: 0L), h.timeRead).execute()
            historyCount++
        }
        for (bk in payload.localBookmarks) {
            val chId = chapterIdByKey[bk.mangaUrl to bk.chapterUrl] ?: continue
            if (db.bookmarksQueries.countByChapterAndPage(chId, bk.page).execute() == 0L) {
                db.bookmarksQueries.insert(chId, bk.page, bk.createdAt).execute()
                bookmarkCount++
            }
        }

        val oldToNew = mutableMapOf<Long, Long>()
        for (c in payload.categories) {
            // categories.insert 实际签名为 (name, order, flags, version, uid, last_modified_at)，
            // manga_order 在 .sq 中固定为 ""（空列表），不是绑定参数。
            val newId = db.categoriesQueries.insert(c.name, c.sort.toLong(), c.flags.toLong(), 1L, 0L, 0L).execute()
            if (newId <= 0L) continue
            oldToNew[c.id] = newId
        }

        val linksByManga = payload.categoryLinks.groupBy { it.mangaUrl }
        for ((mangaUrl, links) in linksByManga) {
            val mangaId = mangaIdByUrl[mangaUrl] ?: continue
            val catIds = links.mapNotNull { oldToNew[it.categoryId] }.distinct()
            if (catIds.isNotEmpty()) mangaRepo.setMangaCategories(mangaId, catIds)
        }

        return BackupSummary(
            mangas = payload.localMangas.size,
            chapters = payload.localChapters.size,
            history = historyCount,
            bookmarks = bookmarkCount,
            categories = payload.categories.size,
        )
    }

    private fun buildManga(b: BkManga): Manga = Manga.create().copy(
        id = 0,
        source = b.source,
        favorite = b.favorite,
        lastUpdate = 0L,
        nextUpdate = 0L,
        fetchInterval = -1,
        dateAdded = b.dateAdded,
        viewerFlags = b.viewerFlags,
        chapterFlags = b.chapterFlags,
        coverLastModified = 0L,
        url = b.url,
        ogTitle = b.title,
        ogArtist = b.artist,
        ogAuthor = b.author,
        ogThumbnailUrl = b.thumbnailUrl,
        ogDescription = b.description,
        ogGenre = b.genre.ifEmpty { null },
        ogStatus = b.status,
        updateStrategy = runCatching { UpdateStrategy.valueOf(b.updateStrategy) }
            .getOrDefault(UpdateStrategy.ALWAYS_UPDATE),
        initialized = b.initialized,
        lastModifiedAt = 0L,
        favoriteModifiedAt = null,
        version = b.version,
        notes = b.notes,
        memo = runCatching { json.decodeFromString<JsonObject>(b.memo) }.getOrDefault(JsonObject.EMPTY),
    )

    private fun buildChapter(mangaId: Long, b: BkChapter): Chapter = Chapter.create().copy(
        id = -1,
        mangaId = mangaId,
        read = b.read,
        bookmark = b.bookmark,
        lastPageRead = b.lastPageRead,
        bookmarkPage = b.bookmarkPage,
        dateFetch = b.dateFetch,
        sourceOrder = b.sourceOrder,
        url = b.url,
        name = b.name,
        dateUpload = b.dateUpload,
        chapterNumber = b.chapterNumber,
        scanlator = b.scanlator,
        lastModifiedAt = 0L,
        version = b.version,
        memo = runCatching { json.decodeFromString<JsonObject>(b.memo) }.getOrDefault(JsonObject.EMPTY),
    )

    // ---------------------------------------------------------------- 加密

    private object BackupCrypto {
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256
        private const val GCM_IV = 12
        private const val GCM_TAG = 128

        fun encrypt(plainJson: String, password: String, appVersion: String): BackupEnvelope {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(GCM_IV).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(password, salt), "AES"),
                    GCMParameterSpec(GCM_TAG, iv))
            }
            val ct = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
            return BackupEnvelope(
                appVersion = appVersion,
                encrypted = true,
                kdf = KdfInfo(Base64.encodeToString(salt, Base64.NO_WRAP), ITERATIONS),
                iv = Base64.encodeToString(iv, Base64.NO_WRAP),
                data = Base64.encodeToString(ct, Base64.NO_WRAP),
            )
        }

        fun decrypt(saltB64: String?, ivB64: String?, dataB64: String?, password: String): String {
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ct = Base64.decode(dataB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(password, salt), "AES"),
                    GCMParameterSpec(GCM_TAG, iv))
            }
            return String(cipher.doFinal(ct), Charsets.UTF_8)
        }

        private fun deriveKey(password: String, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        }
    }

    // ---------------------------------------------------------------- 数据结构

    @Serializable
    data class BackupEnvelope(
        val format: String = "komiho-backup",
        val version: Int = 1,
        val appVersion: String = "",
        val encrypted: Boolean = false,
        val kdf: KdfInfo? = null,
        val salt: String? = null,
        val iv: String? = null,
        val data: String = "",
    )

    @Serializable
    data class KdfInfo(val salt: String, val iterations: Int, val alg: String = "PBKDF2WithHmacSHA256")

    @Serializable
    data class BackupPayload(
        val komgaPrefs: List<PrefEntry> = emptyList(),
        val smbConnections: List<SmbConn> = emptyList(),
        val webDavConnections: List<WebDavConn> = emptyList(),
        val sourceVisibility: SourceVisibilityBackup? = null,
        val dashboard: DashboardBackup? = null,
        val localMangas: List<BkManga> = emptyList(),
        val localChapters: List<BkChapter> = emptyList(),
        val localHistory: List<BkHistory> = emptyList(),
        val localBookmarks: List<BkBookmark> = emptyList(),
        val categories: List<BkCategory> = emptyList(),
        val categoryLinks: List<BkCategoryLink> = emptyList(),
    )

    @Serializable
    data class PrefEntry(val k: String, val t: Int, val v: String)

    @Serializable
    data class SmbConn(
        val id: String, val name: String, val host: String, val port: Int,
        val share: String, val path: String, val domain: String, val user: String,
        val password: String,
    )

    @Serializable
    data class WebDavConn(
        val id: String, val name: String, val baseUrl: String, val user: String, val password: String,
    )

    @Serializable
    private data class SmbStored(
        val id: String, val name: String, val host: String, val port: Int,
        val share: String, val path: String, val domain: String, val user: String, val passEnc: String,
    )

    @Serializable
    private data class WebDavStored(
        val id: String, val name: String, val baseUrl: String, val user: String, val passEnc: String,
    )

    @Serializable
    data class SourceVisibilityBackup(val hidden: List<String>, val order: List<String>)

    @Serializable
    data class DashboardBackup(val global: Int, val perSource: Map<String, Int>)

    @Serializable
    data class BkManga(
        val source: Long, val url: String, val title: String,
        val artist: String? = null, val author: String? = null,
        val description: String? = null, val genre: List<String> = emptyList(),
        val status: Long = 0, val thumbnailUrl: String? = null,
        val favorite: Boolean = false, val viewerFlags: Long = 0,
        val chapterFlags: Long = 0, val dateAdded: Long = 0,
        val updateStrategy: String = "ALWAYS_UPDATE", val initialized: Boolean = false,
        val version: Long = 1, val notes: String = "", val memo: String = "{}",
    )

    @Serializable
    data class BkChapter(
        val mangaUrl: String, val url: String, val name: String,
        val scanlator: String? = null, val read: Boolean = false,
        val bookmark: Boolean = false, val lastPageRead: Long = 0,
        val bookmarkPage: Long = 0, val chapterNumber: Double = 0.0,
        val sourceOrder: Long = 0, val dateFetch: Long = 0, val dateUpload: Long = 0,
        val version: Long = 1, val memo: String = "{}",
    )

    @Serializable
    data class BkHistory(
        val mangaUrl: String, val chapterUrl: String,
        val lastRead: Long? = null, val timeRead: Long = 0,
    )

    @Serializable
    data class BkBookmark(
        val mangaUrl: String, val chapterUrl: String, val page: Long, val createdAt: Long,
    )

    @Serializable
    data class BkCategory(val id: Long, val name: String, val sort: Int, val flags: Int)

    @Serializable
    data class BkCategoryLink(val mangaUrl: String, val categoryId: Long)

    /** 恢复统计（用于 UI 提示）。 */
    @Serializable
    data class BackupSummary(
        val mangas: Int, val chapters: Int, val history: Int,
        val bookmarks: Int, val categories: Int,
    ) {
        override fun toString(): String =
            "已恢复：书 $mangas · 章节 $chapters · 历史 $history · 书签 $bookmarks · 分类 $categories"
    }
}
