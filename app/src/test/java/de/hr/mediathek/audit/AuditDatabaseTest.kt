package de.hr.mediathek.audit

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import de.hr.mediathek.data.backup.BackupImporter
import de.hr.mediathek.data.backup.BackupWriter
import de.hr.mediathek.data.backup.ImportRejected
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.di.MIGRATIONS
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Audit: echte Room-/SQLite-Tests (Robolectric). Synthetische Daten, generiert aus den exportierten Schemas.
 * - Migration jeder Schemaversion 1..7 auf 8 mit Daten
 * - Backup-Roundtrip über alle gesicherten Tabellen
 * - Fehlerfälle beim Import (kaputt, neueres Schema)
 * - Befunde A-012 (Medien-Import) und H-001 (gelöschte Geschichte kehrt zurück)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuditDatabaseTest {

    private val schemaDir = File("schemas/de.hr.mediathek.data.db.MediathekDatabase")

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MediathekDatabase::class.java)

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val opened = ArrayList<MediathekDatabase>()

    @After fun close() { opened.forEach { it.close() } }

    private fun memDb(): MediathekDatabase =
        Room.inMemoryDatabaseBuilder(context, MediathekDatabase::class.java).allowMainThreadQueries().build().also { opened += it }

    // --- Generator ---------------------------------------------------------------------

    private class Col(val name: String, val affinity: String, val notNull: Boolean)
    private class Table(val name: String, val cols: List<Col>)

    private fun schema(version: Int): List<Table> {
        val json = JSONObject(File(schemaDir, "$version.json").readText())
        val ents = json.getJSONObject("database").getJSONArray("entities")
        return (0 until ents.length()).map { i ->
            val e = ents.getJSONObject(i)
            val f = e.getJSONArray("fields")
            Table(e.getString("tableName"), (0 until f.length()).map { j ->
                val c = f.getJSONObject(j)
                Col(c.getString("columnName"), c.getString("affinity"), c.optBoolean("notNull", false))
            })
        }
    }

    /** Eine synthetische Zeile: jede Spalte gefüllt, jede zweite nullbare Spalte NULL (bei [row] ungerade). */
    private fun row(t: Table, row: Int, override: Map<String, Any?> = emptyMap()): ContentValues {
        val cv = ContentValues()
        t.cols.forEachIndexed { i, c ->
            if (c.name == "id") return@forEachIndexed
            if (override.containsKey(c.name)) {
                when (val v = override[c.name]) {
                    null -> cv.putNull(c.name); is Long -> cv.put(c.name, v); is Int -> cv.put(c.name, v.toLong())
                    is Double -> cv.put(c.name, v); else -> cv.put(c.name, v.toString())
                }
                return@forEachIndexed
            }
            if (!c.notNull && row % 2 == 1 && i % 2 == 0) { cv.putNull(c.name); return@forEachIndexed }
            when (c.affinity) {
                "INTEGER" -> cv.put(c.name, (row * 37L + i) % 5 + 1)
                "REAL" -> cv.put(c.name, row + i / 10.0)
                "BLOB" -> cv.put(c.name, byteArrayOf(row.toByte(), i.toByte()))
                else -> cv.put(c.name, "v_${t.name}_${c.name}_$row")
            }
        }
        return cv
    }

    private fun fill(db: SupportSQLiteDatabase, tables: List<Table>, rows: Int) {
        for (t in tables) for (r in 0 until rows) {
            db.insert(t.name, SQLiteDatabase.CONFLICT_ABORT, row(t, r, fixups(t, r)))
        }
    }

    /** Fremdbezüge so setzen, dass der Backup-Filter (Medien mit Nutzerdaten) und Aliase passen. */
    private fun fixups(t: Table, r: Int): Map<String, Any?> = when (t.name) {
        "media_file" -> mapOf("rating" to 3L)
        "media_alias" -> mapOf("mediaUuid" to "v_media_file_uuid_$r")
        "setting" -> mapOf("key" to "audit_key_$r")
        else -> emptyMap()
    }

    private fun dump(db: SupportSQLiteDatabase, table: String): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        db.query("SELECT * FROM `$table`").use { c ->
            while (c.moveToNext()) {
                val m = LinkedHashMap<String, Any?>()
                for (i in 0 until c.columnCount) {
                    val n = c.getColumnName(i)
                    if (n == "id") continue
                    m[n] = when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                        else -> c.getString(i)
                    }
                }
                out += m
            }
        }
        return out.sortedBy { it.toString() }
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    // --- Migration ---------------------------------------------------------------------

    @Test fun migration_every_version_to_latest_keeps_rows() {
        for (v in 1 until MediathekDatabase.VERSION) {
            val name = "mig-$v.db"
            val tables = schema(v)
            helper.createDatabase(name, v).apply { fill(this, tables, 3); close() }
            val migrated = helper.runMigrationsAndValidate(name, MediathekDatabase.VERSION, true, *MIGRATIONS)
            for (t in tables) assertEquals("v$v -> ${MediathekDatabase.VERSION}: ${t.name}", 3, count(migrated, t.name))
            migrated.close()
        }
    }

    @Test fun migration_7_to_8_word_count() {
        val name = "mig-wc.db"
        val tables = schema(7)
        helper.createDatabase(name, 7).apply {
            val story = tables.first { it.name == "story" }
            insert("story", SQLiteDatabase.CONFLICT_ABORT, row(story, 0, mapOf("uuid" to "s1")))
            insert("story_text", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply { put("storyUuid", "s1"); put("text", "eins zwei drei\nvier") })
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 8, true, *MIGRATIONS)
        val wc = db.query("SELECT wordCount FROM story WHERE uuid='s1'").use { it.moveToFirst(); it.getInt(0) }
        db.close()
        // Befund (Niedrig): Zeilenumbrüche zählen nicht als Worttrenner -> 3 statt 4
        assertEquals(4, wc)
    }

    // --- Backup-Roundtrip --------------------------------------------------------------

    private fun writeBackup(db: MediathekDatabase): File {
        val f = File.createTempFile("audit-backup", ".zip")
        runBlocking { BackupWriter(db).write(f, "audit") }
        return f
    }

    @Test fun backup_roundtrip_all_tables() {
        val a = memDb()
        val tables = schema(MediathekDatabase.VERSION)
        fill(a.openHelper.writableDatabase, tables, 4)
        val file = writeBackup(a)
        val b = memDb()
        runBlocking { BackupImporter(b).import({ file.inputStream() }) }

        val diffs = ArrayList<String>()
        for (t in tables) {
            if (t.name in BackupWriter.EXCLUDED_TABLES) continue
            var expected = dump(a.openHelper.readableDatabase, t.name)
            if (t.name == "media_file") {
                // Erwartete Änderung beim Import: Medien sind erst nach einem Scan wieder online.
                expected = expected.map {
                    it.toMutableMap().apply {
                        put("documentIdCache", null); put("lastModified", null); put("lastSeenScanUuid", null); put("missingScanCount", 0L)
                        if (get("status") == "ONLINE") put("status", "OFFLINE")
                    }
                }.sortedBy { it.toString() }
            }
            val actual = dump(b.openHelper.readableDatabase, t.name)
            if (expected != actual) diffs += "${t.name}: erwartet ${expected.size}, ist ${actual.size}" +
                (expected.zip(actual).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> " | erste Abweichung: ${x.filter { (k, v) -> y[k] != v }.keys}" } ?: "")
        }
        assertTrue(diffs.joinToString("\n"), diffs.isEmpty())
    }

    @Test fun import_twice_is_idempotent() {
        val a = memDb()
        val tables = schema(MediathekDatabase.VERSION)
        fill(a.openHelper.writableDatabase, tables, 3)
        val file = writeBackup(a)
        val b = memDb()
        runBlocking { BackupImporter(b).import({ file.inputStream() }) }
        val first = tables.filter { it.name !in BackupWriter.EXCLUDED_TABLES }.associate { it.name to dump(b.openHelper.readableDatabase, it.name) }
        val r2 = runBlocking { BackupImporter(b).import({ file.inputStream() }) }
        val second = tables.filter { it.name !in BackupWriter.EXCLUDED_TABLES }.associate { it.name to dump(b.openHelper.readableDatabase, it.name) }
        assertEquals(first, second)
        assertEquals(0, r2.inserted)
    }

    @Test fun corrupt_backups_are_rejected_and_change_nothing() {
        val a = memDb()
        fill(a.openHelper.writableDatabase, schema(MediathekDatabase.VERSION), 2)
        val good = writeBackup(a).readBytes()
        val b = memDb()
        fill(b.openHelper.writableDatabase, schema(MediathekDatabase.VERSION).filter { it.name == "tag" }, 1)
        val before = dump(b.openHelper.readableDatabase, "tag")
        val variants = mapOf(
            "abgeschnitten" to good.copyOf(good.size / 2),
            "leer" to ByteArray(0),
            "kein zip" to "hallo".toByteArray(),
        )
        for ((label, bytes) in variants) {
            try {
                runBlocking { BackupImporter(b).import({ bytes.inputStream() }) }
                fail("$label: Import hätte abgelehnt werden müssen")
            } catch (e: Exception) { /* erwartet */ }
            assertEquals(label, before, dump(b.openHelper.readableDatabase, "tag"))
        }
    }

    @Test fun newer_schema_is_rejected() {
        val a = memDb()
        fill(a.openHelper.writableDatabase, schema(MediathekDatabase.VERSION), 1)
        val src = writeBackup(a)
        val patched = File.createTempFile("audit-newer", ".zip")
        ZipInputStream(src.inputStream()).use { zin ->
            ZipOutputStream(patched.outputStream()).use { zout ->
                var e = zin.nextEntry
                while (e != null) {
                    var bytes = zin.readBytes()
                    if (e.name == BackupWriter.MANIFEST) {
                        bytes = String(bytes).replace("\"schemaVersion\":${MediathekDatabase.VERSION}", "\"schemaVersion\":99").toByteArray()
                    }
                    zout.putNextEntry(ZipEntry(e.name)); zout.write(bytes); zout.closeEntry()
                    e = zin.nextEntry
                }
            }
        }
        try {
            runBlocking { BackupImporter(memDb()).import({ patched.inputStream() }) }
            fail("neueres Schema muss abgelehnt werden")
        } catch (e: ImportRejected) { /* erwartet */ }
    }

    // --- Befunde ------------------------------------------------------------------------

    /** Befund A-012: Ein älteres Backup mit neuerem Nutzer-Zeitstempel überschreibt Pfad/Fingerabdruck und setzt offline. */
    @Test fun import_keeps_local_technical_media_fields() {
        val media = schema(MediathekDatabase.VERSION).first { it.name == "media_file" }
        val a = memDb()
        a.openHelper.writableDatabase.insert("media_file", SQLiteDatabase.CONFLICT_ABORT, row(media, 0, mapOf(
            "uuid" to "m1", "rating" to 4L, "userUpdatedAt" to 200L, "relativePath" to "ordner/clip.mkv",
            "fingerprint" to "ALT", "status" to "ONLINE", "deletedAt" to null,
        )))
        val file = writeBackup(a)
        val b = memDb()
        b.openHelper.writableDatabase.insert("media_file", SQLiteDatabase.CONFLICT_ABORT, row(media, 0, mapOf(
            "uuid" to "m1", "rating" to 2L, "userUpdatedAt" to 100L, "relativePath" to "ordner/clip.mp4",
            "fingerprint" to "NEU", "status" to "ONLINE", "documentIdCache" to "doc", "deletedAt" to null,
        )))
        runBlocking { BackupImporter(b).import({ file.inputStream() }) }
        val r = dump(b.openHelper.readableDatabase, "media_file").single()
        assertEquals(4L, r["rating"])
        assertEquals("ordner/clip.mp4", r["relativePath"])
        assertEquals("NEU", r["fingerprint"])
        assertEquals("ONLINE", r["status"])
    }

    /** Befund H-001: endgültig gelöschte Geschichte (Papierkorb geleert) kommt beim Import eines Backups zurück. */
    @Test fun purged_story_does_not_come_back_from_backup() {
        val db = memDb()
        val story = schema(MediathekDatabase.VERSION).first { it.name == "story" }
        db.openHelper.writableDatabase.insert("story", SQLiteDatabase.CONFLICT_ABORT, row(story, 0, mapOf("uuid" to "s1", "deletedAt" to null)))
        val file = writeBackup(db)
        runBlocking { db.storyDao().purge("s1") }
        runBlocking { BackupImporter(db).import({ file.inputStream() }) }
        val alive = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM story WHERE uuid='s1' AND deletedAt IS NULL").use { it.moveToFirst(); it.getInt(0) }
        assertEquals("gelöschte Geschichte ist wieder da", 0, alive)
    }
}
