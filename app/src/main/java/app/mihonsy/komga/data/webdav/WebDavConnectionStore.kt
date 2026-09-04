package app.mihonsy.komga.data.webdav

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

// SY --> Komiho Phase4：WebDAV 连接存储（D1.A：DataStore 单键 JSON 数组）。
//
// 连接数是个位数、无复杂查询，用 PreferenceStore 一个字符串键存 JSON 数组即可，
// 不必上 Room。密码落盘前经 [WebDavCredentialCrypto] 加密（enc1: 前缀）。
//
// 章节 URL 双格式（D2.A）：
// - 新格式：`webdav://<connId>/<完整http(s) URL>`——凭据按 connId 精确取；
// - 旧格式：`webdav:<完整http(s) URL>`（Phase3）——回落「baseUrl 最长前缀匹配的连接，
//   否则第一个连接」，历史章节不改 DB 即可继续读。
// LocalSource 侧只认 `webdav:` 前缀分派，`webdav://` 天然兼容，无需改动。
object WebDavConnectionStore {

    /** 新格式章节 URL 前缀：`webdav://<connId>/<完整URL>`。 */
    const val CONN_URL_PREFIX = "webdav://"

    @Serializable
    private data class StoredConnection(
        val id: String,
        val name: String,
        val baseUrl: String,
        val user: String,
        val passEnc: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: PreferenceStore by lazy { Injekt.get() }

    private val storagePrefs by lazy { Injekt.get<tachiyomi.domain.storage.service.StoragePreferences>() }

    private val key = Preference.appStateKey("webdav_connections_v1")

    private val lock = Any()

    // ------------------------------------------------------------ 读取 / 写入

    private fun loadLocked(): MutableList<StoredConnection> {
        val raw = runBlocking { prefs.getString(key, "[]").get() }
        return runCatching {
            json.decodeFromString<List<StoredConnection>>(raw)
        }.getOrDefault(emptyList()).toMutableList()
    }

    private fun saveLocked(list: List<StoredConnection>) {
        val raw = json.encodeToString(list)
        runBlocking { prefs.getString(key, "[]").set(raw) }
    }

    /** 全部连接（解密后的形态，仅内存使用，不要把 pass 明文写日志）。 */
    fun all(): List<WebDavConnection> = synchronized(lock) {
        migrateLegacyIfNeededLocked()
        loadLocked().map { it.toPublic() }
    }

    fun add(name: String, baseUrl: String, user: String, pass: String): WebDavConnection =
        synchronized(lock) {
            val conn = StoredConnection(
                id = UUID.randomUUID().toString().replace("-", "").take(8),
                name = name.trim(),
                baseUrl = normalizeBase(baseUrl),
                user = user.trim(),
                passEnc = WebDavCredentialCrypto.encrypt(pass),
            )
            val list = loadLocked()
            list.add(conn)
            saveLocked(list)
            conn.toPublic()
        }

    fun update(
        id: String,
        name: String,
        baseUrl: String,
        user: String,
        pass: String,
    ): Unit = synchronized(lock) {
        val list = loadLocked()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = list[idx]
        list[idx] = old.copy(
            name = name.trim(),
            baseUrl = normalizeBase(baseUrl),
            user = user.trim(),
            // 密码留空 = 不修改旧密码（编辑框避免每次回显解密密码）
            passEnc = if (pass.isBlank()) old.passEnc else WebDavCredentialCrypto.encrypt(pass),
        )
        saveLocked(list)
    }

    fun remove(id: String): Unit = synchronized(lock) {
        val list = loadLocked()
        list.removeAll { it.id == id }
        saveLocked(list)
    }

    // ------------------------------------------------------------ 章节 URL 双格式

    /** 新格式章节 URL：`webdav://<connId>/<完整http(s) URL>`。 */
    fun toChapterUrl(connId: String, fullHttpUrl: String): String = "$CONN_URL_PREFIX$connId/$fullHttpUrl"

    /** 从章节 URL 取出完整 http(s) URL（新旧格式均可）。 */
    fun extractFullUrl(chapterUrl: String): String {
        if (chapterUrl.startsWith(CONN_URL_PREFIX)) {
            // webdav://<connId>/<完整URL>：去掉前缀与 connId 段
            val rest = chapterUrl.removePrefix(CONN_URL_PREFIX)
            return rest.substringAfter('/', rest)
        }
        return chapterUrl.removePrefix("webdav:")
    }

    /**
     * 按章节 URL 匹配凭据：新格式按 connId 精确取；旧格式 baseUrl 最长前缀匹配，
     * 兜底第一个连接；无任何连接返回 null（匿名，行为与 Phase3 无凭据一致）。
     * @return (user, 明文密码) —— 密码仅内存传递，禁止落日志
     */
    fun credentialsFor(chapterUrl: String): Pair<String, String>? = synchronized(lock) {
        migrateLegacyIfNeededLocked()
        val list = loadLocked()
        if (list.isEmpty()) return null
        val conn: StoredConnection = if (chapterUrl.startsWith(CONN_URL_PREFIX)) {
            val connId = chapterUrl.removePrefix(CONN_URL_PREFIX).substringBefore('/')
            list.firstOrNull { it.id == connId } ?: list.first()
        } else {
            // 旧格式：最长 baseUrl 前缀优先，兜底第一个
            val fullUrl = extractFullUrl(chapterUrl)
            list.filter { fullUrl.startsWith(it.baseUrl) }
                .maxByOrNull { it.baseUrl.length }
                ?: list.first()
        }
        conn.user to WebDavCredentialCrypto.decryptStored(conn.passEnc)
    }

    /** 把手输的文件路径解析成完整 URL：绝对 http(s) 原样；`/` 开头或裸路径拼到 base 后。 */
    fun resolveFileUrl(conn: WebDavConnection, raw: String): String {
        val input = raw.trim()
        if (input.startsWith("http://") || input.startsWith("https://")) return input
        val base = conn.baseUrl.trimEnd('/')
        return if (input.startsWith("/")) base + input else "$base/$input"
    }

    // ------------------------------------------------------------ 迁移

    /**
     * Phase3 遗留迁移：无任何连接且有测试配置时，把 webdavTest* 转成「默认连接」
     * （base URL 取测试 URL 的目录部分），密码加密落盘后清掉明文残留。
     */
    private fun migrateLegacyIfNeededLocked() {
        if (loadLocked().isNotEmpty()) return
        val url = runBlocking { storagePrefs.webdavTestUrl.get() }
        if (url.isBlank()) return
        val user = runBlocking { storagePrefs.webdavTestUser.get() }
        val pass = runBlocking { storagePrefs.webdavTestPass.get() }
        val base = url.substringBeforeLast('/')
        val conn = StoredConnection(
            id = UUID.randomUUID().toString().replace("-", "").take(8),
            name = "默认连接",
            baseUrl = normalizeBase(base),
            user = user.trim(),
            passEnc = WebDavCredentialCrypto.encrypt(WebDavCredentialCrypto.decryptStored(pass)),
        )
        saveLocked(listOf(conn))
        // 明文/半明文测试凭据使命完成，清掉（webdavTestUrl 保留作「上次打开路径」）
        runBlocking {
            storagePrefs.webdavTestUser.set("")
            storagePrefs.webdavTestPass.set("")
        }
        logcat(LogPriority.INFO) { "[WebDav] 已迁移 Phase3 测试配置为连接「默认连接」($base)" }
    }

    // ------------------------------------------------------------ 工具

    private fun normalizeBase(raw: String): String = raw.trim().trimEnd('/')

    private fun StoredConnection.toPublic() = WebDavConnection(
        id = id,
        name = name,
        baseUrl = baseUrl,
        user = user,
        passEnc = passEnc,
    )
}
// SY <--
