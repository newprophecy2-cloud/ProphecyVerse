package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.firebase.VersionCheckState
import com.example.data.firebase.VersionConfigParseResult
import com.example.data.firebase.VersionControlConfig
import com.example.data.firebase.VersionControlManager
import com.example.data.firebase.parseVersionControlConfig
import com.example.data.identity.CanonicalEpisodeCatalogBuilder
import com.example.data.identity.EpisodeSourceEpisodeIds
import com.example.data.identity.ProviderCatalogPayload
import com.example.data.identity.ProviderPriorityPolicy
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.remote.AniListData
import com.example.data.remote.AniListGraphQLError
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLResponse
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.AniListMedia
import com.example.data.remote.AniListPage
import com.example.data.remote.AniListTitle
import com.example.data.remote.DirectPlayData
import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.DirectPlayResponse
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecyProviderMappingDto
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecySeasonDto
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.streaming.MonotonicMetrics
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase123RootCauseRegressionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        MonotonicMetrics.clear()
        context.getSharedPreferences("version_control_verification", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun idleAniList(): AniListGraphQLService = object : AniListGraphQLService {
        override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
            Response.success(AniListGraphQLResponse(data = AniListData()))
    }

    private fun noopProphecyApi(): ProphecyVerseApiService = object : ProphecyVerseApiService {
        override suspend fun searchAnime(query: String, limit: Int?, cursor: String?): Response<ProphecySearchResponse> =
            Response.success(ProphecySearchResponse())

        override suspend fun getAnimeDetails(animeId: String): Response<ProphecyAnimeDetailsResponse> =
            Response.error(404, "not found".toResponseBody(null))

        override suspend fun getAnimeEpisodes(animeId: String): Response<List<ProphecyEpisodeDto>> =
            Response.success(emptyList())

        override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> =
            Response.error(404, "not found".toResponseBody(null))

        override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> =
            Response.error(404, "not found".toResponseBody(null))
    }

    // ============================================================================================
    // PHASE 1: 4KHDHUB-FIRST CANONICAL EPISODE MAPPING & PLAYBACK FALLBACK
    // ============================================================================================

    @Test
    fun phase1_fourKhDHubCanonicalAuthority_limitsBleachS1To51AndSkipsDramachiExtras() = runTest {
        val animeId = "anilist_269"
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = animeId,
                aniListId = 269,
                backendAnimeId = "bleach_backend",
                preferredTitle = "Bleach",
                titleEnglish = "Bleach",
                titleRomaji = "Bleach",
                isPlayable = true,
                mediaCategory = "ANIME"
            )
        )

        val fourKhEps = (1..51).map { num ->
            ProphecyEpisodeDto(
                id = "4khd_bleach_s1_e$num",
                canonicalId = "bleach_s1_e$num",
                providerMappings = listOf(ProphecyProviderMappingDto(provider = "4khdhub", id = "4khd_bleach_s1_e$num")),
                season = 1,
                number = num,
                title = "Soul Reaper Chapter $num"
            )
        }
        val dramachiEps = (1..331).map { num ->
            ProphecyEpisodeDto(
                id = "dramachi_bleach_s1_e$num",
                providerMappings = listOf(ProphecyProviderMappingDto(provider = "dramachi", id = "dramachi_bleach_s1_e$num")),
                season = 1,
                number = num,
                title = "Soul Reaper Chapter $num"
            )
        }

        val repo = AnimeRepository(
            database = database,
            prophecyApi = noopProphecyApi(),
            aniListApi = idleAniList(),
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker()
        )

        val result = repo.syncMultiProviderCatalog(
            animeId = animeId,
            payloads = listOf(
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_DRAMACHI,
                    animeIdentity = "Bleach",
                    seasons = listOf(ProphecySeasonDto(number = 1, title = "Season 1", episodes = dramachiEps)),
                    episodes = dramachiEps
                ),
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_4KHDHUB,
                    animeIdentity = "Bleach",
                    seasons = listOf(ProphecySeasonDto(number = 1, title = "Season 1", episodes = fourKhEps)),
                    episodes = fourKhEps
                )
            )
        )

        val persistedEpisodes = database.episodeDao().getEpisodesForAnime(animeId)
        assertTrue(result.primaryAuthorityAvailable)
        assertFalse(result.preservedPriorCatalog)
        assertEquals("4KHDHUB confirmed 51 episodes so APK must store strictly 51", 51, persistedEpisodes.size)
        assertEquals(280, result.skippedUnmatchedFallbackCount)
        assertEquals(listOf(1), database.episodeDao().getSeasonsForAnime(animeId).map { it.seasonNumber })

        val ep12 = persistedEpisodes.first { it.episodeNumber == 12 }
        assertEquals("4khd_bleach_s1_e12", ep12.id)
        val boundIds = EpisodeSourceEpisodeIds.decode(ep12.sourceEpisodeIds)
        assertTrue("4KHDHUB backendEpisodeId must remain first", boundIds.first() == "4khd_bleach_s1_e12")
        assertTrue("Verified Dramachi backendEpisodeId must stay separate & attached", "dramachi_bleach_s1_e12" in boundIds)
        assertTrue("Unmatched Dramachi ep 52 must never exist in Room", persistedEpisodes.none { it.episodeNumber > 51 })
    }

    @Test
    fun phase1_fallbackMapping_neverMergesOnEpisodeNumberAlone_skipsUnmatchedAndDuplicates() {
        val primary = (1..3).map { num ->
            ProphecyEpisodeDto(
                id = "4khd_s1_e$num",
                canonicalId = "canon_e$num",
                season = 1,
                number = num,
                title = when (num) {
                    1 -> "The Awakening Blade"
                    2 -> "Echoes in Kyoto"
                    else -> "Dawn Over the Citadel"
                }
            )
        }
        val dramachi = listOf(
            // Matching verified episode 1
            ProphecyEpisodeDto(
                id = "dramachi_s1_e1_valid",
                season = 1,
                number = 1,
                title = "The Awakening Blade"
            ),
            // Duplicate episode 1 from Dramachi -> must be skipped without creating duplicate canonical episode
            ProphecyEpisodeDto(
                id = "dramachi_s1_e1_dup",
                season = 1,
                number = 1,
                title = "The Awakening Blade"
            ),
            // Same episode number 2, DIFFERENT title -> must NOT merge solely on episode number
            ProphecyEpisodeDto(
                id = "dramachi_s1_e2_conflict",
                season = 1,
                number = 2,
                title = "Filler Beach Resort Recap"
            ),
            // Same canonical ID conflict on episode 3 -> must NOT merge
            ProphecyEpisodeDto(
                id = "dramachi_s1_e3_bad_canon",
                canonicalId = "different_canon_e99",
                season = 1,
                number = 3,
                title = "Dawn Over the Citadel"
            )
        )

        val result = CanonicalEpisodeCatalogBuilder.build(
            animeId = "anilist_9001",
            canonicalAnimeIdentity = "9001",
            payloads = listOf(
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_4KHDHUB,
                    animeIdentity = "Samurai Arc",
                    seasons = listOf(ProphecySeasonDto(number = 1, episodes = primary)),
                    episodes = primary
                ),
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_DRAMACHI,
                    animeIdentity = "Samurai Arc",
                    seasons = listOf(ProphecySeasonDto(number = 1, episodes = dramachi)),
                    episodes = dramachi
                )
            )
        )

        assertEquals(3, result.episodes.size)
        assertEquals(1, result.skippedDuplicateFallbackCount)
        assertEquals(2, result.skippedUnmatchedFallbackCount)

        val ep1Ids = EpisodeSourceEpisodeIds.decode(result.episodes[0].sourceEpisodeIds)
        val ep2Ids = EpisodeSourceEpisodeIds.decode(result.episodes[1].sourceEpisodeIds)
        val ep3Ids = EpisodeSourceEpisodeIds.decode(result.episodes[2].sourceEpisodeIds)
        assertEquals(listOf("4khd_s1_e1", "dramachi_s1_e1_valid"), ep1Ids)
        assertEquals(listOf("4khd_s1_e2"), ep2Ids)
        assertEquals(listOf("4khd_s1_e3"), ep3Ids)
    }

    @Test
    fun phase1_fourKhDHubUnavailable_preservesExistingCanonicalCatalogWithoutDramachiOverride() = runTest {
        val animeId = "anilist_777"
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = animeId,
                aniListId = 777,
                backendAnimeId = "b_777",
                preferredTitle = "Chronicle Zero",
                isPlayable = true
            )
        )
        val initialFourKh = (1..4).map { n ->
            ProphecyEpisodeDto(
                id = "4khd_777_e$n",
                season = 1,
                number = n,
                title = "Chronicle Part $n"
            )
        }

        // Now suppose 4KHDHUB becomes unavailable while Dramachi returns partial ep 1 + extra ep 5..12
        var returnPrimary = true
        val fakeApi = object : ProphecyVerseApiService by noopProphecyApi() {
            override suspend fun getAnimeDetails(animeId: String): Response<ProphecyAnimeDetailsResponse> {
                val eps = if (returnPrimary) {
                    initialFourKh
                } else {
                    listOf(
                        ProphecyEpisodeDto(id = "dramachi_777_e1", season = 1, number = 1, title = "Chronicle Part 1")
                    ) + (5..12).map { n ->
                        ProphecyEpisodeDto(id = "dramachi_777_e$n", season = 1, number = n, title = "Extra $n")
                    }
                }
                return Response.success(
                    ProphecyAnimeDetailsResponse(
                        id = "b_777",
                        title = "Chronicle Zero",
                        mediaType = "ANIME",
                        partial = false,
                        seasons = listOf(ProphecySeasonDto(number = 1, title = "Season 1", episodes = eps)),
                        episodeCount = eps.size
                    )
                )
            }
        }

        val repo = AnimeRepository(
            database = database,
            prophecyApi = fakeApi,
            aniListApi = idleAniList(),
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker()
        )

        val firstRefresh = repo.refreshAnimeDetails(animeId, forceRefresh = true)
        assertTrue(firstRefresh.isSuccess)
        assertEquals(4, database.episodeDao().getEpisodesForAnime(animeId).size)

        returnPrimary = false
        val secondRefresh = repo.refreshAnimeDetails(animeId, forceRefresh = true)
        assertTrue(secondRefresh.isSuccess)

        val storedAfterOutage = database.episodeDao().getEpisodesForAnime(animeId)
        assertEquals("Missing 4KHDHUB must preserve 4 canonical episodes", 4, storedAfterOutage.size)
        assertEquals(
            listOf("4khd_777_e1", "4khd_777_e2", "4khd_777_e3", "4khd_777_e4"),
            storedAfterOutage.map { it.id }
        )
        // Missing Dramachi episodes 2..4 did NOT delete canonical episodes 2..4, and ep 1 gained Dramachi binding
        assertTrue("dramachi_777_e1" in EpisodeSourceEpisodeIds.decode(storedAfterOutage.first().sourceEpisodeIds))
    }

    @Test
    fun phase1_playbackTriesFourKhDHubFirst_neverQueriesDramachiWhenPrimarySucceeds() = runTest {
        val animeId = "anilist_301"
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = animeId,
                aniListId = 301,
                backendAnimeId = "b_301",
                preferredTitle = "Blade Soul",
                isPlayable = true
            )
        )
        val fourKhEp = ProphecyEpisodeDto(id = "4khd_301_e1", season = 1, number = 1, title = "First Strike")
        val dramachiEp = ProphecyEpisodeDto(id = "dramachi_301_e1", season = 1, number = 1, title = "First Strike")

        val queriedEpisodeFullIds = CopyOnWriteArrayList<String>()
        val directPlayRequests = CopyOnWriteArrayList<DirectPlayRequest>()

        val fakeApi = object : ProphecyVerseApiService by noopProphecyApi() {
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                queriedEpisodeFullIds += episodeId
                return when (episodeId) {
                    "4khd_301_e1" -> Response.success(
                        ProphecyEpisodeFullResponse(
                            selectedEpisode = fourKhEp,
                            sources = listOf(
                                ProphecySourceDto(id = "src_4khd_1", label = "4KHDHUB Ultra", quality = "720p")
                            ),
                            sourcesStatus = "available",
                            sourcesPartial = false
                        )
                    )
                    "dramachi_301_e1" -> Response.success(
                        ProphecyEpisodeFullResponse(
                            selectedEpisode = dramachiEp,
                            sources = listOf(
                                ProphecySourceDto(id = "src_dramachi_1", label = "Dramachi HD", quality = "1080p")
                            ),
                            sourcesStatus = "available",
                            sourcesPartial = false
                        )
                    )
                    else -> Response.error(404, "missing".toResponseBody(null))
                }
            }

            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                directPlayRequests += request
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = request.sourceId,
                            playbackUrl = "https://cdn.example.com/4khd/master.m3u8",
                            urlType = "hls"
                        )
                    )
                )
            }
        }

        val repo = AnimeRepository(
            database = database,
            prophecyApi = fakeApi,
            aniListApi = idleAniList(),
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker()
        )

        repo.syncMultiProviderCatalog(
            animeId = animeId,
            payloads = listOf(
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_4KHDHUB,
                    animeIdentity = "Blade Soul",
                    seasons = listOf(ProphecySeasonDto(number = 1, episodes = listOf(fourKhEp))),
                    episodes = listOf(fourKhEp)
                ),
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_DRAMACHI,
                    animeIdentity = "Blade Soul",
                    seasons = listOf(ProphecySeasonDto(number = 1, episodes = listOf(dramachiEp))),
                    episodes = listOf(dramachiEp)
                )
            )
        )

        val playback = repo.resolvePlayableStream(
            animeId = animeId,
            seasonNumber = 1,
            episodeNumber = 1,
            catalogEpisodeId = "4khd_301_e1"
        )

        assertTrue(playback.isSuccess)
        assertEquals("src_4khd_1", playback.getOrThrow().source.id)
        assertEquals(listOf("4khd_301_e1"), queriedEpisodeFullIds)
        assertEquals(1, directPlayRequests.size)
        assertEquals("4khd_301_e1", directPlayRequests.single().episodeId)
    }

    @Test
    fun phase1_playbackFallsBackFourKhDHubToDramachiToThirdProvider_preservingEachBackendEpisodeId() = runTest {
        val animeId = "anilist_402"
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = animeId,
                aniListId = 402,
                backendAnimeId = "b_402",
                preferredTitle = "Astral Gate",
                isPlayable = true
            )
        )
        val ep4kh = ProphecyEpisodeDto(id = "4khd_402_e3", season = 1, number = 3, title = "Gatekeeper")
        val epDram = ProphecyEpisodeDto(id = "dramachi_402_e3", season = 1, number = 3, title = "Gatekeeper")
        val epAddon = ProphecyEpisodeDto(id = "addons_402_e3", season = 1, number = 3, title = "Gatekeeper")

        val queriedEpisodeFullIds = CopyOnWriteArrayList<String>()
        val directPlayRequests = CopyOnWriteArrayList<DirectPlayRequest>()

        val fakeApi = object : ProphecyVerseApiService by noopProphecyApi() {
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                queriedEpisodeFullIds += episodeId
                return when (episodeId) {
                    "4khd_402_e3" -> Response.success(
                        ProphecyEpisodeFullResponse(
                            selectedEpisode = ep4kh,
                            sources = listOf(ProphecySourceDto(id = "src_4khd_broken", label = "4KHDHUB")),
                            sourcesStatus = "available",
                            sourcesPartial = false
                        )
                    )
                    "dramachi_402_e3" -> Response.success(
                        ProphecyEpisodeFullResponse(
                            selectedEpisode = epDram,
                            sources = listOf(ProphecySourceDto(id = "src_dram_broken", label = "Dramachi")),
                            sourcesStatus = "available",
                            sourcesPartial = false
                        )
                    )
                    "addons_402_e3" -> Response.success(
                        ProphecyEpisodeFullResponse(
                            selectedEpisode = epAddon,
                            sources = listOf(ProphecySourceDto(id = "src_addon_ok", label = "Stremio Addon")),
                            sourcesStatus = "available",
                            sourcesPartial = false
                        )
                    )
                    else -> Response.error(404, "missing".toResponseBody(null))
                }
            }

            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                directPlayRequests += request
                return when (request.sourceId) {
                    "src_addon_ok" -> Response.success(
                        DirectPlayResponse(
                            success = true,
                            data = DirectPlayData(
                                sourceId = request.sourceId,
                                playbackUrl = "https://cdn.example.com/addon/stream.mp4",
                                urlType = "mp4"
                            )
                        )
                    )
                    else -> Response.error(503, "source expired".toResponseBody(null))
                }
            }
        }

        val repo = AnimeRepository(
            database = database,
            prophecyApi = fakeApi,
            aniListApi = idleAniList(),
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker()
        )

        repo.syncMultiProviderCatalog(
            animeId = animeId,
            payloads = listOf(
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_4KHDHUB,
                    animeIdentity = "Astral Gate",
                    seasons = listOf(ProphecySeasonDto(number = 1, episodes = listOf(ep4kh))),
                    episodes = listOf(ep4kh)
                ),
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_DRAMACHI,
                    animeIdentity = "Astral Gate",
                    seasons = listOf(ProphecySeasonDto(number = 1, episodes = listOf(epDram))),
                    episodes = listOf(epDram)
                ),
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_ADDONS,
                    animeIdentity = "Astral Gate",
                    seasons = listOf(ProphecySeasonDto(number = 1, episodes = listOf(epAddon))),
                    episodes = listOf(epAddon)
                )
            )
        )

        val playback = repo.resolvePlayableStream(
            animeId = animeId,
            seasonNumber = 1,
            episodeNumber = 3,
            catalogEpisodeId = "4khd_402_e3"
        )

        assertTrue(playback.isSuccess)
        assertEquals("src_addon_ok", playback.getOrThrow().source.id)
        assertEquals(listOf("4khd_402_e3", "dramachi_402_e3", "addons_402_e3"), queriedEpisodeFullIds)
        // Each fallback must use its own provider's original backendEpisodeId, NEVER the 4KHDHUB canonical ID
        assertEquals(
            listOf(
                "src_4khd_broken" to "4khd_402_e3",
                "src_dram_broken" to "dramachi_402_e3",
                "src_addon_ok" to "addons_402_e3"
            ),
            directPlayRequests.map { it.sourceId to it.episodeId }
        )
    }

    @Test
    fun phase1_configurableProviderPriority_respectsFallbackOrderWithoutChangingCanonicalAuthority() = runTest {
        val customPolicy = ProviderPriorityPolicy(
            priorityOrder = listOf(
                ProviderPriorityPolicy.PROVIDER_4KHDHUB,
                "futurehub",
                ProviderPriorityPolicy.PROVIDER_DRAMACHI
            )
        )
        val animeId = "anilist_515"
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = animeId,
                aniListId = 515,
                backendAnimeId = "b_515",
                preferredTitle = "Neon Horizon",
                isPlayable = true
            )
        )

        val primaryEp = ProphecyEpisodeDto(id = "4khd_515_e1", season = 1, number = 1, title = "Horizon")
        val futureEp = ProphecyEpisodeDto(
            id = "futurehub_515_e1",
            providerMappings = listOf(ProphecyProviderMappingDto(provider = "futurehub", id = "futurehub_515_e1")),
            season = 1,
            number = 1,
            title = "Horizon"
        )
        val dramEp = ProphecyEpisodeDto(id = "dramachi_515_e1", season = 1, number = 1, title = "Horizon")

        val queriedOrder = CopyOnWriteArrayList<String>()
        val fakeApi = object : ProphecyVerseApiService by noopProphecyApi() {
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                queriedOrder += episodeId
                return when (episodeId) {
                    // 4KHDHUB has zero usable sources -> falls back according to configurable priority
                    "4khd_515_e1" -> Response.success(
                        ProphecyEpisodeFullResponse(selectedEpisode = primaryEp, sources = emptyList(), sourcesStatus = "empty")
                    )
                    "futurehub_515_e1" -> Response.success(
                        ProphecyEpisodeFullResponse(
                            selectedEpisode = futureEp,
                            sources = listOf(ProphecySourceDto(id = "src_future_1", label = "FutureHub")),
                            sourcesStatus = "available"
                        )
                    )
                    else -> Response.success(
                        ProphecyEpisodeFullResponse(
                            selectedEpisode = dramEp,
                            sources = listOf(ProphecySourceDto(id = "src_dram_1", label = "Dramachi")),
                            sourcesStatus = "available"
                        )
                    )
                }
            }

            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> =
                Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = request.sourceId,
                            playbackUrl = "https://cdn.example.com/future/master.m3u8",
                            urlType = "hls"
                        )
                    )
                )
        }

        val repo = AnimeRepository(
            database = database,
            prophecyApi = fakeApi,
            aniListApi = idleAniList(),
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker(),
            providerPolicy = customPolicy
        )

        val catalogResult = repo.syncMultiProviderCatalog(
            animeId = animeId,
            payloads = listOf(
                ProviderCatalogPayload(
                    providerId = "futurehub",
                    animeIdentity = "Neon Horizon",
                    seasons = listOf(
                        ProphecySeasonDto(number = 1, episodes = listOf(futureEp)),
                        ProphecySeasonDto(
                            number = 2,
                            episodes = listOf(ProphecyEpisodeDto(id = "futurehub_515_s2e1", season = 2, number = 1, title = "Extra"))
                        )
                    ),
                    episodes = emptyList()
                ),
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_DRAMACHI,
                    animeIdentity = "Neon Horizon",
                    seasons = listOf(ProphecySeasonDto(number = 1, episodes = listOf(dramEp))),
                    episodes = listOf(dramEp)
                ),
                ProviderCatalogPayload(
                    providerId = ProviderPriorityPolicy.PROVIDER_4KHDHUB,
                    animeIdentity = "Neon Horizon",
                    seasons = listOf(ProphecySeasonDto(number = 1, episodes = listOf(primaryEp))),
                    episodes = listOf(primaryEp)
                )
            )
        )

        // Canonical authority remains strictly 4KHDHUB (only S1 E1, futurehub S2 rejected)
        assertEquals(1, catalogResult.episodes.size)
        assertEquals("4khd_515_e1", catalogResult.episodes.single().id)

        // Playback respects custom priority: 4KHDHUB (empty) -> FutureHub (wins) -> Dramachi (never queried!)
        val playback = repo.resolvePlayableStream(animeId, 1, 1, "4khd_515_e1")
        assertTrue(playback.isSuccess)
        assertEquals("src_future_1", playback.getOrThrow().source.id)
        assertEquals(listOf("4khd_515_e1", "futurehub_515_e1"), queriedOrder)
    }

    // ============================================================================================
    // PHASE 2: APK UPDATE POPUP & FIRESTORE VERSION CONFIG PARSING
    // ============================================================================================

    @Test
    fun phase2_releasedHigherVersionCode_producesForceAndOptionalUpdateStates_acrossSchemaVariants() {
        // Variant A: snake_case Firestore schema with Map downloads and stringified numbers
        val snakeForceDoc = mapOf<String, Any?>(
            "enabled" to true,
            "force_update" to true,
            "latest_version_code" to "8",
            "latest_version_name" to "3.0.3",
            "min_supported_version_code" to 8L,
            "package_name" to "com.aistudio.prophecyverse.vykqtb",
            "downloads" to mapOf(
                "github" to mapOf(
                    "id" to "github",
                    "enabled" to true,
                    "label" to "Direct APK",
                    "url" to "https://downloads.example.com/ProphecyVerse-v3.0.3.apk",
                    "priority" to 1,
                    "is_direct_apk" to true
                )
            ),
            "release_notes_en" to "Critical playback update."
        )
        val parsedForce = parseVersionControlConfig(
            rawData = snakeForceDoc,
            expectedPackageName = "com.aistudio.prophecyverse.vykqtb",
            currentVersionCode = 7,
            currentVersionName = "3.0.2"
        )
        assertTrue(parsedForce is VersionConfigParseResult.Valid)
        val forceConfig = (parsedForce as VersionConfigParseResult.Valid).config
        assertTrue(forceConfig.downloads.single().isDirectApk)
        val forceState = VersionControlManager.evaluateVersionState(currentCode = 7, config = forceConfig)
        assertTrue(forceState is VersionCheckState.ForceUpdate)

        // Variant B: camelCase schema with optional fields + direct top-level apk_url fallback
        val camelOptionalDoc = mapOf<String, Any?>(
            "packageName" to "com.aistudio.prophecyverse.vykqtb",
            "latestVersionCode" to 8,
            "minSupportedVersionCode" to 6,
            "forceUpdate" to false,
            "apk_url" to "https://cdn.example.com/releases/app-release.apk"
        )
        val parsedOptional = parseVersionControlConfig(
            rawData = camelOptionalDoc,
            expectedPackageName = "com.aistudio.prophecyverse.vykqtb",
            currentVersionCode = 7,
            currentVersionName = "3.0.2"
        )
        assertTrue(parsedOptional is VersionConfigParseResult.Valid)
        val optConfig = (parsedOptional as VersionConfigParseResult.Valid).config
        val optState = VersionControlManager.evaluateVersionState(currentCode = 7, config = optConfig)
        assertTrue(optState is VersionCheckState.OptionalUpdate)
        assertEquals("v8", optConfig.latestVersionName)
        assertEquals(1, optConfig.downloads.size)
        assertTrue(optConfig.downloads.single().isDirectApk)
    }

    @Test
    fun phase2_sameOrOlderVersionCode_doesNotTriggerUpdatePopup() {
        val docForCurrentBuild = mapOf<String, Any?>(
            "enabled" to true,
            "force_update" to false,
            "latest_version_code" to 7,
            "latest_version_name" to "3.0.2",
            "min_supported_version_code" to 6,
            "package_name" to "com.aistudio.prophecyverse.vykqtb"
        )
        val parsedSame = parseVersionControlConfig(
            rawData = docForCurrentBuild,
            expectedPackageName = "com.aistudio.prophecyverse.vykqtb",
            currentVersionCode = 7,
            currentVersionName = "3.0.2"
        )
        assertTrue(parsedSame is VersionConfigParseResult.Valid)
        val sameState = VersionControlManager.evaluateVersionState(7, (parsedSame as VersionConfigParseResult.Valid).config)
        assertTrue(sameState is VersionCheckState.UpToDate)

        // Current app ahead of latest_version_code (version 8 > 7) -> UpToDate, never ForceUpdate/OptionalUpdate
        val newerInstalledState = VersionControlManager.evaluateVersionState(8, parsedSame.config)
        assertTrue(newerInstalledState is VersionCheckState.UpToDate)
    }

    @Test
    fun phase2_packageMismatchAndMalformedConfig_failSafelyWithoutCrashing() {
        val mismatchedPkg = mapOf<String, Any?>(
            "enabled" to true,
            "latest_version_code" to 9,
            "package_name" to "com.other.app",
            "apk_url" to "https://cdn.example.com/other.apk"
        )
        val pkgRes = parseVersionControlConfig(mismatchedPkg, expectedPackageName = "com.aistudio.prophecyverse.vykqtb", 7, "3.0.2")
        assertTrue(pkgRes is VersionConfigParseResult.Invalid)
        assertEquals("package_name", (pkgRes as VersionConfigParseResult.Invalid).field)

        val nonHttpsUrl = mapOf<String, Any?>(
            "enabled" to true,
            "latest_version_code" to 9,
            "package_name" to "com.aistudio.prophecyverse.vykqtb",
            "downloads" to listOf(mapOf("url" to "http://insecure.example.com/app.apk", "enabled" to true))
        )
        val urlRes = parseVersionControlConfig(nonHttpsUrl, expectedPackageName = "com.aistudio.prophecyverse.vykqtb", 7, "3.0.2")
        assertTrue(urlRes is VersionConfigParseResult.Invalid)
        assertEquals("downloads", (urlRes as VersionConfigParseResult.Invalid).field)

        val minExceedsLatest = mapOf<String, Any?>(
            "enabled" to true,
            "latest_version_code" to 7,
            "min_supported_version_code" to 9,
            "package_name" to "com.aistudio.prophecyverse.vykqtb",
            "apk_url" to "https://cdn.example.com/app.apk"
        )
        val minRes = parseVersionControlConfig(minExceedsLatest, expectedPackageName = "com.aistudio.prophecyverse.vykqtb", 7, "3.0.2")
        assertTrue(minRes is VersionConfigParseResult.Invalid)
        assertEquals("min_supported_version_code", (minRes as VersionConfigParseResult.Invalid).field)
    }

    @Test
    fun phase2_optionalUpdateDismissalAndCacheInvalidation_rePromptsForNewerRelease() {
        val v8Optional = VersionCheckState.OptionalUpdate(
            VersionControlConfig(
                enabled = true,
                forceUpdate = false,
                latestVersionCode = 8,
                minSupportedVersionCode = 6,
                latestVersionName = "3.0.3"
            )
        )
        val v9Optional = VersionCheckState.OptionalUpdate(
            v8Optional.config.copy(latestVersionCode = 9, latestVersionName = "3.0.4")
        )
        val dismissedAt = 1_000_000L

        // Dismissed for versionCode=8 inside 24h window -> suppressed for v8
        assertFalse(
            shouldShowOptionalUpdatePrompt(
                optionalUpdate = v8Optional,
                dismissedOptionalVersionCode = 8,
                dismissedOptionalAtMs = dismissedAt,
                nowMs = dismissedAt + 60_000L
            )
        )
        // New release versionCode=9 arrives inside the 24h window -> MUST immediately re-prompt!
        assertTrue(
            shouldShowOptionalUpdatePrompt(
                optionalUpdate = v9Optional,
                dismissedOptionalVersionCode = 8,
                dismissedOptionalAtMs = dismissedAt,
                nowMs = dismissedAt + 60_000L
            )
        )
        // Same v8 after 24h interval elapses -> re-prompts
        assertTrue(
            shouldShowOptionalUpdatePrompt(
                optionalUpdate = v8Optional,
                dismissedOptionalVersionCode = 8,
                dismissedOptionalAtMs = dismissedAt,
                nowMs = dismissedAt + OPTIONAL_UPDATE_REMINDER_INTERVAL_MS
            )
        )
        // Hidden during active player route or PiP mode
        assertFalse(
            shouldShowOptionalUpdatePrompt(
                optionalUpdate = v9Optional,
                dismissedOptionalVersionCode = -1,
                dismissedOptionalAtMs = 0L,
                isPlayerRoute = true
            )
        )
    }

    // ============================================================================================
    // PHASE 3: ANILIST LATEST FEEDS RETRY, PARTIAL RECOVERY & CACHE PRESERVATION
    // ============================================================================================

    @Test
    fun phase3_aniListHttp429And5xx_retriesBoundedlyAndPreservesCachedCatalog() = runTest {
        val cachedAnime = AnimeEntry(
            id = "anilist_10",
            aniListId = 10,
            preferredTitle = "Cached Legend",
            isTrending = true,
            isSeasonal = true,
            isPopular = true,
            updatedAt = System.currentTimeMillis()
        )
        database.animeDao().upsertAnime(cachedAnime)

        // Scenario A: HTTP 429 then HTTP 503 then 200 OK -> succeeds within max 2 retries (3 total attempts)
        val callsA = AtomicInteger(0)
        val aniListRecovering = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> {
                return when (callsA.incrementAndGet()) {
                    1 -> Response.error(
                        429,
                        "rate limited".toResponseBody(null)
                    )
                    2 -> Response.error(503, "upstream busy".toResponseBody(null))
                    else -> Response.success(
                        AniListGraphQLResponse(
                            data = AniListData(
                                trending = AniListPage(
                                    media = listOf(AniListMedia(id = 20, title = AniListTitle(english = "Recovered Hit")))
                                ),
                                seasonal = AniListPage(media = emptyList()),
                                popular = AniListPage(media = emptyList()),
                                movies = AniListPage(media = emptyList())
                            )
                        )
                    )
                }
            }
        }
        val repoA = AnimeRepository(
            database = database,
            prophecyApi = noopProphecyApi(),
            aniListApi = aniListRecovering,
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker()
        )

        val recoveryRes = repoA.refreshHomeDiscovery(force = true)
        assertTrue("Transient 429/503 should recover cleanly on retry", recoveryRes.isSuccess)
        assertEquals(3, callsA.get())
        assertEquals("anilist_20", database.animeDao().getTrendingOnce().single().id)

        // Scenario B: Exhausted 503s -> fails cleanly after 3 attempts AND preserves cached catalog
        val callsB = AtomicInteger(0)
        val aniListAlways503 = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> {
                callsB.incrementAndGet()
                return Response.error(
                    503,
                    "service unavailable".toResponseBody("text/plain".toMediaType())
                )
            }
        }
        val repoB = AnimeRepository(
            database = database,
            prophecyApi = noopProphecyApi(),
            aniListApi = aniListAlways503,
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker()
        )
        val exhaustedRes = repoB.refreshHomeDiscovery(force = true)
        assertTrue(exhaustedRes.isFailure)
        assertEquals(3, callsB.get())
        assertEquals(listOf("anilist_20"), database.animeDao().getTrendingOnce().map { it.id })
    }

    @Test
    fun phase3_aniListSocketTimeout_retriesBoundedlyAndPreservesCachedCatalog() = runTest {
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_55",
                aniListId = 55,
                preferredTitle = "Preserved Title",
                isTrending = true
            )
        )
        val attempts = AtomicInteger(0)
        val timeoutThenFailApi = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> {
                attempts.incrementAndGet()
                throw SocketTimeoutException("read timed out")
            }
        }
        val repo = AnimeRepository(
            database = database,
            prophecyApi = noopProphecyApi(),
            aniListApi = timeoutThenFailApi,
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker()
        )

        val result = repo.refreshHomeDiscovery(force = true)
        assertTrue(result.isFailure)
        assertEquals(3, attempts.get())
        assertEquals(listOf("anilist_55"), database.animeDao().getTrendingOnce().map { it.id })
    }

    @Test
    fun phase3_aniListGraphQLErrorsWithNullData_failsCleanlyAndPreservesCachedCatalog() = runTest {
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_88",
                aniListId = 88,
                preferredTitle = "Cached Before GraphQL Error",
                isTrending = true,
                isPopular = true
            )
        )
        val calls = AtomicInteger(0)
        val gqlErrorApi = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> {
                calls.incrementAndGet()
                return Response.success(
                    AniListGraphQLResponse(
                        data = null,
                        errors = listOf(AniListGraphQLError(message = "Too many requests", status = 429))
                    )
                )
            }
        }
        val repo = AnimeRepository(
            database = database,
            prophecyApi = noopProphecyApi(),
            aniListApi = gqlErrorApi,
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker()
        )

        val result = repo.refreshHomeDiscovery(force = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Too many requests"))
        assertEquals(1, calls.get())
        assertEquals(listOf("anilist_88"), database.animeDao().getTrendingOnce().map { it.id })
        assertEquals(listOf("anilist_88"), database.animeDao().getPopularOnce().map { it.id })
    }

    @Test
    fun phase3_aniListPartialGraphQLResponse_preservesUnaffectedCachedSectionFlags() = runTest {
        // Cached anime previously populated seasonal & popular sections
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_101",
                aniListId = 101,
                preferredTitle = "Cached Seasonal Hit",
                isSeasonal = true,
                isPopular = true
            )
        )
        // AniList returns valid trending item alongside GraphQL error with null seasonal/popular sections
        val partialGqlApi = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
                Response.success(
                    AniListGraphQLResponse(
                        data = AniListData(
                            trending = AniListPage(
                                media = listOf(
                                    AniListMedia(id = 202, title = AniListTitle(english = "New Trending Hit"))
                                )
                            ),
                            seasonal = null,
                            popular = null,
                            movies = null
                        ),
                        errors = listOf(AniListGraphQLError(message = "seasonal resolver unavailable", status = 500))
                    )
                )
        }
        val repo = AnimeRepository(
            database = database,
            prophecyApi = noopProphecyApi(),
            aniListApi = partialGqlApi,
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker()
        )

        val result = repo.refreshHomeDiscovery(force = true)
        // Reports partial failure so caller knows feed had an error, while updating trending & preserving cached sections
        assertTrue(result.isFailure)
        assertEquals(listOf("anilist_202"), database.animeDao().getTrendingOnce().map { it.id })
        assertEquals(
            "Unaffected cached seasonal flag must NOT be wiped by null/errored partial section",
            listOf("anilist_101"),
            database.animeDao().getSeasonalOnce().map { it.id }
        )
        assertEquals(
            "Unaffected cached popular flag must NOT be wiped by null/errored partial section",
            listOf("anilist_101"),
            database.animeDao().getPopularOnce().map { it.id }
        )
    }

    @Test
    fun phase3_aniListMalformedPayload_failsSafelyAndPreservesCachedCatalog() = runTest {
        // 1. Verify Moshi safely deserializes null entries inside Page.media without crashing
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(AniListGraphQLResponse::class.java)
        val parsedNullEntries = adapter.fromJson(
            """{"data":{"trending":{"media":[null,{"id":0,"title":{"english":"Bad ID"}}]},"seasonal":{"media":null}}}"""
        )
        assertNotNull(parsedNullEntries)

        // 2. Verify repository preserves cached flags and returns failure cleanly when payload has only null/invalid items
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_303",
                aniListId = 303,
                preferredTitle = "Preserved Cached Entry",
                isTrending = true,
                isSeasonal = true,
                isPopular = true
            )
        )
        val malformedApi = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
                Response.success(
                    AniListGraphQLResponse(
                        data = AniListData(
                            trending = AniListPage(media = listOf(null, AniListMedia(id = 0, title = null))),
                            seasonal = AniListPage(media = null),
                            popular = AniListPage(media = emptyList()),
                            movies = null
                        )
                    )
                )
        }
        val repo = AnimeRepository(
            database = database,
            prophecyApi = noopProphecyApi(),
            aniListApi = malformedApi,
            d1DataSource = createTestD1DataSource(),
            memoryCache = MemoryCacheManager(),
            metricsTracker = DatabaseMetricsTracker()
        )

        val res = repo.refreshHomeDiscovery(force = true)
        assertTrue(res.isFailure)
        assertNull(res.getOrNull())
        assertEquals(listOf("anilist_303"), database.animeDao().getTrendingOnce().map { it.id })
        assertEquals(listOf("anilist_303"), database.animeDao().getSeasonalOnce().map { it.id })
        assertEquals(listOf("anilist_303"), database.animeDao().getPopularOnce().map { it.id })
    }
}
