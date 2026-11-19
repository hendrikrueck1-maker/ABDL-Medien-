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
) {
    /** Gibt false zurück, wenn für diese Quelle schon ein Scan eingereiht ist, läuft oder auf Bestätigung wartet. */
    suspend fun enqueueScan(sourceUuid: String): Boolean {
        val params = JSONObject().put("sourceUuid", sourceUuid).toString()
        if (jobDao.findActive(JobType.SCAN, "%\"sourceUuid\":\"$sourceUuid\"%") != null) return false
        jobDao.insert(JobEntity(type = JobType.SCAN, priority = JobPriority.HIGH, paramsJson = params))
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
    }

    private fun trigger() {
        val request = OneTimeWorkRequestBuilder<JobWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "job-runner"
    }
}
