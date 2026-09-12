package eu.kanade.presentation.more.settings.screen

// SY --> Komiho：本地备份与恢复（自写轻量 JSON，覆盖来源列表/个性化/本地阅读历史/书签/收藏分类）
// 作为「设置」顶级入口，不再嵌套在「数据/存储」内。
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
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import eu.kanade.tachiyomi.util.system.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SettingsKomihoBackupScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_backup

    @Composable
    override fun getPreferences(): List<Preference> {
        return listOf(getKomihoBackupGroup())
    }

    @Composable
    private fun getKomihoBackupGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var showExportPwd by remember { mutableStateOf(false) }
        var showImportPwd by remember { mutableStateOf(false) }
        var pendingImportJson by remember { mutableStateOf<String?>(null) }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            val password = pendingExportPassword
            pendingExportPassword = null
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        KomihoBackup.writeBackupZip(context, password, os)
                    }
                    withUIContext { context.toast("备份已导出") }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    withUIContext { context.toast("导出失败：${e.message}") }
                }
            }
        }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                try {
                    // zip 与旧版 .json 都支持：readBackupText 按文件头自动识别。
                    val json = context.contentResolver.openInputStream(uri)
                        ?.use { KomihoBackup.readBackupText(it) }.orEmpty()
                    if (json.isBlank()) {
                        withUIContext { context.toast("文件为空或无法读取") }
                        return@launch
                    }
                    if (KomihoBackup.peekEncrypted(json)) {
                        pendingImportJson = json
                        withUIContext { showImportPwd = true }
                    } else {
                        val summary = KomihoBackup.importBackup(context, json, null)
                        withUIContext { context.toast(summary.toString()) }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    withUIContext { context.toast("导入失败：${e.message}") }
                }
            }
        }

        if (showExportPwd) {
            PasswordDialog(
                title = "设置备份密码（可选）",
                placeholder = "留空则不加密；设置密码会加密整个备份文件（含 SMB/WebDAV 密码）",
                confirmLabel = "导出",
                onConfirm = { pwd ->
                    showExportPwd = false
                    pendingExportPassword = pwd
                    val name = "komiho-backup-" +
                        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
                    runCatching { exportLauncher.launch(name) }
                        .onFailure { context.toast(MR.strings.file_picker_error) }
                },
                onDismiss = { showExportPwd = false },
            )
        }

        if (showImportPwd) {
            PasswordDialog(
                title = "输入备份密码",
                placeholder = "该备份已加密",
                confirmLabel = "导入",
                onConfirm = { pwd ->
                    showImportPwd = false
                    val json = pendingImportJson
                    pendingImportJson = null
                    if (json == null) return@PasswordDialog
                    scope.launch(Dispatchers.IO) {
                        try {
                            val summary = KomihoBackup.importBackup(context, json, pwd)
                            withUIContext { context.toast(summary.toString()) }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e)
                            withUIContext { context.toast("导入失败：${e.message}") }
                        }
                    }
                },
                onDismiss = { showImportPwd = false },
            )
        }

        return Preference.PreferenceGroup(
            title = "Komiho 备份与恢复",
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = "导出备份",
                    subtitle = "来源列表 / 个性化 / 本地阅读历史 / 书签 / 收藏分类",
                    onClick = { showExportPwd = true },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "导入备份",
                    subtitle = "从本地文件恢复以上数据（不覆盖 Komga 服务端记录）",
                    onClick = { importLauncher.launch("*/*") },
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
            dismissButton = { TextButton(onClick = onDismiss) { Text(text = "取消") } },
        )
    }
}
// SY <--
