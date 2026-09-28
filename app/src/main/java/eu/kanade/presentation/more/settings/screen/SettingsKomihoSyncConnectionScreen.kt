package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.mihonsy.komga.data.webdav.WebDavConnectionStore
import app.mihonsy.komga.data.webdav.WebDavCredentialCrypto
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.util.system.toast

// SY --> Komiho：同步连接编辑子屏（从「备份」页点「同步连接」进入）。
// 与来源连接列表（WebDavConnectionStore）解耦：同步配置独立存放，
// 仅「从已有 WebDAV 载入」时把来源凭据复制进同步配置，删除只清同步配置、不动来源。
object SettingsKomihoSyncConnectionScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.komiho_sync_connection

    @Composable
    override fun getPreferences(): List<Preference> = buildSyncConnectionPreferences()

    @Composable
    private fun buildSyncConnectionPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { Injekt.get<PreferenceStore>() }
        val syncUrlPref = remember { prefs.getString(KOMIHO_SYNC_URL, "") }
        val syncUserPref = remember { prefs.getString(KOMIHO_SYNC_USER, "") }
        val syncServerPassPref = remember { prefs.getString(KOMIHO_SYNC_SERVER_PASS, "") }
        val syncDirPref = remember { prefs.getString(KOMIHO_SYNC_DIR, "komiho") }
        val syncPwdPref = remember { prefs.getString(KOMIHO_SYNC_PASSWORD, "") }

        val davs = remember { WebDavConnectionStore.all() }

        return listOf(
            Preference.PreferenceGroup(
                title = "从已有 WebDAV 载入（可选）",
                preferenceItems = buildList<Preference> {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = "手动新建 / 清空已载入",
                            subtitle = "下方连接信息留空即为未配置",
                            onClick = {
                                prefs.getString(KOMIHO_SYNC_URL, "").set("")
                                prefs.getString(KOMIHO_SYNC_USER, "").set("")
                                prefs.getString(KOMIHO_SYNC_SERVER_PASS, "").set("")
                                context.toast("已清空连接信息，可手动填写")
                            },
                        ),
                    )
                    davs.forEach { conn ->
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = conn.displayName(),
                                subtitle = conn.baseUrl,
                                onClick = {
                                    prefs.getString(KOMIHO_SYNC_URL, "").set(conn.baseUrl)
                                    prefs.getString(KOMIHO_SYNC_USER, "").set(conn.user)
                                    prefs.getString(KOMIHO_SYNC_SERVER_PASS, "")
                                        .set(WebDavCredentialCrypto.decryptStored(conn.passEnc))
                                    // 目录保留用户已设，不覆盖
                                    context.toast("已载入：${conn.displayName()}")
                                },
                            ),
                        )
                    }
                },
            ),
            Preference.PreferenceGroup(
                title = "连接信息",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncUrlPref,
                        title = "服务器地址（WebDAV 根）",
                        subtitle = "如 https://dav.example.com:10007/QNAP2",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncUserPref,
                        title = "用户名（留空=匿名）",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncServerPassPref,
                        title = "连接密码（留空=匿名）",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncDirPref,
                        title = "同步目录名",
                        subtitle = "备份存于 <服务器根>/%s/backup",
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "同步加密（方案2）",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncPwdPref,
                        title = "同步加密密码（可选）",
                        subtitle = "留空=明文 zip；填写=KMH1 加密容器",
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "操作",
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "删除此同步连接",
                        subtitle = "清空配置，回到未配置（不删 WebDAV 来源）",
                        onClick = {
                            prefs.getString(KOMIHO_SYNC_URL, "").set("")
                            prefs.getString(KOMIHO_SYNC_USER, "").set("")
                            prefs.getString(KOMIHO_SYNC_SERVER_PASS, "").set("")
                            prefs.getString(KOMIHO_SYNC_DIR, "komiho").set("komiho")
                            prefs.getString(KOMIHO_SYNC_PASSWORD, "").set("")
                            context.toast("已删除同步连接")
                            navigator.pop()
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
    private const val KOMIHO_SYNC_PASSWORD = "komiho_sync_center_password"
}
// SY <--
