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
    private val compressionExecutor: de.hr.mediathek.data.compress.CompressionJobExecutor,
    private val compressionSettings: de.hr.mediathek.data.compress.CompressionSettings,
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

    /** Sicherung und Produktfotos auf die Spiegelkarte kopieren. */
    suspend fun enqueueMirrorCopy(): Boolean {
        if (jobDao.findActive(JobType.MIRROR_COPY, "%") != null) return false
        jobDao.insert(JobEntity(type = JobType.MIRROR_COPY, priority = JobPriority.MEDIUM))
        trigger()
        return true
    }

    /** Vorschaubilder nachziehen (läuft sonst automatisch nach jedem Scan). */
    suspend fun enqueueThumbnails(): Boolean {
        if (jobDao.findActive(JobType.THUMBNAILS, "%") != null) return false
        jobDao.insert(JobEntity(type = JobType.THUMBNAILS, priority = JobPriority.LOW))
        trigger()
        return true
    }

    /** Bilder-Analyse (Hashes für Duplikate) starten. */
    suspend fun enqueueHashes(): Boolean {
        if (jobDao.findActive(JobType.PHASH, "%") != null) return false
        jobDao.insert(JobEntity(type = JobType.PHASH, priority = JobPriority.LOW))
        trigger()
        return true
    }

    /** Komprimierung eines Videos einreihen. false, wenn für dasselbe Video schon einer eingereiht ist oder läuft. */
    suspend fun enqueueCompress(params: de.hr.mediathek.data.compress.CompressionParams): Boolean {
        if (jobDao.findActive(JobType.COMPRESS, "%\"mediaUuid\":\"${params.mediaUuid}\"%") != null) return false
        jobDao.insert(JobEntity(type = JobType.COMPRESS, priority = JobPriority.MEDIUM, paramsJson = params.toJson()))
        triggerCompress()
        return true
    }

    /** Startet die Warteschlange; mit der Bedingung "nur beim Laden" wartet WorkManager auf das Ladegerät. */
    suspend fun triggerCompress() {
        val builder = OneTimeWorkRequestBuilder<JobWorker>()
        if (compressionSettings.onlyCharging()) {
            builder.setConstraints(androidx.work.Constraints.Builder().setRequiresCharging(true).build())
        }
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME_COMPRESS, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
    }

    /** Pausiert oder setzt die Komprimierung fort. Ein laufender Job wird beendet, wenn er fertig ist. */
    suspend fun setCompressPaused(paused: Boolean) {
        compressionSettings.setPaused(paused)
        if (!paused) triggerCompress()
    }

    suspend fun setCompressOnlyCharging(only: Boolean) {
        compressionSettings.setOnlyCharging(only)
        triggerCompress()
    }

    /** Verschiebt einen wartenden Komprimierungs-Job in der Reihenfolge um einen Platz. */
    suspend fun moveCompress(jobUuid: String, up: Boolean) {
        val queued = jobDao.compressQueued()
        val i = queued.indexOfFirst { it.uuid == jobUuid }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in queued.indices) return
        val a = queued[i]
        val b = queued[j]
        // Gleiche Zeit wäre nicht eindeutig: tauschen und bei Gleichstand um 1 ms versetzen.
        val aTime = if (a.createdAt == b.createdAt) b.createdAt + (if (up) -1 else 1) else b.createdAt
        jobDao.setCreatedAt(a.uuid, aTime)
        jobDao.setCreatedAt(b.uuid, a.createdAt)
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
        // Angefangene Komprimierungen: vor VERIFIED verwerfen, danach zu Ende führen.
        compressionExecutor.abandonInterrupted().forEach {
            enqueueCompress(
                de.hr.mediathek.data.compress.CompressionParams(
                    it.mediaUuid,
                    runCatching { de.hr.mediathek.data.compress.CompressionLevel.valueOf(it.level ?: "") }
                        .getOrDefault(de.hr.mediathek.data.compress.CompressionLevel.BALANCED),
                    confirmed = true,
                ),
            )
        }
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
        const val WORK_NAME_COMPRESS = "job-runner-compress"
        const val BACKUP_INTERVAL_MS = 20L * 60 * 60 * 1000
    }
}
