package eu.kanade.presentation.more.settings.screen

// SY --> Komiho：本地备份与恢复（自写轻量 JSON，覆盖来源列表/个性化/本地阅读历史/书签/收藏分类）
// 作为「设置」顶级入口，不再嵌套在「数据/存储」内。
// 「同步」分组为 WebDAV 同步：状态行 + 触发条件（多选）+ 立即推送/立即恢复；
// 连接配置在子屏（就地切换），统一同步模型见 [app.mihonsy.komga.data.backup.KomihoSync]。
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.mihonsy.komga.data.backup.KomihoBackup
import app.mihonsy.komga.data.backup.KomihoSync
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource as ctxStringRes
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import eu.kanade.tachiyomi.util.system.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsKomihoBackupScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_backup

    @Composable
    override fun getPreferences(): List<Preference> = buildBackupPreferences()

    @Composable
    private fun buildBackupPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val prefs = remember { Injekt.get<PreferenceStore>() }

        val triggersPref = remember { prefs.getStringSet(KomihoSync.KEY_TRIGGERS, emptySet()) }

        var showExportPwd by remember { mutableStateOf(false) }
        var showImportPwd by remember { mutableStateOf(false) }
        var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
        var showSyncConn by remember { mutableStateOf(false) }
        var syncInFlight by remember { mutableStateOf(false) }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            val password = pendingExportPassword
            pendingExportPassword = null
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        KomihoBackup.writeBackupFile(context, password, os)
                    }
                    withUIContext { context.toast(context.ctxStringRes(MR.strings.backup_exported)) }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    withUIContext { context.toast(context.ctxStringRes(MR.strings.backup_export_failed, e.message ?: "")) }
                }
            }
        }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        withUIContext { context.toast(context.ctxStringRes(MR.strings.backup_file_empty)) }
                        return@launch
                    }
                    if (KomihoBackup.isEncrypted(bytes)) {
                        // 加密容器：先留住字节，等用户输入密码再解密 + 解压。
                        pendingImportBytes = bytes
                        withUIContext { showImportPwd = true }
                    } else {
                        val json = KomihoBackup.readBackupBytes(bytes, null)
                        val summary = KomihoBackup.importBackup(context, json, null)
                        withUIContext { context.toast(summary.toString()) }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    withUIContext { context.toast(context.ctxStringRes(MR.strings.backup_import_failed, e.message ?: "")) }
                }
            }
        }

        if (showExportPwd) {
            PasswordDialog(
                title = stringResource(MR.strings.backup_set_password),
                placeholder = stringResource(MR.strings.backup_password_optional),
                confirmLabel = "创建备份",
                onConfirm = { pwd ->
                    showExportPwd = false
                    pendingExportPassword = pwd
                    // 无密码 → 标准 zip；有密码 → KMH1 私有容器（后缀区分，免得被当 zip 解压失败）。
                    val ext = if (pwd.isBlank()) "zip" else "komiho"
                    val name = "komiho-backup-" +
                        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".$ext"
                    runCatching { exportLauncher.launch(name) }
                        .onFailure { context.toast(MR.strings.file_picker_error) }
                },
                onDismiss = { showExportPwd = false },
            )
        }

        if (showImportPwd) {
            PasswordDialog(
                title = stringResource(MR.strings.backup_enter_password),
                placeholder = stringResource(MR.strings.backup_encrypted_hint),
                confirmLabel = "还原备份",
                onConfirm = { pwd ->
                    showImportPwd = false
                    val bytes = pendingImportBytes
                    pendingImportBytes = null
                    if (bytes == null) return@PasswordDialog
                    scope.launch(Dispatchers.IO) {
                        try {
                            val json = KomihoBackup.readBackupBytes(bytes, pwd)
                            val summary = KomihoBackup.importBackup(context, json, pwd)
                            withUIContext { context.toast(summary.toString()) }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e)
                            withUIContext { context.toast(context.ctxStringRes(MR.strings.backup_import_failed, e.message ?: "")) }
                        }
                    }
                },
                onDismiss = { showImportPwd = false },
            )
        }

        fun manualSync(push: Boolean) {
            if (syncInFlight || KomihoSync.busy) return
            if (!KomihoSync.configured(prefs)) {
                context.toast("请先配置 WebDAV 同步")
                showSyncConn = true
                return
            }
            syncInFlight = true
            scope.launch(Dispatchers.IO) {
                val outcome = KomihoSync.sync(context.applicationContext, push)
                withUIContext {
                    syncInFlight = false
                    context.toast(outcome.message)
                }
            }
        }

        // 系统返回键：处在 WebDAV 同步子屏时先退回备份页。
        BackHandler(enabled = showSyncConn) { showSyncConn = false }

        // WebDAV 同步子屏：本页由 Komga 设置页直接渲染（没有 voyager Navigator 可用），
        // 所以不 push 页面，而是就地切换成子屏的偏好列表，靠返回项/返回键退回。
        if (showSyncConn) {
            return SettingsKomihoSyncConnectionScreen.buildSyncConnectionPreferences(
                onBack = { showSyncConn = false },
            )
        }

        val configured = KomihoSync.configured(prefs)
        val statusSubtitle = KomihoSync.statusSummary(prefs) ?: "未配置 · 点击设置"

        // 本地备份两条归入「备份与还原」分组，与「同步」分组并列。
        return listOf(
            Preference.PreferenceGroup(
                title = "备份与还原",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "创建备份",
                        onClick = { showExportPwd = true },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = "还原备份",
                        onClick = { importLauncher.launch("*/*") },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "同步",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "WebDAV 同步",
                        subtitle = statusSubtitle,
                        onClick = { showSyncConn = true },
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = triggersPref,
                        entries = KomihoSync.ALL_TRIGGERS.toMap(),
                        title = "触发条件",
                        // 设计约定：条目下方不显示已选项（状态/取值以外不放小字）。
                        subtitle = null,
                        subtitleProvider = { _, _ -> null },
                        enabled = configured,
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = "立即推送",
                        // 完整同步：拉→合→推。
                        enabled = configured && !syncInFlight,
                        onClick = { manualSync(push = true) },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = "立即恢复",
                        // 只拉合，不推送。
                        enabled = configured && !syncInFlight,
                        onClick = { manualSync(push = false) },
                    ),
                ),
            ),
        )
    }

    private var pendingExportPassword: String? = null

    @Composable
    private fun PasswordDialog(
        title: String,
        placeholder: String,
        confirmLabel: String,
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = title) },
            text = {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text(text = placeholder) },
                )
            },
            confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(text = confirmLabel) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(text = stringResource(MR.strings.action_cancel)) } },
        )
    }

}
// SY <--
