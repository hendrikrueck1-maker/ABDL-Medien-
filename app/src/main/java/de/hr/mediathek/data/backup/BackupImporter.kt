package de.hr.mediathek.data.backup

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import de.hr.mediathek.data.db.MediathekDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

class ImportRejected(message: String) : Exception(message)

class ImportResult(
    val inserted: Int,
    val updated: Int,
    val kept: Int,
    val conflicts: Int,
    val olderThanTombstoneRetention: Boolean,
    val backupSchema: Int,
)

/**
 * Spielt ein Backup ein: Zusammenführen nach UUID, der neuere userUpdatedAt gewinnt (bei Gleichstand bleibt lokal).
 * Ältere Schema-Versionen werden über die gemeinsamen Spalten gelesen, neuere abgelehnt.
 * Alles läuft in einer Transaktion: bei einem Fehler bleibt die Datenbank unverändert.
 */
@Singleton
class BackupImporter @Inject constructor(
    private val db: MediathekDatabase,
) {
    /** [open] liefert bei jedem Aufruf einen frischen Stream (die Datei wird zweimal gelesen: Prüfung, Import). */
    suspend fun import(open: () -> InputStream, skipTables: Set<String> = emptySet()): ImportResult = withContext(Dispatchers.IO) {
        val info = open().use { BackupWriter.inspect(it) }
        if (info.schemaVersion > MediathekDatabase.VERSION) {
            throw ImportRejected("Das Backup stammt aus einer neueren App-Version. Bitte zuerst die App aktualisieren.")
        }
        val oldBackup = info.createdAt != null &&
            System.currentTimeMillis() - info.createdAt > TOMBSTONE_RETENTION_MS

        var inserted = 0
        var updated = 0
        var kept = 0
        var conflicts = 0
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val wonStories = HashSet<String>()
            ZipInputStream(open().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".ndjson")) {
                        val table = entry.name.removeSuffix(".ndjson")
                        val meta = if (table in skipTables) null else tableMeta(sql, table)
                        if (meta != null) {
                            val reader = zip.bufferedReader(Charsets.UTF_8)
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isBlank()) continue
                                when (processRow(sql, table, meta, JSONObject(line), wonStories)) {
                                    Outcome.INSERTED -> inserted++
                                    Outcome.UPDATED -> { updated++; conflicts++ }
                                    Outcome.KEPT_LOCAL -> kept++
                                    Outcome.KEPT_CONFLICT -> { kept++; conflicts++ }
                                }
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        ImportResult(inserted, updated, kept, conflicts, oldBackup, info.schemaVersion)
    }

    private enum class Outcome { INSERTED, UPDATED, KEPT_LOCAL, KEPT_CONFLICT }

    private class Column(val name: String, val type: String, val notNull: Boolean, val hasDefault: Boolean)
    private class Meta(val columns: List<Column>) {
        val names = columns.map { it.name }.toSet()
    }

    private fun tableMeta(sql: SupportSQLiteDatabase, table: String): Meta? {
        if (table in BackupWriter.EXCLUDED_TABLES) return null
        val cols = ArrayList<Column>()
        sql.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) {
                cols += Column(
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    type = c.getString(c.getColumnIndexOrThrow("type")).uppercase(),
                    notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1,
                    hasDefault = !c.isNull(c.getColumnIndexOrThrow("dflt_value")),
                )
            }
        }
        return if (cols.isEmpty()) null else Meta(cols)
    }

    private fun processRow(
        sql: SupportSQLiteDatabase,
        table: String,
        meta: Meta,
        row: JSONObject,
        wonStories: MutableSet<String>,
    ): Outcome {
        if (table == "setting") {
            val key = row.optString("key")
            if (key in DEVICE_SETTINGS || DEVICE_SETTING_PREFIXES.any { key.startsWith(it) }) return Outcome.KEPT_LOCAL
            return insertIgnore(sql, table, meta, row)
        }
        if (table == "story_text") {
            val storyUuid = row.optString("storyUuid")
            return if (storyUuid in wonStories) {
                sql.insert(table, SQLiteDatabase.CONFLICT_REPLACE, values(meta, row))
                Outcome.INSERTED
            } else {
                insertIgnore(sql, table, meta, row)
            }
        }
        if ("uuid" !in meta.names) return insertIgnore(sql, table, meta, row)

        val uuid = row.getString("uuid")
        val hasStamp = "userUpdatedAt" in meta.names
        var localStamp: Long? = null
        var exists = false
        sql.query(
            "SELECT ${if (hasStamp) "userUpdatedAt" else "0"} FROM `$table` WHERE uuid = ?",
            arrayOf(uuid),
        ).use { c ->
            if (c.moveToFirst()) {
                exists = true
                localStamp = c.getLong(0)
            }
        }
        val cv = values(meta, row)
        if (table == "media_file") prepareMedia(cv)

        if (!exists) {
            cv.remove("id")
            return try {
                sql.insert(table, SQLiteDatabase.CONFLICT_ABORT, cv)
                if (table == "story") wonStories += uuid
                Outcome.INSERTED
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Eindeutiger Schlüssel (z. B. Kriterium) existiert lokal unter anderer UUID: lokal behalten.
                Outcome.KEPT_CONFLICT
            }
        }
        val incoming = if (hasStamp) row.optLong("userUpdatedAt", 0L) else 0L
        val local = localStamp ?: 0L
        return if (hasStamp && incoming > local) {
            cv.remove("id")
            cv.remove("uuid")
            sql.update(table, SQLiteDatabase.CONFLICT_ABORT, cv, "uuid = ?", arrayOf(uuid))
            if (table == "story") wonStories += uuid
            Outcome.UPDATED
        } else if (incoming != local) {
            Outcome.KEPT_CONFLICT
        } else {
            Outcome.KEPT_LOCAL
        }
    }

    /**
     * Medien aus einem Backup sind erst "online", wenn ein Scan sie bestätigt. Die Änderungszeit wird geleert,
     * damit der nächste Scan die Datei neu auflöst (Dokument-ID dieses Geräts) statt sie als unverändert zu überspringen.
     */
    private fun prepareMedia(cv: ContentValues) {
        if (cv.getAsString("status") == "ONLINE") cv.put("status", "OFFLINE")
        cv.putNull("documentIdCache")
        cv.putNull("lastModified")
        cv.putNull("lastSeenScanUuid")
        cv.put("missingScanCount", 0)
    }

    private fun insertIgnore(sql: SupportSQLiteDatabase, table: String, meta: Meta, row: JSONObject): Outcome {
        val cv = values(meta, row)
        val id = sql.insert(table, SQLiteDatabase.CONFLICT_IGNORE, cv)
        return if (id == -1L) Outcome.KEPT_LOCAL else Outcome.INSERTED
    }

    /** Baut die Werte aus den gemeinsamen Spalten; fehlende Pflichtspalten (neuer als das Backup) bekommen neutrale Werte. */
    private fun values(meta: Meta, row: JSONObject): ContentValues {
        val cv = ContentValues()
        for (col in meta.columns) {
            if (col.name == "id") continue
            if (!row.has(col.name)) {
                if (col.notNull && !col.hasDefault) {
                    if (col.type.contains("INT")) cv.put(col.name, 0L) else cv.put(col.name, "")
                }
                continue
            }
            if (row.isNull(col.name)) {
                cv.putNull(col.name)
                continue
            }
            when (val v = row.get(col.name)) {
                is Int -> cv.put(col.name, v.toLong())
                is Long -> cv.put(col.name, v)
                is Double -> if (col.type.contains("INT")) cv.put(col.name, v.toLong()) else cv.put(col.name, v)
                is Boolean -> cv.put(col.name, if (v) 1L else 0L)
                is JSONObject -> cv.put(col.name, Base64.getDecoder().decode(v.getString("\$b64")))
                else -> cv.put(col.name, v.toString())
            }
        }
        return cv
    }

    companion object {
        const val TOMBSTONE_RETENTION_MS = 365L * 24 * 60 * 60 * 1000

        /** Gerätespezifisch: nie aus einem Backup übernehmen. */
        private val DEVICE_SETTINGS = setOf(
            "work_folder_uri", "work_folder_label", "saf_onboarding_seen",
            "backup_last_success", "backup_last_records", "backup_warning",
        )
        private val DEVICE_SETTING_PREFIXES = listOf("thumb_", "compress_", "search_sig_")
    }
}
