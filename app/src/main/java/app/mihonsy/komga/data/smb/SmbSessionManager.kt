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
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
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
// 并发：**按会话键加锁** —— 同一连接的建连/取共享串行，不同连接之间互不阻塞
// （建连本身不适合并发——同一凭据并发 negotiate 容易被 NAS 限速/拒连）。
//
// Komiho 排查记录（2026-09-12）：原来用一把全局锁包住所有网络 I/O + 30s 超时，
// 现场表现为「SMB 目录十几秒到几十秒才出来」，实测把三处代价叠加放大了：
//   1) 请求超时 30s：NAS 一旦不回应，一次卡顿就是 30s；
//   2) 全局锁：一处卡住，其它连接的请求全部排队，级联成 30~90s；
//   3) socket 读超时 30s：smbj 的 PacketReader 在空闲到达该时长后会直接判死整条连接
//      （实测 30s 空闲即 `DiskShare has already been closed`），与本对象 IDLE_MS=60s
//      的回收意图冲突 —— 每次「隔一会儿再进目录」都摸到死连接、都要重连。
object SmbSessionManager {

    /**
     * 请求级超时：卡住的请求最多等这么久就上抛，交给 [SmbBrowse] 的
     * 「作废会话 + 重试一次」快速接上（原来是 30s，一次卡顿就是半分钟白等）。
     */
    private const val REQUEST_TIMEOUT_MS = 8_000L

    private val config: SmbConfig = SmbConfig.builder()
        // SMB1 已废弃且多数 NAS 默认关闭；从 2.0.2 起协商（部分老 NAS/旧 Samba 最高只到
        // 2.0.2，不含它会在 NEGOTIATE 阶段被服务器直接断开 → 表现为 broken pipe）。
        .withDialects(
            SMB2Dialect.SMB_2_0_2,
            SMB2Dialect.SMB_2_1,
            SMB2Dialect.SMB_3_0,
            SMB2Dialect.SMB_3_0_2,
            SMB2Dialect.SMB_3_1_1,
        )
        // 签名「启用但不强制」：域环境/WinServer 要求签名时可用，老 NAS 不要求也能连。
        .withSigningEnabled(true)
        .withTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // 0 = 不超时。非 0 时 smbj 的 PacketReader 会在空闲到达该时长后判死整条连接
        // （见文件头注释 3）。真正卡死由 [REQUEST_TIMEOUT_MS] 兜底，不需要它。
        .withSoTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val client = SMBClient(config)

    private val sessions = ConcurrentHashMap<String, SessionRef>()
    private val shares = ConcurrentHashMap<String, ShareRef>()

    /** 每会话键一把锁（见文件头「并发」说明）。 */
    private val keyLocks = ConcurrentHashMap<String, Any>()

    private fun lockFor(key: String): Any = keyLocks.computeIfAbsent(key) { Any() }

    private class SessionRef(val connection: Connection, val session: Session, var lastUsed: Long)

    private class ShareRef(val share: DiskShare, var lastUsed: Long)

    /**
     * 取（或建）[DiskShare]。断线时自动重连一次。
     * @param password 明文密码（匿名连接传空串）
     * @param shareName 目标共享名；连接未配置共享（[SmbConnection.share] 为空）时由调用方
     * 传入（浏览/阅读层从路径第一段解析），为空抛错。
     */
    fun share(conn: SmbConnection, password: String, shareName: String = conn.share): DiskShare {
        if (shareName.isBlank()) {
            throw IllegalArgumentException("连接未指定共享且未提供共享名: ${conn.location()}")
        }
        val now = System.currentTimeMillis()
        trimIdle(now)
        val sKey = sessionKey(conn)
        val shKey = "$sKey|$shareName"
        synchronized(lockFor(sKey)) {
            val existing = sessions[sKey]
            if (existing != null && !existing.connection.isConnected) {
                logcat(LogPriority.WARN) { "[Smb] 会话已断开，重建: ${conn.location()}" }
                closeKeyLocked(sKey)
            }

            val sessionRef = sessions[sKey] ?: connectLocked(conn, password, sKey, now)
            sessionRef.lastUsed = now

            val shareRef = shares[shKey] ?: run {
                val treeStart = System.currentTimeMillis()
                val share = sessionRef.session.connectShare(shareName) as DiskShare
                logcat(LogPriority.WARN) {
                    "[Smb] TreeConnect 完成 \\${conn.host}\\$shareName，耗时 ${System.currentTimeMillis() - treeStart}ms"
                }
                ShareRef(share, now).also { shares[shKey] = it }
            }
            shareRef.lastUsed = now
            return shareRef.share
        }
    }

    /**
     * 预热：提前把 [conn] 的会话（未配置共享时连共享枚举）建好，把首次浏览的建连成本
     * 挪到后台。失败静默（下次真正访问会自己重连）。
     */
    fun prewarm(conn: SmbConnection, password: String) {
        val start = System.currentTimeMillis()
        runCatching {
            if (conn.share.isNotBlank()) {
                share(conn, password, conn.share)
            } else {
                listShares(conn, password)
            }
        }.onSuccess {
            logcat(LogPriority.WARN) { "[Smb] 预热完成 ${conn.location()}，耗时 ${System.currentTimeMillis() - start}ms" }
        }.onFailure {
            logcat(LogPriority.WARN) { "[Smb] 预热失败 ${conn.location()}（${System.currentTimeMillis() - start}ms）: ${it.message}" }
        }
    }

    /**
     * 枚举服务器上的共享（srvsvc/NetShareEnum over IPC$，质感文件 Material Files 同款配方）。
     * 仅用于连接未配置共享的「服务器根」浏览：路径留空 = 列出全部共享。
     * 过滤打印机/设备/IPC 型与 $ 结尾的管理性共享，只留磁盘型文件共享。
     */
    fun listShares(conn: SmbConnection, password: String): List<String> {
        val now = System.currentTimeMillis()
        trimIdle(now)
        val sKey = sessionKey(conn)
        val session = synchronized(lockFor(sKey)) {
            val existing = sessions[sKey]
            if (existing != null && !existing.connection.isConnected) {
                logcat(LogPriority.WARN) { "[Smb] 会话已断开，重建: ${conn.location()}" }
                closeKeyLocked(sKey)
            }
            val sessionRef = sessions[sKey] ?: connectLocked(conn, password, sKey, now)
            sessionRef.lastUsed = now
            sessionRef.session
        }
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        val serverService = ServerService(transport)
        return serverService.shares1
            .mapNotNull { info ->
                val name = info.netName
                val type = info.type
                if (name.isBlank()) return@mapNotNull null
                // shi1_type 低 2 位 = 0:DISKTREE / 1:PRINTQ / 2:DEVICE / 3:IPC；非 0 即非文件共享。
                val isDisk = (type.toLong() and 0x3L) == 0L
                if (isDisk && !name.endsWith("$")) name else null
            }
            .sortedBy { it.lowercase() }
    }

    /** 打开共享内文件（只读、允许他人并发读）。
     *  连接未配置共享时，[relPath] 第一段即共享名（浏览根=共享列表的口径），其余为共享内路径。
     *  @param relPath `/` 分隔的相对路径。 */
    fun openFile(conn: SmbConnection, password: String, relPath: String): File {
        val shareName: String
        val inSharePath: String
        if (conn.share.isBlank()) {
            val norm = relPath.trim('/')
            shareName = norm.substringBefore('/')
            inSharePath = norm.substringAfter('/', "")
        } else {
            shareName = conn.share
            inSharePath = relPath
        }
        val share = share(conn, password, shareName)
        return share.openFile(
            toSmbPath(inSharePath),
            // FILE_READ_ATTRIBUTES：getFileInformation()（取大小/修改时间）需要它，
            // 只给 FILE_READ_DATA 时 QNAP 等会回 STATUS_ACCESS_DENIED（0xc0000022）。
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    /** 读写失败时调用：作废该连接的会话与共享，下次访问自动重连。 */
    fun invalidate(conn: SmbConnection) {
        val key = sessionKey(conn)
        synchronized(lockFor(key)) { closeKeyLocked(key) }
    }

    /** 按连接 id 作废（删除连接时调用；id 为空的临时连接不处理）。 */
    fun invalidateById(id: String) {
        if (id.isBlank()) return
        sessions.keys.filter { it.startsWith("$id|") }.forEach { key ->
            synchronized(lockFor(key)) { closeKeyLocked(key) }
        }
    }

    /** 关闭全部会话（设置-存储「清除缓存」等场景）。 */
    fun closeAll() {
        sessions.keys.toList().forEach { key ->
            synchronized(lockFor(key)) { closeKeyLocked(key) }
        }
    }

    // ------------------------------------------------------------ 内部

    /** 建连 + 认证；失败时回收 TCP 连接，避免泄漏。调用方需已持有该会话键的锁。 */
    private fun connectLocked(conn: SmbConnection, password: String, sKey: String, now: Long): SessionRef {
        val connectStart = System.currentTimeMillis()
        val connection = client.connect(conn.host, conn.port)
        val tcpMs = System.currentTimeMillis() - connectStart
        try {
            // SY: 用户名留空 = guest 访客（QNAP 等 NAS 口径），不再走匿名空会话——
            // anonymous（null session）会被 QNAP 直接断开，表现即 SESSION_SETUP 阶段 broken pipe。
            val auth = if (conn.user.isBlank()) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(
                    conn.user,
                    password.toCharArray(),
                    conn.domain.ifBlank { null },
                )
            }
            val authStart = System.currentTimeMillis()
            val session = connection.authenticate(auth)
            val ref = SessionRef(connection, session, now)
            sessions[sKey] = ref
            logcat(LogPriority.WARN) {
                "[Smb] 建连完成 ${conn.host}:${conn.port}（user=${conn.user.ifBlank { "guest" }}）" +
                    "，TCP+协商 ${tcpMs}ms，认证 ${System.currentTimeMillis() - authStart}ms"
            }
            return ref
        } catch (e: Exception) {
            runCatching { connection.close() }
            throw e
        }
    }

    /** 会话键：连接 id 优先（同一 NAS 不同账户互不串），测试用的临时连接退回主机端口。 */
    private fun sessionKey(conn: SmbConnection): String {
        val identity = "${conn.host}:${conn.port}|${conn.user}|${conn.domain}"
        return if (conn.id.isBlank()) identity else "${conn.id}|$identity"
    }

    /** `/` 分隔的相对路径 → SMB 反斜杠路径（去掉首尾分隔符）。 */
    fun toSmbPath(relPath: String): String =
        relPath.replace('/', '\\').trim('\\')

    /** 回收空闲会话：逐个按会话键加锁检查，避免误关正在使用的连接。 */
    private fun trimIdle(now: Long) {
        sessions.keys.toList().forEach { key ->
            synchronized(lockFor(key)) {
                val ref = sessions[key] ?: return@synchronized
                if (now - ref.lastUsed > IDLE_MS) {
                    logcat(LogPriority.INFO) { "[Smb] 回收空闲会话 ${key.substringBefore('|')}" }
                    closeKeyLocked(key)
                }
            }
        }
    }

    /** 关闭会话：先关其下所有 share（文件句柄挂在这里），再关 session 与 TCP 连接。 */
    private fun closeKeyLocked(sKey: String) {
        shares.keys.filter { it.startsWith("$sKey|") }.forEach { k ->
            shares.remove(k)?.let { runCatching { it.share.close() } }
        }
        sessions.remove(sKey)?.let { ref ->
            val closeStart = System.currentTimeMillis()
            runCatching { ref.session.close() }
            runCatching { ref.connection.close() }
            // 断开慢也是现场问题之一（logoff 等不到回应时会阻塞到请求超时），记一笔。
            val cost = System.currentTimeMillis() - closeStart
            if (cost > SLOW_LOG_MS) {
                logcat(LogPriority.WARN) { "[Smb] 关闭会话耗时 ${cost}ms: ${sKey.substringBefore('|')}" }
            }
        }
        // 该键已无会话。注意：不回收 keyLocks —— 若在这里删锁对象，可能与仍持有旧锁的
        // 线程形成「两把锁并存」的竞态；锁对象很小，按连接数累积可忽略。
    }

    /** 超过这个耗时就算「慢」，打 WARN 便于 release 版现场取证。 */
    internal const val SLOW_LOG_MS = 1_500L

    private const val IDLE_MS = 60_000L
}
// SY <--
