package com.example

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.CloudHistoryRepository
import com.example.data.firebase.FirestoreFailureCategory
import com.example.data.firebase.FirestoreOperationException
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadEntry
import com.example.data.local.EpisodeEntry
import com.example.data.local.PlaybackHistory
import com.example.data.local.SeasonEntry
import com.example.data.local.StreamSourceEntity
import com.example.data.local.WatchlistEntry
import com.example.data.remote.AniListData
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLResponse
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.repository.ExpiryState
import com.example.data.repository.SourceExpiryTime
import com.example.data.streaming.DiscoveryStatus
import com.example.data.streaming.FirestoreStreamingRepository
import com.example.data.streaming.StreamingCloudStore
import com.example.player.PlayerManager
import com.example.ui.navigation.Screen
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepairV3RootCauseVerificationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            runCatching { FirebaseApp.initializeApp(context) }
        }
    }

    @Test
    fun mainActivity_launchesWithoutNavArgumentCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val activity = controller.get()
        assertNotNull(activity)
        assertFalse("MainActivity must not close immediately on launch", activity.isFinishing)
        assertFalse(activity.isDestroyed)
        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        controller.pause().stop().destroy()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun navigationRoutes_singleEncodeSpecialCharacters() {
        val detailRoute = Screen.Detail.createRoute("anilist/195?foo=bar", seasonNumber = 2)
        assertEquals("detail/anilist%2F195%3Ffoo%3Dbar?seasonNumber=2", detailRoute)

        val playerRoute = Screen.Player.createRoute("anime/1", "ep/2", "prov/src 3")
        assertEquals("player/anime%2F1/ep%2F2?preferredSourceId=prov%2Fsrc%203", playerRoute)
    }

    @Test
    fun roomMigration_fromVersion1And2And4And9And10To11_validatesSchemaAndPreservesData() = runTest {
        verifyFullRoomMigration("test_v1_to_v11.db", 1) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `anime_entries` (
                    `id` TEXT NOT NULL, `aniListId` INTEGER, `backendAnimeId` TEXT,
                    `titleRomaji` TEXT, `titleEnglish` TEXT, `titleNative` TEXT,
                    `preferredTitle` TEXT NOT NULL, `synopsis` TEXT, `year` TEXT,
                    `season` TEXT, `format` TEXT, `status` TEXT, `genres` TEXT NOT NULL,
                    `score` REAL, `episodeCount` INTEGER, `duration` INTEGER,
                    `studios` TEXT, `posterUrl` TEXT, `bannerUrl` TEXT,
                    `isPlayable` INTEGER NOT NULL, `isTrending` INTEGER NOT NULL,
                    `isSeasonal` INTEGER NOT NULL, `isPopular` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_aniListId` ON `anime_entries` (`aniListId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_backendAnimeId` ON `anime_entries` (`backendAnimeId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_isTrending` ON `anime_entries` (`isTrending`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_isSeasonal` ON `anime_entries` (`isSeasonal`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_isPopular` ON `anime_entries` (`isPopular`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_isPlayable` ON `anime_entries` (`isPlayable`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `anime_aliases` (`aliasId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `animeId` TEXT NOT NULL, `alias` TEXT NOT NULL, `originalAlias` TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_aliases_animeId` ON `anime_aliases` (`animeId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_aliases_alias` ON `anime_aliases` (`alias`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_mappings` (`aniListId` INTEGER NOT NULL, `backendAnimeId` TEXT NOT NULL, `confidence` REAL NOT NULL, `mappedAt` INTEGER NOT NULL, PRIMARY KEY(`aniListId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_mappings_backendAnimeId` ON `catalog_mappings` (`backendAnimeId`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `season_entries` (`id` TEXT NOT NULL, `animeId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `title` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_season_entries_animeId` ON `season_entries` (`animeId`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `episode_entries` (`id` TEXT NOT NULL, `animeId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL, `title` TEXT, `overview` TEXT, `isAvailable` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_entries_animeId` ON `episode_entries` (`animeId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_entries_animeId_seasonNumber` ON `episode_entries` (`animeId`, `seasonNumber`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `search_queries` (`query` TEXT NOT NULL, `totalResults` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`query`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `search_result_refs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `normalizedQuery` TEXT NOT NULL, `animeId` TEXT NOT NULL, `rankIndex` INTEGER NOT NULL, `fetchTime` INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_result_refs_normalizedQuery` ON `search_result_refs` (`normalizedQuery`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_result_refs_animeId` ON `search_result_refs` (`animeId`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `watchlist_entries` (`animeId` TEXT NOT NULL, `status` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `userScore` INTEGER, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`animeId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `playback_history` (`id` TEXT NOT NULL, `animeId` TEXT NOT NULL, `episodeId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL, `episodeTitle` TEXT, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_animeId` ON `playback_history` (`animeId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_lastPlayedAt` ON `playback_history` (`lastPlayedAt`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `user_preferences` (`prefKey` TEXT NOT NULL, `prefValue` TEXT NOT NULL, PRIMARY KEY(`prefKey`))")

            db.execSQL("INSERT INTO anime_entries VALUES ('anilist_1', 1, 'b1', 'Cowboy Bebop', 'Cowboy Bebop', NULL, 'Cowboy Bebop', 'Space', '1998', 'SPRING', 'TV', 'FINISHED', 'Action', 8.9, 26, 24, 'Sunrise', 'https://p/1.jpg', NULL, 1, 1, 0, 1, 1000)")
            db.execSQL("INSERT INTO watchlist_entries VALUES ('anilist_1', 'WATCHING', 1, NULL, 1000, 2000)")
            db.execSQL("INSERT INTO playback_history VALUES ('h1', 'anilist_1', 'ep1', 1, 1, 'Asteroid Blues', 50000, 1440000, 0, 2500)")
        }

        verifyFullRoomMigration("test_v10_to_v11.db", 10) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `anime_entries` (
                    `id` TEXT NOT NULL, `aniListId` INTEGER, `backendAnimeId` TEXT,
                    `titleRomaji` TEXT, `titleEnglish` TEXT, `titleNative` TEXT,
                    `preferredTitle` TEXT NOT NULL, `synopsis` TEXT, `year` TEXT,
                    `season` TEXT, `format` TEXT, `status` TEXT, `genres` TEXT NOT NULL,
                    `score` REAL, `episodeCount` INTEGER, `duration` INTEGER,
                    `studios` TEXT, `posterUrl` TEXT, `bannerUrl` TEXT,
                    `isPlayable` INTEGER NOT NULL, `isTrending` INTEGER NOT NULL,
                    `isSeasonal` INTEGER NOT NULL, `isPopular` INTEGER NOT NULL,
                    `mediaCategory` TEXT NOT NULL DEFAULT 'ANIME',
                    `metadataCompleteness` TEXT NOT NULL DEFAULT 'UNKNOWN',
                    `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_aniListId` ON `anime_entries` (`aniListId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_backendAnimeId` ON `anime_entries` (`backendAnimeId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_isTrending` ON `anime_entries` (`isTrending`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_isSeasonal` ON `anime_entries` (`isSeasonal`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_isPopular` ON `anime_entries` (`isPopular`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_entries_isPlayable` ON `anime_entries` (`isPlayable`)")

            db.execSQL("CREATE TABLE IF NOT EXISTS `anime_aliases` (`aliasId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `animeId` TEXT NOT NULL, `alias` TEXT NOT NULL, `originalAlias` TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_aliases_animeId` ON `anime_aliases` (`animeId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_anime_aliases_alias` ON `anime_aliases` (`alias`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_mappings` (`aniListId` INTEGER NOT NULL, `backendAnimeId` TEXT NOT NULL, `confidence` REAL NOT NULL, `mappedAt` INTEGER NOT NULL, PRIMARY KEY(`aniListId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_mappings_backendAnimeId` ON `catalog_mappings` (`backendAnimeId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `season_entries` (`id` TEXT NOT NULL, `animeId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `backendSeasonId` TEXT, `title` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_season_entries_animeId` ON `season_entries` (`animeId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `episode_entries` (`id` TEXT NOT NULL, `animeId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL, `title` TEXT, `overview` TEXT, `isAvailable` INTEGER NOT NULL, `sourceEpisodeIds` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_entries_animeId` ON `episode_entries` (`animeId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_entries_animeId_seasonNumber` ON `episode_entries` (`animeId`, `seasonNumber`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `search_queries` (`query` TEXT NOT NULL, `totalResults` INTEGER NOT NULL, `currentPage` INTEGER NOT NULL DEFAULT 1, `hasNextPage` INTEGER NOT NULL DEFAULT 0, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`query`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `search_result_refs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `normalizedQuery` TEXT NOT NULL, `animeId` TEXT NOT NULL, `rankIndex` INTEGER NOT NULL, `fetchTime` INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_result_refs_normalizedQuery` ON `search_result_refs` (`normalizedQuery`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_result_refs_animeId` ON `search_result_refs` (`animeId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `watchlist_entries` (`animeId` TEXT NOT NULL, `status` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `userScore` INTEGER, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`animeId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `playback_history` (`id` TEXT NOT NULL, `animeId` TEXT NOT NULL, `episodeId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL, `episodeTitle` TEXT, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `animeTitle` TEXT DEFAULT NULL, `posterUrl` TEXT DEFAULT NULL, `mediaType` TEXT DEFAULT NULL, `lastPlayedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_animeId` ON `playback_history` (`animeId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_lastPlayedAt` ON `playback_history` (`lastPlayedAt`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playback_history_animeId_episodeId` ON `playback_history` (`animeId`, `episodeId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `user_preferences` (`prefKey` TEXT NOT NULL, `prefValue` TEXT NOT NULL, PRIMARY KEY(`prefKey`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `download_entries` (`id` TEXT NOT NULL, `animeId` TEXT NOT NULL, `animeTitle` TEXT NOT NULL, `posterUrl` TEXT, `seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL, `episodeTitle` TEXT, `localFilePath` TEXT, `downloadStatus` TEXT NOT NULL, `downloadedBytes` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, `downloadUrl` TEXT, `mediaType` TEXT, `errorMessage` TEXT, `quality` TEXT DEFAULT NULL, `audioLanguage` TEXT DEFAULT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_entries_animeId` ON `download_entries` (`animeId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_entries_downloadStatus` ON `download_entries` (`downloadStatus`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `local_profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `avatarId` TEXT NOT NULL, `bio` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `episode_source_snapshots` (`cacheKey` TEXT NOT NULL, `animeId` TEXT NOT NULL, `seasonNumber` INTEGER, `episodeId` TEXT NOT NULL, `episodeNumber` INTEGER, `state` TEXT NOT NULL, `backendStatus` TEXT, `fetchedAtMs` INTEGER NOT NULL, `apiCandidateCount` INTEGER NOT NULL, `expiredCandidateCount` INTEGER NOT NULL, `exactDuplicateCount` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_source_snapshots_animeId` ON `episode_source_snapshots` (`animeId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_source_snapshots_fetchedAtMs` ON `episode_source_snapshots` (`fetchedAtMs`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `episode_source_candidates` (`candidateKey` TEXT NOT NULL, `cacheKey` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `providerLabel` TEXT, `quality` TEXT, `codec` TEXT, `audioLanguage` TEXT, `sizeBytes` INTEGER, `downloadable` INTEGER, `expiresAt` TEXT, PRIMARY KEY(`candidateKey`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episode_source_candidates_cacheKey` ON `episode_source_candidates` (`cacheKey`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `shared_catalog_cache` (`documentId` TEXT NOT NULL, `contentType` TEXT NOT NULL, `canonicalId` TEXT, `backendId` TEXT, `schemaVersion` INTEGER NOT NULL, `metadataHash` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `lastSyncedAt` INTEGER, PRIMARY KEY(`documentId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_catalog_cache_contentType` ON `shared_catalog_cache` (`contentType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_catalog_cache_backendId` ON `shared_catalog_cache` (`backendId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `shared_catalog_pending` (`documentId` TEXT NOT NULL, `collectionName` TEXT NOT NULL, `contentType` TEXT NOT NULL, `metadataHash` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `queuedAt` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, `lastErrorCategory` TEXT, `state` TEXT NOT NULL, PRIMARY KEY(`documentId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_catalog_pending_state` ON `shared_catalog_pending` (`state`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_shared_catalog_pending_nextAttemptAt` ON `shared_catalog_pending` (`nextAttemptAt`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `stream_containers` (`scopeKey` TEXT NOT NULL, `contentKey` TEXT NOT NULL, `anilistId` INTEGER, `contentType` TEXT NOT NULL, `backendId` TEXT, `seasonCount` INTEGER, `totalEpisodeCount` INTEGER, `providerCount` INTEGER NOT NULL, `sourceCount` INTEGER NOT NULL, `discoveryStatus` TEXT NOT NULL, `version` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`scopeKey`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_containers_contentKey` ON `stream_containers` (`contentKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_containers_anilistId` ON `stream_containers` (`anilistId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_containers_backendId` ON `stream_containers` (`backendId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_containers_contentType` ON `stream_containers` (`contentType`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `stream_seasons` (`seasonDocKey` TEXT NOT NULL, `contentKey` TEXT NOT NULL, `seasonId` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `backendSeasonId` TEXT, `episodeCount` INTEGER NOT NULL, `discoveryStatus` TEXT NOT NULL, `version` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`seasonDocKey`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_seasons_contentKey` ON `stream_seasons` (`contentKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_seasons_contentKey_seasonNumber` ON `stream_seasons` (`contentKey`, `seasonNumber`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `stream_episodes` (`episodeDocKey` TEXT NOT NULL, `contentKey` TEXT NOT NULL, `seasonId` TEXT NOT NULL, `episodeId` TEXT NOT NULL, `catalogEpisodeId` TEXT, `seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL, `backendEpisodeId` TEXT, `backendSourceEpisodeIds` TEXT, `providerCount` INTEGER NOT NULL, `sourceCount` INTEGER NOT NULL, `discoveryStatus` TEXT NOT NULL, `version` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`episodeDocKey`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_episodes_contentKey` ON `stream_episodes` (`contentKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_episodes_contentKey_seasonNumber_episodeNumber` ON `stream_episodes` (`contentKey`, `seasonNumber`, `episodeNumber`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_episodes_catalogEpisodeId` ON `stream_episodes` (`catalogEpisodeId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `stream_providers` (`providerDocKey` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `providerId` TEXT NOT NULL, `providerName` TEXT NOT NULL, `status` TEXT NOT NULL, `sourceCount` INTEGER NOT NULL, `version` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`providerDocKey`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_providers_scopeKey` ON `stream_providers` (`scopeKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_providers_providerId` ON `stream_providers` (`providerId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `stream_sources` (`sourceDocKey` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, `providerId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `sourceName` TEXT NOT NULL, `backendEpisodeId` TEXT, `streamUrl` TEXT, `urlType` TEXT NOT NULL, `contentType` TEXT, `quality` TEXT, `codec` TEXT, `audioLanguage` TEXT, `downloadable` INTEGER, `sizeBytes` INTEGER, `safeHeadersJson` TEXT, `expiresAt` TEXT, `status` TEXT NOT NULL, `version` INTEGER NOT NULL, `lastVerifiedAt` INTEGER, `lastPlaybackSuccessAt` INTEGER, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`sourceDocKey`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_sources_scopeKey` ON `stream_sources` (`scopeKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_sources_providerId` ON `stream_sources` (`providerId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_sources_sourceId` ON `stream_sources` (`sourceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_sources_status` ON `stream_sources` (`status`)")

            db.execSQL("INSERT INTO anime_entries VALUES ('anilist_1', 1, 'b1', 'Cowboy Bebop', 'Cowboy Bebop', NULL, 'Cowboy Bebop', 'Space', '1998', 'SPRING', 'TV', 'FINISHED', 'Action', 8.9, 26, 24, 'Sunrise', 'https://p/1.jpg', NULL, 1, 1, 0, 1, 'ANIME', 'COMPLETE', 1000)")
            db.execSQL("INSERT INTO watchlist_entries VALUES ('anilist_1', 'WATCHING', 1, NULL, 1000, 2000)")
            db.execSQL("INSERT INTO playback_history VALUES ('history_anilist_1_ep1', 'anilist_1', 'ep1', 1, 1, 'Asteroid Blues', 50000, 1440000, 0, 'Cowboy Bebop', 'https://p/1.jpg', 'TV', 2500)")
        }
    }

    private suspend fun verifyFullRoomMigration(
        dbName: String,
        startVersion: Int,
        seedSchema: (SupportSQLiteDatabase) -> Unit
    ) {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.delete()
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(startVersion) {
                    override fun onCreate(db: SupportSQLiteDatabase) = seedSchema(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11
            )
            .build()
        try {
            val anime = migrated.animeDao().getAnimeById("anilist_1")
            assertNotNull("Migrated anime must survive v$startVersion -> v11", anime)
            assertEquals("Cowboy Bebop", anime!!.preferredTitle)

            val watchlist = migrated.watchlistDao().getWatchlistEntry("anilist_1")
            assertNotNull("Migrated watchlist must survive v$startVersion -> v11", watchlist)
            assertTrue(watchlist!!.isFavorite)

            val history = migrated.playbackHistoryDao().getHistoryForEpisode("anilist_1", "ep1")
            assertNotNull("Migrated history must survive v$startVersion -> v11", history)
            assertEquals(50000L, history!!.positionMs)
        } finally {
            migrated.close()
            dbFile.delete()
        }
    }

    @Test
    fun canonicalAliasMigration_preservesAllEpisodesHistoryAndDownloads() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = createStubRepo(db)
            val aliasId = "anilist_195604"
            val canonicalId = "anilist_97940"

            db.animeDao().upsertAnime(
                AnimeEntry(id = canonicalId, aniListId = 97940, preferredTitle = "Black Clover", isTrending = true)
            )
            db.animeDao().upsertAnime(
                AnimeEntry(
                    id = aliasId,
                    aniListId = 195604,
                    preferredTitle = "Black Clover Season 2",
                    canonicalAnimeId = 97940,
                    canonicalTitle = "Black Clover",
                    canonicalSeasonNumber = 2,
                    isPopular = true
                )
            )
            db.episodeDao().upsertSeasons(listOf(SeasonEntry("${aliasId}_s2", aliasId, 2)))
            db.episodeDao().upsertEpisodes(
                (1..3).map { ep -> EpisodeEntry("ep_bc_s2_$ep", aliasId, 2, ep, "Ep $ep") }
            )
            db.watchlistDao().upsertWatchlistEntry(WatchlistEntry(aliasId, "WATCHING", isFavorite = true, updatedAt = 100L))
            db.playbackHistoryDao().upsertHistories(
                (1..3).map { ep ->
                    PlaybackHistory(
                        id = "history_${aliasId}_ep_bc_s2_$ep",
                        animeId = aliasId,
                        episodeId = "ep_bc_s2_$ep",
                        seasonNumber = 2,
                        episodeNumber = ep,
                        episodeTitle = "Ep $ep",
                        positionMs = ep * 10_000L,
                        durationMs = 1_400_000L,
                        completed = ep == 1,
                        animeTitle = "Black Clover",
                        lastPlayedAt = ep * 1000L
                    )
                }
            )
            db.downloadDao().upsertDownload(
                DownloadEntry(
                    id = "ep_bc_s2_1",
                    animeId = aliasId,
                    animeTitle = "Black Clover",
                    seasonNumber = 2,
                    episodeNumber = 1,
                    downloadStatus = "COMPLETED"
                )
            )

            val route = repo.resolveCanonicalRoute(aliasId, 2)
            assertEquals(canonicalId, route.animeId)

            val migratedHistory = db.playbackHistoryDao().getAllHistoryForAnime(canonicalId)
            assertEquals("ALL 3 episode history rows must migrate to canonical anime", 3, migratedHistory.size)
            assertTrue(db.playbackHistoryDao().getAllHistoryForAnime(aliasId).isEmpty())

            val migratedWatchlist = db.watchlistDao().getWatchlistEntry(canonicalId)
            assertNotNull(migratedWatchlist)
            assertTrue(migratedWatchlist!!.isFavorite)
            assertNull(db.watchlistDao().getWatchlistEntry(aliasId))

            val migratedDownload = db.downloadDao().getDownload("ep_bc_s2_1")
            assertEquals(canonicalId, migratedDownload?.animeId)

            val migratedEpisodes = db.episodeDao().getEpisodesForAnime(canonicalId)
            assertEquals(3, migratedEpisodes.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun watchlistFavoriteAndHistoryMetadata_updateAndMergeAccurately() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = createStubRepo(db)
            val animeId = "anilist_21"
            db.animeDao().upsertAnime(
                AnimeEntry(
                    id = animeId,
                    aniListId = 21,
                    preferredTitle = "One Piece",
                    posterUrl = "https://img/op.jpg",
                    format = "TV"
                )
            )

            // Watchlist toggle: add -> change status -> remove (returns null)
            val added = repo.toggleWatchlist(animeId, "PLANNING")
            assertNotNull(added)
            assertEquals("PLANNING", added!!.status)

            val fav = repo.toggleFavorite(animeId)
            assertTrue(fav.isFavorite)

            val changed = repo.toggleWatchlist(animeId, "WATCHING")
            assertNotNull(changed)
            assertEquals("WATCHING", changed!!.status)
            assertTrue("Changing watchlist status must preserve favorite", changed.isFavorite)

            val removed = repo.toggleWatchlist(animeId, "WATCHING")
            assertNull("Re-tapping active status must remove watchlist entry", removed)
            assertNull(db.watchlistDao().getWatchlistEntry(animeId))

            // History update preserves metadata and monotonic completed flag
            repo.updatePlaybackProgress(
                animeId = animeId,
                episodeId = "ep_1",
                seasonNumber = 1,
                episodeNumber = 1,
                episodeTitle = "Romance Dawn",
                positionMs = 1_350_000L,
                durationMs = 1_400_000L,
                animeTitle = "One Piece",
                posterUrl = "https://img/op.jpg",
                mediaType = "TV"
            )
            // Re-seeking backward must keep completed=true and metadata intact
            repo.updatePlaybackProgress(
                animeId = animeId,
                episodeId = "ep_1",
                seasonNumber = 1,
                episodeNumber = 1,
                episodeTitle = null,
                positionMs = 100_000L,
                durationMs = 1_400_000L,
                animeTitle = null,
                posterUrl = null,
                mediaType = null
            )
            val savedHistory = db.playbackHistoryDao().getHistoryForEpisode(animeId, "ep_1")!!
            assertTrue("Completed flag must remain monotonic", savedHistory.completed)
            assertEquals("One Piece", savedHistory.animeTitle)
            assertEquals("https://img/op.jpg", savedHistory.posterUrl)
            assertEquals("TV", savedHistory.mediaType)
            assertEquals("Romance Dawn", savedHistory.episodeTitle)

            // Merge helper timestamp & metadata preservation
            val cloudWithoutMetadata = savedHistory.copy(
                animeTitle = null,
                posterUrl = null,
                mediaType = null,
                completed = false,
                lastPlayedAt = savedHistory.lastPlayedAt + 1000L
            )
            val merged = CloudHistoryRepository.mergePlaybackHistories(savedHistory, cloudWithoutMetadata)
            assertEquals("One Piece", merged.animeTitle)
            assertEquals("https://img/op.jpg", merged.posterUrl)
            assertTrue(merged.completed)

            // Watchlist bidirectional merge plan keeps newer local over older cloud and newer cloud over older local
            val plan = CloudHistoryRepository.computeWatchlistMergePlan(
                localEntries = listOf(
                    WatchlistEntry("guest_only", "WATCHING", false, updatedAt = 100L),
                    WatchlistEntry("shared_newer_local", "COMPLETED", true, updatedAt = 500L),
                    WatchlistEntry("shared_older_local", "PLANNING", false, updatedAt = 100L)
                ),
                cloudEntries = listOf(
                    WatchlistEntry("shared_newer_local", "WATCHING", false, updatedAt = 200L),
                    WatchlistEntry("shared_older_local", "COMPLETED", true, updatedAt = 400L),
                    WatchlistEntry("cloud_only", "WATCHING", true, updatedAt = 300L)
                )
            )
            assertEquals(setOf("shared_older_local", "cloud_only"), plan.toUpsertLocal.map { it.animeId }.toSet())
            assertEquals(setOf("guest_only", "shared_newer_local"), plan.toPushCloud.map { it.animeId }.toSet())
        } finally {
            db.close()
        }
    }

    @Test
    fun streamingContracts_expiryEvaluation_readOutcome_andOutboxFlushWorkEndToEnd() = runTest {
        assertEquals(ExpiryState.MISSING, SourceExpiryTime.evaluateExpiry(null))
        assertEquals(ExpiryState.MISSING, SourceExpiryTime.evaluateExpiry("  "))
        assertEquals(ExpiryState.INVALID, SourceExpiryTime.evaluateExpiry("not-a-timestamp"))
        assertEquals(ExpiryState.EXPIRED, SourceExpiryTime.evaluateExpiry("2020-01-01T00:00:00Z"))
        assertEquals(ExpiryState.VALID, SourceExpiryTime.evaluateExpiry("2099-01-01T00:00:00Z"))
        assertFalse(SourceExpiryTime.isExpiredOrInvalid(null))
        assertTrue(SourceExpiryTime.isExpiredOrInvalid("not-a-timestamp"))
        assertTrue(SourceExpiryTime.isExpiredOrInvalid("2020-01-01T00:00:00Z"))
        assertFalse(SourceExpiryTime.isExpiredOrInvalid("2099-01-01T00:00:00Z"))

        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val docs = ConcurrentHashMap<String, LinkedHashMap<String, Any?>>()
            var authorized = false
            var simulatePermissionDeniedOnRead = false
            val fakeStore = object : StreamingCloudStore {
                override suspend fun getDoc(operation: String, path: String, preferCache: Boolean): StreamingCloudStore.RemoteSnapshot? {
                    if (simulatePermissionDeniedOnRead) {
                        throw FirestoreOperationException(
                            FirestoreFailureCategory.PERMISSION_DENIED, operation, 0L,
                            FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
                        )
                    }
                    val d = docs[path] ?: return StreamingCloudStore.RemoteSnapshot(path.substringAfterLast('/'), path, false)
                    return StreamingCloudStore.RemoteSnapshot(path.substringAfterLast('/'), path, true, d)
                }

                override suspend fun listCollection(operation: String, collectionPath: String, preferCache: Boolean): List<StreamingCloudStore.RemoteSnapshot> {
                    if (simulatePermissionDeniedOnRead) {
                        throw FirestoreOperationException(
                            FirestoreFailureCategory.PERMISSION_DENIED, operation, 0L,
                            FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
                        )
                    }
                    val prefix = "$collectionPath/"
                    return docs.entries
                        .filter { it.key.startsWith(prefix) && !it.key.removePrefix(prefix).contains('/') }
                        .map { StreamingCloudStore.RemoteSnapshot(it.key.substringAfterLast('/'), it.key, true, it.value) }
                }

                override suspend fun commitBatch(operation: String, writes: List<StreamingCloudStore.DocWrite>) {
                    writes.forEach { w -> docs[w.path] = LinkedHashMap(w.data) }
                }

                override suspend fun mergeDoc(operation: String, path: String, data: Map<String, Any?>) {
                    val m = docs.getOrPut(path) { LinkedHashMap() }
                    m.putAll(data)
                }

                override suspend fun <T> runTx(operation: String, readPath: String, block: (StreamingCloudStore.RemoteSnapshot?) -> Pair<T, List<StreamingCloudStore.DocWrite>>): T {
                    val (res, writes) = block(getDoc(operation, readPath, false))
                    writes.forEach { w -> docs[w.path] = LinkedHashMap(w.data) }
                    return res
                }

                override fun observeDoc(path: String, onUpdate: (StreamingCloudStore.RemoteSnapshot?) -> Unit, onError: (Throwable) -> Unit) = {}
                override fun observeCollection(collectionPath: String, onUpdate: (List<StreamingCloudStore.RemoteSnapshot>) -> Unit, onError: (Throwable) -> Unit) = {}
            }

            val streamingRepo = FirestoreStreamingRepository(
                streamCacheDao = db.streamCacheDao(),
                firestoreProvider = { null },
                isClientWriteAuthorized = { authorized },
                cloudStoreProvider = { fakeStore }
            )

            // Queue write while unauthenticated
            val src = StreamSourceEntity(
                sourceDocKey = "anime:999:s1:e1|p|prov1|s|src1",
                scopeKey = "anime:999:s1:e1",
                providerId = "prov1",
                sourceId = "src1",
                sourceName = "Provider 1",
                streamUrl = "https://cdn.example/video.mp4"
            )
            val publishedWhileUnauth = streamingRepo.publishResolvedStreamUrl(
                sourceEntity = src,
                contentKey = "999",
                isMovie = false,
                seasonNumber = 1,
                episodeNumber = 1,
                streamUrl = "https://cdn.example/video.mp4",
                urlType = "direct",
                contentType = "video/mp4",
                safeHeaders = emptyList(),
                expiresAt = "2099-01-01T00:00:00Z",
                canonicalTitle = "Test Anime"
            )
            assertFalse(publishedWhileUnauth)
            assertEquals(
                FirestoreStreamingRepository.WriteOutcomeState.DEFERRED_UNAUTHENTICATED,
                streamingRepo.lastWriteOutcome?.state
            )
            assertEquals(1, streamingRepo.pendingOutboxCount())

            // Authorize and flush outbox
            authorized = true
            val flushed = streamingRepo.flushPendingOutbox()
            assertEquals(1, flushed)
            assertEquals(0, streamingRepo.pendingOutboxCount())
            assertEquals(
                FirestoreStreamingRepository.WriteOutcomeState.SUCCEEDED,
                streamingRepo.lastWriteOutcome?.state
            )

            val hit = streamingRepo.fetchAndSyncScopeWithOutcome("999", isMovie = false, seasonNumber = 1, episodeNumber = 1)
            assertEquals(FirestoreStreamingRepository.ScopeReadStatus.HIT, hit.status)
            assertFalse(hit.isHardError)

            simulatePermissionDeniedOnRead = true
            val denied = streamingRepo.fetchAndSyncScopeWithOutcome("999", isMovie = false, seasonNumber = 1, episodeNumber = 1)
            assertEquals(FirestoreStreamingRepository.ScopeReadStatus.PERMISSION_DENIED, denied.status)
            assertTrue(denied.isHardError)
        } finally {
            db.close()
        }

        // Player diagnostics default contract check
        val diagnostics = PlayerManager.PlaybackDiagnostics(
            sourceQuality = "1080p",
            resolveDurationMs = 123L
        )
        assertEquals("1080p", diagnostics.sourceQuality)
        assertEquals(123L, diagnostics.resolveDurationMs)
    }

    private fun createStubRepo(db: AppDatabase): AnimeRepository {
        val aniList = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
                Response.success(AniListGraphQLResponse(AniListData(null)))
        }
        val prophecyApi = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse(emptyList()))
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = animeId, title = ""))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(emptyList<ProphecyEpisodeDto>())
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(ProphecyEpisodeFullResponse())
            override suspend fun resolveDirectPlay(request: com.example.data.remote.DirectPlayRequest) =
                Response.success(com.example.data.remote.DirectPlayResponse(false, null))
        }
        return AnimeRepository(db, prophecyApi, aniList, context)
    }
}
