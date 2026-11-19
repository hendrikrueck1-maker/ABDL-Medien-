package de.hr.mediathek

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import de.hr.mediathek.data.backup.BackupTriggerWorker
import java.util.concurrent.TimeUnit
import de.hr.mediathek.core.jobs.JobScheduler
import de.hr.mediathek.core.log.CrashHandler
import de.hr.mediathek.core.log.ErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MediathekApp : Application() {

    @Inject lateinit var errorLogger: ErrorLogger
    @Inject lateinit var jobScheduler: JobScheduler

    private fun scheduleDailyBackupCheck() {
        val request = PeriodicWorkRequestBuilder<BackupTriggerWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("backup-daily", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        errorLogger.importPendingCrash()
        // Jobs eines früheren Prozesses (z. B. nach Absturz) wieder einreihen.
        appScope.launch { jobScheduler.recover() }
        scheduleDailyBackupCheck()
    }
}
