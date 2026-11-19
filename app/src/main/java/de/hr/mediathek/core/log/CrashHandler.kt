package de.hr.mediathek.core.log

import android.content.Context

/**
 * Schreibt unbehandelte Exceptions synchron in eine Datei (Datenbankzugriffe im Absturz sind riskant).
 * Beim nächsten Start übernimmt [ErrorLogger.importPendingCrash] die Datei ins Fehlerprotokoll.
 */
object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val text = buildString {
                    appendLine("Thread: ${thread.name}")
                    append(throwable.stackTraceToString().take(3500))
                }
                ErrorLogger.crashFile(appContext).writeText(text)
            } catch (e: Throwable) {
                // Nichts weiter tun, der Absturz wird unten normal weitergereicht.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
