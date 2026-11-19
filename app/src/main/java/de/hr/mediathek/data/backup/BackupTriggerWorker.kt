package de.hr.mediathek.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.hr.mediathek.core.jobs.JobScheduler

/** Läuft täglich und reiht bei Bedarf einen Backup-Job ein; die eigentliche Arbeit macht der Job-Runner. */
class BackupTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun jobScheduler(): JobScheduler
    }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Entry::class.java).jobScheduler().enqueueBackupIfDue()
        return Result.success()
    }
}
