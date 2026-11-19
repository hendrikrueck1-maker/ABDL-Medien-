package de.hr.mediathek

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        errorLogger.importPendingCrash()
        // Jobs eines früheren Prozesses (z. B. nach Absturz) wieder einreihen.
        appScope.launch { jobScheduler.recover() }
    }
}
