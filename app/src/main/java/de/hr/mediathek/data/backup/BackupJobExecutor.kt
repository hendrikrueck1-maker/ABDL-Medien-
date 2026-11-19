package de.hr.mediathek.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.BuildConfig
import de.hr.mediathek.core.jobs.JobContext
import de.hr.mediathek.core.jobs.JobOutcome
import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.core.log.ErrorType
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.storage.StorageRepository
import java.io.File
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sichert die Nutzerdaten in den Arbeitsordner: Temp-Datei, Prüfung, Umbenennen, Plausibilität, dann Rotation.
 * Läuft im Job-Runner nacheinander mit allen anderen Jobs, also nie parallel zu einem Scan.
 */
@Singleton
class BackupJobExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: StorageRepository,
    private val store: BackupStore,
    private val writer: BackupWriter,
    private val errorLogger: ErrorLogger,
) {
    suspend fun execute(ctx: JobContext): JobOutcome {
        val workTree = storage.getWorkFolderUri() ?: return JobOutcome.Failed("Kein Arbeitsordner gewählt")
        val dir = store.backupDir(workTree)
            ?: return JobOutcome.Completed("Übersprungen: Arbeitsordner nicht erreichbar")

        ctx.report("Daten werden gesichert", null, force = true)
        val temp = File(context.cacheDir, "backup-work.zip")
        temp.delete()
        try {
            val summary = writer.write(temp, BuildConfig.VERSION_NAME)
            ctx.checkpoint()

            // Erst lokal prüfen, dann kopieren und am Ziel noch einmal zurücklesen.
            temp.inputStream().use { BackupWriter.verify(it, summary.schemaVersion) }
            ctx.report("Sicherung wird geprüft", null, force = true)

            // Halbfertige Dateien früherer Läufe aufräumen.
            for (f in store.list(workTree, dir)) {
                if (f.name.endsWith(".tmp")) runCatching { store.delete(workTree, f.docId) }
            }

            val name = store.newFileName()
            val (_, records) = store.copyAndVerify(workTree, dir, temp, name, summary.schemaVersion)
            ctx.checkpoint()

            val previous = store.lastRecordCount()
            val suspicious = previous != null && previous > 0 && records < previous * 0.8
            val warning = if (suspicious) "Deutlich weniger Datensätze als beim letzten Backup ($previous → $records)" else null
            store.recordSuccess(records, warning)

            if (suspicious) {
                errorLogger.logNow(ErrorType.BACKUP, "Backup: Zahl der Datensätze stark gesunken, nicht rotiert")
                return JobOutcome.Completed("Gesichert ($records Datensätze), Warnung: Datensätze stark gesunken, alte Backups bleiben")
            }
            val removed = rotate(workTree, dir)
            return JobOutcome.Completed(
                "Gesichert ($records Datensätze)" + if (removed > 0) ", $removed alte Backups entfernt" else "",
            )
        } finally {
            temp.delete()
        }
    }

    private suspend fun rotate(workTree: Uri, dir: Uri): Int {
        val files = store.list(workTree, dir)
            .mapNotNull { f -> store.timestampOf(f.name)?.let { it to f } }
        val keep = BackupRetention.keep(files.map { it.first }, ZoneId.systemDefault())
        var removed = 0
        for ((ts, file) in files) {
            if (ts !in keep) {
                runCatching { store.delete(workTree, file.docId) }.onSuccess { removed++ }
            }
        }
        return removed
    }
}
