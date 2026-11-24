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
        ShareInbox.accept(intent)
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
        if (savedInstanceState == null) ShareInbox.accept(intent)
        setContent {
            MediathekTheme {
                AppRoot()
            }
        }
    }
}
