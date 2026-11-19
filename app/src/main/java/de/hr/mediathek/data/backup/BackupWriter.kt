package de.hr.mediathek.data.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import de.hr.mediathek.data.backup.Ndjson.Cell
import de.hr.mediathek.data.db.MediathekDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

class BackupSummary(
    val schemaVersion: Int,
    val tableCounts: Map<String, Int>,
    val checksums: Map<String, String>,
) {
    val totalRecords: Int get() = tableCounts.values.sum()
}

/**
 * Schreibt alle Nutzerdaten als ZIP mit einer NDJSON-Datei je Tabelle und einer Kopfdatei (manifest.json).
 * Die Tabellen werden zeilenweise gestreamt, nie als ganzes JSON im Speicher gehalten.
 */
@Singleton
class BackupWriter @Inject constructor(
    private val db: MediathekDatabase,
) {
    /** Schreibt ein Backup in [target]. Der Snapshot ist konsistent, weil alles in einer Transaktion gelesen wird. */
    suspend fun write(target: File, appVersion: String): BackupSummary = withContext(Dispatchers.IO) {
        val counts = LinkedHashMap<String, Int>()
        val checksums = LinkedHashMap<String, String>()
        db.withTransaction {
            val sql = db.openHelper.readableDatabase
            val tables = tableNames(sql)
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                for (table in tables) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0
                    zip.putNextEntry(ZipEntry("$table.ndjson"))
                    val columns = exportColumns(sql, table)
                    sql.query(selectFor(table, columns)).use { cursor ->
                        val out = DigestingWriter(zip, digest)
                        while (cursor.moveToNext()) {
                            out.line(Ndjson.encodeRow(columns, cells(cursor, columns.size)))
                            count++
                        }
                    }
                    zip.closeEntry()
                    counts[table] = count
                    checksums[table] = hex(digest.digest())
                }
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifestJson(appVersion, counts, checksums).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        BackupSummary(MediathekDatabase.VERSION, counts, checksums)
    }

    private fun tableNames(sql: androidx.sqlite.db.SupportSQLiteDatabase): List<String> {
        val result = ArrayList<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (name in EXCLUDED_TABLES || name.startsWith("sqlite_")) continue
                result += name
            }
        }
        return result
    }

    private fun exportColumns(sql: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<String> {
        val all = ArrayList<String>()
        sql.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) all += c.getString(c.getColumnIndexOrThrow("name"))
        }
        // Die lokale Zeilen-ID ist geräteabhängig; Identität sind UUIDs.
        return if ("uuid" in all) all.filter { it != "id" } else all
    }

    private fun selectFor(table: String, columns: List<String>): String {
        val cols = columns.joinToString(",") { "`$it`" }
        val where = when (table) {
            "media_file" -> " WHERE $MEDIA_WITH_USER_DATA"
            "media_alias" -> " WHERE mediaUuid IN (SELECT uuid FROM media_file WHERE $MEDIA_WITH_USER_DATA)"
            "setting" -> " WHERE `key` NOT LIKE 'thumb\\_%' ESCAPE '\\'"
            else -> ""
        }
        return "SELECT $cols FROM `$table`$where"
    }

    private fun cells(cursor: Cursor, n: Int): List<Cell> = List(n) { i ->
        when (cursor.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> Cell.Null
            Cursor.FIELD_TYPE_INTEGER -> Cell.Int(cursor.getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> Cell.Real(cursor.getDouble(i))
            Cursor.FIELD_TYPE_BLOB -> Cell.Blob(cursor.getBlob(i))
            else -> Cell.Text(cursor.getString(i))
        }
    }

    private class DigestingWriter(private val out: java.io.OutputStream, private val digest: MessageDigest) {
        fun line(text: String) {
            val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
            digest.update(bytes)
            out.write(bytes)
        }
    }

    private fun manifestJson(appVersion: String, counts: Map<String, Int>, checksums: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("{\"format\":1,\"schemaVersion\":").append(MediathekDatabase.VERSION)
        sb.append(",\"appVersion\":\"").append(appVersion.replace("\"", "")).append('"')
        sb.append(",\"createdAt\":").append(System.currentTimeMillis())
        sb.append(",\"tables\":{")
        var first = true
        for ((t, n) in counts) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(t).append("\":{\"count\":").append(n)
                .append(",\"sha256\":\"").append(checksums[t]).append("\"}")
        }
        sb.append("}}")
        return sb.toString()
    }

    companion object {
        const val MANIFEST = "manifest.json"

        /** Nicht gesichert: technische Daten, die neu entstehen oder nur lokal Sinn haben. */
        val EXCLUDED_TABLES = setOf("job", "error_log", "thumbnail", "compression_record", "room_master_table", "android_metadata")

        /** Medien-Einträge werden nur gesichert, wenn sie Nutzerdaten tragen; der Rest entsteht beim Scan neu. */
        const val MEDIA_WITH_USER_DATA =
            "(rating > 0 OR favorite = 1 OR seenState != 'UNSEEN' OR resumePositionMs > 0 OR deletedAt IS NOT NULL " +
                "OR uuid IN (SELECT entityUuid FROM category_assignment WHERE entityType = 'MEDIA') " +
                "OR uuid IN (SELECT entityUuid FROM tag_assignment WHERE entityType = 'MEDIA') " +
                "OR uuid IN (SELECT mediaUuid FROM playlist_item))"

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /**
         * Prüft ein fertiges Backup: ZIP lesbar, Kopfdaten vorhanden, Schema passt, Prüfsummen und Zeilenzahlen stimmen.
         * Gibt die Gesamtzahl der Datensätze zurück oder wirft eine Exception mit neutraler Meldung.
         */
        fun verify(input: java.io.InputStream, expectedSchema: Int): Int {
            val sums = HashMap<String, String>()
            val counts = HashMap<String, Int>()
            var manifest: String? = null
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == MANIFEST) {
                        manifest = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (entry.name.endsWith(".ndjson")) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        var lines = 0
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            for (i in 0 until n) if (buffer[i] == '\n'.code.toByte()) lines++
                        }
                        val name = entry.name.removeSuffix(".ndjson")
                        sums[name] = hex(digest.digest())
                        counts[name] = lines
                    }
                    entry = zip.nextEntry
                }
            }
            val m = manifest ?: error("Kopfdatei fehlt")
            val schema = Regex("\"schemaVersion\":(\\d+)").find(m)?.groupValues?.get(1)?.toInt() ?: error("Schema fehlt")
            check(schema == expectedSchema) { "Schema-Version stimmt nicht" }
            var total = 0
            val tablePattern = Regex("\"([a-z_]+)\":\\{\"count\":(\\d+),\"sha256\":\"([0-9a-f]+)\"\\}")
            val found = tablePattern.findAll(m).toList()
            check(found.size == sums.size) { "Tabellenzahl stimmt nicht" }
            for (match in found) {
                val (name, count, sha) = match.destructured
                check(sums[name] == sha) { "Prüfsumme stimmt nicht" }
                check(counts[name] == count.toInt()) { "Zeilenzahl stimmt nicht" }
                total += count.toInt()
            }
            return total
        }
    }
}
