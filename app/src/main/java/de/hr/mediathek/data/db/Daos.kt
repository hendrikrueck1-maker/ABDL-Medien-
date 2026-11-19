package de.hr.mediathek.data.db

import androidx.room.Dao
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
}

@Dao
interface JobDao {
    @Insert
    suspend fun insert(job: JobEntity): Long

    @Update
    suspend fun update(job: JobEntity)

    @Query("SELECT * FROM job WHERE uuid = :uuid")
    suspend fun get(uuid: String): JobEntity?

    @Query("SELECT * FROM job WHERE status = 'QUEUED' ORDER BY priority DESC, createdAt ASC LIMIT 1")
    suspend fun nextQueued(): JobEntity?

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

    @Query("SELECT * FROM job WHERE type = 'SCAN' ORDER BY createdAt DESC LIMIT 30")
    fun observeRecentScans(): Flow<List<JobEntity>>

    @Query("SELECT COUNT(*) FROM job WHERE status = 'QUEUED'")
    suspend fun countQueued(): Int

    @Query("DELETE FROM job WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED') AND finishedAt < :before")
    suspend fun deleteFinishedBefore(before: Long)
}
