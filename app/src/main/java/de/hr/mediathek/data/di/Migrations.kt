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

/** 5 -> 6: Markierungen (zum Löschen, 💧) und Abspielzähler. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_file ADD COLUMN markedDelete INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE media_file ADD COLUMN markedPee INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE media_file ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE media_file ADD COLUMN lastPlayedAt INTEGER")
    }
}

/** 6 -> 7: Markdown-Import für Geschichten (Slug, Autor, Status, Veröffentlichungsdatum, Import-Hash). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE story ADD COLUMN slug TEXT")
        db.execSQL("ALTER TABLE story ADD COLUMN author TEXT")
        db.execSQL("ALTER TABLE story ADD COLUMN status TEXT")
        db.execSQL("ALTER TABLE story ADD COLUMN publishedAt INTEGER")
        db.execSQL("ALTER TABLE story ADD COLUMN importHash TEXT")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_story_slug` ON `story` (`slug`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_importHash` ON `story` (`importHash`)")
    }
}

/** 7 -> 8: Geschichten bekommen Ordner, Lesefortschritt, Lesezeichen und Wortzahl. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE story ADD COLUMN lastReadAt INTEGER")
        db.execSQL("ALTER TABLE story ADD COLUMN readProgress REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE story ADD COLUMN wordCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `story_folder` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `parentUuid` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`userUpdatedAt` INTEGER NOT NULL, `deletedAt` INTEGER)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_story_folder_uuid` ON `story_folder` (`uuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_folder_parentUuid` ON `story_folder` (`parentUuid`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `story_folder_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, " +
                "`folderUuid` TEXT NOT NULL, `storyUuid` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`userUpdatedAt` INTEGER NOT NULL, `deletedAt` INTEGER)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_story_folder_item_uuid` ON `story_folder_item` (`uuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_folder_item_folderUuid` ON `story_folder_item` (`folderUuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_folder_item_storyUuid` ON `story_folder_item` (`storyUuid`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `story_bookmark` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, " +
                "`storyUuid` TEXT NOT NULL, `paragraph` INTEGER NOT NULL, `label` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `userUpdatedAt` INTEGER NOT NULL, `deletedAt` INTEGER)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_story_bookmark_uuid` ON `story_bookmark` (`uuid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_story_bookmark_storyUuid` ON `story_bookmark` (`storyUuid`)")
        // Wortzahl grob aus den Leerzeichen; genügt für Sortierung und Statistik.
        db.execSQL(
            "UPDATE story SET wordCount = COALESCE((SELECT LENGTH(TRIM(text)) - LENGTH(REPLACE(TRIM(text), ' ', '')) + 1 " +
                "FROM story_text WHERE storyUuid = story.uuid AND LENGTH(TRIM(text)) > 0), 0)",
        )
    }
}

/** Alle Schema-Migrationen in Reihenfolge. */
val MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
)
