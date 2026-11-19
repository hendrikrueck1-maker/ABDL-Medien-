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

/** Alle Schema-Migrationen in Reihenfolge. */
val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
