package eu.kanade.presentation.more.settings.screen

// SY --> Komiho：本地备份与恢复（自写轻量 JSON，覆盖来源列表/个性化/本地阅读历史/书签/收藏分类）
// 作为「设置」顶级入口，不再嵌套在「数据/存储」内。
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import app.mihonsy.komga.data.webdav.WebDavConnection
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.core.common.i18n.stringResource as ctxStringRes
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
        val syncUrlPref = remember { prefs.getString(KOMIHO_SYNC_URL, "") }
        val syncUserPref = remember { prefs.getString(KOMIHO_SYNC_USER, "") }
        val syncServerPassPref = remember { prefs.getString(KOMIHO_SYNC_SERVER_PASS, "") }
        val syncDirPref = remember { prefs.getString(KOMIHO_SYNC_DIR, "komiho") }
        val syncPushPref = remember { prefs.getBoolean(KOMIHO_SYNC_PUSH, false) }
        val syncAutoPref = remember { prefs.getBoolean(KOMIHO_SYNC_AUTORESTORE, false) }
        val syncPwdPref = remember { prefs.getString(KOMIHO_SYNC_PASSWORD, "") }

        var showExportPwd by remember { mutableStateOf(false) }
        var showImportPwd by remember { mutableStateOf(false) }
        var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }

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
                    if (prefs.getBoolean(KOMIHO_SYNC_PUSH, false).get()) {
                        runCatching {
                            val url = prefs.getString(KOMIHO_SYNC_URL, "").get().trimEnd('/')
                            val user = prefs.getString(KOMIHO_SYNC_USER, "").get()
                            val serverPass = prefs.getString(KOMIHO_SYNC_SERVER_PASS, "").get()
                            val dir = prefs.getString(KOMIHO_SYNC_DIR, "komiho").get().ifBlank { "komiho" }
                            val backupPwd = prefs.getString(KOMIHO_SYNC_PASSWORD, "").get().ifBlank { null }
                            if (url.isNotBlank()) {
                                val conn = WebDavConnection(
                                    id = "sync", name = "同步中心", baseUrl = url,
                                    user = user, passEnc = WebDavCredentialCrypto.encrypt(serverPass),
                                )
                                KomihoBackup.pushToWebDav(context, conn, dir, backupPwd)
                                withUIContext { context.toast("已推送到同步中心") }
                            } else {
                                withUIContext { context.toast("请先填写同步中心服务器地址") }
                            }
                        }.onFailure { e ->
                            logcat(LogPriority.ERROR, e) { "同步中心推送失败" }
                        }
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
                confirmLabel = stringResource(MR.strings.backup_export),
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
                confirmLabel = stringResource(MR.strings.backup_import),
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

        // 不用分组：直接平铺「导出 / 导入」两条，省掉多余的分组标题行。
        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.backup_export),
                onClick = { showExportPwd = true },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.backup_import),
                onClick = { importLauncher.launch("*/*") },
            ),
            Preference.PreferenceGroup(
                title = "同步中心（独立 WebDAV）",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncUrlPref,
                        title = "同步中心服务器地址",
                        subtitle = "WebDAV 根地址，如 https://dav.example.com:10007/QNAP2",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncUserPref,
                        title = "用户名",
                        subtitle = "留空=匿名",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncServerPassPref,
                        title = "服务器密码",
                        subtitle = "留空=匿名",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncDirPref,
                        title = "同步目录名",
                        subtitle = "备份存于 <服务器根>/%s/backup",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncPwdPref,
                        title = "备份加密密码（可选）",
                        subtitle = "留空=明文 zip；填写=KMH1 加密容器",
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = syncPushPref,
                        title = "导出后推送到同步中心",
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = syncAutoPref,
                        title = "启动时自动恢复",
                        subtitle = "拉取最新备份，按较新胜合并",
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = "立即同步到 WebDAV",
                        subtitle = "把当前备份推送到同步中心",
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val url = runBlocking { syncUrlPref.get() }.trimEnd('/')
                                    val user = runBlocking { syncUserPref.get() }
                                    val serverPass = runBlocking { syncServerPassPref.get() }
                                    val dir = runBlocking { syncDirPref.get() }.ifBlank { "komiho" }
                                    val backupPwd = runBlocking { syncPwdPref.get() }.ifBlank { null }
                                    if (url.isBlank()) {
                                        withUIContext { context.toast("请先填写同步中心服务器地址") }
                                        return@launch
                                    }
                                    val conn = WebDavConnection(
                                        id = "sync", name = "同步中心", baseUrl = url,
                                        user = user, passEnc = WebDavCredentialCrypto.encrypt(serverPass),
                                    )
                                    KomihoBackup.pushToWebDav(context, conn, dir, backupPwd)
                                    withUIContext { context.toast("已推送到同步中心") }
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e)
                                    withUIContext { context.toast("推送失败：${e.message}") }
                                }
                            }
                        },
                    ),
                ),
            ),
        )
    }

    private const val KOMIHO_SYNC_URL = "komiho_sync_center_url"
    private const val KOMIHO_SYNC_USER = "komiho_sync_center_user"
    private const val KOMIHO_SYNC_SERVER_PASS = "komiho_sync_center_server_pass"
    private const val KOMIHO_SYNC_DIR = "komiho_sync_center_dir"
    private const val KOMIHO_SYNC_PUSH = "komiho_sync_center_push"
    private const val KOMIHO_SYNC_AUTORESTORE = "komiho_sync_center_autorestore"
    private const val KOMIHO_SYNC_PASSWORD = "komiho_sync_center_password"

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
