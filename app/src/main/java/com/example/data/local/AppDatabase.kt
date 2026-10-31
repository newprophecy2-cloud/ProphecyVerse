package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AnimeEntry::class,
        AnimeAlias::class,
        CatalogMapping::class,
        SeasonEntry::class,
        EpisodeEntry::class,
        SearchQuery::class,
        SearchResultRef::class,
        WatchlistEntry::class,
        PlaybackHistory::class,
        UserPreference::class,
        DownloadEntry::class,
        LocalProfile::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun searchDao(): SearchDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun downloadDao(): DownloadDao
    abstract fun localProfileDao(): LocalProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_history ADD COLUMN animeTitle TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE playback_history ADD COLUMN posterUrl TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE playback_history ADD COLUMN mediaType TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE anime_entries ADD COLUMN mediaCategory TEXT NOT NULL DEFAULT 'ANIME'")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `download_entries` (
                        `id` TEXT NOT NULL,
                        `animeId` TEXT NOT NULL,
                        `animeTitle` TEXT NOT NULL,
                        `posterUrl` TEXT,
                        `seasonNumber` INTEGER NOT NULL,
                        `episodeNumber` INTEGER NOT NULL,
                        `episodeTitle` TEXT,
                        `localFilePath` TEXT,
                        `downloadStatus` TEXT NOT NULL,
                        `downloadedBytes` INTEGER NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `downloadUrl` TEXT,
                        `mediaType` TEXT,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_entries_animeId` ON `download_entries` (`animeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_entries_downloadStatus` ON `download_entries` (`downloadStatus`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `local_profiles` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `avatarId` TEXT NOT NULL,
                        `bio` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "prophecyverse.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
