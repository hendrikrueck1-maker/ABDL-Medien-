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
    @Query("SELECT * FROM source WHERE deletedAt IS NULL ORDER BY createdAt")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source WHERE deletedAt IS NULL ORDER BY createdAt")
    suspend fun getAll(): List<SourceEntity>

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

data class SourceCount(val sourceUuid: String, val total: Int, val offline: Int)

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

    @Query("UPDATE media_file SET rating = :rating, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun setRating(uuid: String, rating: Int, now: Long)

    @Query("UPDATE media_file SET favorite = :favorite, updatedAt = :now, userUpdatedAt = :now WHERE uuid = :uuid")
    suspend fun setFavorite(uuid: String, favorite: Boolean, now: Long)

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
}
