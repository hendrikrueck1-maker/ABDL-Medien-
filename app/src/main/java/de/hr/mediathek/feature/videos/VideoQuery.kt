package de.hr.mediathek.feature.videos

enum class VideoSort {
    PLAYLIST_ORDER, ADDED_DESC, NAME_ASC, NAME_DESC, DATE_DESC, RATING_DESC, SIZE_DESC, DURATION_DESC, RANDOM,
    ADDED_ASC, DATE_ASC, RATING_ASC, SIZE_ASC, DURATION_ASC,
}

enum class SeenFilter { ALL, UNSEEN, IN_PROGRESS, SEEN }

/** Filter nach Dateigröße: Untergrenze inklusive, Obergrenze exklusive. */
enum class SizeRange(val minBytes: Long?, val maxBytes: Long?) {
    ALL(null, null),
    UNDER_100_MB(null, 100L shl 20),
    FROM_100_MB_TO_1_GB(100L shl 20, 1L shl 30),
    FROM_1_GB_TO_5_GB(1L shl 30, 5L shl 30),
    OVER_5_GB(5L shl 30, null),
}

/** Nur bereits komprimierte oder nur noch nicht komprimierte Videos. */
enum class CompressedFilter { ALL, ONLY, NOT }

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
    /** Nur Dateien in diesem Ordner der Quelle samt Unterordnern (Bilder, Diashow); null = alle. */
    val folder: String? = null,
    /**
     * Nur Dateien, die direkt in diesem Ordner liegen (Ordneransicht); null = keine Einschränkung,
     * "" = oberste Ebene der Quelle.
     */
    val directFolder: String? = null,
    val sizeRange: SizeRange = SizeRange.ALL,
    val compressed: CompressedFilter = CompressedFilter.ALL,
)

/** Baut die Listenabfrage aus dem Filter (rein, damit sie testbar bleibt). */
object VideoQuery {
    class Built(val sql: String, val args: List<Any>)

    /** Phasen, in denen ein Video als komprimiert gilt (auch nachdem das Original gelöscht wurde). */
    private const val COMPRESSED_SUBQUERY =
        "SELECT mediaUuid FROM compression_record WHERE phase IN ('DB_UPDATED', 'ORIGINAL_DELETED')"

    private class Where(val sql: String, val args: List<Any>)

    private fun typeClause(type: String): String {
        require(type == "VIDEO" || type == "IMAGE" || type == "ALL")
        return if (type == "ALL") "type IN ('VIDEO', 'IMAGE')" else "type = '$type'"
    }

    /** Startposition für SQLite-substr, ab der der Pfad unterhalb von [parent] beginnt (zählt Zeichen, nicht UTF-16-Einheiten). */
    internal fun restStart(parent: String): Int =
        if (parent.isEmpty()) 1 else parent.codePointCount(0, parent.length) + 2

    private fun where(filter: VideoFilter, type: String): Where {
        val where = StringBuilder("${typeClause(type)} AND deletedAt IS NULL AND status != 'TRASHED'")
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
        filter.sizeRange.minBytes?.let {
            where.append(" AND size >= ?")
            args += it
        }
        filter.sizeRange.maxBytes?.let {
            where.append(" AND size < ?")
            args += it
        }
        when (filter.compressed) {
            CompressedFilter.ALL -> Unit
            CompressedFilter.ONLY -> where.append(" AND uuid IN ($COMPRESSED_SUBQUERY)")
            CompressedFilter.NOT -> where.append(" AND uuid NOT IN ($COMPRESSED_SUBQUERY)")
        }
        filter.folder?.takeIf { it.isNotBlank() }?.let {
            where.append(" AND relativePath LIKE ? ESCAPE '\\'")
            args += escapeLike(it) + "/%"
        }
        filter.directFolder?.let { parent ->
            if (parent.isEmpty()) {
                where.append(" AND instr(relativePath, '/') = 0")
            } else {
                where.append(" AND relativePath LIKE ? ESCAPE '\\' AND instr(substr(relativePath, ?), '/') = 0")
                args += escapeLike(parent) + "/%"
                args += restStart(parent)
            }
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
        return Where(where.toString(), args)
    }

    fun build(
        filter: VideoFilter,
        randomSeed: Long,
        select: String = "*",
        orderOverride: String? = null,
        type: String = "VIDEO",
    ): Built {
        val w = where(filter, type)
        val args = ArrayList<Any>(w.args)
        val order = orderOverride ?: when (filter.sort) {
            VideoSort.PLAYLIST_ORDER ->
                if (filter.playlistUuid != null) {
                    args += filter.playlistUuid
                    "(SELECT position FROM playlist_item p WHERE p.playlistUuid = ? AND p.mediaUuid = media_file.uuid AND p.deletedAt IS NULL)"
                } else {
                    "createdAt DESC"
                }
            VideoSort.ADDED_DESC -> "createdAt DESC"
            VideoSort.ADDED_ASC -> "createdAt ASC"
            VideoSort.NAME_ASC -> "displayName COLLATE NOCASE ASC"
            VideoSort.NAME_DESC -> "displayName COLLATE NOCASE DESC"
            VideoSort.DATE_DESC -> "dateDetermined DESC"
            VideoSort.DATE_ASC -> "dateDetermined ASC"
            VideoSort.RATING_DESC -> "rating DESC"
            VideoSort.RATING_ASC -> "rating ASC"
            VideoSort.SIZE_DESC -> "size DESC"
            VideoSort.SIZE_ASC -> "size ASC"
            VideoSort.DURATION_DESC -> "durationMs DESC"
            VideoSort.DURATION_ASC -> "durationMs ASC"
            // Stabile Pseudo-Zufallsreihenfolge je Sitzung: gleicher Seed, gleiche Reihenfolge, keine Lücken beim Blättern.
            VideoSort.RANDOM -> "((id * 1103515245 + ${randomSeed.coerceIn(0, 2_000_000_000L)}) % 2147483647)"
        }
        return Built("SELECT $select FROM media_file WHERE ${w.sql} ORDER BY $order, id", args)
    }

    /**
     * Die Unterordner direkt unterhalb von [parent] ("" = oberste Ebene) mit der Zahl der Dateien darin
     * (Unterordner eingerechnet). Spalten: name, cnt. Der Filter wirkt auf die Zählung; Ordnerregeln der Ansicht
     * ([VideoFilter.folder], [VideoFilter.directFolder]) werden ignoriert.
     */
    fun buildFolderRows(filter: VideoFilter, type: String, parent: String): Built {
        val w = where(filter.copy(folder = null, directFolder = null), type)
        val args = ArrayList<Any>()
        args += restStart(parent)
        args += w.args
        val sql = StringBuilder()
        sql.append("SELECT name, COUNT(*) AS cnt FROM (")
        sql.append("SELECT CASE WHEN instr(rest, '/') > 0 THEN substr(rest, 1, instr(rest, '/') - 1) END AS name FROM (")
        sql.append("SELECT substr(relativePath, ?) AS rest FROM media_file WHERE ${w.sql}")
        if (parent.isNotEmpty()) {
            sql.append(" AND relativePath LIKE ? ESCAPE '\\'")
            args += escapeLike(parent) + "/%"
        }
        sql.append(")) WHERE name IS NOT NULL GROUP BY name ORDER BY name COLLATE NOCASE ")
        sql.append(if (filter.sort == VideoSort.NAME_DESC) "DESC" else "ASC")
        return Built(sql.toString(), args)
    }

    /** Einige Dateien aus einem Ordner (samt Unterordnern) für die Vorschau-Collage; bewertete zuerst. */
    fun buildFolderPreview(filter: VideoFilter, type: String, folderPath: String, limit: Int = 12): Built {
        val w = where(filter.copy(folder = null, directFolder = null), type)
        val args = ArrayList<Any>(w.args)
        args += escapeLike(folderPath) + "/%"
        return Built(
            "SELECT uuid FROM media_file WHERE ${w.sql} AND relativePath LIKE ? ESCAPE '\\' " +
                "ORDER BY rating DESC, id LIMIT ${limit.coerceIn(1, 50)}",
            args,
        )
    }

    /** Ein zufälliges verfügbares Video aus dem aktuellen Filter. */
    fun buildRandomPick(filter: VideoFilter): Built {
        val base = build(filter.copy(onlineOnly = true), 0, select = "uuid", orderOverride = "RANDOM()")
        return Built(base.sql + " LIMIT 1", base.args)
    }

    /** Alle verfügbaren Videos in der Reihenfolge des Filters (für "Nächstes" in Playlists). */
    fun buildOrderedUuids(filter: VideoFilter, randomSeed: Long, type: String = "VIDEO"): Built =
        build(filter.copy(onlineOnly = true), randomSeed, select = "uuid", type = type)

    fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
