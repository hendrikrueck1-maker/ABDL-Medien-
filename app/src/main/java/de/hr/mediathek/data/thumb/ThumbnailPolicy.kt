package de.hr.mediathek.data.thumb

/** Reine Rechenregeln des Vorschaubild-Caches (ohne Android, damit sie testbar sind). */
object ThumbnailPolicy {
    const val HARD_LIMIT_BYTES = 2L * 1024 * 1024 * 1024
    const val LOW_SPACE_BYTES = 1L * 1024 * 1024 * 1024
    const val MIN_LIMIT_BYTES = 100L * 1024 * 1024
    const val NORMAL_PX = 360
    const val SMALL_PX = 120

    /** Beim Verdrängen wird bis zu diesem Anteil des Limits abgebaut, damit nicht bei jedem Bild verdrängt wird. */
    const val EVICT_TARGET = 0.9

    /**
     * Wirksames Limit: Normalerweise die harte Grenze. Ist intern weniger als 1 GB frei, schrumpft der Cache
     * um den fehlenden Platz, aber nie unter die Mindestgröße und nie über die harte Grenze.
     */
    fun effectiveLimit(usedBytes: Long, freeBytes: Long): Long {
        if (freeBytes >= LOW_SPACE_BYTES) return HARD_LIMIT_BYTES
        val shortfall = LOW_SPACE_BYTES - freeBytes
        return (usedBytes - shortfall).coerceIn(MIN_LIMIT_BYTES, HARD_LIMIT_BYTES)
    }

    fun isLowSpace(freeBytes: Long): Boolean = freeBytes < LOW_SPACE_BYTES

    /**
     * Wählt aus [candidates] (am längsten ungenutzt zuerst, Paare aus Schlüssel und Größe) so viele aus,
     * dass [usedBytes] auf höchstens [limit] * [EVICT_TARGET] sinkt. Liegt der Cache unter dem Limit, wird nichts gewählt.
     */
    fun planEviction(candidates: List<Pair<String, Long>>, usedBytes: Long, limit: Long): List<String> {
        if (usedBytes <= limit) return emptyList()
        val target = (limit * EVICT_TARGET).toLong()
        var remaining = usedBytes
        val result = ArrayList<String>()
        for ((key, size) in candidates) {
            if (remaining <= target) break
            result += key
            remaining -= size
        }
        return result
    }

    /** Zielgröße (längste Kante) je nach Variante. */
    fun pixelsFor(variant: de.hr.mediathek.data.db.ThumbVariant): Int =
        if (variant == de.hr.mediathek.data.db.ThumbVariant.SMALL) SMALL_PX else NORMAL_PX
}
