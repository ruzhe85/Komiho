package eu.kanade.tachiyomi.crash

import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import app.mihonsy.komga.ui.KomgaLauncherActivity
import eu.kanade.presentation.crash.CrashScreen
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent

class CrashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val exception = GlobalExceptionHandler.getThrowableFromIntent(intent)
        setComposeContent {
            CrashScreen(
                exception = exception,
                onRestartClick = {
                    finishAffinity()
                    startActivity(
                        Intent(this@CrashActivity, KomgaLauncherActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        },
                    )
                },
            )
        }
    }
}
