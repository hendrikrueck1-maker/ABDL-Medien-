package de.hr.mediathek.core.ui

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/** Link, den ein anderer App (z. B. der Browser über "Teilen") an die Mediathek geschickt hat; wartet, bis AppRoot ihn abholt. */
object ShareInbox {
    val pending = MutableStateFlow<String?>(null)

    private val URL = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    /** Nimmt den ersten Link aus einem Teilen-Intent (ACTION_SEND, text/plain) entgegen. Gibt true zurück, wenn einer da war. */
    fun accept(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
        val link = URL.find(text)?.value?.trimEnd('.', ',', ')', ';') ?: return false
        pending.value = link
        return true
    }
}
