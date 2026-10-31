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
    version = 4,
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Safely migrate playback_history to have exactly 1 record per anime/movie (id = animeId)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `playback_history_new` (
                        `id` TEXT NOT NULL,
                        `animeId` TEXT NOT NULL,
                        `episodeId` TEXT NOT NULL,
                        `seasonNumber` INTEGER NOT NULL,
                        `episodeNumber` INTEGER NOT NULL,
                        `episodeTitle` TEXT,
                        `positionMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `completed` INTEGER NOT NULL,
                        `animeTitle` TEXT,
                        `posterUrl` TEXT,
                        `mediaType` TEXT,
                        `lastPlayedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                // Deduplicate: preserve the latest watched episode per animeId
                db.execSQL("""
                    INSERT OR REPLACE INTO `playback_history_new`
                    SELECT h.animeId AS id, h.animeId, h.episodeId, h.seasonNumber, h.episodeNumber,
                           h.episodeTitle, h.positionMs, h.durationMs, h.completed, h.animeTitle,
                           h.posterUrl, h.mediaType, h.lastPlayedAt
                    FROM `playback_history` h
                    INNER JOIN (
                        SELECT animeId, MAX(lastPlayedAt) AS maxPlayedAt
                        FROM `playback_history`
                        GROUP BY animeId
                    ) latest ON h.animeId = latest.animeId AND h.lastPlayedAt = latest.maxPlayedAt
                """.trimIndent())

                db.execSQL("DROP TABLE IF EXISTS `playback_history`")
                db.execSQL("ALTER TABLE `playback_history_new` RENAME TO `playback_history`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_animeId` ON `playback_history` (`animeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_lastPlayedAt` ON `playback_history` (`lastPlayedAt`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_entries` ADD COLUMN `quality` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `download_entries` ADD COLUMN `audioLanguage` TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "prophecyverse.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
