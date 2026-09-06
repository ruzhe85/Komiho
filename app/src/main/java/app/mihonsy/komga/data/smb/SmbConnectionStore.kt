package app.mihonsy.komga.data.smb

import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

// SY --> Komiho Phase7：SMB 连接存储（镜像 WebDavConnectionStore）。
//
// 同样是「连接数是个位数、无复杂查询」→ PreferenceStore 一个字符串键存 JSON 数组，
// 不引入 Room。密码落盘前复用 [WebDavCredentialCrypto]（同一个 Keystore 密钥，
// 标记与形态完全一致，两源凭据加密口径统一）。
//
// 章节 URL 契约：`smb://<connId>/<共享内相对路径>`
// - 相对路径以 `/` 分隔、不含 share 名（share 属于连接，改共享名不改章节 URL 语义）；
// - 已包含连接上配置的起始目录（完整路径），打开时直接与 share 拼接即可；
// - 与 `webdav://` 同构，LocalSource 侧同样只认前缀分派。
object SmbConnectionStore {

    /** 章节 URL 前缀：`smb://<connId>/<relPath>`。 */
    const val CONN_URL_PREFIX = "smb://"

    /** 一次解析结果：连接 + 共享内相对路径 + 明文密码（仅内存传递，禁止落日志）。 */
    data class SmbTarget(
        val conn: SmbConnection,
        /** 共享内相对路径（`/` 分隔，已含连接起始目录）。 */
        val relPath: String,
        val password: String,
    )

    @Serializable
    private data class StoredConnection(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val share: String,
        val path: String,
        val domain: String,
        val user: String,
        val passEnc: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: PreferenceStore by lazy { Injekt.get() }

    private val key = Preference.appStateKey("smb_connections_v1")

    private val lock = Any()

    // ------------------------------------------------------------ 读取 / 写入

    private fun loadLocked(): MutableList<StoredConnection> {
        val raw = runBlocking { prefs.getString(key, "[]").get() }
        return runCatching {
            json.decodeFromString<List<StoredConnection>>(raw)
        }.getOrDefault(emptyList()).toMutableList()
    }

    private fun saveLocked(list: List<StoredConnection>) {
        runBlocking { prefs.getString(key, "[]").set(json.encodeToString(list)) }
    }

    /** 全部连接（passEnc 为密文形态，取明文用 [resolve]）。 */
    fun all(): List<SmbConnection> = synchronized(lock) {
        loadLocked().map { it.toPublic() }
    }

    fun add(
        name: String,
        host: String,
        port: Int,
        share: String,
        path: String,
        domain: String,
        user: String,
        pass: String,
    ): SmbConnection = synchronized(lock) {
        val conn = StoredConnection(
            id = newId(),
            name = name.trim(),
            host = host.trim(),
            port = port,
            share = normalizeShare(share),
            path = normalizePath(path),
            domain = domain.trim(),
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
        host: String,
        port: Int,
        share: String,
        path: String,
        domain: String,
        user: String,
        pass: String,
    ): Unit = synchronized(lock) {
        val list = loadLocked()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = list[idx]
        list[idx] = old.copy(
            name = name.trim(),
            host = host.trim(),
            port = port,
            share = normalizeShare(share),
            path = normalizePath(path),
            domain = domain.trim(),
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
        // 会话可能还挂着：一并作废，避免删掉的连接仍占着 socket。
        SmbSessionManager.invalidateById(id)
    }

    // ------------------------------------------------------------ 章节 URL

    /** 新章节 URL：`smb://<connId>/<relPath>`（relPath 以 `/` 分隔，前导斜杠会被规整掉）。 */
    fun toChapterUrl(connId: String, relPath: String): String =
        CONN_URL_PREFIX + connId + "/" + relPath.trim().trimStart('/')

    /** 从章节 URL 取出共享内相对路径（`smb://<connId>/<relPath>` → relPath）。 */
    fun extractRelPath(chapterUrl: String): String {
        val rest = chapterUrl.removePrefix(CONN_URL_PREFIX)
        return rest.substringAfter('/', "")
    }

    /** 从章节 URL 取出 connId。 */
    fun extractConnId(chapterUrl: String): String =
        chapterUrl.removePrefix(CONN_URL_PREFIX).substringBefore('/')

    /** 构造「测试连接」用的临时连接对象（未落盘；pass 为明文，直接塞进 passEnc 字段）。 */
    fun temp(
        name: String,
        host: String,
        port: Int,
        share: String,
        path: String,
        domain: String,
        user: String,
        pass: String,
    ): SmbConnection = SmbConnection(
        id = "",
        name = name,
        host = host.trim(),
        port = port,
        share = normalizeShare(share),
        path = normalizePath(path),
        domain = domain.trim(),
        user = user.trim(),
        // 编辑留空沿用旧密码的场景：调用方直接传旧密文，decryptStored 兼容明文/密文两种形态。
        passEnc = pass,
    )

    /**
     * 按章节 URL 解析出连接 + 相对路径 + 明文密码。
     * 找不到连接（已删除）返回 null —— 调用方按「来源失效」提示，不崩溃。
     */
    fun resolve(chapterUrl: String): SmbTarget? = synchronized(lock) {
        if (!chapterUrl.startsWith(CONN_URL_PREFIX)) return null
        val connId = extractConnId(chapterUrl)
        val stored = loadLocked().firstOrNull { it.id == connId } ?: return null
        SmbTarget(
            conn = stored.toPublic(),
            relPath = extractRelPath(chapterUrl),
            password = WebDavCredentialCrypto.decryptStored(stored.passEnc),
        )
    }

    // ------------------------------------------------------------ 工具

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    private fun normalizeShare(raw: String): String = raw.trim().trim('\\', '/')

    /** 起始目录：统一 `/` 分隔、去首尾斜杠；空 = 共享根。 */
    private fun normalizePath(raw: String): String =
        raw.trim().replace('\\', '/').trim('/')

    private fun StoredConnection.toPublic() = SmbConnection(
        id = id,
        name = name,
        host = host,
        port = port,
        share = share,
        path = path,
        domain = domain,
        user = user,
        passEnc = passEnc,
    )
}
// SY <--
