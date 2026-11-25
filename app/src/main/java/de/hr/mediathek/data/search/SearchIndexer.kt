package de.hr.mediathek.data.search

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suchindex (FTS4, liegt neben dem Room-Schema und wird nicht gesichert). Er wird je Bereich neu aufgebaut,
 * sobald sich dessen Signatur (Anzahl, letzte Änderung) geändert hat, also auch nach Backup-Einspielen oder Import.
 */
@Singleton
class SearchIndexer @Inject constructor(
    private val db: MediathekDatabase,
    private val settings: SettingDao,
) {
    private class Area(val kind: String, val signatureSql: String, val rebuildSql: String)

    private val areas = listOf(
        Area(
            "STORY",
            "SELECT (SELECT COUNT(*) FROM story WHERE deletedAt IS NULL) || '-' || COALESCE((SELECT MAX(updatedAt) FROM story WHERE deletedAt IS NULL), 0) || '-' || COALESCE((SELECT SUM(LENGTH(text)) FROM story_text), 0)",
            folded(
                "SELECT 'STORY' AS k, s.uuid AS u, s.title AS t, COALESCE(s.sourceText, '') || ' ' || COALESCE(t.text, '') AS b " +
                    "FROM story s LEFT JOIN story_text t ON t.storyUuid = s.uuid WHERE s.deletedAt IS NULL",
            ),
        ),
        Area(
            "PRODUCT",
            "SELECT (SELECT COUNT(*) FROM product WHERE deletedAt IS NULL) || '-' || COALESCE((SELECT MAX(updatedAt) FROM product WHERE deletedAt IS NULL), 0) || '-' || COALESCE((SELECT MAX(updatedAt) FROM brand), 0)",
            folded(
                "SELECT 'PRODUCT' AS k, p.uuid AS u, p.title AS t, COALESCE(b.name, '') || ' ' || COALESCE(p.sizeLabel, '') || ' ' || COALESCE(p.overallNote, '') AS b " +
                    "FROM product p LEFT JOIN brand b ON b.uuid = p.brandUuid WHERE p.deletedAt IS NULL",
            ),
        ),
        mediaArea("VIDEO"),
        mediaArea("IMAGE"),
    )

    private fun mediaArea(type: String) = Area(
        type,
        "SELECT COUNT(*) || '-' || COALESCE(MAX(updatedAt), 0) FROM media_file WHERE type = '$type' AND deletedAt IS NULL AND status != 'TRASHED'",
        folded(
            "SELECT '$type' AS k, uuid AS u, displayName AS t, relativePath AS b FROM media_file " +
                "WHERE type = '$type' AND deletedAt IS NULL AND status != 'TRASHED'",
        ),
    )

    /** ß wird beim Indexieren zu ss (wie bei der Suche), damit „strasse“ auch „Straße“ findet. */
    private fun folded(select: String): String =
        "INSERT INTO search_index(kind, uuid, title, body) SELECT k, u, ${fold("t")}, ${fold("b")} FROM ($select)"

    private fun fold(col: String) = "REPLACE(REPLACE($col, 'ß', 'ss'), 'ẞ', 'SS')"

    /** Baut veraltete Bereiche neu auf. Gibt die Zahl der neu aufgebauten Bereiche zurück. */
    suspend fun sync(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        createTable(sql)
        var rebuilt = 0
        for (a in areas) {
            // Versionszusatz: ändert sich das Indexformat (z. B. ß-Faltung), wird jeder Bereich einmal neu aufgebaut.
            val sig = INDEX_VERSION + sql.query(a.signatureSql).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
            val key = KEY_PREFIX + a.kind
            if (!force && settings.get(key) == sig) continue
            sql.beginTransaction()
            try {
                sql.execSQL("DELETE FROM search_index WHERE kind = ?", arrayOf<Any>(a.kind))
                sql.execSQL(a.rebuildSql)
                sql.setTransactionSuccessful()
            } finally {
                sql.endTransaction()
            }
            settings.set(SettingEntity(key, sig))
            rebuilt++
        }
        rebuilt
    }

    suspend fun search(parsed: ParsedQuery, kinds: Set<String>, limit: Int = 200): List<SearchHit> = withContext(Dispatchers.IO) {
        val args = ArrayList<Any>()
        val sb = StringBuilder("SELECT kind, uuid, title, snippet(search_index, '[', ']', '…', -1, 12) FROM search_index WHERE search_index MATCH ?")
        args += parsed.match
        if (kinds.isNotEmpty()) {
            sb.append(" AND kind IN (").append(kinds.joinToString(",") { "?" }).append(')')
            args.addAll(kinds)
        }
        for (ex in parsed.excluded) {
            sb.append(" AND uuid NOT IN (SELECT uuid FROM search_index WHERE search_index MATCH ?)")
            args += ex
        }
        sb.append(" LIMIT ").append(limit)
        val result = ArrayList<SearchHit>()
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery(sb.toString(), args.toTypedArray())).use { c ->
            while (c.moveToNext()) result += SearchHit(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "")
        }
        result
    }

    companion object {
        private const val INDEX_VERSION = "v2:"
        private const val KEY_PREFIX = "search_sig_"

        /** Wird beim Öffnen der Datenbank aufgerufen. */
        fun createTable(sql: SupportSQLiteDatabase) {
            sql.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS search_index USING fts4(kind, uuid, title, body, notindexed=kind, notindexed=uuid, tokenize=unicode61)",
            )
        }
    }
}

data class SearchHit(val kind: String, val uuid: String, val title: String, val snippet: String)
