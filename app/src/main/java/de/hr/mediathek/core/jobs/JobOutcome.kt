package de.hr.mediathek.core.jobs

import kotlin.coroutines.cancellation.CancellationException

sealed interface JobOutcome {
    /** [summary] ist ein kurzer, neutraler Text (keine Dateinamen). */
    data class Completed(val summary: String) : JobOutcome

    /** Der Job wartet auf eine Entscheidung des Nutzers; [paramsJson] ersetzt die Parameter des Jobs. */
    data class NeedsConfirmation(val paramsJson: String) : JobOutcome

    data class Failed(val message: String) : JobOutcome
    data object Cancelled : JobOutcome
}

/** Wird von [JobContext.checkpoint] geworfen, wenn der Nutzer den laufenden Job abbricht. */
class JobCancelledByUser : CancellationException("Vom Nutzer abgebrochen")
