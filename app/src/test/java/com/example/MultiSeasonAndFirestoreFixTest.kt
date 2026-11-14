package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.FirestoreFailureCategory
import com.example.data.firebase.FirestoreOperationException
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.PlaybackHistory
import com.example.data.local.SeasonEntry
import com.example.data.local.EpisodeEntry
import com.example.data.local.WatchlistEntry
import com.example.data.remote.AniListAiringScheduleNode
import com.example.data.remote.AniListData
import com.example.data.remote.AniListFuzzyDate
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLResponse
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.AniListMedia
import com.example.data.remote.AniListRelatedMediaNode
import com.example.data.remote.AniListRelationEdge
import com.example.data.remote.AniListRelationsConnection
import com.example.data.remote.AniListTitle
import com.example.data.remote.DirectPlayData
import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.DirectPlayResponse
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecyProviderMappingDto
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecySearchResultItem
import com.example.data.remote.ProphecySeasonDto
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.streaming.ContentIdentityHelper
import com.example.data.streaming.FirestoreStreamingRepository
import com.example.data.streaming.FirestoreWriteOperation
import com.example.data.streaming.MonotonicMetrics
import com.example.data.streaming.StreamingCloudStore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MultiSeasonAndFirestoreFixTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        MonotonicMetrics.clear()
    }

    @After
    fun teardown() {
        database.close()
    }

    /**
     * Stateful in-memory cloud store faithfully enforcing Firestore document/collection hierarchy,
     * server merges, atomic transactions, and optional PERMISSION_DENIED simulation.
     */
    private class InMemoryCloudStore : StreamingCloudStore {
        val docs = ConcurrentHashMap<String, LinkedHashMap<String, Any?>>()
        var simulatedWriteFailure: Throwable? = null
        var writeCommitCount = AtomicInteger(0)

        private fun mergeMaps(existing: Map<String, Any?>?, incoming: Map<String, Any?>): LinkedHashMap<String, Any?> {
            val merged = LinkedHashMap<String, Any?>()
            if (existing != null) merged.putAll(existing)
            merged.putAll(incoming)
            return merged
        }

        private fun docId(path: String): String = path.substringAfterLast('/')

        override suspend fun getDoc(
            operation: String,
            path: String,
            preferCache: Boolean
        ): StreamingCloudStore.RemoteSnapshot? {
            val data = docs[path] ?: return StreamingCloudStore.RemoteSnapshot(docId(path), path, exists = false)
            return StreamingCloudStore.RemoteSnapshot(docId(path), path, exists = true, data = data.toMap())
        }

        override suspend fun listCollection(
            operation: String,
            collectionPath: String,
            preferCache: Boolean
        ): List<StreamingCloudStore.RemoteSnapshot> {
            val prefix = "$collectionPath/"
            return docs.entries
                .filter { (path, _) ->
                    path.startsWith(prefix) && !path.removePrefix(prefix).contains('/')
                }
                .map { (path, data) ->
                    StreamingCloudStore.RemoteSnapshot(docId(path), path, exists = true, data = data.toMap())
                }
        }

        override suspend fun commitBatch(operation: String, writes: List<StreamingCloudStore.DocWrite>) {
            simulatedWriteFailure?.let { throw it }
            writeCommitCount.incrementAndGet()
            for (write in writes) {
                docs[write.path] = mergeMaps(docs[write.path], write.data)
            }
        }

        override suspend fun mergeDoc(operation: String, path: String, data: Map<String, Any?>) {
            simulatedWriteFailure?.let { throw it }
            writeCommitCount.incrementAndGet()
            docs[path] = mergeMaps(docs[path], data)
        }

        override suspend fun <T> runTx(
            operation: String,
            readPath: String,
            block: (StreamingCloudStore.RemoteSnapshot?) -> Pair<T, List<StreamingCloudStore.DocWrite>>
        ): T {
            simulatedWriteFailure?.let { throw it }
            val current = getDoc(operation, readPath, preferCache = false)
            val (result, writes) = block(current)
            if (writes.isNotEmpty()) {
                writeCommitCount.incrementAndGet()
                for (write in writes) {
                    docs[write.path] = mergeMaps(docs[write.path], write.data)
                }
            }
            return result
        }

        override fun observeDoc(
            path: String,
            onUpdate: (StreamingCloudStore.RemoteSnapshot?) -> Unit,
            onError: (Throwable) -> Unit
        ): () -> Unit = {}

        override fun observeCollection(
            collectionPath: String,
            onUpdate: (List<StreamingCloudStore.RemoteSnapshot>) -> Unit,
            onError: (Throwable) -> Unit
        ): () -> Unit = {}
    }

    // --- Live Black Clover multi-season + duplicate backend + multi-numbering fixtures ---

    private fun blackCloverAniListService(): AniListGraphQLService = object : AniListGraphQLService {
        override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> {
            val id = (request.variables["id"] as? Number)?.toInt() ?: 0
            val media = when (id) {
                // Season 1 (Original Black Clover)
                97940 -> AniListMedia(
                    id = 97940,
                    title = AniListTitle(romaji = "Black Clover", english = "Black Clover"),
                    format = "TV",
                    status = "FINISHED",
                    episodes = 170,
                    seasonYear = 2017,
                    startDate = AniListFuzzyDate(2017, 10, 3),
                    relations = AniListRelationsConnection(edges = emptyList())
                )
                // Season 2 (Black Clover Season 2, 6 aired episodes so far)
                195604 -> AniListMedia(
                    id = 195604,
                    title = AniListTitle(romaji = "Black Clover Season 2", english = "Black Clover Season 2"),
                    format = "TV",
                    status = "RELEASING",
                    episodes = null,
                    nextAiringEpisode = AniListAiringScheduleNode(episode = 7),
                    seasonYear = 2026,
                    startDate = AniListFuzzyDate(2026, 4, 1),
                    relations = AniListRelationsConnection(
                        edges = listOf(
                            AniListRelationEdge(
                                relationType = "PREQUEL",
                                node = AniListRelatedMediaNode(
                                    id = 97940,
                                    type = "ANIME",
                                    title = AniListTitle(romaji = "Black Clover", english = "Black Clover"),
                                    format = "TV",
                                    status = "FINISHED",
                                    seasonYear = 2017,
                                    episodes = 170
                                )
                            )
                        )
                    )
                )
                // 3-season chain fixture: S3 (5003) -> S2 (5002, 12 eps) -> S1 (5001, 24 eps)
                5001 -> AniListMedia(
                    id = 5001,
                    title = AniListTitle(romaji = "Chrono Blade", english = "Chrono Blade"),
                    format = "TV",
                    status = "FINISHED",
                    episodes = 24,
                    seasonYear = 2020,
                    relations = AniListRelationsConnection(edges = emptyList())
                )
                5002 -> AniListMedia(
                    id = 5002,
                    title = AniListTitle(romaji = "Chrono Blade II", english = "Chrono Blade Season 2"),
                    format = "TV",
                    status = "FINISHED",
                    episodes = 12,
                    seasonYear = 2022,
                    relations = AniListRelationsConnection(
                        edges = listOf(
                            AniListRelationEdge(
                                relationType = "PREQUEL",
                                node = AniListRelatedMediaNode(
                                    id = 5001,
                                    type = "ANIME",
                                    title = AniListTitle(romaji = "Chrono Blade", english = "Chrono Blade"),
                                    format = "TV",
                                    episodes = 24
                                )
                            )
                        )
                    )
                )
                5003 -> AniListMedia(
                    id = 5003,
                    title = AniListTitle(romaji = "Chrono Blade Season 3", english = "Chrono Blade Season 3"),
                    format = "TV",
                    status = "RELEASING",
                    episodes = 10,
                    seasonYear = 2024,
                    relations = AniListRelationsConnection(
                        edges = listOf(
                            AniListRelationEdge(
                                relationType = "PREQUEL",
                                node = AniListRelatedMediaNode(
                                    id = 5002,
                                    type = "ANIME",
                                    title = AniListTitle(romaji = "Chrono Blade II", english = "Chrono Blade Season 2"),
                                    format = "TV",
                                    episodes = 12
                                )
                            )
                        )
                    )
                )
                // Movie fixture
                21519 -> AniListMedia(
                    id = 21519,
                    title = AniListTitle(romaji = "Kimi no Na wa.", english = "Your Name."),
                    format = "MOVIE",
                    status = "FINISHED",
                    episodes = 1,
                    seasonYear = 2016
                )
                else -> null
            }
            return Response.success(AniListGraphQLResponse(data = AniListData(media = media)))
        }
    }

    private class BlackCloverBackendFake : ProphecyVerseApiService {
        val searchQueries = mutableListOf<String>()
        val episodeFullRequests = mutableListOf<Pair<String, String>>()
        val resolveRequests = AtomicInteger(0)
        var resolveShouldFail = false
        var simulateTemporaryOutage = false
        var resolveUrl = "https://cdn.dramachi.example/streams/bc_s2_e1.m3u8?token=secret-url-token"
        var resolveExpiresAt: String? = "2099-01-01T00:00:00Z"

        override suspend fun searchAnime(query: String, limit: Int?, cursor: String?): Response<ProphecySearchResponse> {
            searchQueries += query
            val items = when {
                query.equals("Black Clover Season 2", ignoreCase = true) -> listOf(
                    // Dead addons-only duplicate returned for exact "Black Clover Season 2" query
                    ProphecySearchResultItem(
                        id = "bc_addons_dup",
                        title = "Black Clover Season 2",
                        year = "2026",
                        mediaType = "anime",
                        seasonCount = null,
                        providerMappings = listOf(ProphecyProviderMappingDto(provider = "addons", value = "stremio:bc_s2"))
                    )
                )
                query.equals("Black Clover", ignoreCase = true) -> listOf(
                    // Same addons duplicate mixed in, plus unified multi-season backend entry
                    ProphecySearchResultItem(
                        id = "bc_addons_dup",
                        title = "Black Clover Season 2",
                        year = "2026",
                        mediaType = "anime",
                        seasonCount = null,
                        providerMappings = listOf(ProphecyProviderMappingDto(provider = "addons", value = "stremio:bc_s2"))
                    ),
                    ProphecySearchResultItem(
                        id = "bc_unified",
                        canonicalId = "anilist_97940",
                        title = "Black Clover",
                        year = "2017",
                        mediaType = "anime",
                        seasonCount = 4,
                        providerMappings = listOf(
                            ProphecyProviderMappingDto(provider = "fourkhdhub", value = "4khd:bc"),
                            ProphecyProviderMappingDto(provider = "dramachi", value = "dramachi:bc"),
                            ProphecyProviderMappingDto(provider = "addons", value = "stremio:bc")
                        )
                    )
                )
                query.equals("Chrono Blade", ignoreCase = true) -> listOf(
                    ProphecySearchResultItem(
                        id = "chrono_unified",
                        canonicalId = "anilist_5001",
                        title = "Chrono Blade",
                        year = "2020",
                        mediaType = "anime",
                        seasonCount = 3,
                        providerMappings = listOf(
                            ProphecyProviderMappingDto(provider = "fourkhdhub", value = "4khd:chrono"),
                            ProphecyProviderMappingDto(provider = "dramachi", value = "dramachi:chrono")
                        )
                    )
                )
                query.contains("Your Name", ignoreCase = true) -> listOf(
                    ProphecySearchResultItem(
                        id = "backend_movie_21519",
                        canonicalId = "anilist_21519",
                        title = "Your Name.",
                        year = "2016",
                        mediaType = "movie",
                        seasonCount = 1
                    )
                )
                else -> emptyList()
            }
            return Response.success(ProphecySearchResponse(results = items))
        }

        override suspend fun getAnimeDetails(animeId: String): Response<ProphecyAnimeDetailsResponse> {
            val details = when (animeId) {
                "bc_addons_dup" -> ProphecyAnimeDetailsResponse(
                    id = animeId,
                    title = "Black Clover Season 2",
                    year = "2026",
                    mediaType = "anime",
                    seasons = listOf(
                        ProphecySeasonDto(
                            number = 1,
                            episodes = (1..6).map { ep ->
                                ProphecyEpisodeDto(id = "dup_ep_$ep", number = ep, season = 1, title = "Ep $ep")
                            }
                        )
                    )
                )
                "bc_unified" -> ProphecyAnimeDetailsResponse(
                    id = animeId,
                    title = "Black Clover",
                    year = "2017",
                    mediaType = "anime",
                    seasonCount = 4,
                    seasons = listOf(
                        ProphecySeasonDto(
                            number = 1,
                            episodes = listOf(
                                ProphecyEpisodeDto(id = "bc_s1_e1", number = 1, season = 1, title = "Asta and Yuno"),
                                ProphecyEpisodeDto(id = "bc_s1_e170", number = 170, season = 1, title = "Faraway Future"),
                                // Continuous cumulative Season 2 episodes stored by Dramachi under season = 1
                                ProphecyEpisodeDto(id = "bc_s1_dramachi_e171", number = 171, season = 1, title = "Season 2 Ep 1"),
                                ProphecyEpisodeDto(id = "bc_s1_dramachi_e172", number = 172, season = 1, title = "Season 2 Ep 2")
                            )
                        ),
                        ProphecySeasonDto(
                            number = 2,
                            episodes = (1..13).map { ep ->
                                ProphecyEpisodeDto(id = "bc_s2_addons_e$ep", number = ep, season = 2, title = "S2 Ep $ep")
                            }
                        )
                    )
                )
                "chrono_unified" -> ProphecyAnimeDetailsResponse(
                    id = animeId,
                    title = "Chrono Blade",
                    year = "2020",
                    mediaType = "anime",
                    seasonCount = 3,
                    seasons = listOf(
                        ProphecySeasonDto(
                            number = 1,
                            episodes = listOf(
                                ProphecyEpisodeDto(id = "cb_s1_e1", number = 1, season = 1),
                                ProphecyEpisodeDto(id = "cb_s1_e37", number = 37, season = 1)
                            )
                        )
                    )
                )
                "bc_dedicated_sequel" -> ProphecyAnimeDetailsResponse(
                    id = animeId,
                    title = "Black Clover Season 2",
                    year = "2026",
                    mediaType = "anime",
                    seasons = listOf(
                        ProphecySeasonDto(
                            number = 1,
                            episodes = listOf(
                                ProphecyEpisodeDto(id = "bc_dedicated_s2_e1", number = 1, season = 1, title = "Sequel Ep 1")
                            )
                        )
                    )
                )
                "backend_movie_21519" -> ProphecyAnimeDetailsResponse(
                    id = animeId,
                    title = "Your Name.",
                    year = "2016",
                    mediaType = "movie",
                    seasons = listOf(
                        ProphecySeasonDto(
                            number = 1,
                            episodes = listOf(
                                ProphecyEpisodeDto(id = "movie_s1e1_ep", number = 1, season = 1, title = "S1 & E1")
                            )
                        )
                    )
                )
                else -> return Response.error(404, okhttp3.ResponseBody.create(null, "not found"))
            }
            return Response.success(details)
        }

        override suspend fun getAnimeEpisodes(animeId: String): Response<List<ProphecyEpisodeDto>> =
            Response.success(emptyList())

        override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
            episodeFullRequests += animeId to episodeId
            if (simulateTemporaryOutage) {
                return Response.error(503, okhttp3.ResponseBody.create(null, "service unavailable"))
            }
            val response = when (episodeId) {
                // Dead addons-only duplicate and dead Season 2 addons entries return zero sources
                "dup_ep_1", "bc_s2_addons_e1", "bc_s2_addons_e2" -> ProphecyEpisodeFullResponse(
                    sources = emptyList(),
                    sourcesStatus = "empty",
                    sourcesPartial = true,
                    sourceError = "NO_SOURCE_CANDIDATES"
                )
                "bc_s1_e1" -> ProphecyEpisodeFullResponse(
                    sources = listOf(
                        ProphecySourceDto(
                            id = "src_bc_s1_e1",
                            label = "FourKHDHub",
                            quality = "1080p",
                            codec = "H264",
                            language = "Japanese",
                            downloadable = true
                        )
                    ),
                    sourcesStatus = "available",
                    sourcesPartial = false
                )
                "bc_s1_dramachi_e171" -> ProphecyEpisodeFullResponse(
                    sources = listOf(
                        ProphecySourceDto(
                            id = "provider/dramachi/ep171", // Contains '/' to verify Firestore doc ID sanitization!
                            label = "Dramachi CDN",
                            quality = "1080p",
                            codec = "H265",
                            language = "Japanese",
                            sizeBytes = 420_000_000L,
                            downloadable = true
                        )
                    ),
                    sourcesStatus = "available",
                    sourcesPartial = false
                )
                "cb_s1_e37" -> ProphecyEpisodeFullResponse(
                    sources = listOf(
                        ProphecySourceDto(
                            id = "src_cb_s3_e1",
                            label = "Dramachi CDN",
                            quality = "1080p",
                            language = "Japanese"
                        )
                    ),
                    sourcesStatus = "available",
                    sourcesPartial = false
                )
                "bc_dedicated_s2_e1" -> ProphecyEpisodeFullResponse(
                    sources = listOf(
                        ProphecySourceDto(
                            id = "src_dedicated_s2_e1",
                            label = "Dedicated CDN",
                            quality = "1080p",
                            language = "Japanese"
                        )
                    ),
                    sourcesStatus = "available",
                    sourcesPartial = false
                )
                "movie_s1e1_ep" -> ProphecyEpisodeFullResponse(
                    sources = listOf(
                        ProphecySourceDto(
                            id = "src_movie_21519",
                            label = "4KHDHub Movie",
                            quality = "2160p",
                            language = "Japanese",
                            downloadable = true
                        )
                    ),
                    sourcesStatus = "available",
                    sourcesPartial = false
                )
                else -> ProphecyEpisodeFullResponse(sources = emptyList(), sourcesStatus = "empty", sourcesPartial = false)
            }
            return Response.success(response)
        }

        override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
            resolveRequests.incrementAndGet()
            if (resolveShouldFail) {
                return Response.success(DirectPlayResponse(success = false, data = null))
            }
            return Response.success(
                DirectPlayResponse(
                    success = true,
                    data = DirectPlayData(
                        sourceId = request.sourceId,
                        playbackUrl = resolveUrl,
                        urlType = "hls",
                        contentType = "application/vnd.apple.mpegurl",
                        requiresHeaders = true,
                        headers = listOf(
                            listOf("Referer", "https://dramachi.example/"),
                            listOf("User-Agent", "ProphecyVerse/3.0.2"),
                            listOf("Authorization", "Bearer top-secret-token"),
                            listOf("Cookie", "session=private-cookie")
                        ),
                        expiresAt = resolveExpiresAt
                    )
                )
            )
        }
    }

    private fun createRepo(
        db: AppDatabase = database,
        backend: BlackCloverBackendFake = BlackCloverBackendFake(),
        cloudStore: InMemoryCloudStore? = InMemoryCloudStore(),
        writeAuthorized: Boolean = true
    ): Triple<AnimeRepository, BlackCloverBackendFake, FirestoreStreamingRepository> {
        val streamingRepo = FirestoreStreamingRepository(
            streamCacheDao = db.streamCacheDao(),
            firestoreProvider = { null },
            isClientWriteAuthorized = { writeAuthorized },
            cloudStoreProvider = { cloudStore }
        )
        val repo = AnimeRepository(
            database = db,
            prophecyApi = backend,
            aniListApi = blackCloverAniListService(),
            applicationContext = context,
            streamingRepository = streamingRepo
        )
        return Triple(repo, backend, streamingRepo)
    }

    @Test
    fun blackCloverSeason2_usesCanonicalIdentity_healsStaleMapping_playsDramachiWithoutLeakingSeason1() = runTest {
        val cloud = InMemoryCloudStore()
        val (repo, backend, streamingRepo) = createRepo(cloudStore = cloud)

        // Pre-seed stale legacy Season 1 placeholder + stale addons-only backendAnimeId + user history & watchlist!
        val animeId = "anilist_195604"
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = animeId,
                aniListId = 195604,
                backendAnimeId = "bc_addons_dup",
                preferredTitle = "Black Clover Season 2",
                genres = "Action, Fantasy",
                format = "TV",
                mediaCategory = "ANIME",
                episodeCount = 6,
                isPlayable = true
            )
        )
        database.episodeDao().upsertSeasons(listOf(SeasonEntry("anilist_195604_s1", animeId, 1, null, null)))
        database.episodeDao().upsertEpisodes(listOf(EpisodeEntry("legacy_ep1", animeId, 1, 1, "Episode 1")))
        database.watchlistDao().upsertWatchlistEntry(WatchlistEntry(animeId = animeId, status = "WATCHING", isFavorite = true))
        database.playbackHistoryDao().upsertHistory(
            PlaybackHistory("h_bc", animeId, "legacy_ep1", 1, 1, "Episode 1", 45_000L, 1_440_000L, false)
        )

        // 1. Refresh details from AniList: UI preserves "Black Clover Season 2", canonical resolves to 97940 ("Black Clover"), Season 2, offset 170
        repo.refreshAnimeDetails(animeId).getOrThrow()

        val updatedEntry = database.animeDao().getAnimeById(animeId)!!
        assertEquals("Black Clover Season 2", updatedEntry.preferredTitle)
        assertEquals("Black Clover", updatedEntry.canonicalTitle)
        assertEquals(97940, updatedEntry.canonicalAnimeId)
        assertEquals(2, updatedEntry.canonicalSeasonNumber)
        assertEquals(170, updatedEntry.cumulativeEpisodeOffset)

        val seasons = database.episodeDao().getSeasonsForAnime(animeId)
        assertEquals(listOf(2), seasons.map { it.seasonNumber })
        val eps = database.episodeDao().getEpisodesForAnime(animeId)
        assertEquals(6, eps.size)
        assertTrue(eps.all { it.seasonNumber == 2 })

        // Existing Watchlist & History preserved!
        assertTrue(database.watchlistDao().getWatchlistEntry(animeId)?.isFavorite == true)
        assertEquals(45_000L, database.playbackHistoryDao().getHistoryForAnime(animeId)?.positionMs)

        // 2. User opens Season 2 Episode 1: self-heals away from bc_addons_dup -> bc_unified, skips empty S2 addons, plays Dramachi E171!
        val s2Ep1 = eps.first { it.episodeNumber == 1 }
        val resolved = repo.resolvePlayableStream(
            animeId = animeId,
            seasonNumber = s2Ep1.seasonNumber,
            episodeNumber = s2Ep1.episodeNumber,
            catalogEpisodeId = s2Ep1.id
        ).getOrThrow()

        assertEquals("provider/dramachi/ep171", resolved.source.id)
        assertEquals("Dramachi CDN", resolved.source.displayProviderName)
        assertEquals("BACKEND_RESOLVE", resolved.cacheTier)
        assertEquals(backend.resolveUrl, resolved.directData.playbackUrl)
        assertEquals("bc_unified", database.animeDao().getAnimeById(animeId)?.backendAnimeId)

        // Verify Season 1 Ep 1..170 were NEVER requested for Season 2!
        assertFalse(backend.episodeFullRequests.any { it.second == "bc_s1_e1" || it.second == "bc_s1_e170" })
        assertTrue(backend.episodeFullRequests.any { it.first == "bc_unified" && it.second == "bc_s1_dramachi_e171" })

        // Verify Original Black Clover Season 1 (97940) plays independently at season_1/ep_1
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_97940",
                aniListId = 97940,
                preferredTitle = "Black Clover",
                genres = "Action",
                format = "TV",
                mediaCategory = "ANIME",
                episodeCount = 170,
                isPlayable = true
            )
        )
        repo.refreshAnimeDetails("anilist_97940").getOrThrow()
        val s1Resolved = repo.resolvePlayableStream("anilist_97940", seasonNumber = 1, episodeNumber = 1).getOrThrow()
        assertEquals("src_bc_s1_e1", s1Resolved.source.id)
        assertNotNull(cloud.docs["anime_streams/97940/seasons/season_1/episodes/ep_1"])
    }

    @Test
    fun generalMultiSeasonChain_traverses3SeasonsAndNeverFallsBackToSeason1Episodes() = runTest {
        val (repo, backend, _) = createRepo()

        // Chrono Blade Season 3 -> Chrono Blade II (12) -> Chrono Blade (24) => canonical = 5001, season = 3, offset = 36
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_5003",
                aniListId = 5003,
                preferredTitle = "Chrono Blade Season 3",
                genres = "Action",
                format = "TV",
                mediaCategory = "ANIME",
                episodeCount = 10,
                isPlayable = true
            )
        )
        repo.refreshAnimeDetails("anilist_5003").getOrThrow()
        val s3 = database.animeDao().getAnimeById("anilist_5003")!!
        assertEquals(5001, s3.canonicalAnimeId)
        assertEquals("Chrono Blade", s3.canonicalTitle)
        assertEquals(3, s3.canonicalSeasonNumber)
        assertEquals(36, s3.cumulativeEpisodeOffset)

        val discovery = repo.discoverStreamsForScope("anilist_5003", seasonNumber = 3, episodeNumber = 1).getOrThrow()
        assertEquals(listOf("src_cb_s3_e1"), discovery.sources.map { it.id })

        // Verify Season 2 Ep 1 NEVER returns Season 1 Ep 1 ("cb_s1_e1") when backend only has cb_s1_e1 (24 eps < offset)
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_5002",
                aniListId = 5002,
                preferredTitle = "Chrono Blade Season 2",
                genres = "Action",
                format = "TV",
                mediaCategory = "ANIME",
                episodeCount = 12,
                isPlayable = true
            )
        )
        repo.refreshAnimeDetails("anilist_5002").getOrThrow()
        val leaked = repo.discoverStreamsForScope("anilist_5002", seasonNumber = 2, episodeNumber = 1)
        assertTrue(leaked.isFailure)
        assertFalse(backend.episodeFullRequests.any { it.second == "cb_s1_e1" })
    }

    @Test
    fun dedicatedSequelBackendEntry_resolvesSeason2_whilePreservingCanonicalFirestorePath() = runTest {
        val cloud = InMemoryCloudStore()
        val (repo, _, _) = createRepo(cloudStore = cloud)

        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_195604",
                aniListId = 195604,
                canonicalAnimeId = 97940,
                canonicalTitle = "Black Clover",
                canonicalSeasonNumber = 2,
                cumulativeEpisodeOffset = 170,
                backendAnimeId = "bc_dedicated_sequel",
                preferredTitle = "Black Clover Season 2",
                year = "2026",
                genres = "Action",
                format = "TV",
                mediaCategory = "ANIME",
                episodeCount = 6,
                isPlayable = true
            )
        )

        val resolved = repo.resolvePlayableStream("anilist_195604", seasonNumber = 2, episodeNumber = 1).getOrThrow()
        assertEquals("src_dedicated_s2_e1", resolved.source.id)
        assertNotNull(cloud.docs["anime_streams/97940/seasons/season_2/episodes/ep_1"])
        assertEquals("Black Clover", cloud.docs["anime_streams/97940"]?.get("canonical_title"))
    }

    @Test
    fun firestorePersistence_createsParentHierarchy_sanitizesIds_writesAllRequiredFields_andRedactsLogs() = runTest {
        val cloud = InMemoryCloudStore()
        val (repo, _, streamingRepo) = createRepo(cloudStore = cloud)

        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_195604",
                aniListId = 195604,
                preferredTitle = "Black Clover Season 2",
                genres = "Action",
                format = "TV",
                mediaCategory = "ANIME",
                episodeCount = 6,
                isPlayable = true
            )
        )
        repo.refreshAnimeDetails("anilist_195604").getOrThrow()
        val resolved = repo.resolvePlayableStream("anilist_195604", seasonNumber = 2, episodeNumber = 1).getOrThrow()
        repo.reportPlaybackSuccess("anilist_195604", resolved.source.id, seasonNumber = 2, episodeNumber = 1)

        val provId = ContentIdentityHelper.deterministicProviderId("Dramachi CDN")
        val rawSourceId = "provider/dramachi/ep171"
        val docId = ContentIdentityHelper.sourceDocId(rawSourceId)
        assertFalse(docId.contains("/"))

        val containerPath = "anime_streams/97940"
        val seasonPath = "anime_streams/97940/seasons/season_2"
        val episodePath = "anime_streams/97940/seasons/season_2/episodes/ep_1"
        val providerPath = "$episodePath/providers/$provId"
        val sourcePath = "$providerPath/sources/$docId"

        val container = cloud.docs[containerPath]!!
        val season = cloud.docs[seasonPath]!!
        val episode = cloud.docs[episodePath]!!
        val provider = cloud.docs[providerPath]!!
        val source = cloud.docs[sourcePath]!!

        assertEquals("Black Clover", container["canonical_title"])
        assertEquals(97940, container["anilist_id"])
        assertEquals("bc_unified", container["backend_id"])
        assertEquals("ANIME", container["content_type"])

        assertEquals("season_2", season["season_id"])
        assertEquals(2, season["season_number"])
        assertEquals("ep_1", episode["episode_id"])
        assertEquals("season_2", episode["season_id"])

        assertEquals(provId, provider["provider_id"])
        assertEquals("Dramachi CDN", provider["provider_name"])

        // Source contains ALL required fields & ONLY sanitized safe headers
        assertEquals(provId, source["provider_id"])
        assertEquals("Dramachi CDN", source["provider_name"])
        assertEquals(rawSourceId, source["source_id"])
        assertEquals("Dramachi CDN", source["source_name"])
        assertNotNull(source["stream_url"])
        assertEquals("hls", source["url_type"])
        assertEquals("1080p", source["quality"])
        assertEquals("H265", source["codec"])
        assertEquals("Japanese", source["audio_language"])
        assertEquals("AVAILABLE", source["status"])
        assertTrue((source["version"] as Number).toLong() >= 2L)
        assertNotNull(source["last_verified_at"])
        assertNotNull(source["last_playback_success_at"])
        assertNotNull(source["updated_at"])
        assertEquals("2099-01-01T00:00:00Z", source["expires_at"])

        @Suppress("UNCHECKED_CAST")
        val headersMap = source["safe_headers"] as Map<String, String>
        assertTrue(headersMap.containsKey("Referer"))
        assertTrue(headersMap.containsKey("User-Agent"))
        assertFalse(headersMap.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) })

        // Verify observable redacted audit logs never contain stream URLs or secrets
        val logs = streamingRepo.recentWriteLogs()
        assertTrue(logs.any { it.operation == FirestoreWriteOperation.ADD && it.success })
        assertTrue(logs.any { it.operation == FirestoreWriteOperation.UPDATE && it.success })
        logs.forEach { logEntry ->
            assertEquals("ANIME", logEntry.contentType)
            assertEquals("Black Clover", logEntry.canonicalTitle)
            assertEquals("97940", logEntry.anilistId)
            assertEquals("season_2", logEntry.seasonId)
            assertEquals("ep_1", logEntry.episodeId)
            val serialized = logEntry.toString()
            assertFalse(serialized.contains("cdn.dramachi.example"))
            assertFalse(serialized.contains("secret-url-token"))
        }
    }

    @Test
    fun secondOpen_reusesFirestoreWithoutBackendDiscoveryOrResolve() = runTest {
        val sharedCloud = InMemoryCloudStore()

        // Device A discovers & resolves Black Clover Season 2
        val (repoA, backendA, _) = createRepo(cloudStore = sharedCloud)
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_195604",
                aniListId = 195604,
                canonicalAnimeId = 97940,
                canonicalTitle = "Black Clover",
                canonicalSeasonNumber = 2,
                cumulativeEpisodeOffset = 170,
                preferredTitle = "Black Clover Season 2",
                genres = "Action",
                format = "TV",
                mediaCategory = "ANIME",
                episodeCount = 6,
                isPlayable = true
            )
        )
        val resA = repoA.resolvePlayableStream("anilist_195604", seasonNumber = 2, episodeNumber = 1).getOrThrow()
        assertEquals("BACKEND_RESOLVE", resA.cacheTier)
        assertEquals(1, backendA.resolveRequests.get())

        // Device B starts with empty Room DB pointing to the SAME Firestore store
        val dbB = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val (repoB, backendB, _) = createRepo(db = dbB, cloudStore = sharedCloud)
            dbB.animeDao().upsertAnime(
                AnimeEntry(
                    id = "anilist_195604",
                    aniListId = 195604,
                    canonicalAnimeId = 97940,
                    canonicalTitle = "Black Clover",
                    canonicalSeasonNumber = 2,
                    cumulativeEpisodeOffset = 170,
                    preferredTitle = "Black Clover Season 2",
                    genres = "Action",
                    format = "TV",
                    mediaCategory = "ANIME",
                    episodeCount = 6,
                    isPlayable = true
                )
            )
            val resB = repoB.resolvePlayableStream("anilist_195604", seasonNumber = 2, episodeNumber = 1).getOrThrow()
            assertEquals("FIRESTORE", resB.cacheTier)
            assertTrue(resB.fromCache)
            assertEquals(backendA.resolveUrl, resB.directData.playbackUrl)
            assertEquals(0, backendB.searchQueries.size)
            assertEquals(0, backendB.episodeFullRequests.size)
            assertEquals(0, backendB.resolveRequests.get())
        } finally {
            dbB.close()
        }
    }

    @Test
    fun expiryUpdatesVersion_failedResolvePreservesUrl_temporaryOutageNeverSoftDeletes_andPermissionDeniedIsLogged() = runTest {
        val cloud = InMemoryCloudStore()
        val (repo, backend, streamingRepo) = createRepo(cloudStore = cloud)

        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_195604",
                aniListId = 195604,
                canonicalAnimeId = 97940,
                canonicalTitle = "Black Clover",
                canonicalSeasonNumber = 2,
                cumulativeEpisodeOffset = 170,
                backendAnimeId = "bc_unified",
                preferredTitle = "Black Clover Season 2",
                genres = "Action",
                format = "TV",
                mediaCategory = "ANIME",
                episodeCount = 6,
                isPlayable = true
            )
        )

        // 1. Seed initial expired stream URL in Firestore + Room
        backend.resolveUrl = "https://cdn.dramachi.example/old.m3u8"
        backend.resolveExpiresAt = "2020-01-01T00:00:00Z" // already expired
        repo.discoverStreamsForScope("anilist_195604", seasonNumber = 2, episodeNumber = 1).getOrThrow()

        // Valid future resolve updates Firestore and increments version
        backend.resolveUrl = "https://cdn.dramachi.example/fresh_v2.m3u8"
        backend.resolveExpiresAt = "2099-01-01T00:00:00Z"
        val fresh = repo.resolvePlayableStream("anilist_195604", seasonNumber = 2, episodeNumber = 1, forceResolveFresh = true).getOrThrow()
        assertEquals("https://cdn.dramachi.example/fresh_v2.m3u8", fresh.directData.playbackUrl)

        val provId = ContentIdentityHelper.deterministicProviderId("Dramachi CDN")
        val docId = ContentIdentityHelper.sourceDocId("provider/dramachi/ep171")
        val sourcePath = "anime_streams/97940/seasons/season_2/episodes/ep_1/providers/$provId/sources/$docId"
        val versionAfterFresh = (cloud.docs[sourcePath]!!["version"] as Number).toLong()
        assertEquals("https://cdn.dramachi.example/fresh_v2.m3u8", cloud.docs[sourcePath]!!["stream_url"])

        // 2. Failed force-resolve MUST NOT overwrite valid stream_url
        backend.resolveShouldFail = true
        val failedResolve = repo.resolvePlayableStream("anilist_195604", seasonNumber = 2, episodeNumber = 1, forceResolveFresh = true)
        assertTrue(failedResolve.isFailure)
        assertEquals("https://cdn.dramachi.example/fresh_v2.m3u8", cloud.docs[sourcePath]!!["stream_url"])
        backend.resolveShouldFail = false

        // 3. Temporary 503 outage MUST NOT soft-delete sources in Firestore or emit REMOVE audit log
        backend.simulateTemporaryOutage = true
        val staleSnap = repo.discoverStreamsForScope("anilist_195604", seasonNumber = 2, episodeNumber = 1, forceRefresh = true)
        assertTrue(staleSnap.isSuccess) // returns stale Room cache cleanly
        assertEquals("AVAILABLE", cloud.docs[sourcePath]!!["status"])
        assertNull(cloud.docs[sourcePath]!!["deleted_at"])
        assertFalse(streamingRepo.recentWriteLogs().any { it.operation == FirestoreWriteOperation.REMOVE })
        backend.simulateTemporaryOutage = false

        // 4. Simulate Firestore security rule PERMISSION_DENIED and verify surfaced reason & redacted log
        cloud.simulatedWriteFailure = FirestoreOperationException(
            category = FirestoreFailureCategory.PERMISSION_DENIED,
            operation = "publish_stream_url",
            retryAfterMs = 0L,
            cause = FirebaseFirestoreException("PERMISSION_DENIED", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        )
        val ok = streamingRepo.softDeleteSource(
            sourceEntity = database.streamCacheDao().getActiveSources("anime:97940:s2:e1").first(),
            contentKey = "97940",
            isMovie = false,
            seasonNumber = 2,
            episodeNumber = 1,
            canonicalTitle = "Black Clover"
        )
        assertFalse(ok)
        assertTrue(streamingRepo.lastWriteRejectionReason?.contains("PERMISSION_DENIED") == true)
        val deniedLog = streamingRepo.recentWriteLogs().last { !it.success }
        assertEquals(FirestoreWriteOperation.REMOVE, deniedLog.operation)
        assertEquals("PERMISSION_DENIED", deniedLog.firebaseErrorCode)
        assertTrue((cloud.docs[sourcePath]!!["version"] as Number).toLong() >= versionAfterFresh)
    }

    @Test
    fun movieRemainsStrictlyFlatInFirestore_andRoomMigration10To11PreservesExistingData() = runTest {
        val cloud = InMemoryCloudStore()
        val (repo, _, _) = createRepo(cloudStore = cloud)

        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_21519",
                aniListId = 21519,
                preferredTitle = "Your Name.",
                genres = "Romance, Drama",
                format = "MOVIE",
                mediaCategory = "MOVIE",
                episodeCount = 1,
                isPlayable = true
            )
        )
        repo.refreshAnimeDetails("anilist_21519").getOrThrow()
        assertTrue(database.episodeDao().getSeasonsForAnime("anilist_21519").isEmpty())
        assertTrue(database.episodeDao().getEpisodesForAnime("anilist_21519").isEmpty())

        val resolved = repo.resolvePlayableStream("anilist_21519", seasonNumber = 0, episodeNumber = 1).getOrThrow()
        assertEquals("src_movie_21519", resolved.source.id)

        val provId = ContentIdentityHelper.deterministicProviderId("4KHDHub Movie")
        val movieDoc = cloud.docs["movie_streams/21519"]!!
        val movieProvider = cloud.docs["movie_streams/21519/providers/$provId"]!!
        val movieSource = cloud.docs["movie_streams/21519/providers/$provId/sources/src_movie_21519"]!!
        assertEquals("MOVIE", movieDoc["content_type"])
        assertEquals("Your Name.", movieDoc["canonical_title"])
        assertNotNull(movieProvider["provider_id"])
        assertEquals("src_movie_21519", movieSource["source_id"])
        assertFalse(cloud.docs.keys.any { it.contains("/seasons/") || it.contains("/episodes/") })

        // Verify Room MIGRATION_10_11 preserves existing v10 data without destructiveness
        val dbName = "migration_10_11_test.db"
        File(context.getDatabasePath(dbName).path).delete()
        val v10Callback = object : SupportSQLiteOpenHelper.Callback(10) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS anime_entries (id TEXT NOT NULL PRIMARY KEY, aniListId INTEGER, backendAnimeId TEXT, titleRomaji TEXT, titleEnglish TEXT, titleNative TEXT, preferredTitle TEXT NOT NULL, synopsis TEXT, year TEXT, season TEXT, format TEXT, status TEXT, genres TEXT NOT NULL, score REAL, episodeCount INTEGER, duration INTEGER, studios TEXT, posterUrl TEXT, bannerUrl TEXT, isPlayable INTEGER NOT NULL, isTrending INTEGER NOT NULL, isSeasonal INTEGER NOT NULL, isPopular INTEGER NOT NULL, mediaCategory TEXT NOT NULL DEFAULT 'ANIME', metadataCompleteness TEXT NOT NULL DEFAULT 'UNKNOWN', updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS stream_containers (scopeKey TEXT NOT NULL PRIMARY KEY, contentKey TEXT NOT NULL, anilistId INTEGER, contentType TEXT NOT NULL, backendId TEXT, seasonCount INTEGER, totalEpisodeCount INTEGER, providerCount INTEGER NOT NULL, sourceCount INTEGER NOT NULL, discoveryStatus TEXT NOT NULL, version INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("INSERT INTO anime_entries VALUES ('anilist_97940', 97940, 'bc_unified', 'Black Clover', 'Black Clover', NULL, 'Black Clover', NULL, '2017', 'FALL', 'TV', 'FINISHED', 'Action', 8.2, 170, 24, 'Pierrot', NULL, NULL, 1, 1, 0, 1, 'ANIME', 'COMPLETE', 1000)")
                db.execSQL("INSERT INTO stream_containers VALUES ('anime:97940', '97940', 97940, 'ANIME', 'bc_unified', 2, 176, 1, 1, 'COMPLETED', 1, 1000)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(dbName).callback(v10Callback).build()
        )
        val sqlDb = helper.writableDatabase
        AppDatabase.MIGRATION_10_11.migrate(sqlDb)
        sqlDb.query("SELECT preferredTitle, canonicalAnimeId, canonicalTitle, canonicalSeasonNumber, cumulativeEpisodeOffset FROM anime_entries WHERE id = 'anilist_97940'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Black Clover", it.getString(0))
            assertTrue(it.isNull(1))
            assertTrue(it.isNull(2))
            assertTrue(it.isNull(3))
            assertTrue(it.isNull(4))
        }
        sqlDb.query("SELECT backendId, canonicalTitle FROM stream_containers WHERE scopeKey = 'anime:97940'").use {
            assertTrue(it.moveToFirst())
            assertEquals("bc_unified", it.getString(0))
            assertTrue(it.isNull(1))
        }
        sqlDb.close()
        File(context.getDatabasePath(dbName).path).delete()
    }
}
