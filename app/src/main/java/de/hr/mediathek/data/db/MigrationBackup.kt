package de.hr.mediathek.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Vor jeder Room-Migration wird eine Kopie der Datenbankdatei im internen Speicher abgelegt,
 * die letzten 3 bleiben erhalten.
 */
object MigrationBackup {
    private const val KEEP = 3

    fun backupIfUpgradeNeeded(context: Context) {
        try {
            val dbFile = context.getDatabasePath(MediathekDatabase.NAME)
            if (!dbFile.exists()) return
            val oldVersion = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                .use { it.version }
            if (oldVersion < 1 || oldVersion >= MediathekDatabase.VERSION) return

            val dir = File(context.filesDir, "db-backups").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            for (suffix in listOf("", "-wal")) {
                val source = File(dbFile.path + suffix)
                if (source.exists()) {
                    source.copyTo(File(dir, "mediathek-v$oldVersion-$stamp.db$suffix"), overwrite = true)
                }
            }
            val mainCopies = dir.listFiles { f -> f.name.endsWith(".db") }?.sortedBy { it.lastModified() }.orEmpty()
            mainCopies.dropLast(KEEP).forEach { old ->
                old.delete()
                File(old.path + "-wal").delete()
            }
        } catch (e: Exception) {
            // Ein fehlgeschlagenes Backup darf den Start nicht verhindern.
        }
    }
}
