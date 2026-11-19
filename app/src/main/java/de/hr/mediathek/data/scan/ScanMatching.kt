package de.hr.mediathek.data.scan

import de.hr.mediathek.data.db.MediaStatus
import kotlin.math.abs

/** Reine Zuordnungslogik des Scans (ohne Android-Abhängigkeit, daher unit-testbar). */
object ScanMatching {
    /** exFAT/FAT speichern Zeiten nur auf 2 Sekunden genau. */
    const val MTIME_TOLERANCE_MS = 2_000L

    fun isUnchanged(knownSize: Long, knownMtime: Long?, size: Long, mtime: Long): Boolean =
        knownSize == size && knownMtime != null && abs(knownMtime - mtime) <= MTIME_TOLERANCE_MS

    data class Candidate(val uuid: String, val sourceUuid: String, val status: MediaStatus)

    sealed interface Resolution {
        /** Genau ein passender, fehlender Eintrag: Datei wurde verschoben oder umbenannt. */
        data class Claim(val candidate: Candidate) : Resolution

        /** Kein passender Eintrag: neue Datei. */
        data object CreateNew : Resolution

        /** Mehrere passende Einträge: nicht raten, neuer Eintrag, der Nutzer entscheidet später. */
        data class Ambiguous(val count: Int) : Resolution
    }

    /**
     * Entscheidet, ob eine neue Datei mit bekanntem Fingerabdruck einen vorhandenen Eintrag übernimmt.
     *
     * @param seen Einträge, die in diesem Scan schon über ihren Pfad gefunden wurden (also nicht fehlen).
     * @param claimed Einträge, die in diesem Scan schon von einer anderen neuen Datei übernommen wurden.
     */
    fun resolve(
        candidates: List<Candidate>,
        seen: Set<String>,
        claimed: Set<String>,
        currentSourceUuid: String,
    ): Resolution {
        val eligible = candidates
            .distinctBy { it.uuid }
            .filter { it.uuid !in seen && it.uuid !in claimed }
            .filter { it.status != MediaStatus.TRASHED }
            // Ein Eintrag einer anderen Quelle, der dort noch online ist, ist eine Kopie, kein Umzug.
            .filter { it.status == MediaStatus.OFFLINE || it.sourceUuid == currentSourceUuid }
        return when (eligible.size) {
            0 -> Resolution.CreateNew
            1 -> Resolution.Claim(eligible.first())
            else -> Resolution.Ambiguous(eligible.size)
        }
    }

    /** Schutzregel: mehr als 20 % fehlende Dateien oder ein plötzlich leeres Listing verlangen eine Bestätigung. */
    fun isSuspiciousMissing(totalOnline: Int, missingOnline: Int, listedFiles: Int): Boolean {
        if (totalOnline > 0 && listedFiles == 0) return true
        return totalOnline >= MIN_FOR_PERCENT_RULE && missingOnline * 5 > totalOnline
    }

    private const val MIN_FOR_PERCENT_RULE = 10
}
