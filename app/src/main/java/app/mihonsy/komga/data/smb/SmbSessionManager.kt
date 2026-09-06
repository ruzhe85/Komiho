package app.mihonsy.komga.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.EnumSet
import java.util.concurrent.TimeUnit

// SY --> Komiho Phase7：SMB 会话管理（连接池 + 空闲回收 + 断线重连）。
//
// 与 WebDAV 最大的差异：SMB **有状态**——一次访问要建立
// `Connection（TCP）→ Session（认证）→ TreeConnect（共享）` 三层，建连成本高
// （协商 + NTLM 多轮往返，通常 100ms~1s），且会话会随 NAS 休眠/网络切换断掉。
// 因此本对象统一持有：
// - 会话池：[sessions] 按「连接 id + 主机端口 + 用户域」复用 Session；
// - 共享池：[shares] 按「会话 + 共享名」复用 DiskShare（打开的文件句柄挂在 share 上）；
// - 断线重连：取用前校验 `connection.isConnected`，断了整条作废重建；外部读写失败
//   调 [invalidate] 主动作废，下一次访问自动重连（不必重启 App）；
// - 空闲回收：超过 [IDLE_MS] 未用的会话连带其 share 一起关闭，避免长期占着 socket。
//
// 并发：全部操作在 [lock] 内串行（建连本身不适合并发——同一凭据并发 negotiate
// 容易被 NAS 限速/拒连），代价仅为「首次打开或重连时排队」，正常路径互斥时间很短。
object SmbSessionManager {

    private val config: SmbConfig = SmbConfig.builder()
        // SMB1 已废弃且多数 NAS 默认关闭，只协商 2.1 及以上；3.1.1 支持加密与更强签名。
        .withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_1_1)
        // 签名「启用但不强制」：域环境/WinServer 要求签名时可用，老 NAS 不要求也能连。
        .withSigningEnabled(true)
        .withTimeout(30, TimeUnit.SECONDS)
        .withSoTimeout(30, TimeUnit.SECONDS)
        .build()

    private val client = SMBClient(config)

    private val lock = Any()

    private val sessions = HashMap<String, SessionRef>()
    private val shares = HashMap<String, ShareRef>()

    private class SessionRef(val connection: Connection, val session: Session, var lastUsed: Long)

    private class ShareRef(val share: DiskShare, var lastUsed: Long)

    /**
     * 取（或建）[DiskShare]。断线时自动重连一次。
     * @param password 明文密码（匿名连接传空串）
     */
    fun share(conn: SmbConnection, password: String): DiskShare {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            trimIdleLocked(now)
            val sKey = sessionKey(conn)
            val shKey = "$sKey|${conn.share}"

            val existing = sessions[sKey]
            if (existing != null && !existing.connection.isConnected) {
                logcat(LogPriority.DEBUG) { "[Smb] 会话已断开，重建: ${conn.location()}" }
                closeLocked(sKey)
            }

            val sessionRef = sessions[sKey] ?: run {
                val connection = client.connect(conn.host, conn.port)
                val auth = if (conn.user.isBlank() && password.isBlank()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(
                        conn.user,
                        password.toCharArray(),
                        conn.domain.ifBlank { null },
                    )
                }
                val session = connection.authenticate(auth)
                SessionRef(connection, session, now).also { sessions[sKey] = it }
            }
            sessionRef.lastUsed = now

            val shareRef = shares[shKey] ?: run {
                val share = sessionRef.session.connectShare(conn.share) as DiskShare
                ShareRef(share, now).also { shares[shKey] = it }
            }
            shareRef.lastUsed = now
            return shareRef.share
        }
    }

    /** 打开共享内文件（只读、允许他人并发读）。@param relPath `/` 分隔的相对路径。 */
    fun openFile(conn: SmbConnection, password: String, relPath: String): File {
        val share = share(conn, password)
        return share.openFile(
            toSmbPath(relPath),
            EnumSet.of(AccessMask.FILE_READ_DATA),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    /** 读写失败时调用：作废该连接的会话与共享，下次访问自动重连。 */
    fun invalidate(conn: SmbConnection): Unit = synchronized(lock) {
        closeLocked(sessionKey(conn))
    }

    /** 按连接 id 作废（删除连接时调用；id 为空的临时连接不处理）。 */
    fun invalidateById(id: String): Unit = synchronized(lock) {
        if (id.isBlank()) return
        sessions.keys.filter { it.startsWith("$id|") }.forEach { closeLocked(it) }
    }

    /** 关闭全部会话（设置-存储「清除缓存」等场景）。 */
    fun closeAll(): Unit = synchronized(lock) {
        sessions.keys.toList().forEach { closeLocked(it) }
    }

    // ------------------------------------------------------------ 内部

    /** 会话键：连接 id 优先（同一 NAS 不同账户互不串），测试用的临时连接退回主机端口。 */
    private fun sessionKey(conn: SmbConnection): String {
        val identity = "${conn.host}:${conn.port}|${conn.user}|${conn.domain}"
        return if (conn.id.isBlank()) identity else "${conn.id}|$identity"
    }

    /** `/` 分隔的相对路径 → SMB 反斜杠路径（去掉首尾分隔符）。 */
    fun toSmbPath(relPath: String): String =
        relPath.replace('/', '\\').trim('\\')

    private fun trimIdleLocked(now: Long) {
        val dead = sessions.filter { now - it.value.lastUsed > IDLE_MS }.map { it.key }
        dead.forEach { closeLocked(it) }
    }

    /** 关闭会话：先关其下所有 share（文件句柄挂在这里），再关 session 与 TCP 连接。 */
    private fun closeLocked(sKey: String) {
        shares.keys.filter { it.startsWith("$sKey|") }.forEach { k ->
            shares.remove(k)?.let { runCatching { it.share.close() } }
        }
        sessions.remove(sKey)?.let { ref ->
            runCatching { ref.session.close() }
            runCatching { ref.connection.close() }
        }
    }

    private const val IDLE_MS = 60_000L
}
// SY <--
