package de.hr.mediathek.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM source WHERE deletedAt IS NULL AND role = 'MEDIA' ORDER BY createdAt")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source WHERE deletedAt IS NULL AND role = 'MEDIA' ORDER BY createdAt")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM source WHERE deletedAt IS NULL AND role = 'BACKUP' ORDER BY createdAt DESC LIMIT 1")
    fun observeMirror(): Flow<SourceEntity?>

    @Query("SELECT * FROM source WHERE deletedAt IS NULL AND role = 'BACKUP' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getMirror(): SourceEntity?

    @Query("SELECT * FROM source WHERE deletedAt IS NULL AND treeUri = :treeUri LIMIT 1")
    suspend fun findByTreeUri(treeUri: String): SourceEntity?

    @Query("SELECT * FROM source WHERE deletedAt IS NULL AND uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): SourceEntity?

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Query("UPDATE source SET lastMountedAt = :time, updatedAt = :time WHERE uuid = :uuid")
    suspend fun markMounted(uuid: String, time: Long)

    @Query("UPDATE source SET treeUri = :treeUri, volumeUuid = :volumeUuid, lastMountedAt = :time, updatedAt = :time WHERE uuid = :uuid")
    suspend fun rebind(uuid: String, treeUri: String, volumeUuid: String?, time: Long)

    @Query("UPDATE source SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun tombstone(uuid: String, now: Long)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM setting WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM setting WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun set(setting: SettingEntity)
}

@Dao
abstract class ErrorLogDao {
    @Query("SELECT * FROM error_log ORDER BY lastAt DESC")
    abstract fun observeAll(): Flow<List<ErrorLogEntity>>

    @Query("SELECT * FROM error_log ORDER BY lastAt DESC")
    abstract suspend fun getAll(): List<ErrorLogEntity>

    @Query("SELECT * FROM error_log WHERE type = :type AND message = :message LIMIT 1")
    abstract suspend fun find(type: String, message: String): ErrorLogEntity?

    @Insert
    abstract suspend fun insert(entry: ErrorLogEntity): Long

    @Update
    abstract suspend fun update(entry: ErrorLogEntity)

    @Query("SELECT COUNT(*) FROM error_log")
    abstract suspend fun count(): Int

    @Query("DELETE FROM error_log WHERE id IN (SELECT id FROM error_log ORDER BY lastAt ASC LIMIT :n)")
    abstract suspend fun deleteOldest(n: Int)

    @Query("DELETE FROM error_log")
    abstract suspend fun clear()

    /** Fasst gleiche Fehler zusammen und hält die Liste unter [maxEntries]. */
    @Transaction
    open suspend fun record(type: String, message: String, now: Long, maxEntries: Int) {
        val existing = find(type, message)
        if (existing != null) {
            update(existing.copy(count = existing.count + 1, lastAt = now))
        } else {
            insert(ErrorLogEntity(type = type, message = message, firstAt = now, lastAt = now))
            val excess = count() - maxEntries
            if (excess > 0) deleteOldest(excess)
        }
    }
}

/** Schlanke Zeile für den Scan-Abgleich (lädt nicht die ganze Entität). */
data class ScanRow(
    val id: Long,
    val uuid: String,
    val relativePath: String,
    val size: Long,
    val lastModified: Long?,
    val status: MediaStatus,
    val fingerprint: String?,
)

data class HashCandidate(
    val uuid: String,
    val relativePath: String,
    val sourceUuid: String,
    val documentIdCache: String?,
    val fingerprint: String?,
)

data class HashStats(val total: Int, val done: Int)

data class PerceptualRow(val uuid: String, val hash: Long)

data class SourceCount(val sourceUuid: String, val total: Int, val offline: Int)

/** Ein Unterordner mit der Zahl der Dateien darin (für die Ordneransicht). */
data class FolderRow(val name: String, val cnt: Int)

/** Zähler der Markierungen in einer Auswahl. */
data class FlagCounts(val fav: Int, val pee: Int, val del: Int)

@Dao
interface MediaFileDao {
    @Query(
        "SELECT id, uuid, relativePath, size, lastModified, status, fingerprint FROM media_file " +
            "WHERE sourceUuid = :sourceUuid AND deletedAt IS NULL AND status != 'TRASHED'",
    )
    suspend fun scanRows(sourceUuid: String): List<ScanRow>

    @Insert
    suspend fun insertAll(items: List<MediaFileEntity>)

    @Insert
    suspend fun insertAlias(alias: MediaAliasEntity)

    @Query(
        "UPDATE media_file SET lastSeenScanUuid = :scan, missingScanCount = 0, " +
            "status = CASE WHEN status = 'OFFLINE' THEN 'ONLINE' ELSE status END, updatedAt = :now " +
            "WHERE id IN (:ids)",
    )
    suspend fun markSeen(ids: List<Long>, scan: String, now: Long)

    /** Inhalt der Datei am selben Pfad hat sich geändert. */
    @Query(
        "UPDATE media_file SET size = :size, lastModified = :lastModified, fingerprint = :fingerprint, " +
            "documentIdCache = :docId, displayName = :name, mimeType = :mime, " +
            "status = CASE WHEN status IN ('OFFLINE', 'DEFECT') THEN 'ONLINE' ELSE status END, " +
            "lastSeenScanUuid = :scan, missingScanCount = 0, updatedAt = :now WHERE id = :id",
    )
    suspend fun applyChange(
        id: Long,
        size: Long,
        lastModified: Long,
        fingerprint: String,
        docId: String,
        name: String,
        mime: String?,
        scan: String,
        now: Long,
    )

    /** Datei wurde verschoben oder umbenannt: der vorhandene Eintrag zieht um, alle Nutzerdaten bleiben. */
    @Query(
        "UPDATE media_file SET sourceUuid = :sourceUuid, relativePath = :path, documentIdCache = :docId, " +
            "displayName = :name, size = :size, lastModified = :lastModified, mimeType = :mime, " +
            "fingerprint = :fingerprint, " +
            "status = CASE WHEN status IN ('OFFLINE', 'DEFECT') THEN 'ONLINE' ELSE status END, " +
            "lastSeenScanUuid = :scan, missingScanCount = 0, updatedAt = :now WHERE uuid = :uuid",
    )
    suspend fun claim(
        uuid: String,
        sourceUuid: String,
        path: String,
        docId: String,
        name: String,
        size: Long,
        lastModified: Long,
        mime: String?,
        fingerprint: String,
        scan: String,
        now: Long,
    )

    @Query("SELECT * FROM media_file WHERE deletedAt IS NULL AND type = :type AND fingerprint = :fingerprint")
    suspend fun findByFingerprint(fingerprint: String, type: MediaType): List<MediaFileEntity>

    @Query(
        "SELECT m.* FROM media_file m INNER JOIN media_alias a ON a.mediaUuid = m.uuid " +
            "WHERE m.deletedAt IS NULL AND m.type = :type AND a.fingerprint = :fingerprint",
    )
    suspend fun findByAlias(fingerprint: String, type: MediaType): List<MediaFileEntity>

    @Query("SELECT COUNT(*) FROM media_file WHERE sourceUuid = :sourceUuid AND deletedAt IS NULL AND status = 'ONLINE'")
    suspend fun countOnline(sourceUuid: String): Int

    @Query(
        "SELECT COUNT(*) FROM media_file WHERE sourceUuid = :sourceUuid AND deletedAt IS NULL AND status = 'ONLINE' " +
            "AND (lastSeenScanUuid IS NULL OR lastSeenScanUuid != :scan)",
    )
    suspend fun countMissingOnline(sourceUuid: String, scan: String): Int

    /** Zählt fehlende Dateien hoch; erst ab dem zweiten Scan in Folge wird eine Datei offline. */
    @Query(
        "UPDATE media_file SET missingScanCount = missingScanCount + 1, " +
            "status = CASE WHEN status = 'ONLINE' AND missingScanCount + 1 >= 2 THEN 'OFFLINE' ELSE status END, " +
            "updatedAt = :now WHERE sourceUuid = :sourceUuid AND deletedAt IS NULL AND status IN ('ONLINE', 'OFFLINE') " +
            "AND (lastSeenScanUuid IS NULL OR lastSeenScanUuid != :scan)",
    )
    suspend fun markMissing(sourceUuid: String, scan: String, now: Long): Int

    @Query(
        "SELECT sourceUuid, COUNT(*) AS total, SUM(CASE WHEN status = 'OFFLINE' THEN 1 ELSE 0 END) AS offline " +
            "FROM media_file WHERE deletedAt IS NULL AND status != 'TRASHED' GROUP BY sourceUuid",
    )
    fun observeCounts(): Flow<List<SourceCount>>

    @RawQuery(observedEntities = [MediaFileEntity::class])
    fun pagingVideos(query: SupportSQLiteQuery): PagingSource<Int, MediaFileEntity>

    @Query("SELECT * FROM media_file WHERE uuid = :uuid AND deletedAt IS NULL")
    suspend fun getByUuid(uuid: String): MediaFileEntity?

    @Query("SELECT * FROM media_file WHERE uuid = :uuid AND deletedAt IS NULL")
    fun observeByUuid(uuid: String): Flow<MediaFileEntity?>

    /** Unterordner der aktuellen Ebene samt Dateizahl; Abfrage kommt aus [de.hr.mediathek.feature.videos.VideoQuery.buildFolderRows]. */
    @RawQuery(observedEntities = [MediaFileEntity::class])
    fun observeFolderRows(query: SupportSQLiteQuery): Flow<List<FolderRow>>

    /** Quelle entkoppeln, Einträge behalten: sie werden offline und kommen beim erneuten Einbinden über den Fingerabdruck zurück. */
    @Query(
        "UPDATE media_file SET status = 'OFFLINE', updatedAt = :now WHERE sourceUuid = :sourceUuid " +
            "AND deletedAt IS NULL AND status IN ('ONLINE', 'DEFECT')",
    )
    suspend fun markSourceOffline(sourceUuid: String, now: Long)

    /** Quelle entkoppeln und Einträge entfernen (Tombstone); die Dateien selbst bleiben unberührt. */
    @Query(
        "UPDATE media_file SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now " +
            "WHERE sourceUuid = :sourceUuid AND deletedAt IS NULL",
    )
    suspend fun tombstoneBySource(sourceUuid: String, now: Long): Int

    /** Oberste Ordner der Bilder (für den Ordnerfilter). */
    @Query(
        "SELECT DISTINCT substr(relativePath, 1, instr(relativePath, '/') - 1) FROM media_file " +
            "WHERE type = 'IMAGE' AND deletedAt IS NULL AND status != 'TRASHED' AND instr(relativePath, '/') > 0 " +
            "ORDER BY 1 COLLATE NOCASE",
    )
    fun observeImageFolders(): Flow<List<String>>

    @Query("UPDATE media_file SET rating = :rating, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun setRating(uuid: String, rating: Int, now: Long)

    @Query("UPDATE media_file SET favorite = :favorite, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun setFavorite(uuid: String, favorite: Boolean, now: Long)

    @Query("UPDATE media_file SET markedDelete = :marked, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun setMarkedDelete(uuid: String, marked: Boolean, now: Long)

    @Query("UPDATE media_file SET markedPee = :marked, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun setMarkedPee(uuid: String, marked: Boolean, now: Long)

    @Query("UPDATE media_file SET markedDelete = :marked, updatedAt = :now, userUpdatedAt = :now WHERE uuid IN (:uuids)")
    suspend fun setMarkedDeleteBulk(uuids: List<String>, marked: Boolean, now: Long)

    @Query("UPDATE media_file SET markedPee = :marked, updatedAt = :now, userUpdatedAt = :now WHERE uuid IN (:uuids)")
    suspend fun setMarkedPeeBulk(uuids: List<String>, marked: Boolean, now: Long)

    /** Wie viele der gewählten Einträge Favorit, Tropfen oder Löschmarkierung tragen (für die Schalter der Auswahlleiste). */
    @Query(
        "SELECT COALESCE(SUM(favorite), 0) AS fav, COALESCE(SUM(markedPee), 0) AS pee, " +
            "COALESCE(SUM(markedDelete), 0) AS del FROM media_file WHERE uuid IN (:uuids)",
    )
    fun observeFlagCounts(uuids: List<String>): Flow<FlagCounts>

    @Query("UPDATE media_file SET playCount = playCount + 1, lastPlayedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun incrementPlayCount(uuid: String, now: Long)

    // --- Vorschläge für die Übersicht ---

    /** Am häufigsten geöffnete Videos (nur solche, die schon mindestens einmal geöffnet wurden). */
    @Query("SELECT * FROM media_file WHERE type = 'VIDEO' AND status = 'ONLINE' AND deletedAt IS NULL AND playCount >= 1 ORDER BY playCount DESC, RANDOM() LIMIT :limit")
    suspend fun suggestFrequent(limit: Int): List<MediaFileEntity>

    /** Schon einmal angesehene Videos, nach seltenster Nutzung zuerst. */
    @Query("SELECT * FROM media_file WHERE type = 'VIDEO' AND status = 'ONLINE' AND deletedAt IS NULL AND (playCount >= 1 OR seenState != 'UNSEEN') ORDER BY playCount ASC, COALESCE(lastPlayedAt, 0) ASC, RANDOM() LIMIT :limit")
    suspend fun suggestRare(limit: Int): List<MediaFileEntity>

    @Query("SELECT * FROM media_file WHERE type = 'VIDEO' AND status = 'ONLINE' AND deletedAt IS NULL AND playCount = 0 AND seenState = 'UNSEEN' ORDER BY RANDOM() LIMIT :limit")
    suspend fun suggestNeverWatched(limit: Int): List<MediaFileEntity>

    @Query("SELECT uuid FROM media_file WHERE type = 'IMAGE' AND status = 'ONLINE' AND deletedAt IS NULL ORDER BY RANDOM() LIMIT 1")
    suspend fun randomImageUuid(): String?

    @Query(
        "UPDATE media_file SET resumePositionMs = :position, seenState = :seen, updatedAt = :now, userUpdatedAt = :now " +
            "WHERE uuid = :uuid",
    )
    suspend fun setProgress(uuid: String, position: Long, seen: SeenState, now: Long)

    @Query("UPDATE media_file SET updatedAt = :now, userUpdatedAt = :now WHERE uuid IN (:uuids)")
    suspend fun touch(uuids: List<String>, now: Long)

    @Query("UPDATE media_file SET favorite = :favorite, updatedAt = :now, userUpdatedAt = :now WHERE uuid IN (:uuids)")
    suspend fun setFavoriteBulk(uuids: List<String>, favorite: Boolean, now: Long)

    @Query("UPDATE media_file SET rating = :rating, updatedAt = :now, userUpdatedAt = :now WHERE uuid IN (:uuids)")
    suspend fun setRatingBulk(uuids: List<String>, rating: Int, now: Long)

    @Query(
        "UPDATE media_file SET status = 'TRASHED', documentIdCache = :docId, updatedAt = :now, userUpdatedAt = :now " +
            "WHERE uuid = :uuid",
    )
    suspend fun markTrashed(uuid: String, docId: String, now: Long)

    @Query(
        "UPDATE media_file SET status = 'ONLINE', documentIdCache = :docId, lastModified = NULL, " +
            "updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid",
    )
    suspend fun markRestored(uuid: String, docId: String, now: Long)

    @Query("UPDATE media_file SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun tombstone(uuid: String, now: Long)

    /** Exakte Duplikate: gleicher Fingerabdruck (enthält die Größe), nur eigene Videos ohne Gelöschte. */
    @Query(
        "SELECT * FROM media_file WHERE type = 'VIDEO' AND deletedAt IS NULL AND status IN ('ONLINE', 'OFFLINE') " +
            "AND fingerprint IN (SELECT fingerprint FROM media_file WHERE type = 'VIDEO' AND deletedAt IS NULL " +
            "AND status IN ('ONLINE', 'OFFLINE') AND fingerprint IS NOT NULL GROUP BY fingerprint HAVING COUNT(*) > 1) " +
            "ORDER BY fingerprint, relativePath",
    )
    fun observeDuplicates(): Flow<List<MediaFileEntity>>

    @RawQuery(observedEntities = [MediaFileEntity::class])
    suspend fun uuids(query: SupportSQLiteQuery): List<String>

    /** Nach der Komprimierung: Datei umhängen, alle Nutzerdaten (Bewertung, Kategorien, Datum, ...) bleiben. */
    @Query(
        "UPDATE media_file SET relativePath = :path, displayName = :name, size = :size, fingerprint = :fingerprint, " +
            "documentIdCache = :docId, lastModified = :modified, mimeType = 'video/mp4', status = 'ONLINE', " +
            "missingScanCount = 0, updatedAt = :now WHERE uuid = :uuid",
    )
    suspend fun applyCompression(
        uuid: String, path: String, name: String, size: Long, fingerprint: String,
        docId: String, modified: Long?, now: Long,
    )

    // --- Bild-Hashes ---

    @Query(
        "SELECT uuid, relativePath, sourceUuid, documentIdCache, fingerprint FROM media_file " +
            "WHERE type = 'IMAGE' AND status = 'ONLINE' AND deletedAt IS NULL AND documentIdCache IS NOT NULL " +
            "AND hashFingerprint IS NOT fingerprint LIMIT :limit",
    )
    suspend fun imagesNeedingHash(limit: Int): List<HashCandidate>

    @Query("UPDATE media_file SET contentHash = :content, perceptualHash = :perceptual, hashFingerprint = :fingerprint WHERE uuid = :uuid")
    suspend fun setHashes(uuid: String, content: String?, perceptual: Long?, fingerprint: String?)

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN hashFingerprint IS fingerprint THEN 1 ELSE 0 END), 0) AS done FROM media_file " +
            "WHERE type = 'IMAGE' AND status != 'TRASHED' AND deletedAt IS NULL",
    )
    fun observeHashStats(): Flow<HashStats>

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN hashFingerprint IS fingerprint THEN 1 ELSE 0 END), 0) AS done FROM media_file " +
            "WHERE type = 'IMAGE' AND status != 'TRASHED' AND deletedAt IS NULL",
    )
    suspend fun hashStatsOnce(): HashStats

    @Query(
        "SELECT * FROM media_file WHERE type = 'IMAGE' AND deletedAt IS NULL AND status != 'TRASHED' " +
            "AND contentHash IN (SELECT contentHash FROM media_file WHERE type = 'IMAGE' AND contentHash IS NOT NULL " +
            "AND deletedAt IS NULL AND status != 'TRASHED' GROUP BY contentHash HAVING COUNT(*) > 1) " +
            "ORDER BY contentHash, size DESC",
    )
    suspend fun exactImageDuplicates(): List<MediaFileEntity>

    @Query(
        "SELECT uuid, perceptualHash AS hash FROM media_file WHERE type = 'IMAGE' AND perceptualHash IS NOT NULL " +
            "AND deletedAt IS NULL AND status != 'TRASHED'",
    )
    suspend fun perceptualHashes(): List<PerceptualRow>

    @Query("SELECT * FROM media_file WHERE uuid IN (:uuids) AND deletedAt IS NULL")
    suspend fun byUuids(uuids: List<String>): List<MediaFileEntity>

    @Query("UPDATE media_file SET status = 'OFFLINE', updatedAt = :now WHERE uuid = :uuid AND status = 'ONLINE'")
    suspend fun markOffline(uuid: String, now: Long)

    @Query("UPDATE media_file SET status = 'DEFECT', updatedAt = :now WHERE uuid = :uuid")
    suspend fun markDefect(uuid: String, now: Long)

    @Query(
        "UPDATE media_file SET durationMs = COALESCE(durationMs, :duration), width = COALESCE(width, :width), " +
            "height = COALESCE(height, :height), updatedAt = :now WHERE uuid = :uuid",
    )
    suspend fun setVideoMeta(uuid: String, duration: Long?, width: Int?, height: Int?, now: Long)
}

@Dao
interface JobDao {
    @Insert
    suspend fun insert(job: JobEntity): Long

    @Update
    suspend fun update(job: JobEntity)

    @Query("SELECT * FROM job WHERE uuid = :uuid")
    suspend fun get(uuid: String): JobEntity?

    /** Nächster Job; Komprimierung nur, wenn [allowCompress] (nicht pausiert, am Ladegerät). */
    @Query(
        "SELECT * FROM job WHERE status = 'QUEUED' AND (:allowCompress = 1 OR type != 'COMPRESS') " +
            "ORDER BY priority DESC, createdAt ASC LIMIT 1",
    )
    suspend fun nextQueued(allowCompress: Boolean): JobEntity?

    @Query("SELECT * FROM job WHERE type = 'COMPRESS' AND status IN ('QUEUED', 'RUNNING') ORDER BY priority DESC, createdAt ASC")
    fun observeCompressQueue(): Flow<List<JobEntity>>

    @Query("SELECT * FROM job WHERE type = 'COMPRESS' AND status = 'QUEUED' ORDER BY priority DESC, createdAt ASC")
    suspend fun compressQueued(): List<JobEntity>

    @Query("UPDATE job SET createdAt = :createdAt WHERE uuid = :uuid")
    suspend fun setCreatedAt(uuid: String, createdAt: Long)

    /** Nach Absturz oder Neustart: laufende Jobs werden neu eingereiht (Scans sind wiederholbar). */
    @Query("UPDATE job SET status = 'QUEUED' WHERE status = 'RUNNING'")
    suspend fun resetRunning()

    @Query("UPDATE job SET status = :status, finishedAt = :finishedAt WHERE uuid = :uuid")
    suspend fun setStatus(uuid: String, status: JobStatus, finishedAt: Long?)

    @Query("UPDATE job SET phase = :phase, progress = :progress WHERE uuid = :uuid")
    suspend fun updateProgress(uuid: String, phase: String?, progress: Float?)

    @Query(
        "SELECT * FROM job WHERE type = :type AND status IN ('QUEUED', 'RUNNING', 'PAUSED') " +
            "AND paramsJson LIKE :pattern LIMIT 1",
    )
    suspend fun findActive(type: JobType, pattern: String): JobEntity?

    @Query("SELECT * FROM job WHERE type = :type ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(type: JobType): Flow<JobEntity?>

    @Query("SELECT * FROM job WHERE type = 'SCAN' ORDER BY createdAt DESC LIMIT 30")
    fun observeRecentScans(): Flow<List<JobEntity>>

    @Query("SELECT COUNT(*) FROM job WHERE status = 'QUEUED'")
    suspend fun countQueued(): Int

    @Query("DELETE FROM job WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED') AND finishedAt < :before")
    suspend fun deleteFinishedBefore(before: Long)
}

data class PendingThumb(
    val uuid: String,
    val type: MediaType,
    val documentIdCache: String,
    val fingerprint: String?,
    val sourceUuid: String,
)

data class ThumbStats(val count: Int, val bytes: Long)

@Dao
interface ThumbnailDao {
    @Upsert
    suspend fun upsert(thumb: ThumbnailEntity)

    @Query("SELECT * FROM thumbnail WHERE mediaUuid = :uuid")
    suspend fun get(uuid: String): ThumbnailEntity?

    @Query("DELETE FROM thumbnail WHERE mediaUuid IN (:uuids)")
    suspend fun delete(uuids: List<String>)

    @Query("DELETE FROM thumbnail")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM thumbnail")
    suspend fun totalBytes(): Long

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS bytes FROM thumbnail")
    fun observeStats(): Flow<ThumbStats>

    @Query("UPDATE thumbnail SET lastAccess = :time WHERE mediaUuid IN (:uuids)")
    suspend fun touch(uuids: List<String>, time: Long)

    /** Bilder online verfügbarer Dateien, am längsten ungenutzt zuerst: diese dürfen verdrängt werden. */
    @Query(
        "SELECT t.* FROM thumbnail t INNER JOIN media_file m ON m.uuid = t.mediaUuid " +
            "WHERE m.status != 'OFFLINE' ORDER BY t.lastAccess ASC LIMIT :limit",
    )
    suspend fun evictableOnline(limit: Int): List<ThumbnailEntity>

    /** Offline-Dateien behalten ihr Bild, aber nur im Kleinformat. */
    @Query(
        "SELECT t.* FROM thumbnail t INNER JOIN media_file m ON m.uuid = t.mediaUuid " +
            "WHERE m.status = 'OFFLINE' AND t.variant = 'NORMAL' LIMIT :limit",
    )
    suspend fun offlineNormal(limit: Int): List<ThumbnailEntity>

    /** Bilder zu Einträgen, die es nicht mehr gibt oder die im Papierkorb liegen. */
    @Query(
        "SELECT t.mediaUuid FROM thumbnail t LEFT JOIN media_file m ON m.uuid = t.mediaUuid " +
            "WHERE m.uuid IS NULL OR m.deletedAt IS NOT NULL OR m.status = 'TRASHED' LIMIT :limit",
    )
    suspend fun orphans(limit: Int): List<String>

    @Query(
        "SELECT m.uuid AS uuid, m.type AS type, m.documentIdCache AS documentIdCache, " +
            "m.fingerprint AS fingerprint, m.sourceUuid AS sourceUuid " +
            "FROM media_file m LEFT JOIN thumbnail t ON t.mediaUuid = m.uuid " +
            "WHERE m.deletedAt IS NULL AND m.status = 'ONLINE' AND m.documentIdCache IS NOT NULL " +
            "AND (t.mediaUuid IS NULL OR t.fingerprint IS NOT m.fingerprint OR t.variant != 'NORMAL') " +
            "ORDER BY m.id LIMIT :limit",
    )
    suspend fun pending(limit: Int): List<PendingThumb>

    @Query(
        "SELECT COUNT(*) FROM media_file m LEFT JOIN thumbnail t ON t.mediaUuid = m.uuid " +
            "WHERE m.deletedAt IS NULL AND m.status = 'ONLINE' AND m.documentIdCache IS NOT NULL " +
            "AND (t.mediaUuid IS NULL OR t.fingerprint IS NOT m.fingerprint OR t.variant != 'NORMAL')",
    )
    suspend fun countPending(): Int
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE deletedAt IS NULL AND scope IN ('VIDEO', 'ALL') ORDER BY name COLLATE NOCASE")
    fun observeVideoCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE deletedAt IS NULL AND scope IN ('IMAGE', 'ALL') ORDER BY name COLLATE NOCASE")
    fun observeImageCategories(): Flow<List<CategoryEntity>>

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Query("UPDATE category SET name = :name, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun rename(uuid: String, name: String, now: Long)

    @Query("UPDATE category SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun tombstone(uuid: String, now: Long)

    @Query(
        "UPDATE category_assignment SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now " +
            "WHERE categoryUuid = :categoryUuid AND deletedAt IS NULL",
    )
    suspend fun tombstoneAllAssignments(categoryUuid: String, now: Long)

    @Query(
        "SELECT * FROM category_assignment WHERE entityType = 'MEDIA' AND categoryUuid = :categoryUuid " +
            "AND entityUuid IN (:mediaUuids)",
    )
    suspend fun assignmentsFor(categoryUuid: String, mediaUuids: List<String>): List<CategoryAssignmentEntity>

    @Upsert
    suspend fun upsertAssignments(items: List<CategoryAssignmentEntity>)

    @Query(
        "UPDATE category_assignment SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now " +
            "WHERE entityType = 'MEDIA' AND categoryUuid = :categoryUuid AND entityUuid IN (:mediaUuids) AND deletedAt IS NULL",
    )
    suspend fun tombstoneAssignments(categoryUuid: String, mediaUuids: List<String>, now: Long)

    /** Kategorien, die mindestens einem der Medien zugeordnet sind (für die Anzeige im Auswahldialog). */
    @Query(
        "SELECT DISTINCT categoryUuid FROM category_assignment WHERE entityType = 'MEDIA' AND deletedAt IS NULL " +
            "AND entityUuid IN (:mediaUuids)",
    )
    suspend fun usedCategories(mediaUuids: List<String>): List<String>
}

data class TrashRow(
    val id: Long,
    val uuid: String,
    val entityUuid: String,
    val originalPath: String?,
    val trashPath: String?,
    val deletedAt: Long,
    val expiresAt: Long,
    val displayName: String,
    val size: Long,
    val sourceUuid: String,
)

@Dao
interface TrashDao {
    @Insert
    suspend fun insert(item: TrashItemEntity): Long

    @Query("DELETE FROM trash_item WHERE uuid = :uuid")
    suspend fun delete(uuid: String)

    @Query(
        "SELECT t.id AS id, t.uuid AS uuid, t.entityUuid AS entityUuid, t.originalPath AS originalPath, " +
            "t.trashPath AS trashPath, t.deletedAt AS deletedAt, t.expiresAt AS expiresAt, " +
            "m.displayName AS displayName, m.size AS size, m.sourceUuid AS sourceUuid " +
            "FROM trash_item t INNER JOIN media_file m ON m.uuid = t.entityUuid " +
            "WHERE t.entityType = 'MEDIA' ORDER BY t.deletedAt DESC",
    )
    fun observeMedia(): Flow<List<TrashRow>>

    @Query(
        "SELECT t.id AS id, t.uuid AS uuid, t.entityUuid AS entityUuid, t.originalPath AS originalPath, " +
            "t.trashPath AS trashPath, t.deletedAt AS deletedAt, t.expiresAt AS expiresAt, " +
            "m.displayName AS displayName, m.size AS size, m.sourceUuid AS sourceUuid " +
            "FROM trash_item t INNER JOIN media_file m ON m.uuid = t.entityUuid " +
            "WHERE t.entityType = 'MEDIA' AND t.expiresAt < :now",
    )
    suspend fun expired(now: Long): List<TrashRow>
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tag WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag WHERE deletedAt IS NULL AND nameNormalized = :normalized LIMIT 1")
    suspend fun findByNormalized(normalized: String): TagEntity?

    @Insert
    suspend fun insert(tag: TagEntity): Long

    @Query("UPDATE tag SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun tombstone(uuid: String, now: Long)

    @Query(
        "UPDATE tag_assignment SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now " +
            "WHERE tagUuid = :tagUuid AND deletedAt IS NULL",
    )
    suspend fun tombstoneAllAssignments(tagUuid: String, now: Long)

    @Query("SELECT * FROM tag_assignment WHERE entityType = 'MEDIA' AND tagUuid = :tagUuid AND entityUuid IN (:mediaUuids)")
    suspend fun assignmentsFor(tagUuid: String, mediaUuids: List<String>): List<TagAssignmentEntity>

    @Upsert
    suspend fun upsertAssignments(items: List<TagAssignmentEntity>)

    @Query("SELECT * FROM tag_assignment WHERE entityType = 'STORY' AND entityUuid = :storyUuid AND deletedAt IS NULL")
    suspend fun storyAssignments(storyUuid: String): List<TagAssignmentEntity>

    @Query("UPDATE tag_assignment SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid IN (:uuids)")
    suspend fun tombstoneAssignmentsByUuid(uuids: List<String>, now: Long)

    @Query(
        "UPDATE tag_assignment SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now " +
            "WHERE entityType = 'MEDIA' AND tagUuid = :tagUuid AND entityUuid IN (:mediaUuids) AND deletedAt IS NULL",
    )
    suspend fun tombstoneAssignments(tagUuid: String, mediaUuids: List<String>, now: Long)

    @Query("SELECT DISTINCT tagUuid FROM tag_assignment WHERE entityType = 'MEDIA' AND deletedAt IS NULL AND entityUuid IN (:mediaUuids)")
    suspend fun usedTags(mediaUuids: List<String>): List<String>
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE uuid = :uuid AND deletedAt IS NULL")
    suspend fun get(uuid: String): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlist SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun tombstone(uuid: String, now: Long)

    @Query("UPDATE playlist_item SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE playlistUuid = :uuid AND deletedAt IS NULL")
    suspend fun tombstoneAllItems(uuid: String, now: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_item WHERE playlistUuid = :uuid AND deletedAt IS NULL")
    suspend fun maxPosition(uuid: String): Int

    @Query("SELECT * FROM playlist_item WHERE playlistUuid = :uuid AND mediaUuid IN (:mediaUuids)")
    suspend fun itemsFor(uuid: String, mediaUuids: List<String>): List<PlaylistItemEntity>

    @Upsert
    suspend fun upsertItems(items: List<PlaylistItemEntity>)

    @Query(
        "UPDATE playlist_item SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now " +
            "WHERE playlistUuid = :uuid AND mediaUuid IN (:mediaUuids) AND deletedAt IS NULL",
    )
    suspend fun tombstoneItems(uuid: String, mediaUuids: List<String>, now: Long)
}

data class GraceStats(val count: Int, val bytes: Long)

@Dao
interface CompressionDao {
    @Insert
    suspend fun insert(record: CompressionRecordEntity): Long

    @Update
    suspend fun update(record: CompressionRecordEntity)

    @Query("DELETE FROM compression_record WHERE id = :id")
    suspend fun delete(id: Long)

    /** Ein Eintrag, der noch zu Ende geführt werden muss (Phase ENCODING bis RENAMED). */
    @Query(
        "SELECT * FROM compression_record WHERE mediaUuid = :mediaUuid " +
            "AND phase IN ('ENCODING', 'VERIFIED', 'ORIGINAL_MOVED', 'RENAMED') LIMIT 1",
    )
    suspend fun unfinishedFor(mediaUuid: String): CompressionRecordEntity?

    @Query("SELECT * FROM compression_record WHERE phase IN ('ENCODING', 'VERIFIED', 'ORIGINAL_MOVED', 'RENAMED')")
    suspend fun allUnfinished(): List<CompressionRecordEntity>

    /** Originale in der Schonfrist (liegen im _alt-Ordner). */
    @Query("SELECT * FROM compression_record WHERE phase = 'DB_UPDATED' AND altPath IS NOT NULL")
    suspend fun inGrace(): List<CompressionRecordEntity>

    @Query("SELECT * FROM compression_record WHERE phase = 'DB_UPDATED' AND altPath IS NOT NULL AND deleteAfter <= :now")
    suspend fun graceExpired(now: Long): List<CompressionRecordEntity>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(origSize), 0) AS bytes FROM compression_record WHERE phase = 'DB_UPDATED'")
    fun observeGrace(): Flow<GraceStats>

    @Query("SELECT mediaUuid FROM compression_record WHERE phase = 'BLOCKED'")
    fun observeBlocked(): Flow<List<String>>

    @Query("DELETE FROM compression_record WHERE mediaUuid = :mediaUuid AND phase IN ('BLOCKED', 'FAILED')")
    suspend fun clearMarks(mediaUuid: String)

    /** Videos, die komprimiert wurden (auch wenn das Original schon gelöscht ist). */
    @Query("SELECT DISTINCT mediaUuid FROM compression_record WHERE phase IN ('DB_UPDATED', 'ORIGINAL_DELETED')")
    fun observeCompressedUuids(): Flow<List<String>>

    /** Das aufbewahrte Original eines komprimierten Videos (liegt im Ordner _alt); null, wenn es keins (mehr) gibt. */
    @Query(
        "SELECT * FROM compression_record WHERE mediaUuid = :mediaUuid AND phase = 'DB_UPDATED' " +
            "AND altPath IS NOT NULL ORDER BY id DESC LIMIT 1",
    )
    suspend fun originalFor(mediaUuid: String): CompressionRecordEntity?
}

/** Listenzeile einer Geschichte: nie mit Text, damit große Texte die Liste nicht bremsen. */
data class StoryRow(
    val uuid: String,
    val title: String,
    val category: StoryCategory,
    val rating: Int,
    val favorite: Boolean,
    val continueFlag: Boolean,
    val sourceText: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Dao
interface StoryDao {
    @Query(
        "SELECT uuid, title, category, rating, favorite, continueFlag, sourceText, createdAt, updatedAt, deletedAt " +
            "FROM story WHERE deletedAt IS NULL",
    )
    fun observeActive(): Flow<List<StoryRow>>

    @Query(
        "SELECT uuid, title, category, rating, favorite, continueFlag, sourceText, createdAt, updatedAt, deletedAt " +
            "FROM story WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC",
    )
    fun observeDeleted(): Flow<List<StoryRow>>

    /** Zuletzt verwendete Quellen und Adressen als Schnellauswahl im Editor. */
    @Query(
        "SELECT sourceText FROM story WHERE deletedAt IS NULL AND sourceText IS NOT NULL AND sourceText != '' " +
            "GROUP BY sourceText ORDER BY MAX(updatedAt) DESC LIMIT 15",
    )
    fun observeRecentSources(): Flow<List<String>>

    @Query(
        "SELECT sourceUrl FROM story WHERE deletedAt IS NULL AND sourceUrl IS NOT NULL AND sourceUrl != '' " +
            "GROUP BY sourceUrl ORDER BY MAX(updatedAt) DESC LIMIT 15",
    )
    fun observeRecentUrls(): Flow<List<String>>

    @Query("SELECT * FROM story WHERE uuid = :uuid")
    suspend fun get(uuid: String): StoryEntity?

    @Query("SELECT * FROM story WHERE uuid = :uuid")
    fun observe(uuid: String): Flow<StoryEntity?>

    @Query("SELECT uuid FROM story WHERE deletedAt IS NULL AND textHash = :hash LIMIT 1")
    suspend fun uuidByHash(hash: String): String?

    @Query("SELECT title FROM story WHERE deletedAt IS NULL AND textHash = :hash LIMIT 1")
    suspend fun titleByHash(hash: String): String?

    /** Markdown-Import: Geschichte zur stabilen Kennung (auch aus dem Papierkorb, der Slug ist eindeutig). */
    @Query("SELECT * FROM story WHERE slug = :slug LIMIT 1")
    suspend fun getBySlug(slug: String): StoryEntity?

    /** Aktive Geschichte mit gleichem Import-Hash, die nicht zum angegebenen Slug gehört. */
    @Query(
        "SELECT title FROM story WHERE deletedAt IS NULL AND importHash = :hash AND (slug IS NULL OR slug != :slug) LIMIT 1",
    )
    suspend fun titleByImportHash(hash: String, slug: String): String?

    @Query(
        "SELECT title FROM story WHERE deletedAt IS NULL AND textHash = :hash AND (slug IS NULL OR slug != :slug) LIMIT 1",
    )
    suspend fun titleByTextHashExcept(hash: String, slug: String): String?

    @Query(
        "SELECT t.name FROM tag t JOIN tag_assignment a ON a.tagUuid = t.uuid " +
            "WHERE a.entityType = 'STORY' AND a.entityUuid = :uuid AND a.deletedAt IS NULL AND t.deletedAt IS NULL " +
            "ORDER BY t.name COLLATE NOCASE",
    )
    fun observeTagNames(uuid: String): Flow<List<String>>

    @Query(
        "SELECT t.name FROM tag t JOIN tag_assignment a ON a.tagUuid = t.uuid " +
            "WHERE a.entityType = 'STORY' AND a.entityUuid = :uuid AND a.deletedAt IS NULL AND t.deletedAt IS NULL",
    )
    suspend fun tagNames(uuid: String): List<String>

    @Insert
    suspend fun insert(story: StoryEntity): Long

    @Update
    suspend fun update(story: StoryEntity)

    @Query("SELECT text FROM story_text WHERE storyUuid = :uuid")
    suspend fun getText(uuid: String): String?

    @Upsert
    suspend fun upsertText(text: StoryTextEntity)

    @Query("UPDATE story SET readingParagraph = :paragraph, readingOffset = :offset WHERE uuid = :uuid")
    suspend fun setReading(uuid: String, paragraph: Int, offset: Int)

    @Query("UPDATE story SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    @Query("UPDATE story SET deletedAt = NULL, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun restore(uuid: String, now: Long)

    @Query("SELECT uuid FROM story WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun expiredUuids(before: Long): List<String>

    @Query("DELETE FROM story WHERE uuid = :uuid")
    suspend fun deleteStory(uuid: String)

    @Query("DELETE FROM story_text WHERE storyUuid = :uuid")
    suspend fun deleteText(uuid: String)

    @Query("DELETE FROM story_draft_version WHERE storyUuid = :uuid")
    suspend fun deleteDrafts(uuid: String)

    @Transaction
    suspend fun purge(uuid: String) {
        deleteText(uuid)
        deleteDrafts(uuid)
        deleteStory(uuid)
    }

    @Insert
    suspend fun insertDraft(draft: StoryDraftVersionEntity)

    @Query("SELECT * FROM story_draft_version WHERE storyUuid = :uuid ORDER BY savedAt DESC")
    suspend fun drafts(uuid: String): List<StoryDraftVersionEntity>

    @Query("SELECT savedAt FROM story_draft_version WHERE storyUuid = :uuid ORDER BY savedAt DESC LIMIT 1")
    suspend fun lastDraftAt(uuid: String): Long?

    @Query(
        "DELETE FROM story_draft_version WHERE storyUuid = :uuid AND id NOT IN " +
            "(SELECT id FROM story_draft_version WHERE storyUuid = :uuid ORDER BY savedAt DESC LIMIT :keep)",
    )
    suspend fun pruneDrafts(uuid: String, keep: Int)

    @Query("SELECT COUNT(*) FROM story WHERE deletedAt IS NULL")
    fun observeCount(): Flow<Int>
}

data class ProductRow(
    val uuid: String,
    val title: String,
    val brandUuid: String?,
    val sizeLabel: String?,
    val stars: Int?,
    val favorite: Boolean,
    val unitPriceMin: Double?,
    val unitPriceMax: Double?,
    val unitPriceOverride: Double?,
    val createdAt: Long,
)

@Dao
interface ProductDao {
    @Query(
        "SELECT uuid, title, brandUuid, sizeLabel, stars, favorite, unitPriceMin, unitPriceMax, unitPriceOverride, createdAt " +
            "FROM product WHERE deletedAt IS NULL",
    )
    fun observeActive(): Flow<List<ProductRow>>

    @Query("SELECT * FROM product WHERE deletedAt IS NULL")
    suspend fun allActive(): List<ProductEntity>

    @Query("SELECT * FROM product WHERE uuid = :uuid")
    suspend fun get(uuid: String): ProductEntity?

    @Query("SELECT * FROM product WHERE uuid = :uuid")
    fun observe(uuid: String): Flow<ProductEntity?>

    @Insert
    suspend fun insert(product: ProductEntity): Long

    @Update
    suspend fun update(product: ProductEntity)

    @Query("UPDATE product SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)

    @Query("SELECT uuid FROM product WHERE deletedAt IS NULL AND lower(title) = lower(:title) LIMIT 1")
    suspend fun uuidByTitle(title: String): String?

    @Query("SELECT * FROM product_criterion_value WHERE productUuid = :uuid AND deletedAt IS NULL")
    suspend fun values(uuid: String): List<ProductCriterionValueEntity>

    @Insert
    suspend fun insertValue(value: ProductCriterionValueEntity): Long

    @Update
    suspend fun updateValue(value: ProductCriterionValueEntity)

    @Query("SELECT * FROM product_photo WHERE productUuid = :uuid AND deletedAt IS NULL ORDER BY kind, rowIndex, sortOrder")
    fun observePhotos(uuid: String): Flow<List<ProductPhotoEntity>>

    @Query("SELECT * FROM product_photo WHERE productUuid = :uuid AND deletedAt IS NULL ORDER BY kind, rowIndex, sortOrder")
    suspend fun photos(uuid: String): List<ProductPhotoEntity>

    @Query("SELECT * FROM product_photo WHERE deletedAt IS NULL AND kind = 'PACKAGE' ORDER BY sortOrder")
    suspend fun allPackagePhotos(): List<ProductPhotoEntity>

    @Insert
    suspend fun insertPhoto(photo: ProductPhotoEntity): Long

    @Query("UPDATE product_photo SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun softDeletePhoto(uuid: String, now: Long)

    @Query("SELECT * FROM product_photo WHERE uuid = :uuid")
    suspend fun photo(uuid: String): ProductPhotoEntity?
}

@Dao
interface CriterionDao {
    @Query("SELECT * FROM criterion_catalog ORDER BY sortOrder")
    suspend fun all(): List<CriterionCatalogEntity>

    @Query("SELECT COUNT(*) FROM criterion_catalog")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(items: List<CriterionCatalogEntity>)
}

@Dao
interface BrandDao {
    @Query("SELECT * FROM brand WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<BrandEntity>>

    @Query("SELECT * FROM brand WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<BrandEntity>

    @Query("SELECT COUNT(*) FROM brand")
    suspend fun count(): Int

    @Insert
    suspend fun insert(brand: BrandEntity): Long

    @Update
    suspend fun update(brand: BrandEntity)
}

@Dao
interface TrackerDao {
    @Query("SELECT * FROM tracker_entry WHERE deletedAt IS NULL ORDER BY instant DESC")
    fun observeAll(): Flow<List<TrackerEntryEntity>>

    @Query("SELECT * FROM tracker_entry WHERE uuid = :uuid")
    suspend fun get(uuid: String): TrackerEntryEntity?

    @Insert
    suspend fun insert(entry: TrackerEntryEntity): Long

    @Update
    suspend fun update(entry: TrackerEntryEntity)

    @Query("UPDATE tracker_entry SET deletedAt = :now, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun softDelete(uuid: String, now: Long)
}

data class PackageRow(val uuid: String, val size: Long)

data class PackageSourceRow(val uuid: String, val label: String, val treeUri: String)

@Dao
interface PackageDao {
    @Query(
        "SELECT uuid, size FROM media_file WHERE deletedAt IS NULL AND status = 'ONLINE' AND type = :type AND favorite = 1 " +
            "ORDER BY rating DESC, displayName",
    )
    suspend fun favorites(type: String): List<PackageRow>

    @Query(
        "SELECT uuid, size FROM media_file WHERE deletedAt IS NULL AND status = 'ONLINE' AND type = :type AND rating >= :min " +
            "ORDER BY rating DESC, displayName",
    )
    suspend fun rated(type: String, min: Int): List<PackageRow>

    @Query(
        "SELECT uuid, size FROM media_file WHERE deletedAt IS NULL AND status = 'ONLINE' AND uuid IN " +
            "(SELECT mediaUuid FROM playlist_item WHERE playlistUuid = :playlistUuid AND deletedAt IS NULL) ORDER BY displayName",
    )
    suspend fun inPlaylist(playlistUuid: String): List<PackageRow>

    @Query(
        "SELECT uuid, size FROM media_file WHERE deletedAt IS NULL AND status = 'ONLINE' AND type = 'VIDEO' AND seenState = 'UNSEEN' " +
            "ORDER BY createdAt DESC",
    )
    suspend fun unseenVideos(): List<PackageRow>

    @Query("SELECT * FROM media_file WHERE uuid IN (:uuids)")
    suspend fun byUuids(uuids: List<String>): List<MediaFileEntity>

    @Query("SELECT uuid FROM media_file WHERE uuid IN (:uuids)")
    suspend fun existing(uuids: List<String>): List<String>

    @Query(
        "UPDATE media_file SET sourceUuid = :source, relativePath = :path, documentIdCache = :docId, fingerprint = :fp, size = :size, " +
            "status = 'ONLINE', missingScanCount = 0, lastModified = NULL, deletedAt = NULL, updatedAt = :now WHERE uuid = :uuid",
    )
    suspend fun attach(uuid: String, source: String, path: String, docId: String?, fp: String, size: Long, now: Long)

    @Query("SELECT uuid FROM media_file WHERE sourceUuid = :source AND deletedAt IS NULL")
    suspend fun uuidsOfSource(source: String): List<String>

    @Query("UPDATE media_file SET status = 'OFFLINE', updatedAt = :now WHERE uuid IN (:uuids)")
    suspend fun markOffline(uuids: List<String>, now: Long)

    @Query("SELECT COUNT(*) FROM media_file WHERE sourceUuid = :source AND deletedAt IS NULL")
    fun observeCount(source: String): Flow<Int>

    @Query("SELECT uuid, label, treeUri FROM source WHERE deletedAt IS NULL AND role = 'MEDIA' AND label LIKE 'Paket: %' ORDER BY createdAt DESC")
    fun observePackageSources(): Flow<List<PackageSourceRow>>

    @Query("SELECT COUNT(*) FROM media_alias WHERE mediaUuid = :mediaUuid AND fingerprint = :fp")
    suspend fun aliasCount(mediaUuid: String, fp: String): Int
}

data class MediaStats(
    val count: Int, val bytes: Long, val avgRating: Double?, val unrated: Int, val unseen: Int, val favorites: Int, val offline: Int,
)

data class StoryStats(val count: Int, val avgRating: Double?, val unrated: Int, val favorites: Int, val continueCount: Int)

data class ProductStats(val count: Int, val avgStars: Double?, val unrated: Int, val favorites: Int)

@Dao
interface StatsDao {
    @Query(
        "SELECT COUNT(*) AS count, COALESCE(SUM(size), 0) AS bytes, AVG(CASE WHEN rating > 0 THEN rating END) AS avgRating, " +
            "COALESCE(SUM(CASE WHEN rating = 0 THEN 1 ELSE 0 END), 0) AS unrated, " +
            "COALESCE(SUM(CASE WHEN seenState = 'UNSEEN' THEN 1 ELSE 0 END), 0) AS unseen, " +
            "COALESCE(SUM(CASE WHEN favorite = 1 THEN 1 ELSE 0 END), 0) AS favorites, " +
            "COALESCE(SUM(CASE WHEN status = 'OFFLINE' THEN 1 ELSE 0 END), 0) AS offline " +
            "FROM media_file WHERE type = :type AND deletedAt IS NULL AND status != 'TRASHED'",
    )
    suspend fun media(type: String): MediaStats

    @Query(
        "SELECT COUNT(*) AS count, AVG(CASE WHEN rating > 0 THEN rating END) AS avgRating, " +
            "COALESCE(SUM(CASE WHEN rating = 0 THEN 1 ELSE 0 END), 0) AS unrated, " +
            "COALESCE(SUM(CASE WHEN favorite = 1 THEN 1 ELSE 0 END), 0) AS favorites, " +
            "COALESCE(SUM(CASE WHEN continueFlag = 1 THEN 1 ELSE 0 END), 0) AS continueCount FROM story WHERE deletedAt IS NULL",
    )
    suspend fun stories(): StoryStats

    @Query(
        "SELECT COUNT(*) AS count, AVG(stars) AS avgStars, COALESCE(SUM(CASE WHEN stars IS NULL THEN 1 ELSE 0 END), 0) AS unrated, " +
            "COALESCE(SUM(CASE WHEN favorite = 1 THEN 1 ELSE 0 END), 0) AS favorites FROM product WHERE deletedAt IS NULL",
    )
    suspend fun products(): ProductStats

    @Query("SELECT COUNT(*) FROM tracker_entry WHERE deletedAt IS NULL")
    suspend fun trackerEntries(): Int
}
