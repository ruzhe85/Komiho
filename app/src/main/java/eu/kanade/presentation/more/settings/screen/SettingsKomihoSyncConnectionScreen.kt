package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.mihonsy.komga.data.backup.KomihoSync
import app.mihonsy.komga.data.webdav.WebDavConnection
import app.mihonsy.komga.data.webdav.WebDavConnectionStore
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource as composeStringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.util.system.toast

// SY --> Komiho：WebDAV 同步连接编辑子屏（从「备份与同步」页点「WebDAV 同步」进入）。
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

    private enum class Field(val titleRes: StringResource, val masked: Boolean = false) {
        URL(MR.strings.komiho_sync_field_url),
        USER(MR.strings.komiho_sync_field_user),
        SERVER_PASS(MR.strings.komiho_sync_field_pass, masked = true),
        DIR(MR.strings.komiho_sync_field_dir),
        BACKUP_PASS(MR.strings.komiho_sync_field_backup_pass, masked = true),
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

    private fun hostOf(url: String): String =
        try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }

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
            context.toast(context.stringResource(MR.strings.komiho_sync_saved))
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
            context.toast(context.stringResource(MR.strings.komiho_sync_cleared))
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
                val (ok, detail) = KomihoSync.testConnection(cfg)
                KomihoSync.recordTestResult(prefs, ok, detail)
                withUIContext {
                    testing = false
                    val resultText = if (ok) {
                        context.stringResource(MR.strings.komiho_sync_result_test_ok, detail)
                    } else {
                        context.stringResource(MR.strings.komiho_sync_result_fail, detail)
                    }
                    context.toast(context.stringResource(MR.strings.komiho_sync_test_result, resultText))
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
                title = { Text(text = composeStringResource(MR.strings.komiho_sync_pick_title)) },
                text = {
                    Column {
                        if (davs.isEmpty()) {
                            Text(text = composeStringResource(MR.strings.komiho_sync_pick_empty))
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
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showPick = false }) { Text(text = composeStringResource(MR.strings.action_cancel)) }
                },
            )
        }

        // ---------- 清空确认 ----------
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text(text = composeStringResource(MR.strings.komiho_sync_clear_title)) },
                text = {
                    Text(text = composeStringResource(MR.strings.komiho_sync_clear_msg))
                },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirm = false
                        clearAll()
                        onBack?.invoke()
                    }) { Text(text = composeStringResource(MR.strings.komiho_sync_clear_btn)) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text(text = composeStringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        // ---------- 状态行 ----------
        val statusItem = when {
            dirty -> Preference.PreferenceItem.TextPreference(
                title = composeStringResource(MR.strings.komiho_sync_state_pending),
                subtitle = composeStringResource(MR.strings.komiho_sync_state_pending_desc),
            )
            saved.url.isBlank() -> Preference.PreferenceItem.TextPreference(
                title = composeStringResource(MR.strings.komiho_sync_state_unconfigured),
            )
            else -> Preference.PreferenceItem.TextPreference(
                title = composeStringResource(
                    MR.strings.komiho_sync_state_connected,
                    hostOf(saved.url),
                ),
                subtitle = KomihoSync.statusSummary(context, prefs),
            )
        }

        return listOfNotNull<Preference>(
            onBack?.let { back ->
                Preference.PreferenceItem.TextPreference(
                    title = composeStringResource(MR.strings.komiho_sync_back),
                    onClick = back,
                )
            },
            Preference.PreferenceGroup(
                title = composeStringResource(MR.strings.komiho_sync_group_status),
                preferenceItems = listOf(statusItem),
            ),
            Preference.PreferenceGroup(
                title = composeStringResource(MR.strings.komiho_sync_group_conn),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = composeStringResource(MR.strings.komiho_sync_field_url),
                        subtitle = form.url.ifBlank { composeStringResource(MR.strings.komiho_sync_value_unset) },
                        onClick = { editingField = Field.URL },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = composeStringResource(MR.strings.komiho_sync_field_user),
                        subtitle = form.user.ifBlank { composeStringResource(MR.strings.komiho_sync_value_unset) },
                        onClick = { editingField = Field.USER },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = composeStringResource(MR.strings.komiho_sync_field_pass),
                        subtitle = if (form.serverPass.isBlank()) {
                            composeStringResource(MR.strings.komiho_sync_value_unset)
                        } else {
                            composeStringResource(MR.strings.komiho_sync_value_set)
                        },
                        onClick = { editingField = Field.SERVER_PASS },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = composeStringResource(MR.strings.komiho_sync_field_dir),
                        subtitle = form.dir,
                        onClick = { editingField = Field.DIR },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = composeStringResource(MR.strings.komiho_sync_field_backup_pass),
                        subtitle = if (form.backupPass.isBlank()) {
                            composeStringResource(MR.strings.komiho_sync_value_no_pass)
                        } else {
                            composeStringResource(MR.strings.komiho_sync_value_set)
                        },
                        onClick = { editingField = Field.BACKUP_PASS },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = composeStringResource(MR.strings.komiho_sync_field_insecure),
                        subtitle = if (form.insecureTls) {
                            composeStringResource(MR.strings.komiho_sync_value_set)
                        } else {
                            composeStringResource(MR.strings.komiho_sync_value_unset)
                        },
                        onClick = { form = form.copy(insecureTls = !form.insecureTls) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = composeStringResource(MR.strings.komiho_sync_group_pick),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = composeStringResource(MR.strings.komiho_sync_pick),
                        onClick = { showPick = true },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = composeStringResource(MR.strings.komiho_sync_group_ops),
                preferenceItems = buildList {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = composeStringResource(MR.strings.komiho_sync_save),
                            enabled = dirty,
                            onClick = { save() },
                        ),
                    )
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = composeStringResource(MR.strings.komiho_sync_test),
                            enabled = !testing && form.url.isNotBlank(),
                            onClick = { testConnection() },
                        ),
                    )
                    if (saved.url.isNotBlank()) {
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = composeStringResource(MR.strings.komiho_sync_clear),
                                onClick = { showClearConfirm = true },
                            ),
                        )
                    }
                },
            ),
        )
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
            title = { Text(text = composeStringResource(field.titleRes)) },
            text = {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = if (field.masked) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    placeholder = {
                        when (field) {
                            Field.URL -> Text(text = "https://dav.example.com:10007/QNAP2")
                            Field.USER -> Text(text = composeStringResource(MR.strings.komiho_sync_hint_anon))
                            Field.SERVER_PASS -> Text(text = composeStringResource(MR.strings.komiho_sync_hint_anon))
                            Field.DIR -> Text(text = "komiho")
                            Field.BACKUP_PASS -> Text(text = composeStringResource(MR.strings.komiho_sync_value_no_pass))
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(text) }) {
                    Text(text = composeStringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = composeStringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
// SY <--
