package de.hr.mediathek.data.di

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 1 -> 2: Änderungszeit der Datei für den inkrementellen Scan. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_file ADD COLUMN lastModified INTEGER")
    }
}

/** 2 -> 3: Vorschaubild-Cache. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `thumbnail` (`mediaUuid` TEXT NOT NULL, `fingerprint` TEXT, " +
                "`variant` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`lastAccess` INTEGER NOT NULL, PRIMARY KEY(`mediaUuid`))",
        )
    }
}

/** 3 -> 4: Phasen-Journal der Komprimierung bekommt die Felder zum Fortsetzen. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "sourceUuid TEXT", "relativePath TEXT", "origName TEXT", "targetName TEXT", "level TEXT",
            "origDocId TEXT", "newDocId TEXT", "localTemp TEXT", "oldFingerprint TEXT", "newFingerprint TEXT",
            "origSize INTEGER", "newSize INTEGER", "note TEXT",
        ).forEach { db.execSQL("ALTER TABLE compression_record ADD COLUMN $it") }
    }
}

/** 4 -> 5: Bild-Hashes für Duplikaterkennung. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_file ADD COLUMN contentHash TEXT")
        db.execSQL("ALTER TABLE media_file ADD COLUMN hashFingerprint TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_file_contentHash` ON `media_file` (`contentHash`)")
    }
}

/** Alle Schema-Migrationen in Reihenfolge. */
val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
