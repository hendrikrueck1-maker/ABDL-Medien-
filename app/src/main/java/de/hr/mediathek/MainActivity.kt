package de.hr.mediathek

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.hr.mediathek.data.db.SettingDao
import kotlinx.coroutines.launch
import javax.inject.Inject
import de.hr.mediathek.core.ui.theme.MediathekTheme
import de.hr.mediathek.core.ui.AppRoot
import de.hr.mediathek.core.ui.ShareInbox

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingDao: SettingDao

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        shareOrHint(intent)
    }

    /** Geteilter Text ohne Link: kurz Bescheid geben statt still nichts zu tun. */
    private fun shareOrHint(intent: android.content.Intent?) {
        if (!ShareInbox.accept(intent) && intent?.action == android.content.Intent.ACTION_SEND) {
            android.widget.Toast.makeText(this, "Kein Link gefunden – bitte einen Link teilen.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        lifecycleScope.launch {
            settingDao.observe("privacy_secure_screen").collect { value ->
                if (value == "1") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        if (savedInstanceState == null) shareOrHint(intent)
        setContent {
            MediathekTheme {
                AppRoot()
            }
        }
    }
}
