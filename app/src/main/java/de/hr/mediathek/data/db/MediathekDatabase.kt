package de.hr.mediathek.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class,
        MediaFileEntity::class,
        MediaAliasEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        CategoryAssignmentEntity::class,
        TagAssignmentEntity::class,
        SeriesEntity::class,
        StoryEntity::class,
        StoryTextEntity::class,
        StoryDraftVersionEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        BrandEntity::class,
        ProductEntity::class,
        CriterionCatalogEntity::class,
        ProductCriterionValueEntity::class,
        ProductPhotoEntity::class,
        TrackerEntryEntity::class,
        JobEntity::class,
        CompressionRecordEntity::class,
        TrashItemEntity::class,
        ErrorLogEntity::class,
        SettingEntity::class,
        ThumbnailEntity::class,
    ],
    version = MediathekDatabase.VERSION,
    exportSchema = true,
)
abstract class MediathekDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun settingDao(): SettingDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun mediaFileDao(): MediaFileDao
    abstract fun jobDao(): JobDao
    abstract fun thumbnailDao(): ThumbnailDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        const val VERSION = 3
        const val NAME = "mediathek.db"
    }
}
