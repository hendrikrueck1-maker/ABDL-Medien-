package de.hr.mediathek.core.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Löser für den [JobRunner]: läuft im Vordergrunddienst, bis keine Jobs mehr eingereiht sind. */
class JobWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun jobRunner(): JobRunner
        fun compressionSettings(): de.hr.mediathek.data.compress.CompressionSettings
    }

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, Entry::class.java)
        val runner = entry.jobRunner()
        val compress = inputData.getBoolean(KEY_COMPRESS, false)
        if (compress) {
            if (!runner.hasCompressWork()) return Result.success()
        } else if (!runner.hasWork()) {
            return Result.success()
        }

        JobNotifications.ensureChannel(applicationContext)
        val foreground = try {
            setForeground(JobNotifications.foregroundInfo(applicationContext, "", null, compress))
            true
        } catch (e: Exception) {
            false
        }
        if (compress) {
            // Ohne Vordergrunddienst stoppt Android die Kodierung nach etwa 10 Minuten; sie würde dann endlos von vorn
            // beginnen. Deshalb nicht anfangen, sondern warten, bis die App geöffnet wird (JobScheduler.recover startet neu).
            entry.compressionSettings().setWaitingForApp(!foreground)
            if (!foreground) return Result.success()
        }
        // Allgemeine Spur: Start aus dem Hintergrund nicht erlaubt → Arbeit läuft trotzdem, nur ohne Dauerbenachrichtigung.
        val report: (String, Float?) -> Unit = { text, progress ->
            JobNotifications.update(applicationContext, text, progress, compress)
        }
        if (compress) runner.runCompress(report) else runner.runAll(report)
        return Result.success()
    }

    /** Nötig für beschleunigte Aufträge (Android 11 und älter) und als Vordergrund-Fallback. */
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        JobNotifications.ensureChannel(applicationContext)
        return JobNotifications.foregroundInfo(applicationContext, "", null, inputData.getBoolean(KEY_COMPRESS, false))
    }

    companion object {
        const val KEY_COMPRESS = "compress"
    }
}
