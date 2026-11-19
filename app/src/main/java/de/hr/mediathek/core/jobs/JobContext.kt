package de.hr.mediathek.core.jobs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Wird dem Executor übergeben: Fortschritt melden und Abbruch-Punkte setzen. */
class JobContext internal constructor(
    private val jobUuid: String,
    private val cancelRequested: () -> Boolean,
    private val reporter: suspend (phase: String?, progress: Float?, force: Boolean) -> Unit,
) {
    /** Muss regelmäßig aufgerufen werden: wirft bei Nutzerabbruch oder wenn das System den Worker stoppt. */
    suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        if (cancelRequested()) throw JobCancelledByUser()
    }

    /** Fortschritt melden; wird gedrosselt in die Datenbank geschrieben. */
    suspend fun report(phase: String?, progress: Float?, force: Boolean = false) {
        reporter(phase, progress, force)
    }

    val uuid: String get() = jobUuid
}
