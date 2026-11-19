package de.hr.mediathek.data.backup

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

/** Aufbewahrung: je 14 Tage, 8 Wochen und 12 Monate bleibt das jeweils neueste Backup erhalten. */
object BackupRetention {
    const val DAILY = 14
    const val WEEKLY = 8
    const val MONTHLY = 12

    /** Liefert die Zeitstempel (ms), die behalten werden; alle anderen dürfen gelöscht werden. */
    fun keep(timestamps: List<Long>, zone: ZoneId): Set<Long> {
        if (timestamps.isEmpty()) return emptySet()
        val newestFirst = timestamps.sortedDescending()
        val keep = HashSet<Long>()

        fun pick(limit: Int, keyOf: (LocalDate) -> Any) {
            val seen = LinkedHashSet<Any>()
            for (ts in newestFirst) {
                val key = keyOf(Instant.ofEpochMilli(ts).atZone(zone).toLocalDate())
                // Das erste (neueste) Backup je Zeitraum gewinnt; ältere Zeiträume zählen nur bis zum Limit.
                if (seen.add(key)) {
                    if (seen.size > limit) break
                    keep += ts
                }
            }
        }
        pick(DAILY) { it }
        pick(WEEKLY) { it.get(IsoFields.WEEK_BASED_YEAR) * 100 + it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
        pick(MONTHLY) { it.year * 100 + it.monthValue }
        // Das allerneueste bleibt in jedem Fall.
        keep += newestFirst.first()
        return keep
    }
}
