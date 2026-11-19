package de.hr.mediathek

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import de.hr.mediathek.core.log.CrashHandler
import de.hr.mediathek.core.log.ErrorLogger
import javax.inject.Inject

@HiltAndroidApp
class MediathekApp : Application() {

    @Inject lateinit var errorLogger: ErrorLogger

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        errorLogger.importPendingCrash()
    }
}
