package de.hr.mediathek.core.jobs

import de.hr.mediathek.core.log.ErrorLogger
import de.hr.mediathek.core.log.ErrorType
import de.hr.mediathek.data.db.JobDao
import de.hr.mediathek.data.db.JobEntity
import de.hr.mediathek.data.db.JobStatus
import de.hr.mediathek.data.db.JobType
import de.hr.mediathek.data.backup.BackupJobExecutor
import de.hr.mediathek.data.scan.ScanJobExecutor
import de.hr.mediathek.data.thumb.ThumbnailJobExecutor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Priorität: höher wird zuerst ausgeführt. WorkManager kennt keine Prioritäten, deshalb vergeben wir sie selbst. */
object JobPriority {
    const val HIGH = 100 // Medien einlesen, Backup
    const val MEDIUM = 50 // Mobil-Paket, Export, PDF-Import, Spiegelkarten-Kopie
    const val LOW = 10 // pHash-Berechnung, Statistik-Aufbereitung
}

/**
 * Führt Jobs aus der Job-Tabelle nacheinander aus. Die Tabelle ist die einzige Wahrheit für den Zustand,
 * WorkManager dient nur als Auslöser.
 */
@Singleton
class JobRunner @Inject constructor(
    private val jobDao: JobDao,
    private val scanExecutor: ScanJobExecutor,
    private val thumbnailExecutor: ThumbnailJobExecutor,
    private val hashExecutor: de.hr.mediathek.data.images.ImageHashJobExecutor,
    private val backupExecutor: BackupJobExecutor,
    private val compressionExecutor: de.hr.mediathek.data.compress.CompressionJobExecutor,
    private val compressionSettings: de.hr.mediathek.data.compress.CompressionSettings,
    private val errorLogger: ErrorLogger,
) {
    private val runLock = Mutex()
    @Volatile private var cancelRequestedFor: String? = null

    suspend fun hasWork(): Boolean = jobDao.nextQueued(compressionSettings.mayRunNow()) != null

    /** Bittet den laufenden Job mit dieser UUID, an der nächsten Stelle abzubrechen. */
    fun requestCancel(jobUuid: String) {
        cancelRequestedFor = jobUuid
    }

    suspend fun runAll(onProgress: (text: String, progress: Float?) -> Unit) {
        runLock.withLock {
            while (true) {
                val job = jobDao.nextQueued(compressionSettings.mayRunNow()) ?: break
                runOne(job, onProgress)
            }
        }
    }

    private suspend fun runOne(job: JobEntity, onProgress: (String, Float?) -> Unit) {
        val running = job.copy(
            status = JobStatus.RUNNING,
            startedAt = job.startedAt ?: System.currentTimeMillis(),
            lastError = null,
        )
        jobDao.update(running)

        var lastWrite = 0L
        val context = JobContext(
            jobUuid = job.uuid,
            cancelRequested = { cancelRequestedFor == job.uuid },
            reporter = { phase, progress, force ->
                onProgress(phase ?: "", progress)
                val now = System.currentTimeMillis()
                if (force || now - lastWrite >= PROGRESS_WRITE_INTERVAL_MS) {
                    lastWrite = now
                    jobDao.updateProgress(job.uuid, phase, progress)
                }
            },
        )

        val outcome: JobOutcome = try {
            execute(running, context)
        } catch (e: JobCancelledByUser) {
            JobOutcome.Cancelled
        } catch (e: CancellationException) {
            // Das System hat den Worker gestoppt: Job bleibt eingereiht und wird später fortgesetzt.
            withContext(NonCancellable) { jobDao.setStatus(job.uuid, JobStatus.QUEUED, null) }
            throw e
        } catch (e: Exception) {
            errorLogger.logNow(errorTypeFor(job.type), "Job ${job.type} fehlgeschlagen: $e")
            JobOutcome.Failed(e.message ?: e.toString())
        }

        if (cancelRequestedFor == job.uuid) cancelRequestedFor = null
        if (job.type == JobType.SCAN && outcome is JobOutcome.Completed) {
            enqueueThumbnailsIfIdle()
            enqueueHashesIfIdle()
        }
        val now = System.currentTimeMillis()
        when (outcome) {
            is JobOutcome.Completed -> jobDao.update(
                running.copy(status = JobStatus.COMPLETED, phase = outcome.summary, progress = 1f, finishedAt = now),
            )
            is JobOutcome.NeedsConfirmation -> jobDao.update(
                running.copy(
                    status = JobStatus.PAUSED,
                    phase = PHASE_CONFIRM_MISSING,
                    paramsJson = outcome.paramsJson,
                    progress = null,
                ),
            )
            is JobOutcome.Failed -> jobDao.update(
                running.copy(status = JobStatus.FAILED, lastError = outcome.message, finishedAt = now),
            )
            JobOutcome.Cancelled -> jobDao.update(
                running.copy(status = JobStatus.CANCELLED, phase = null, finishedAt = now),
            )
        }
    }

    private suspend fun enqueueThumbnailsIfIdle() {
        if (jobDao.findActive(JobType.THUMBNAILS, "%") == null) {
            jobDao.insert(JobEntity(type = JobType.THUMBNAILS, priority = JobPriority.LOW))
        }
    }

    private suspend fun enqueueHashesIfIdle() {
        if (jobDao.findActive(JobType.PHASH, "%") == null) {
            jobDao.insert(JobEntity(type = JobType.PHASH, priority = JobPriority.LOW))
        }
    }

    private suspend fun execute(job: JobEntity, context: JobContext): JobOutcome = when (job.type) {
        JobType.SCAN -> scanExecutor.execute(job, context)
        JobType.THUMBNAILS -> thumbnailExecutor.execute(context)
        JobType.PHASH -> hashExecutor.execute(context)
        JobType.BACKUP -> backupExecutor.execute(context)
        JobType.COMPRESS -> compressionExecutor.execute(job, context)
        else -> JobOutcome.Failed("Job-Typ ${job.type} ist noch nicht verfügbar")
    }

    private fun errorTypeFor(type: JobType): String = when (type) {
        JobType.SCAN -> ErrorType.SCAN
        JobType.BACKUP, JobType.MIRROR_COPY -> ErrorType.BACKUP
        JobType.COMPRESS -> ErrorType.COMPRESSION
        JobType.PACKAGE_EXPORT, JobType.PACKAGE_IMPORT -> ErrorType.PACKAGE
        JobType.PDF_IMPORT -> ErrorType.IMPORT
        JobType.PHASH, JobType.THUMBNAILS -> ErrorType.SCAN
    }

    companion object {
        const val PHASE_CONFIRM_MISSING = "CONFIRM_MISSING"
        private const val PROGRESS_WRITE_INTERVAL_MS = 700L
    }
}
