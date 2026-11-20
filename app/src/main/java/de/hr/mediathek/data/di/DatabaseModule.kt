package de.hr.mediathek.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.hr.mediathek.data.db.ErrorLogDao
import de.hr.mediathek.data.db.JobDao
import de.hr.mediathek.data.db.MediaFileDao
import de.hr.mediathek.data.db.MediathekDatabase
import de.hr.mediathek.data.db.MigrationBackup
import de.hr.mediathek.data.db.SettingDao
import de.hr.mediathek.data.db.SourceDao
import de.hr.mediathek.data.db.ThumbnailDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MediathekDatabase {
        MigrationBackup.backupIfUpgradeNeeded(context)
        // Migrationen sind Pflicht, kein fallbackToDestructiveMigration: Daten gehen bei Updates nie verloren.
        return Room.databaseBuilder(context, MediathekDatabase::class.java, MediathekDatabase.NAME)
            .addMigrations(*MIGRATIONS)
            .build()
    }

    @Provides fun provideSourceDao(db: MediathekDatabase): SourceDao = db.sourceDao()
    @Provides fun provideSettingDao(db: MediathekDatabase): SettingDao = db.settingDao()
    @Provides fun provideErrorLogDao(db: MediathekDatabase): ErrorLogDao = db.errorLogDao()
    @Provides fun provideMediaFileDao(db: MediathekDatabase): MediaFileDao = db.mediaFileDao()
    @Provides fun provideJobDao(db: MediathekDatabase): JobDao = db.jobDao()
    @Provides fun provideCategoryDao(db: MediathekDatabase): de.hr.mediathek.data.db.CategoryDao = db.categoryDao()
    @Provides fun provideTrashDao(db: MediathekDatabase): de.hr.mediathek.data.db.TrashDao = db.trashDao()
    @Provides fun provideTagDao(db: MediathekDatabase): de.hr.mediathek.data.db.TagDao = db.tagDao()
    @Provides fun providePlaylistDao(db: MediathekDatabase): de.hr.mediathek.data.db.PlaylistDao = db.playlistDao()
    @Provides fun provideCompressionDao(db: MediathekDatabase): de.hr.mediathek.data.db.CompressionDao = db.compressionDao()
    @Provides fun provideStoryDao(db: MediathekDatabase): de.hr.mediathek.data.db.StoryDao = db.storyDao()
    @Provides fun provideProductDao(db: MediathekDatabase): de.hr.mediathek.data.db.ProductDao = db.productDao()
    @Provides fun provideCriterionDao(db: MediathekDatabase): de.hr.mediathek.data.db.CriterionDao = db.criterionDao()
    @Provides fun provideBrandDao(db: MediathekDatabase): de.hr.mediathek.data.db.BrandDao = db.brandDao()
    @Provides fun provideThumbnailDao(db: MediathekDatabase): ThumbnailDao = db.thumbnailDao()
}
