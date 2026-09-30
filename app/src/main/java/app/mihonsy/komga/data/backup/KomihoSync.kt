package app.mihonsy.komga.data.backup

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.mihonsy.komga.data.webdav.WebDavConnection
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.common.archive.WebDavRandomAccessSource
import okhttp3.OkHttpClient
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

// SY --> Komiho: WebDAV 同步引擎（设置 → 备份与同步 → 同步）。
// 统一同步模型：任何一次同步（手动 + 触发条件）= 拉远端最新 → 较新胜合并进本地 → 需要时导出推送。
// 推送前必须先合并：本机旧快照不得直接覆盖远端，防止多机场景回退他机新进度。
// 凭据（连接密码/备份密码）统一 enc1: 加密落盘；decryptStored 兼容历史明文，保存时即升级。
// 结果只走 app 内反馈（本应用不申请通知权限）：自动同步成功静默（仅写 last_* 供主屏状态行），
// 失败才 toast；手动推送/恢复完成后 toast 确认。
// last_result 持久化为语言中立键（`ok[:detail]` / `fail:<原因原文>`），展示时才本地化。
object KomihoSync {

    const val KEY_URL = "komiho_sync_center_url"
    const val KEY_USER = "komiho_sync_center_user"
    const val KEY_SERVER_PASS = "komiho_sync_center_server_pass"
    const val KEY_DIR = "komiho_sync_center_dir"
    const val KEY_BACKUP_PASS = "komiho_sync_center_password"
    const val KEY_INSECURE_TLS = "komiho_sync_center_insecure_tls"
    const val KEY_TRIGGERS = "komiho_sync_triggers"
    const val KEY_CONTENT = "komiho_sync_content"
    const val KEY_LAST_ACTION = "komiho_sync_last_action"
    const val KEY_LAST_TIME = "komiho_sync_last_time"
    const val KEY_LAST_RESULT = "komiho_sync_last_result"

    const val ACTION_PUSH = "push"
    const val ACTION_PULL = "pull"
    const val ACTION_TEST = "test"

    const val TRIGGER_CHAPTER_READ = "chapter_read"
    const val TRIGGER_CHAPTER_OPEN = "chapter_open"
    const val TRIGGER_APP_START = "app_start"
    const val TRIGGER_FOREGROUND = "foreground"

    /** 触发条件全集（键；展示文案由 UI 侧本地化）。 */
    val ALL_TRIGGERS = listOf(
        TRIGGER_CHAPTER_READ,
        TRIGGER_CHAPTER_OPEN,
        TRIGGER_APP_START,
        TRIGGER_FOREGROUND,
    )

    /** 同步内容选择（多选）：键集合，缺省全开。 */
    const val CONTENT_SOURCE_CONFIG = "source_config"
    const val CONTENT_WEBDAV_HISTORY = "webdav_history"
    const val CONTENT_SMB_HISTORY = "smb_history"
    val DEFAULT_CONTENT = setOf(CONTENT_SOURCE_CONFIG, CONTENT_WEBDAV_HISTORY, CONTENT_SMB_HISTORY)

    fun contentEnabled(prefs: PreferenceStore, key: String): Boolean =
        prefs.getStringSet(KEY_CONTENT, DEFAULT_CONTENT).get().contains(key)

    private val inFlight = AtomicBoolean(false)
    private val autoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var foregroundRegistered = false

    /** 当前是否已有同步在跑（手动与自动共用一把锁）。 */
    val busy: Boolean get() = inFlight.get()

    class SyncConfig(
        val conn: WebDavConnection,
        val dir: String,
        val backupPass: String?,
        val insecureTls: Boolean = false,
    )

    /** 按开关选择客户端：默认走全局共享客户端；开启「忽略证书校验」走信任所有证书的客户端。 */
    private fun clientFor(insecure: Boolean): OkHttpClient =
        if (insecure) WebDavRandomAccessSource.insecureHttpClient() else WebDavRandomAccessSource.sharedHttpClient()

    /** 读取同步中心配置；未配置返回 null。明文历史密码在此处解出。 */
    fun readConfig(prefs: PreferenceStore): SyncConfig? {
        val url = prefs.getString(KEY_URL, "").get().trim().trimEnd('/')
        if (url.isBlank()) return null
        val serverPass = WebDavCredentialCrypto.decryptStored(prefs.getString(KEY_SERVER_PASS, "").get())
        val conn = WebDavConnection(
            id = "sync",
            name = "WebDAV 同步",
            baseUrl = url,
            user = prefs.getString(KEY_USER, "").get(),
            passEnc = WebDavCredentialCrypto.encrypt(serverPass),
        )
        val dir = prefs.getString(KEY_DIR, "komiho").get().ifBlank { "komiho" }
        val backupPass = WebDavCredentialCrypto
            .decryptStored(prefs.getString(KEY_BACKUP_PASS, "").get())
            .ifBlank { null }
        val insecureTls = prefs.getBoolean(KEY_INSECURE_TLS, false).get()
        return SyncConfig(conn, dir, backupPass, insecureTls)
    }

    fun configured(prefs: PreferenceStore): Boolean = readConfig(prefs) != null

    fun triggerEnabled(prefs: PreferenceStore, trigger: String): Boolean =
        prefs.getStringSet(KEY_TRIGGERS, emptySet()).get().contains(trigger)

    data class Outcome(val ok: Boolean, val message: String)

    /**
     * 统一同步入口。push=true：拉→合→推（完整同步）；push=false：只拉合不推。
     * 不抛异常；任何失败写 last_result 后返回失败 Outcome。
     */
    suspend fun sync(context: Context, push: Boolean): Outcome {
        if (!inFlight.compareAndSet(false, true)) {
            return Outcome(false, context.stringResource(MR.strings.komiho_sync_busy))
        }
        try {
            return doSync(context, push)
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun doSync(context: Context, push: Boolean): Outcome {
        val prefs = Injekt.get<PreferenceStore>()
        val action = if (push) ACTION_PUSH else ACTION_PULL
        val cfg = readConfig(prefs)
            ?: return fail(context, prefs, action, context.stringResource(MR.strings.komiho_sync_not_configured))
        val client = clientFor(cfg.insecureTls)

        // 1. 拉远端最新并合并。远端无备份（首次）跳过；
        //    网络失败必须中止——不带合并直接推送等于盲传旧快照，会回退他机进度。
        val bytes = try {
            KomihoBackup.pullLatestFromWebDav(cfg.conn, cfg.dir, client)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "WebDAV 同步拉取失败" }
            return fail(context, prefs, action, e.message ?: e.javaClass.simpleName)
        }
        if (bytes != null) {
            try {
                val json = KomihoBackup.readBackupBytes(bytes, cfg.backupPass)
                KomihoBackup.importBackup(context, json, cfg.backupPass)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "WebDAV 同步合并失败" }
                return fail(context, prefs, action, context.stringResource(MR.strings.komiho_sync_merge_fail, e.message ?: ""))
            }
        }

        // 2. 需要时从合并后的本地库导出推送。
        if (push) {
            try {
                KomihoBackup.pushToWebDav(context, cfg.conn, cfg.dir, cfg.backupPass, client)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "WebDAV 同步推送失败" }
                return fail(context, prefs, ACTION_PUSH, e.message ?: e.javaClass.simpleName)
            }
        }

        record(prefs, action, ok = true, detail = "")
        return Outcome(
            true,
            context.stringResource(if (push) MR.strings.komiho_sync_push_ok else MR.strings.komiho_sync_pull_ok),
        )
    }

    private fun record(prefs: PreferenceStore, action: String, ok: Boolean, detail: String) {
        prefs.getString(KEY_LAST_ACTION, "").set(action)
        prefs.getLong(KEY_LAST_TIME, 0L).set(System.currentTimeMillis())
        prefs.getString(KEY_LAST_RESULT, "").set(if (ok) "ok:$detail" else "fail:$detail")
    }

    private fun fail(context: Context, prefs: PreferenceStore, action: String, reason: String): Outcome {
        record(prefs, action, ok = false, detail = reason)
        return Outcome(false, context.stringResource(MR.strings.komiho_sync_fail_prefix, reason))
    }

    /** 自动触发（触发条件命中时调用）：未启用/未配置直接忽略；成功静默，失败 toast。 */
    fun autoSync(context: Context, trigger: String) {
        val prefs = Injekt.get<PreferenceStore>()
        if (!triggerEnabled(prefs, trigger)) return
        if (!configured(prefs)) return
        val app = context.applicationContext
        autoScope.launch {
            // 「章节阅读后」带短延迟：退出阅读器时的最后一次进度写库是异步的，等它落地再导出。
            if (trigger == TRIGGER_CHAPTER_READ) delay(2_000)
            val outcome = sync(app, push = true)
            if (!outcome.ok) {
                runCatching {
                    withUIContext { app.toast(outcome.message) }
                }
            }
        }
    }

    /** 注册「应用回到前台」触发（含息屏亮屏解锁；进程内只注册一次）。 */
    fun registerForegroundTrigger(context: Context) {
        if (foregroundRegistered) return
        foregroundRegistered = true
        val app = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    autoSync(app, TRIGGER_FOREGROUND)
                }
            },
        )
    }

    /**
     * 测试连接：对给定配置发 PROPFIND。返回 (是否通过, 详情)——
     * 通过时详情为耗时毫秒数，失败时为原始错误文案（异常信息未本地化，原样透出）。
     * 用表单当前值即可测（不必先保存）。
     */
    fun testConnection(cfg: SyncConfig): Pair<Boolean, String> {
        val auth = WebDavRandomAccessSource.basicAuth(
            cfg.conn.user,
            WebDavCredentialCrypto.decryptStored(cfg.conn.passEnc),
        )
        return try {
            val t0 = System.currentTimeMillis()
            WebDavRandomAccessSource.propfind(cfg.conn.baseUrl, auth, clientFor(cfg.insecureTls))
            true to (System.currentTimeMillis() - t0).toString()
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    /** 记录一次测试结果（主屏状态行联动）。 */
    fun recordTestResult(prefs: PreferenceStore, ok: Boolean, detail: String) {
        record(prefs, ACTION_TEST, ok = ok, detail = detail)
    }

    /** 主屏状态行摘要（本地化）；未配置返回 null。 */
    fun statusSummary(context: Context, prefs: PreferenceStore): String? {
        readConfig(prefs) ?: return null
        val time = prefs.getLong(KEY_LAST_TIME, 0L).get()
        val action = prefs.getString(KEY_LAST_ACTION, "").get()
        val raw = prefs.getString(KEY_LAST_RESULT, "").get()
        if (time == 0L || action.isBlank()) {
            return context.stringResource(MR.strings.komiho_sync_status_none)
        }
        val label = context.stringResource(
            when (action) {
                ACTION_PUSH -> MR.strings.komiho_sync_action_push
                ACTION_PULL -> MR.strings.komiho_sync_action_pull
                else -> MR.strings.komiho_sync_action_test
            },
        )
        val ok = !raw.startsWith("fail:")
        val detail = if (ok) raw.removePrefix("ok:") else raw.removePrefix("fail:")
        val resultText = when {
            ok && action == ACTION_TEST -> context.stringResource(MR.strings.komiho_sync_result_test_ok, detail)
            ok -> context.stringResource(MR.strings.komiho_sync_result_ok)
            else -> context.stringResource(MR.strings.komiho_sync_result_fail, detail)
        }
        val timeText = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
        return context.stringResource(MR.strings.komiho_sync_status_line, label, timeText, resultText)
    }
}
// SY <--
