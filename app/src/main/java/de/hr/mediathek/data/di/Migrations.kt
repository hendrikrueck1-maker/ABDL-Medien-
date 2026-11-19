package de.hr.mediathek.data.di

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 1 -> 2: Änderungszeit der Datei für den inkrementellen Scan. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_file ADD COLUMN lastModified INTEGER")
    }
}

/** Alle Schema-Migrationen in Reihenfolge. */
val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
