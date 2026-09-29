package app.mihonsy.komga.data.backup

import android.content.Context
import android.content.SharedPreferences
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.mihonsy.komga.data.DashboardPreferences
import app.mihonsy.komga.data.KomgaConnection
import app.mihonsy.komga.data.KomgaCredentialCrypto
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.SourceVisibilityStore
import app.mihonsy.komga.data.webdav.WebDavConnection
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import app.mihonsy.komga.source.KomgaSource
import mihon.core.common.archive.WebDavRandomAccessSource
import okhttp3.OkHttpClient
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.BookmarkRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
 *  - 仅 SMB / WebDAV 来源（排除本地、排除 Komga）的书 / 章节 / 阅读历史 / 按页书签 / 收藏与分类
 *    （注：本地 / WebDAV / SMB 三者共用 LocalSource.ID，靠章节/书 URL 前缀 smb://、webdav://、webdav: 区分）
 *
 * 不含：本地来源数据、Komga 服务端记录（服务器本身即真相源）、cache、下载清单。
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

    /**
     * 导出：返回备份信封 JSON 文本。**内容始终为明文**——
     * 加密不在这里做，而是由 [writeBackupFile] 对「压缩后的 zip 字节」整体加一次密，
     * 这样才能先压缩再加密（密文不可压缩，反过来做体积会大 4~5 倍）。
     */
    suspend fun exportBackup(context: Context): String {
        val payload = buildPayload(context)
        val plain = json.encodeToString(payload)
        val ver = appVersion(context)
        return json.encodeToString(
            BackupEnvelope(
                appVersion = ver,
                data = plain,
            ),
        )
    }

    /** 导入：解析备份文本并恢复。password 在加密备份（KMH1 容器）时必须提供。返回恢复统计。 */
    suspend fun importBackup(context: Context, rawJson: String, password: String?): BackupSummary {
        val envelope = json.decodeFromString<BackupEnvelope>(rawJson)
        val payload = json.decodeFromString<BackupPayload>(envelope.data)
        // 加密备份 = 出过设备，凭据要按本设备密钥重新落库（才能跨设备恢复）；明文备份则原样。
        return restorePayload(context, payload, !password.isNullOrBlank())
    }

    // ---------------------------------------------------------------- 文件容器

    /** zip 内的备份条目名（内容 = 备份信封 JSON 文本）。 */
    private const val BACKUP_ENTRY = "backup.json"

    /** 标准 zip 本地文件头魔数 `PK\x03\x04`（无密码导出）。 */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** 加密备份容器魔数 `KMH1`：先把整份 zip 字节压缩好，再整体 AES/GCM 加密。 */
    private val ENC_MAGIC = byteArrayOf(0x4B, 0x4D, 0x48, 0x31)

    /**
     * 导出备份文件。两种形态都是「先压缩」，故体积都约为原始 JSON 的 15~20%：
     *  - 无密码 → 标准 zip（内含 [BACKUP_ENTRY]），任何解压工具都能打开查看
     *  - 有密码 → `KMH1` 私有容器：压缩后的 zip 字节整体加密，密文同样只有压缩后的大小
     *    （对比「先加密再打包」的方案，密文不可压缩，体积会大 4~5 倍）
     */
    suspend fun writeBackupFile(context: Context, password: String?, os: OutputStream) {
        val text = exportBackup(context)
        val zipBytes = zipBytes(text)
        if (password.isNullOrBlank()) {
            os.write(zipBytes)
        } else {
            os.write(ENC_MAGIC + BackupCrypto.encryptRaw(zipBytes, password))
        }
    }

    /**
     * 推送备份到指定 WebDAV 连接（同步中心）：先按现有加密逻辑生成备份字节，
     * 再在该连接 baseUrl 下 `<dirName>/backup/` 目录写 `komiho-<时间戳>.zip|.kmh`。
     * 复用现有 [WebDavConnectionStore] 连接机制，只多了「写入」这一步。
     */
    suspend fun pushToWebDav(
        context: Context,
        conn: WebDavConnection,
        dirName: String,
        password: String?,
        client: OkHttpClient = WebDavRandomAccessSource.sharedHttpClient(),
    ) {
        val os = ByteArrayOutputStream()
        writeBackupFile(context, password, os)
        val bytes = os.toByteArray()
        val auth = WebDavRandomAccessSource.basicAuth(conn.user, WebDavCredentialCrypto.decryptStored(conn.passEnc))
        val base = conn.baseUrl.trimEnd('/')
        val dir = dirName.ifBlank { "komiho" }.trim('/')
        WebDavRandomAccessSource.ensureDir(base, "$dir/backup", auth, client)
        val ext = if (password.isNullOrBlank()) "zip" else "kmh"
        val fileName = "komiho-${System.currentTimeMillis()}.$ext"
        val backupDirUrl = "$base/$dir/backup"
        WebDavRandomAccessSource.putFile("$backupDirUrl/$fileName", auth, bytes, client)
        // 只保留最新一份备份，避免时间戳文件无限堆积。清理失败忽略，下次推送再清。
        // 按 href 里的文件名重建 URL（服务器 href 方言各异，直接用会对不上路径）。
        runCatching {
            WebDavRandomAccessSource.propfind(backupDirUrl, auth, client)
                .asSequence()
                .map { hrefName(it) }
                .filter { it != null && it.startsWith("komiho-") }
                .mapNotNull { it }
                .sortedByDescending {
                    it.removePrefix("komiho-").substringBefore('.').toLongOrNull() ?: 0L
                }
                .drop(1)
                .forEach { runCatching { WebDavRandomAccessSource.deleteFile("$backupDirUrl/$it", auth, client) } }
        }
    }

    /**
     * 从指定 WebDAV 连接拉取最新备份字节（同步引擎用）。
     * 列 `<dirName>/backup/` 下所有 .zip/.kmh，按文件名（含时间戳）取最新一份。
     * 语义：远端无备份（目录不存在 404 / 目录为空）返回 null；**其余网络失败直接抛出**——
     * 同步引擎必须区分「无备份可合并」与「拉取失败」，后者中止同步，
     * 防止不带合并的推送用旧快照回退他机进度。
     * 内部做同步网络 IO，调用方应置于 Dispatchers.IO。
     */
    fun pullLatestFromWebDav(
        conn: WebDavConnection,
        dirName: String,
        client: OkHttpClient = WebDavRandomAccessSource.sharedHttpClient(),
    ): ByteArray? {
        val auth = WebDavRandomAccessSource.basicAuth(conn.user, WebDavCredentialCrypto.decryptStored(conn.passEnc))
        val base = conn.baseUrl.trimEnd('/')
        val dir = dirName.ifBlank { "komiho" }.trim('/')
        val dirUrl = "$base/$dir/backup"
        val hrefs = try {
            WebDavRandomAccessSource.propfind(dirUrl, auth, client)
        } catch (e: IOException) {
            // 首次推送时 <dir>/backup 目录尚不存在，PROPFIND 404 = 远端无备份，
            // 返回 null 交由推送侧 ensureDir 建目录；其余错误照常抛出中止同步。
            if (e.message?.contains("HTTP 404") == true) return null
            throw e
        }
        // 只取 href 的文件名重建 URL：服务器 href 方言各异（绝对路径/裸名/完整 URL、
        // 可能带编码），直接拼接会 GET 到错误路径 →「WebDAV 文件不存在」。
        val latest = hrefs.asSequence()
            .mapNotNull { hrefName(it) }
            .filter { it.endsWith(".zip", true) || it.endsWith(".kmh", true) }
            .maxOrNull() ?: return null
        return WebDavRandomAccessSource.getFile("$dirUrl/$latest", auth, client)
    }

    /** 该备份文件是否为加密容器（决定导入时是否要弹密码框）。 */
    fun isEncrypted(bytes: ByteArray): Boolean = startsWith(bytes, ENC_MAGIC)

    /**
     * 从 PROPFIND href 提取文件名：href 可能是绝对路径（`/dir/file`）、裸文件名或完整 URL，
     * 且可能带 URL 编码——取末段并解码，URL 一律由调用方用已知的目录地址自行重建。
     */
    private fun hrefName(href: String): String? =
        runCatching {
            java.net.URLDecoder.decode(
                href.trimEnd('/').substringAfterLast('/'),
                "UTF-8",
            )
        }.getOrNull()

    /**
     * 读取备份文本，按文件头自动分流：
     * `KMH1` 容器（需 [password]，解密后再解压）→ 标准 zip（解压）→ 其余按纯 JSON 文本读。
     */
    fun readBackupBytes(bytes: ByteArray, password: String?): String = when {
        startsWith(bytes, ENC_MAGIC) -> {
            require(!password.isNullOrBlank()) { "该备份已加密，请输入密码" }
            val body = bytes.copyOfRange(ENC_MAGIC.size, bytes.size)
            unzipBytes(BackupCrypto.decryptRaw(body, password))
        }
        startsWith(bytes, ZIP_MAGIC) -> unzipBytes(bytes)
        else -> bytes.toString(Charsets.UTF_8)
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && bytes.copyOf(magic.size).contentEquals(magic)

    private fun zipBytes(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry(BACKUP_ENTRY))
            zos.write(text.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun unzipBytes(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory && entry.name == BACKUP_ENTRY) {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        throw Exception("备份文件内未找到 $BACKUP_ENTRY")
    }

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
        // SY --> Komiho: 本地/WebDAV/SMB 三者共用 LocalSource.ID，只能靠 URL 前缀区分。
        // 先取非 Komga 全集、构建章节映射，再收窄为仅 SMB/WebDAV（排除本地）。
        val allNonKomga = mangaRepo.getAll().filter { it.source != komgaId }
        val chaptersByManga = allNonKomga.associateWith { m -> chapterRepo.getChapterByMangaId(m.id) }
        val localMangas = allNonKomga.filter { m ->
            isRemoteSourceUrl(m.url) || chaptersByManga[m].orEmpty().any { isRemoteSourceUrl(it.url) }
        }
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
                updatedAt = c.lastModifiedAt, // SY --> Komiho: 进度最后更新时间，较新胜比较基准
            )
        }

        val localHistory = localMangas.flatMap { m ->
            historyRepo.getHistoryByMangaId(m.id).mapNotNull { h ->
                val key = chapterRefById[h.chapterId] ?: return@mapNotNull null
                BkHistory(key.first, key.second, h.readAt?.time, h.readDuration, h.readAt?.time ?: 0L) // SY 较新胜基准
            }
        }

        // SY --> Komiho: 书签跨本地/WebDAV/SMB 共用 LocalSource.ID，按章节 URL 前缀排除本地
        val localBookmarks = bookmarkRepo.getBookmarksBySource(LocalSource.ID)
            .filter { isRemoteSourceUrl(it.chapterUrl) }
            .map { b -> BkBookmark(b.mangaUrl, b.chapterUrl, b.page.toLong(), b.createdAt) }

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

    /** 章节/书 URL 是否为 SMB 或 WebDAV（非本地）。本地/WebDAV/SMB 共用 LocalSource.ID，只能靠前缀区分。 */
    private fun isRemoteSourceUrl(url: String): Boolean =
        url.startsWith("smb://") || url.startsWith("webdav://") || url.startsWith("webdav:")

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
            // SY --> Komiho: 较新胜——备份进度更新时间晚于本地最后修改才覆盖进度字段
            val ch = if (existing == null) {
                chapterRepo.addAll(listOf(buildChapter(mangaId, b)))
                chapterRepo.getChapterByUrlAndMangaId(b.url, mangaId)
            } else {
                if (b.updatedAt > existing.lastModifiedAt) {
                    chapterRepo.update(
                        ChapterUpdate(
                            id = existing.id,
                            read = b.read,
                            bookmark = b.bookmark,
                            lastPageRead = b.lastPageRead,
                            bookmarkPage = b.bookmarkPage,
                        ),
                    )
                }
                existing
            } ?: continue
            chapterIdByKey[b.mangaUrl to b.url] = ch.id
        }

        var historyCount = 0
        var bookmarkCount = 0
        db.transaction {
            for (h in payload.localHistory) {
                val chId = chapterIdByKey[h.mangaUrl to h.chapterUrl] ?: continue
                // SY --> Komiho: 较新胜——仅当备份阅读时间不早于本地时才覆盖
                val backupLast = h.lastRead ?: 0L
                val local = db.historyQueries.getHistoryByChapterId(chId).executeAsOneOrNull()
                if (local == null) {
                    db.historyQueries.upsert(chId, Date(backupLast), h.timeRead)
                    historyCount++
                } else if (backupLast >= (local.last_read?.time ?: 0L)) {
                    // 覆盖 last_read；time_read 传 0 避免与本地累计时长重复累加
                    db.historyQueries.upsert(chId, Date(backupLast), 0L)
                    historyCount++
                }
            }
            for (bk in payload.localBookmarks) {
                val chId = chapterIdByKey[bk.mangaUrl to bk.chapterUrl] ?: continue
                if (db.bookmarksQueries.countByChapterAndPage(chId, bk.page).awaitAsOne() == 0L) {
                    db.bookmarksQueries.insert(chId, bk.page, bk.createdAt)
                    bookmarkCount++
                }
            }

            val oldToNew = mutableMapOf<Long, Long>()
            for (c in payload.categories) {
                // categories.insert 实际签名为 (name, order, flags, version, uid, last_modified_at)，
                // manga_order 在 .sq 中固定为 ""（空列表），不是绑定参数。
                val newId = db.categoriesQueries.insert(c.name, c.sort.toLong(), c.flags.toLong(), 1L, 0L, 0L).awaitAsOne()
                if (newId <= 0L) continue
                oldToNew[c.id] = newId
            }

            val linksByManga = payload.categoryLinks.groupBy { it.mangaUrl }
            for ((mangaUrl, links) in linksByManga) {
                val mangaId = mangaIdByUrl[mangaUrl] ?: continue
                val catIds = links.mapNotNull { oldToNew[it.categoryId] }.distinct()
                for (catId in catIds) {
                    db.mangas_categoriesQueries.insert(mangaId, catId)
                }
            }
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
        memo = runCatching { json.decodeFromString<JsonObject>(b.memo) }.getOrDefault(JsonObject(emptyMap())),
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
        memo = runCatching { json.decodeFromString<JsonObject>(b.memo) }.getOrDefault(JsonObject(emptyMap())),
    )

    // ---------------------------------------------------------------- 加密

    private object BackupCrypto {
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256
        private const val GCM_IV = 12
        private const val GCM_TAG = 128
        private const val SALT_BYTES = 16

        /** [encryptRaw] 的逆操作。 */
        fun decryptRaw(body: ByteArray, password: String): ByteArray {
            require(body.size > SALT_BYTES + GCM_IV + GCM_TAG / 8) { "备份文件已损坏或长度不足" }
            val salt = body.copyOfRange(0, SALT_BYTES)
            val iv = body.copyOfRange(SALT_BYTES, SALT_BYTES + GCM_IV)
            val ct = body.copyOfRange(SALT_BYTES + GCM_IV, body.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(deriveKey(password, salt), "AES"),
                    GCMParameterSpec(GCM_TAG, iv),
                )
            }
            return cipher.doFinal(ct)
        }

        /**
         * 加密原始字节 → `[salt 16B][iv 12B][密文]`，**不做 base64**（省掉 33% 体积膨胀）。
         * 密文不可压缩，所以调用方必须先压缩再调这里。
         */
        fun encryptRaw(plain: ByteArray, password: String): ByteArray {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(GCM_IV).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(deriveKey(password, salt), "AES"),
                    GCMParameterSpec(GCM_TAG, iv),
                )
            }
            return salt + iv + cipher.doFinal(plain)
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
        // 加密不在信封层：见 [writeBackupFile]，整份 zip 字节在外层一次性加密。
        val data: String = "",
    )

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
        // SY --> Komiho: 进度最后更新时间（来自 chapters.last_modified_at），导入较新胜比较基准
        val updatedAt: Long = 0,
    )

    @Serializable
    data class BkHistory(
        val mangaUrl: String, val chapterUrl: String,
        val lastRead: Long? = null, val timeRead: Long = 0,
        // SY --> Komiho: 较新胜比较基准（同 lastRead，避免 null 比较），导入时取较晚者
        val updatedAt: Long = 0,
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
