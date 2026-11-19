package de.hr.mediathek.core.jobs

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.hr.mediathek.data.db.JobDao
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.JobStatus
import de.hr.mediathek.data.db.JobType
import de.hr.mediathek.data.scan.ScanJobExecutor
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Legt Jobs in der Job-Tabelle an und stößt den Worker an. */
@Singleton
class JobScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jobDao: JobDao,
    private val runner: JobRunner,
    private val scanExecutor: ScanJobExecutor,
    private val storage: de.hr.mediathek.data.storage.StorageRepository,
    private val backupStore: de.hr.mediathek.data.backup.BackupStore,
) {
    /** Gibt false zurück, wenn für diese Quelle schon ein Scan eingereiht ist, läuft oder auf Bestätigung wartet. */
    suspend fun enqueueScan(sourceUuid: String): Boolean {
        val params = JSONObject().put("sourceUuid", sourceUuid).toString()
        if (jobDao.findActive(JobType.SCAN, "%\"sourceUuid\":\"$sourceUuid\"%") != null) return false
        jobDao.insert(JobEntity(type = JobType.SCAN, priority = JobPriority.HIGH, paramsJson = params))
        trigger()
        return true
    }

    /** Backup jetzt einreihen (Nutzeraktion). */
    suspend fun enqueueBackup(): Boolean {
        if (jobDao.findActive(JobType.BACKUP, "%") != null) return false
        jobDao.insert(JobEntity(type = JobType.BACKUP, priority = JobPriority.HIGH))
        trigger()
        return true
    }

    /** Reiht ein Backup ein, wenn das letzte erfolgreiche länger als etwa einen Tag her ist und ein Arbeitsordner gewählt ist. */
    suspend fun enqueueBackupIfDue() {
        if (storage.getWorkFolderUri() == null) return
        val last = backupStore.lastSuccessAt()
        if (last != null && System.currentTimeMillis() - last < BACKUP_INTERVAL_MS) return
        enqueueBackup()
    }

    /** Vorschaubilder nachziehen (läuft sonst automatisch nach jedem Scan). */
    suspend fun enqueueThumbnails(): Boolean {
        if (jobDao.findActive(JobType.THUMBNAILS, "%") != null) return false
        jobDao.insert(JobEntity(type = JobType.THUMBNAILS, priority = JobPriority.LOW))
        trigger()
        return true
    }

    /** Eingereihte Jobs werden sofort beendet, ein laufender Job bricht an der nächsten Prüfstelle ab. */
    suspend fun cancel(jobUuid: String) {
        val job = jobDao.get(jobUuid) ?: return
        when (job.status) {
            JobStatus.QUEUED, JobStatus.PAUSED ->
                jobDao.update(job.copy(status = JobStatus.CANCELLED, finishedAt = System.currentTimeMillis()))
            JobStatus.RUNNING -> runner.requestCancel(jobUuid)
            else -> Unit
        }
    }

    /** Nutzer bestätigt: die im Scan fehlenden Dateien werden gezählt bzw. als offline markiert. */
    suspend fun confirmMissing(jobUuid: String) {
        val job = jobDao.get(jobUuid) ?: return
        if (job.status != JobStatus.PAUSED) return
        val params = JSONObject(job.paramsJson)
        val marked = scanExecutor.applyMissing(params.getString("sourceUuid"), job.uuid)
        jobDao.update(
            job.copy(
                status = JobStatus.COMPLETED,
                phase = "Fertig, $marked fehlende Dateien übernommen",
                finishedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Nutzer verwirft: nichts wird als fehlend markiert. */
    suspend fun dismissConfirmation(jobUuid: String) {
        val job = jobDao.get(jobUuid) ?: return
        if (job.status != JobStatus.PAUSED) return
        jobDao.update(
            job.copy(
                status = JobStatus.COMPLETED,
                phase = "Fehlende Dateien nicht übernommen",
                finishedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Beim Start: laufende Jobs eines früheren Prozesses wieder einreihen und Alt-Einträge aufräumen. */
    suspend fun recover() {
        jobDao.resetRunning()
        jobDao.deleteFinishedBefore(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        if (runner.hasWork()) trigger()
        // Verpasste Backups (Karte war nicht da, App war zu) werden spätestens hier nachgeholt.
        enqueueBackupIfDue()
    }

    private fun trigger() {
        val request = OneTimeWorkRequestBuilder<JobWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "job-runner"
        const val BACKUP_INTERVAL_MS = 20L * 60 * 60 * 1000
    }
}
