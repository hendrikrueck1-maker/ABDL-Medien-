package de.hr.mediathek.core.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.db.ErrorLogDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Fehlerarten laut Lastenheft. */
object ErrorType {
    const val SCAN = "SCAN"
    const val IMPORT = "IMPORT"
    const val BACKUP = "BACKUP"
    const val SAF = "SAF-BERECHTIGUNG"
    const val COMPRESSION = "KOMPRIMIERUNG"
    const val PACKAGE = "MOBIL-PAKET"
    const val CRASH = "ABSTURZ"
    const val UPDATE = "UPDATE"
}

@Singleton
class ErrorLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ErrorLogDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Schreibt einen Fehler ins Protokoll, ohne den Aufrufer zu blockieren. */
    fun log(type: String, message: String) {
        scope.launch { logNow(type, message) }
    }

    fun log(type: String, throwable: Throwable) {
        log(type, "${throwable::class.java.simpleName}: ${throwable.message ?: "-"}")
    }

    suspend fun logNow(type: String, message: String) {
        try {
            dao.record(type, message.take(MAX_MESSAGE), System.currentTimeMillis(), MAX_ENTRIES)
        } catch (e: Exception) {
            // Das Protokoll darf nie selbst zum Fehler werden.
        }
    }

    /** Übernimmt eine beim letzten Absturz geschriebene Datei ins Protokoll. */
    fun importPendingCrash() {
        scope.launch {
            val file = crashFile(context)
            if (!file.exists()) return@launch
            val text = try {
                file.readText()
            } catch (e: Exception) {
                null
            }
            if (!text.isNullOrBlank()) logNow(ErrorType.CRASH, text)
            file.delete()
        }
    }

    suspend fun clear() = dao.clear()

    companion object {
        private const val MAX_MESSAGE = 4000
        private const val MAX_ENTRIES = 1000

        fun crashFile(context: Context) = File(context.filesDir, "last_crash.txt")
    }
}
