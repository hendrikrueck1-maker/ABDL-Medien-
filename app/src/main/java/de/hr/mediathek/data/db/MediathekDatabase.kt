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
    abstract fun trashDao(): TrashDao
    abstract fun tagDao(): TagDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun compressionDao(): CompressionDao
    abstract fun storyDao(): StoryDao
    abstract fun productDao(): ProductDao
    abstract fun criterionDao(): CriterionDao
    abstract fun brandDao(): BrandDao
    abstract fun trackerDao(): TrackerDao
    abstract fun packageDao(): PackageDao
    abstract fun statsDao(): StatsDao

    companion object {
        const val VERSION = 7
        const val NAME = "mediathek.db"
    }
}
