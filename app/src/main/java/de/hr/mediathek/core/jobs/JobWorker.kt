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
    }

    override suspend fun doWork(): Result {
        val runner = EntryPointAccessors.fromApplication(applicationContext, Entry::class.java).jobRunner()
        if (!runner.hasWork()) return Result.success()

        JobNotifications.ensureChannel(applicationContext)
        try {
            setForeground(JobNotifications.foregroundInfo(applicationContext, "", null))
        } catch (e: Exception) {
            // Start aus dem Hintergrund nicht erlaubt: die Arbeit läuft trotzdem, nur ohne Dauerbenachrichtigung.
        }
        runner.runAll { text, progress ->
            JobNotifications.update(applicationContext, text, progress)
        }
        return Result.success()
    }
}
