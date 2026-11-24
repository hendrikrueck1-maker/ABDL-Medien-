package de.hr.mediathek.feature.stories

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Die Seite in einer eingebauten Browser-Ansicht öffnen, damit der Nutzer sich mit seinem eigenen Konto anmelden kann.
 * Die Anmeldung (Cookies) bleibt gespeichert; der Web-Import nutzt sie danach beim Laden.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(url: String, onDone: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun finish() {
        runCatching { CookieManager.getInstance().flush() }
        onDone()
    }

    // Zurück-Taste: erst in der Seite zurück, dann aus der Ansicht.
    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else finish() }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { finish() }) { Text("← Abbrechen") }
            Text("Anmelden", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { finish() }) { Text("Fertig") }
        }
        Text(
            "Melde dich an. Danach tippe oben auf „Fertig“, die Seite wird dann noch einmal geladen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = WebViewClient()
                    if (url.isNotBlank()) loadUrl(url)
                    webView = this
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
