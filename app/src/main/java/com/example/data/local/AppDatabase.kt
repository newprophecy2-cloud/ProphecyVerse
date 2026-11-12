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
        LocalProfile::class,
        EpisodeSourceSnapshotRecord::class,
        EpisodeSourceCandidateRecord::class,
        SharedCatalogCacheEntry::class,
        SharedCatalogPendingEntry::class
    ],
    version = 9,
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
    abstract fun sharedCatalogDao(): SharedCatalogDao

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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove legacy duplicates before adding the database-level guard.
                db.execSQL("""
                    DELETE FROM playback_history
                    WHERE rowid IN (
                        SELECT old.rowid FROM playback_history AS old
                        WHERE EXISTS (
                            SELECT 1 FROM playback_history AS newer
                            WHERE newer.animeId = old.animeId
                              AND newer.episodeId = old.episodeId
                              AND (newer.lastPlayedAt > old.lastPlayedAt
                                   OR (newer.lastPlayedAt = old.lastPlayedAt AND newer.rowid > old.rowid))
                        )
                    )
                """.trimIndent())
                // Rekey legacy rows (older builds used animeId for most anime).
                db.execSQL("""
                    UPDATE playback_history
                    SET id = 'history_' || animeId || '_' || episodeId
                """.trimIndent())
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_playback_history_animeId_episodeId`
                    ON `playback_history` (`animeId`, `episodeId`)
                """.trimIndent())
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `episode_source_snapshots` (
                        `cacheKey` TEXT NOT NULL,
                        `animeId` TEXT NOT NULL,
                        `seasonNumber` INTEGER,
                        `episodeId` TEXT NOT NULL,
                        `episodeNumber` INTEGER,
                        `state` TEXT NOT NULL,
                        `backendStatus` TEXT,
                        `fetchedAtMs` INTEGER NOT NULL,
                        `apiCandidateCount` INTEGER NOT NULL,
                        `expiredCandidateCount` INTEGER NOT NULL,
                        `exactDuplicateCount` INTEGER NOT NULL,
                        PRIMARY KEY(`cacheKey`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_source_snapshots_animeId` ON `episode_source_snapshots` (`animeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_source_snapshots_fetchedAtMs` ON `episode_source_snapshots` (`fetchedAtMs`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `episode_source_candidates` (
                        `candidateKey` TEXT NOT NULL,
                        `cacheKey` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `providerLabel` TEXT,
                        `quality` TEXT,
                        `codec` TEXT,
                        `audioLanguage` TEXT,
                        `sizeBytes` INTEGER,
                        `downloadable` INTEGER,
                        `expiresAt` TEXT,
                        PRIMARY KEY(`candidateKey`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_source_candidates_cacheKey` ON `episode_source_candidates` (`cacheKey`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `season_entries` ADD COLUMN `backendSeasonId` TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `shared_catalog_cache` (`documentId` TEXT NOT NULL, `contentType` TEXT NOT NULL, `canonicalId` TEXT, `backendId` TEXT, `schemaVersion` INTEGER NOT NULL, `metadataHash` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `lastSyncedAt` INTEGER, PRIMARY KEY(`documentId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_catalog_cache_contentType` ON `shared_catalog_cache` (`contentType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_catalog_cache_backendId` ON `shared_catalog_cache` (`backendId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `shared_catalog_pending` (`documentId` TEXT NOT NULL, `collectionName` TEXT NOT NULL, `contentType` TEXT NOT NULL, `metadataHash` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `queuedAt` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, `lastErrorCategory` TEXT, `state` TEXT NOT NULL, PRIMARY KEY(`documentId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_catalog_pending_state` ON `shared_catalog_pending` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_catalog_pending_nextAttemptAt` ON `shared_catalog_pending` (`nextAttemptAt`)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `episode_entries` ADD COLUMN `sourceEpisodeIds` TEXT")
            }
        }
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "prophecyverse.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
