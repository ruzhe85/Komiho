package app.mihonsy.komga.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.mihonsy.komga.data.KomgaPreferences
import app.mihonsy.komga.data.smb.SmbConnectionStore
import app.mihonsy.komga.data.webdav.WebDavConnectionStore
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komga client launcher (M3-1): app entry point that routes to the main
 * tabbed activity when any source is usable, otherwise to the onboarding
 * welcome screen. Replaces Mihon's MainActivity as the MAIN/LAUNCHER target.
 *
 * SY --> Komiho Onboarding: 无任何可用来源时不再强制 KomgaConnectActivity
 * （Komga 已是可选来源），改进欢迎页让用户三选一（Komga/WebDAV/本地）或跳过。
 * SY <--
 */
class KomgaLauncherActivity : KomgaBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = KomgaPreferences(applicationContext)
        val storagePreferences = Injekt.get<StoragePreferences>()
        // 任一来源可用（Komga 已连接 / 本地根目录已设 / WebDAV 有连接 / SMB 有连接）→ 直接进主界面。
        // SY --> Komiho：旧逻辑只认 Komga 连接 + 本地根目录，漏了 WebDAV/SMB，
        // 导致只配了这两类来源的设备每次冷启动都误判为「无来源」而进欢迎页（三号机实测）。
        val hasAnySource = prefs.hasConnection()
            || storagePreferences.localSourceRoot.get().isNotBlank()
            || WebDavConnectionStore.all().isNotEmpty()
            || SmbConnectionStore.all().isNotEmpty()
        val target = if (hasAnySource) {
            KomgaMainActivity::class.java
        } else {
            OnboardingActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
