package de.hr.mediathek.feature.videos

enum class VideoSort { ADDED_DESC, NAME_ASC, NAME_DESC, DATE_DESC, RATING_DESC, SIZE_DESC, DURATION_DESC, RANDOM }

enum class SeenFilter { ALL, UNSEEN, IN_PROGRESS, SEEN }

data class VideoFilter(
    val sort: VideoSort = VideoSort.ADDED_DESC,
    val search: String = "",
    val favoriteOnly: Boolean = false,
    val minRating: Int = 0,
    val seen: SeenFilter = SeenFilter.ALL,
    val onlineOnly: Boolean = false,
    /** Dateiendung ohne Punkt, z. B. "mp4"; null = alle. */
    val format: String? = null,
)

/** Baut die Listenabfrage aus dem Filter (rein, damit sie testbar bleibt). */
object VideoQuery {
    class Built(val sql: String, val args: List<Any>)

    fun build(filter: VideoFilter, randomSeed: Long): Built {
        val where = StringBuilder("type = 'VIDEO' AND deletedAt IS NULL AND status != 'TRASHED'")
        val args = ArrayList<Any>()
        if (filter.favoriteOnly) where.append(" AND favorite = 1")
        if (filter.minRating > 0) {
            where.append(" AND rating >= ?")
            args += filter.minRating.coerceIn(1, 5)
        }
        when (filter.seen) {
            SeenFilter.ALL -> Unit
            SeenFilter.UNSEEN -> where.append(" AND seenState = 'UNSEEN'")
            SeenFilter.IN_PROGRESS -> where.append(" AND seenState = 'IN_PROGRESS'")
            SeenFilter.SEEN -> where.append(" AND seenState = 'SEEN'")
        }
        if (filter.onlineOnly) where.append(" AND status = 'ONLINE'")
        val term = filter.search.trim()
        if (term.isNotEmpty()) {
            where.append(" AND LOWER(displayName) LIKE ? ESCAPE '\\'")
            args += "%" + escapeLike(term.lowercase()) + "%"
        }
        filter.format?.takeIf { it.isNotBlank() }?.let {
            where.append(" AND LOWER(displayName) LIKE ? ESCAPE '\\'")
            args += "%." + escapeLike(it.lowercase().removePrefix("."))
        }
        val order = when (filter.sort) {
            VideoSort.ADDED_DESC -> "createdAt DESC"
            VideoSort.NAME_ASC -> "displayName COLLATE NOCASE ASC"
            VideoSort.NAME_DESC -> "displayName COLLATE NOCASE DESC"
            VideoSort.DATE_DESC -> "dateDetermined DESC"
            VideoSort.RATING_DESC -> "rating DESC"
            VideoSort.SIZE_DESC -> "size DESC"
            VideoSort.DURATION_DESC -> "durationMs DESC"
            // Stabile Pseudo-Zufallsreihenfolge je Sitzung: gleicher Seed, gleiche Reihenfolge, keine Lücken beim Blättern.
            VideoSort.RANDOM -> "((id * 1103515245 + ${randomSeed.coerceIn(0, 2_000_000_000L)}) % 2147483647)"
        }
        return Built("SELECT * FROM media_file WHERE $where ORDER BY $order, id", args)
    }

    fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
