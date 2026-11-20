package de.hr.mediathek.data.pkg

import de.hr.mediathek.data.db.MediathekDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die für den Export gewählten Medien. Die Tabelle liegt neben dem Room-Schema (wird nicht gesichert),
 * damit der Export-Job auch nach einem Neustart der App mit derselben Auswahl weiterläuft.
 */
@Singleton
class PackageSelectionStore @Inject constructor(
    private val db: MediathekDatabase,
) {
    private fun ensure(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
        sql.execSQL("CREATE TABLE IF NOT EXISTS package_selection(uuid TEXT PRIMARY KEY NOT NULL)")
    }

    suspend fun replace(uuids: Collection<String>) = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        ensure(sql)
        sql.beginTransaction()
        try {
            sql.execSQL("DELETE FROM package_selection")
            for (u in uuids) sql.execSQL("INSERT OR IGNORE INTO package_selection(uuid) VALUES(?)", arrayOf<Any>(u))
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    suspend fun all(): List<String> = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        ensure(sql)
        val result = ArrayList<String>()
        sql.query("SELECT uuid FROM package_selection").use { c -> while (c.moveToNext()) result += c.getString(0) }
        result
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        ensure(sql)
        sql.execSQL("DELETE FROM package_selection")
    }
}
