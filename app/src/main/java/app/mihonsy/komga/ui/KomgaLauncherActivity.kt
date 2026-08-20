package app.mihonsy.komga.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.mihonsy.komga.data.KomgaPreferences

/**
 * Komga client launcher (M3-1): app entry point that routes to the main
 * tabbed activity when a Komga connection is configured, otherwise to the
 * connection setup screen. Replaces Mihon's MainActivity as the
 * MAIN/LAUNCHER target.
 */
class KomgaLauncherActivity : KomgaBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = KomgaPreferences(applicationContext)
        val target = if (prefs.hasConnection()) {
            KomgaMainActivity::class.java
        } else {
            KomgaConnectActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
