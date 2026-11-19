package de.hr.mediathek.feature.videos

enum class VideoSort { PLAYLIST_ORDER, ADDED_DESC, NAME_ASC, NAME_DESC, DATE_DESC, RATING_DESC, SIZE_DESC, DURATION_DESC, RANDOM }

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
    val categoryUuid: String? = null,
    val tagUuid: String? = null,
    /** Nur Videos dieser (manuellen) Playlist. */
    val playlistUuid: String? = null,
)

/** Baut die Listenabfrage aus dem Filter (rein, damit sie testbar bleibt). */
object VideoQuery {
    class Built(val sql: String, val args: List<Any>)

    fun build(filter: VideoFilter, randomSeed: Long, select: String = "*", orderOverride: String? = null): Built {
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
        filter.categoryUuid?.let {
            where.append(
                " AND uuid IN (SELECT entityUuid FROM category_assignment " +
                    "WHERE entityType = 'MEDIA' AND categoryUuid = ? AND deletedAt IS NULL)",
            )
            args += it
        }
        filter.tagUuid?.let {
            where.append(
                " AND uuid IN (SELECT entityUuid FROM tag_assignment " +
                    "WHERE entityType = 'MEDIA' AND tagUuid = ? AND deletedAt IS NULL)",
            )
            args += it
        }
        filter.playlistUuid?.let {
            where.append(
                " AND uuid IN (SELECT mediaUuid FROM playlist_item WHERE playlistUuid = ? AND deletedAt IS NULL)",
            )
            args += it
        }
        val order = orderOverride ?: when (filter.sort) {
            VideoSort.PLAYLIST_ORDER ->
                if (filter.playlistUuid != null) {
                    args += filter.playlistUuid
                    "(SELECT position FROM playlist_item p WHERE p.playlistUuid = ? AND p.mediaUuid = media_file.uuid AND p.deletedAt IS NULL)"
                } else {
                    "createdAt DESC"
                }
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
        return Built("SELECT $select FROM media_file WHERE $where ORDER BY $order, id", args)
    }

    /** Ein zufälliges verfügbares Video aus dem aktuellen Filter. */
    fun buildRandomPick(filter: VideoFilter): Built {
        val base = build(filter.copy(onlineOnly = true), 0, select = "uuid", orderOverride = "RANDOM()")
        return Built(base.sql + " LIMIT 1", base.args)
    }

    /** Alle verfügbaren Videos in der Reihenfolge des Filters (für "Nächstes" in Playlists). */
    fun buildOrderedUuids(filter: VideoFilter, randomSeed: Long): Built =
        build(filter.copy(onlineOnly = true), randomSeed, select = "uuid")

    fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
