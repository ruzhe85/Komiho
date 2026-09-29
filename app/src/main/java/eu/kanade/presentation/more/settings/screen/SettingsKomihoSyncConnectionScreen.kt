package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.backup.KomihoSync
import app.mihonsy.komga.data.webdav.WebDavConnection
import app.mihonsy.komga.data.webdav.WebDavConnectionStore
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.util.system.toast

// SY --> Komiho：WebDAV 同步连接编辑子屏（从「备份与还原」页点「WebDAV 同步」进入）。
// 与来源连接列表（WebDavConnectionStore）解耦：同步配置独立存放，
// 仅「从已有 WebDAV 选择」时把来源凭据复制进表单（待保存态），清空只清同步配置、不动来源。
// 表单模型：字段编辑只改内存，「保存连接」才落盘（密码 enc1: 加密存储）。
// 设计约定：小字只放状态/取值；「留空=匿名」「清空不删来源」等说明移入各对话框。
object SettingsKomihoSyncConnectionScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.komiho_sync_connection

    @Composable
    override fun getPreferences(): List<Preference> = buildSyncConnectionPreferences()

    /** 表单快照（内存态；serverPass/backupPass 一律明文，仅保存时加密）。 */
    private data class SyncForm(
        val url: String = "",
        val user: String = "",
        val serverPass: String = "",
        val dir: String = "komiho",
        val backupPass: String = "",
        val insecureTls: Boolean = false,
    )

    private enum class Field(val title: String, val masked: Boolean = false) {
        URL("服务器地址"),
        USER("用户名"),
        SERVER_PASS("连接密码", masked = true),
        DIR("同步目录名"),
        BACKUP_PASS("备份密码", masked = true),
    }

    private fun PreferenceStore.loadForm(): SyncForm = SyncForm(
        url = getString(KomihoSync.KEY_URL, "").get().trim().trimEnd('/'),
        user = getString(KomihoSync.KEY_USER, "").get(),
        serverPass = decryptStoredPass(getString(KomihoSync.KEY_SERVER_PASS, "").get()),
        dir = getString(KomihoSync.KEY_DIR, "komiho").get().ifBlank { "komiho" },
        backupPass = decryptStoredPass(getString(KomihoSync.KEY_BACKUP_PASS, "").get()),
        insecureTls = getBoolean(KomihoSync.KEY_INSECURE_TLS, false).get(),
    )

    private fun decryptStoredPass(stored: String): String =
        if (stored.isBlank()) "" else WebDavCredentialCrypto.decryptStored(stored)

    /**
     * WebDAV 同步子屏的偏好列表。
     * @param onBack 非空时在列表顶部插入「返回」项（父页就地切换成子屏时使用）；
     *               为空表示由页面栈自己提供返回（voyager 场景）。
     */
    @Composable
    fun buildSyncConnectionPreferences(onBack: (() -> Unit)? = null): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val prefs = remember { Injekt.get<PreferenceStore>() }
        val davs = remember { WebDavConnectionStore.all() }

        var saved by remember { mutableStateOf(prefs.loadForm()) }
        var form by remember { mutableStateOf(saved) }
        val dirty = form != saved

        var editingField by remember { mutableStateOf<Field?>(null) }
        var showPick by remember { mutableStateOf(false) }
        var showClearConfirm by remember { mutableStateOf(false) }
        var testing by remember { mutableStateOf(false) }

        fun save() {
            val normalized = form.copy(
                url = form.url.trim().trimEnd('/'),
                user = form.user.trim(),
                dir = form.dir.ifBlank { "komiho" },
            )
            prefs.getString(KomihoSync.KEY_URL, "").set(normalized.url)
            prefs.getString(KomihoSync.KEY_USER, "").set(normalized.user)
            prefs.getString(KomihoSync.KEY_SERVER_PASS, "").set(
                if (normalized.serverPass.isBlank()) "" else WebDavCredentialCrypto.encrypt(normalized.serverPass),
            )
            prefs.getString(KomihoSync.KEY_DIR, "").set(normalized.dir)
            prefs.getString(KomihoSync.KEY_BACKUP_PASS, "").set(
                if (normalized.backupPass.isBlank()) "" else WebDavCredentialCrypto.encrypt(normalized.backupPass),
            )
            prefs.getBoolean(KomihoSync.KEY_INSECURE_TLS, false).set(normalized.insecureTls)
            saved = normalized
            form = normalized
            context.toast("已保存")
        }

        fun clearAll() {
            prefs.getString(KomihoSync.KEY_URL, "").set("")
            prefs.getString(KomihoSync.KEY_USER, "").set("")
            prefs.getString(KomihoSync.KEY_SERVER_PASS, "").set("")
            prefs.getString(KomihoSync.KEY_DIR, "").set("komiho")
            prefs.getString(KomihoSync.KEY_BACKUP_PASS, "").set("")
            prefs.getBoolean(KomihoSync.KEY_INSECURE_TLS, false).set(false)
            saved = SyncForm()
            form = saved
            context.toast("已清空 WebDAV 同步配置")
        }

        fun testConnection() {
            if (testing || form.url.isBlank()) return
            testing = true
            val cfg = KomihoSync.SyncConfig(
                conn = WebDavConnection(
                    id = "sync",
                    name = "WebDAV 同步",
                    baseUrl = form.url.trim().trimEnd('/'),
                    user = form.user.trim(),
                    passEnc = WebDavCredentialCrypto.encrypt(form.serverPass),
                ),
                dir = form.dir.ifBlank { "komiho" },
                backupPass = form.backupPass.ifBlank { null },
                insecureTls = form.insecureTls,
            )
            scope.launch(Dispatchers.IO) {
                val result = KomihoSync.testConnection(cfg)
                KomihoSync.recordTestResult(prefs, result)
                withUIContext {
                    testing = false
                    context.toast("测试连接：$result")
                }
            }
        }

        // ---------- 字段编辑对话框 ----------
        editingField?.let { field ->
            FieldEditDialog(
                field = field,
                initial = when (field) {
                    Field.URL -> form.url
                    Field.USER -> form.user
                    Field.SERVER_PASS -> form.serverPass
                    Field.DIR -> form.dir
                    Field.BACKUP_PASS -> form.backupPass
                },
                onConfirm = { value ->
                    editingField = null
                    form = when (field) {
                        Field.URL -> form.copy(url = value.trim().trimEnd('/'))
                        Field.USER -> form.copy(user = value.trim())
                        Field.SERVER_PASS -> form.copy(serverPass = value)
                        Field.DIR -> form.copy(dir = value.trim())
                        Field.BACKUP_PASS -> form.copy(backupPass = value)
                    }
                },
                onDismiss = { editingField = null },
            )
        }

        // ---------- 从已有 WebDAV 载入（对话框选择；仅回填表单，待保存） ----------
        if (showPick) {
            AlertDialog(
                onDismissRequest = { showPick = false },
                title = { Text(text = "从已有 WebDAV 载入") },
                text = {
                    Column {
                        if (davs.isEmpty()) {
                            Text(text = "暂无已保存的 WebDAV 来源")
                        }
                        davs.forEach { conn ->
                            Column(
                                modifier = Modifier
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        // 仅复制到表单（待保存态），不自动测试、不落盘。
                                        form = form.copy(
                                            url = conn.baseUrl.trimEnd('/'),
                                            user = conn.user,
                                            serverPass = WebDavCredentialCrypto.decryptStored(conn.passEnc),
                                        )
                                        showPick = false
                                    },
                            ) {
                                Text(text = conn.displayName())
                                Text(
                                    text = conn.baseUrl,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showPick = false }) { Text(text = "取消") }
                },
            )
        }

        // ---------- 清空确认 ----------
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text(text = "清除此 WebDAV 同步配置？") },
                text = {
                    Text(
                        text = "将清空地址、账号、密码与备份密码，回到「未配置」。" +
                            "不会删除任何 WebDAV 来源连接，也不会删除已上传到服务器的备份文件。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirm = false
                        clearAll()
                        onBack?.invoke()
                    }) { Text(text = "清空配置") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text(text = stringResource(MR.strings.action_cancel)) }
                },
            )
        }

        // ---------- 状态行 ----------
        val statusItem = when {
            dirty -> Preference.PreferenceItem.TextPreference(
                title = "待保存",
                subtitle = "修改尚未保存",
            )
            saved.url.isBlank() -> Preference.PreferenceItem.TextPreference(
                title = "未配置",
            )
            else -> Preference.PreferenceItem.TextPreference(
                title = "已配置 · ${hostOf(saved.url)}",
                subtitle = lastRecordLine(prefs),
            )
        }

        return listOfNotNull<Preference>(
            onBack?.let { back ->
                Preference.PreferenceItem.TextPreference(
                    title = "返回",
                    onClick = back,
                )
            },
            Preference.PreferenceGroup(
                title = "状态",
                preferenceItems = listOf(statusItem),
            ),
            Preference.PreferenceGroup(
                title = "连接信息",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "服务器地址 *",
                        subtitle = form.url.ifBlank { "未设置" },
                        onClick = { editingField = Field.URL },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = "用户名",
                        subtitle = form.user.ifBlank { "未设置" },
                        onClick = { editingField = Field.USER },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = "连接密码",
                        subtitle = if (form.serverPass.isBlank()) "未设置" else "已设置",
                        onClick = { editingField = Field.SERVER_PASS },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = "同步目录名",
                        subtitle = form.dir,
                        onClick = { editingField = Field.DIR },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = "备份密码",
                        subtitle = if (form.backupPass.isBlank()) "留空不加密" else "已设置",
                        onClick = { editingField = Field.BACKUP_PASS },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = "忽略 HTTPS 证书校验",
                        // 服务器为自签名/缺中间证书/证书过期时开启；仅作用于同步中心请求。
                        subtitle = if (form.insecureTls) "已开启" else "已关闭",
                        onClick = { form = form.copy(insecureTls = !form.insecureTls) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "快捷载入",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "从已有 WebDAV 选择",
                        onClick = { showPick = true },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "操作",
                preferenceItems = buildList {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = "保存连接",
                            enabled = dirty,
                            onClick = { save() },
                        ),
                    )
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = "测试连接",
                            enabled = !testing && form.url.isNotBlank(),
                            onClick = { testConnection() },
                        ),
                    )
                    if (saved.url.isNotBlank()) {
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = "清空此连接",
                                onClick = { showClearConfirm = true },
                            ),
                        )
                    }
                },
            ),
        )
    }

    private fun hostOf(url: String): String =
        try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }

    /** 上次动作记录行（推送/恢复/测试共用 last_* prefs）。 */
    private fun lastRecordLine(prefs: PreferenceStore): String? {
        val time = prefs.getLong(KomihoSync.KEY_LAST_TIME, 0L).get()
        val action = prefs.getString(KomihoSync.KEY_LAST_ACTION, "").get()
        val result = prefs.getString(KomihoSync.KEY_LAST_RESULT, "").get()
        if (time == 0L || action.isBlank()) return null
        val label = when (action) {
            KomihoSync.ACTION_PUSH -> "推送"
            KomihoSync.ACTION_PULL -> "恢复"
            else -> "测试"
        }
        val timeText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(time))
        return "上次$label $timeText · $result"
    }

    @Composable
    private fun FieldEditDialog(
        field: Field,
        initial: String,
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var text by remember(field) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = field.title) },
            text = {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = if (field.masked) {
                        PasswordVisualTransformation()
                    } else {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    },
                    placeholder = {
                        when (field) {
                            Field.URL -> Text(text = "https://dav.example.com:10007/QNAP2")
                            Field.USER -> Text(text = "留空 = 匿名")
                            Field.SERVER_PASS -> Text(text = "留空 = 匿名")
                            Field.DIR -> Text(text = "komiho")
                            Field.BACKUP_PASS -> Text(text = "留空不加密")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(text) }) { Text(text = "确定") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(text = stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}
// SY <--
