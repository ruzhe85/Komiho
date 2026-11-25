package app.mihonsy.komga.data.cloud115

import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * Komiho：115 连接存储（PreferenceStore 单键 JSON 数组，与 WebDAV/SMB 同款方案）。
 *
 * 章节 URL：`115://<connId>/<parentCid>/<pickCode>/<fileName>`
 * - `pickCode` 是 115 文件的稳定标识，**改名/移动都不变** → 历史、进度、页缓存都靠它；
 * - `parentCid` 用于「同目录其余压缩包一并建章节」（翻完自动续卷）；
 * - `fileName` 只为扩展名分流（zip 直读 / rar-7z 整本回退）。
 */
object Cloud115ConnectionStore {

    /** 单一来源：与 `core.common` 的 [mihon.core.common.archive.RemoteScheme.CLOUD115] 保持同一串。 */
    const val CONN_URL_PREFIX = mihon.core.common.archive.RemoteScheme.CLOUD115

    @Serializable
    private data class StoredConnection(
        val id: String,
        val name: String,
        /** 115 账号 id（`/open/user/info`）；章节 URL 与同步合并都用它。空 = 旧数据未取到。 */
        val userId: String = "",
        val rootCid: String = "0",
        val refreshTokenEnc: String,
        val accessToken: String = "",
        val accessExpiresAt: Long = 0L,
    )

    /** 章节 URL 拆解结果。 */
    data class ChapterRef(
        /** URL 第一段：账号级 userId（旧数据是本机 connId）。 */
        val ownerId: String,
        val parentCid: String,
        val pickCode: String,
        val fileName: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: PreferenceStore by lazy { Injekt.get() }

    private val key = Preference.appStateKey("cloud115_connections_v1")

    private val lock = Any()

    // ------------------------------------------------------------ 读取 / 写入

    private fun loadLocked(): MutableList<StoredConnection> {
        val raw = prefs.getString(key, "[]").get()
        return runCatching {
            json.decodeFromString<List<StoredConnection>>(raw)
        }.getOrDefault(emptyList()).toMutableList()
    }

    private fun saveLocked(list: List<StoredConnection>) {
        prefs.getString(key, "[]").set(json.encodeToString(list))
    }

    /** 全部连接（refresh_token 仍是密文，解密只在 [refreshTokenFor] 发生）。 */
    fun all(): List<Cloud115Connection> = synchronized(lock) {
        loadLocked().map { it.toPublic() }
    }

    /**
     * 新增连接。
     *
     * [accessToken] 是 api.oplist.org 页面上一并给出的短期令牌：填了就**不必立刻刷新**
     * （115 的 refresh_token 是一次性的，首次使用前不动它最安全）。
     */
    fun add(
        name: String,
        rootCid: String,
        refreshToken: String,
        accessToken: String = "",
        accessExpiresAt: Long = 0L,
        userId: String = "",
    ): Cloud115Connection =
        synchronized(lock) {
            val conn = StoredConnection(
                id = UUID.randomUUID().toString().replace("-", "").take(8),
                name = name.trim(),
                userId = userId.trim(),
                rootCid = normalizeCid(rootCid),
                refreshTokenEnc = WebDavCredentialCrypto.encrypt(refreshToken.trim()),
                accessToken = accessToken.trim(),
                accessExpiresAt = accessExpiresAt,
            )
            val list = loadLocked()
            list.add(conn)
            saveLocked(list)
            conn.toPublic()
        }

    fun update(
        id: String,
        name: String,
        rootCid: String,
        refreshToken: String,
        accessToken: String = "",
        accessExpiresAt: Long = 0L,
        userId: String = "",
    ): Unit = synchronized(lock) {
        val list = loadLocked()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = list[idx]
        val newRefresh = if (refreshToken.isBlank()) old.refreshTokenEnc else {
            WebDavCredentialCrypto.encrypt(refreshToken.trim())
        }
        list[idx] = old.copy(
            name = name.trim(),
            userId = if (userId.isBlank()) old.userId else userId.trim(),
            rootCid = normalizeCid(rootCid),
            // 留空 = 不改（编辑框不回显已解密的令牌）
            refreshTokenEnc = newRefresh,
            // 换了 refresh_token → 旧 access_token 作废（可能是别的账号的）；
            // 没换 → 保留（避免"只改名字"顺手把还能用的 access_token 清掉）。
            accessToken = when {
                accessToken.isNotBlank() -> accessToken.trim()
                refreshToken.isNotBlank() -> ""
                else -> old.accessToken
            },
            accessExpiresAt = when {
                accessToken.isNotBlank() -> accessExpiresAt
                refreshToken.isNotBlank() -> 0L
                else -> old.accessExpiresAt
            },
        )
        saveLocked(list)
    }

    fun remove(id: String): Unit = synchronized(lock) {
        val list = loadLocked()
        list.removeAll { it.id == id }
        saveLocked(list)
    }

    fun get(id: String): Cloud115Connection? = synchronized(lock) {
        loadLocked().firstOrNull { it.id == id }?.toPublic()
    }

    /** 取解密后的 refresh_token（每次刷新都要用它，不要缓存明文）。 */
    fun refreshTokenFor(id: String): String? = synchronized(lock) {
        loadLocked().firstOrNull { it.id == id }
            ?.let { WebDavCredentialCrypto.decryptStored(it.refreshTokenEnc) }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 回写刷新后的令牌。
     *
     * refresh_token 是**一次性**的（新的一签发旧的立即失效），所以这里的写入必须
     * 紧跟刷新成功之后、且是原子覆盖 —— 否则进程被杀就等于该账号需要重新授权。
     */
    fun updateTokens(id: String, accessToken: String, newRefreshToken: String?, expiresAt: Long) =
        synchronized(lock) {
            val list = loadLocked()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return
            val old = list[idx]
            list[idx] = old.copy(
                accessToken = accessToken,
                accessExpiresAt = expiresAt,
                refreshTokenEnc = if (newRefreshToken.isNullOrBlank()) {
                    old.refreshTokenEnc
                } else {
                    WebDavCredentialCrypto.encrypt(newRefreshToken)
                },
            )
            saveLocked(list)
            logcat(LogPriority.DEBUG) { "[Cloud115] 令牌已刷新并落盘 conn=$id" }
        }

    // ------------------------------------------------------------ 章节 URL

    /**
     * 构造章节 URL。
     *
     * [ownerId] 传**账号级 userId**（[Cloud115Connection.urlOwnerId]），不要传本机 id ——
     * 这是跨设备对齐历史/进度/缓存的前提。
     */
    fun toChapterUrl(ownerId: String, parentCid: String, pickCode: String, fileName: String): String =
        "$CONN_URL_PREFIX$ownerId/$parentCid/$pickCode/" +
            URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")

    /** 章节 URL → 连接；先按 userId 精确匹配，再兼容旧数据的本机 id。 */
    fun match(chapterUrl: String): Cloud115Connection? = synchronized(lock) {
        matchLocked(loadLocked(), chapterUrl)?.toPublic()
    }

    /**
     * 与 [match] 同规则，但用调用方一次性取好的快照 —— 历史/书签列表逐行渲染时，
     * 每行都 `all()` 会重复读偏好 + 解析 JSON（与 SMB 侧的 matchIn 同一考量）。
     */
    fun matchIn(conns: List<Cloud115Connection>, chapterUrl: String): Cloud115Connection? {
        if (!chapterUrl.startsWith(CONN_URL_PREFIX)) return null
        val ownerId = chapterUrl.removePrefix(CONN_URL_PREFIX).substringBefore('/')
        return conns.firstOrNull { it.userId.isNotBlank() && it.userId == ownerId }
            ?: conns.firstOrNull { it.id == ownerId }
            ?: conns.firstOrNull()
    }

    private fun matchLocked(list: List<StoredConnection>, chapterUrl: String): StoredConnection? {
        if (!chapterUrl.startsWith(CONN_URL_PREFIX)) return null
        val ownerId = chapterUrl.removePrefix(CONN_URL_PREFIX).substringBefore('/')
        return list.firstOrNull { it.userId.isNotBlank() && it.userId == ownerId }
            ?: list.firstOrNull { it.id == ownerId }
            ?: list.firstOrNull()
    }

    /** 章节 URL → 四元组（ownerId + 父目录 + pickCode + 文件名）；格式不对返回 null。 */
    fun parseChapterUrl(chapterUrl: String): ChapterRef? {
        if (!chapterUrl.startsWith(CONN_URL_PREFIX)) return null
        val rest = chapterUrl.removePrefix(CONN_URL_PREFIX)
        // limit=4：文件名里理论上不含 '/'，但真出现时也不要把它截断成多段
        val parts = rest.split('/', limit = 4)
        if (parts.size < 4) return null
        val fileName = runCatching { URLDecoder.decode(parts[3], "UTF-8") }.getOrDefault(parts[3])
        return ChapterRef(
            ownerId = parts[0],
            parentCid = parts[1],
            pickCode = parts[2],
            fileName = fileName,
        )
    }

    // ------------------------------------------------------------ 工具

    /** 根目录/用户输入的 cid 规范化：只留数字，空 = 0。 */
    fun normalizeCid(raw: String): String =
        raw.trim().filter { it.isDigit() }.ifBlank { "0" }

    private fun StoredConnection.toPublic() = Cloud115Connection(
        id = id,
        name = name,
        userId = userId,
        rootCid = normalizeCid(rootCid),
        refreshTokenEnc = refreshTokenEnc,
        accessToken = accessToken,
        accessExpiresAt = accessExpiresAt,
    )

    // ------------------------------------------------------------ 同步（跨设备）

    /**
     * 导出给备份/同步用的连接视图：**只含身份与目录，不含任何令牌**。
     *
     * 按方案 A（每设备独立授权）：令牌进备份等于把「一次性 refresh_token」复制到多台设备上，
     * 谁先刷新谁把对方作废。同步只带 [userId]，另一台设备按它合并成同一条来源，
     * 令牌缺失时提示用户各自去 api.oplist.org 授权。
     */
    fun exportForSync(): List<Cloud115SyncConn> = synchronized(lock) {
        loadLocked().map {
            Cloud115SyncConn(
                id = it.id,
                name = it.name,
                userId = it.userId,
                rootCid = normalizeCid(it.rootCid),
            )
        }
    }

    /**
     * 合并来自备份/同步的连接。
     *
     * 主键是 [Cloud115SyncConn.userId]（账号级）：命中就保留本机 id 与令牌，只更新名称/根目录；
     * 本机没有则新建一条**无令牌**的连接（用户需要重新授权才能用）。
     *
     * 因为章节 URL 用的是 userId，历史/进度不需要改写 connId —— 这一点与 WebDAV/SMB 不同。
     */
    fun mergeFromSync(items: List<Cloud115SyncConn>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            val list = loadLocked()
            items.forEach { item ->
                val key = item.userId.ifBlank { item.id }
                // 主键优先 userId；再加一条「id 相同也算同一条」的兜底 ——
                // 本机是旧数据（userId 还没取到）时，靠 id 也能命中，不会合并出重复来源。
                val idx = list.indexOfFirst { s ->
                    (s.userId.isNotBlank() && s.userId == key) || s.id == item.id
                }
                if (idx >= 0) {
                    val old = list[idx]
                    list[idx] = old.copy(
                        name = item.name.takeIf { it.isNotBlank() } ?: old.name,
                        userId = item.userId.ifBlank { old.userId },
                        rootCid = normalizeCid(item.rootCid),
                    )
                } else {
                    list.add(
                        StoredConnection(
                            id = item.id.ifBlank { UUID.randomUUID().toString().replace("-", "").take(8) },
                            name = item.name,
                            userId = item.userId,
                            rootCid = normalizeCid(item.rootCid),
                            // 空凭据 = 「已同步到来源，但本机还没授权」。
                            refreshTokenEnc = "",
                        ),
                    )
                }
            }
            saveLocked(list)
        }
    }
}

/**
 * 备份/同步里的 115 连接（**不含令牌**，见 [Cloud115ConnectionStore.exportForSync]）。
 */
@Serializable
data class Cloud115SyncConn(
    val id: String = "",
    val name: String = "",
    val userId: String = "",
    val rootCid: String = "0",
)
