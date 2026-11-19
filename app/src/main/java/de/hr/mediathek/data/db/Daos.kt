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
