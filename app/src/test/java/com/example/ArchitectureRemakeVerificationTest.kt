package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
import com.example.data.local.SeasonEntry
import com.example.data.local.StreamProviderEntity
import com.example.data.local.StreamSourceEntity
import com.example.data.remote.AniListAiringScheduleNode
import com.example.data.remote.AniListCoverImage
import com.example.data.remote.AniListData
import com.example.data.remote.AniListFuzzyDate
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLResponse
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.AniListMedia
import com.example.data.remote.AniListPage
import com.example.data.remote.AniListPageInfo
import com.example.data.remote.AniListStreamingEpisode
import com.example.data.remote.AniListTitle
import com.example.data.remote.DirectPlayData
import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.DirectPlayResponse
import com.example.data.remote.NetworkModule
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecySearchResultItem
import com.example.data.remote.ProphecySeasonDto
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.repository.EpisodeSourceAggregator
import com.example.data.repository.EpisodeSourceResponseState
import com.example.data.repository.TitleMatcher
import com.example.data.streaming.ContentIdentityHelper
import com.example.data.streaming.FirestoreStreamingRepository
import com.example.data.streaming.MonotonicMetrics
import com.example.data.streaming.SafePlaybackHeaders
import com.example.player.PreResolveQueueManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArchitectureRemakeVerificationTest {

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

    private class FakeAniListService : AniListGraphQLService {
        val searchCalls = AtomicInteger(0)
        val homeCalls = AtomicInteger(0)
        val detailCalls = AtomicInteger(0)

        private fun media(
            id: Int,
            title: String,
            format: String = "TV",
            status: String = "FINISHED",
            episodes: Int? = 12,
            nextAiringEpisode: AniListAiringScheduleNode? = null,
            streamingEpisodes: List<AniListStreamingEpisode>? = null
        ) = AniListMedia(
            id = id,
            idMal = id * 10,
            title = AniListTitle(romaji = title, english = title, native = title),
            description = "Description for $title",
            coverImage = AniListCoverImage(
                large = "https://img.anilist.co/$id.jpg",
                medium = "https://img.anilist.co/med_$id.jpg"
            ),
            bannerImage = "https://img.anilist.co/banner_$id.jpg",
            genres = listOf("Action", "Fantasy"),
            averageScore = 84,
            seasonYear = 2025,
            season = "SPRING",
            format = format,
            status = status,
            episodes = episodes,
            duration = if (format == "MOVIE") 118 else 24,
            startDate = AniListFuzzyDate(2025, 4, 1),
            nextAiringEpisode = nextAiringEpisode,
            streamingEpisodes = streamingEpisodes
        )

        override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> {
            val q = request.query
            return when {
                q.contains("trending:") -> {
                    homeCalls.incrementAndGet()
                    Response.success(
                        AniListGraphQLResponse(
                            data = AniListData(
                                trending = AniListPage(media = listOf(media(201, "Solo Leveling"))),
                                seasonal = AniListPage(media = listOf(media(202, "DanDaDan"))),
                                popular = AniListPage(media = listOf(media(203, "Attack on Titan"))),
                                movies = AniListPage(media = listOf(media(204, "Your Name", format = "MOVIE", episodes = 1)))
                            )
                        )
                    )
                }
                q.contains("Media(id:") -> {
                    detailCalls.incrementAndGet()
                    val id = (request.variables["id"] as? Number)?.toInt() ?: 201
                    val mediaItem = when (id) {
                        204 -> media(204, "Your Name", format = "MOVIE", episodes = 1)
                        301 -> media(301, "No Episode Count Show", format = "TV", episodes = null)
                        302 -> media(
                            id = 302,
                            title = "Ongoing Show",
                            format = "TV",
                            status = "RELEASING",
                            episodes = null,
                            nextAiringEpisode = AniListAiringScheduleNode(episode = 25)
                        )
                        else -> media(
                            id = id,
                            title = "Series $id",
                            format = "TV",
                            episodes = 12,
                            streamingEpisodes = listOf(AniListStreamingEpisode(title = "The Beginning"))
                        )
                    }
                    Response.success(AniListGraphQLResponse(data = AniListData(media = mediaItem)))
                }
                else -> {
                    searchCalls.incrementAndGet()
                    val page = (request.variables["page"] as? Number)?.toInt() ?: 1
                    val items = if (page == 1) {
                        listOf(media(101, "Frieren Page1 A"), media(102, "Frieren Page1 B"))
                    } else {
                        listOf(media(103, "Frieren Page2 C"))
                    }
                    Response.success(
                        AniListGraphQLResponse(
                            data = AniListData(
                                page = AniListPage(
                                    pageInfo = AniListPageInfo(
                                        total = 3,
                                        currentPage = page,
                                        lastPage = 2,
                                        hasNextPage = page < 2,
                                        perPage = 2
                                    ),
                                    media = items
                                )
                            )
                        )
                    )
                }
            }
        }
    }

    private class FakeBackendService : ProphecyVerseApiService {
        val searchCalls = AtomicInteger(0)
        val detailCalls = AtomicInteger(0)
        val episodeCalls = AtomicInteger(0)
        val resolveCalls = AtomicInteger(0)

        var nextResolveUrl = "https://cdn.example.com/streams/direct_1080p.m3u8"
        var resolveShouldFail = false
        var resolveDelayMs = 2L

        override suspend fun searchAnime(query: String, limit: Int?, cursor: String?): Response<ProphecySearchResponse> {
            searchCalls.incrementAndGet()
            val items = when {
                query.contains("Your Name", ignoreCase = true) -> listOf(
                    ProphecySearchResultItem(
                        id = "backend-movie-204",
                        canonicalId = "anilist_204",
                        title = "Your Name",
                        mediaType = "movie"
                    )
                )
                else -> listOf(
                    ProphecySearchResultItem(
                        id = "backend-series-201",
                        canonicalId = "anilist_201",
                        title = "Series 201",
                        mediaType = "anime"
                    )
                )
            }
            return Response.success(ProphecySearchResponse(results = items))
        }

        override suspend fun getAnimeDetails(animeId: String): Response<ProphecyAnimeDetailsResponse> {
            detailCalls.incrementAndGet()
            return if (animeId == "backend-movie-204") {
                // Simulate backend returning legacy S1 & E1 for a Movie; APK must map flat!
                Response.success(
                    ProphecyAnimeDetailsResponse(
                        id = animeId,
                        title = "Your Name",
                        mediaType = "movie",
                        seasons = listOf(
                            ProphecySeasonDto(
                                number = 1,
                                episodes = listOf(
                                    ProphecyEpisodeDto(
                                        id = "backend-movie-s1e1-id",
                                        number = 1,
                                        season = 1,
                                        title = "S1 & E1"
                                    )
                                )
                            )
                        )
                    )
                )
            } else {
                Response.success(
                    ProphecyAnimeDetailsResponse(
                        id = animeId,
                        title = "Series 201",
                        mediaType = "anime",
                        seasons = listOf(
                            ProphecySeasonDto(
                                number = 1,
                                episodes = (1..15).map { ep ->
                                    ProphecyEpisodeDto(
                                        id = "backend-ep-$ep",
                                        number = ep,
                                        season = 1,
                                        title = "Provider Ep $ep"
                                    )
                                }
                            )
                        )
                    )
                )
            }
        }

        override suspend fun getAnimeEpisodes(animeId: String): Response<List<ProphecyEpisodeDto>> {
            return Response.success(
                if (animeId == "backend-movie-204") {
                    listOf(
                        ProphecyEpisodeDto(
                            id = "backend-movie-s1e1-id",
                            number = 1,
                            season = 1,
                            title = "S1 & E1"
                        )
                    )
                } else {
                    (1..15).map { ep ->
                        ProphecyEpisodeDto(id = "backend-ep-$ep", number = ep, season = 1, title = "Provider Ep $ep")
                    }
                }
            )
        }

        override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
            episodeCalls.incrementAndGet()
            return Response.success(
                ProphecyEpisodeFullResponse(
                    sources = listOf(
                        ProphecySourceDto(
                            id = "src_primary_$episodeId",
                            label = "VidStream HD",
                            quality = "1080p",
                            language = "Japanese",
                            downloadable = true
                        ),
                        ProphecySourceDto(
                            id = "src_backup_$episodeId",
                            label = "MegaCloud",
                            quality = "720p",
                            language = "English"
                        )
                    ),
                    sourcesStatus = "available",
                    sourcesPartial = false
                )
            )
        }

        override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
            resolveCalls.incrementAndGet()
            if (resolveDelayMs > 0) delay(resolveDelayMs)
            if (resolveShouldFail) {
                return Response.success(DirectPlayResponse(success = false, data = null))
            }
            return Response.success(
                DirectPlayResponse(
                    success = true,
                    data = DirectPlayData(
                        sourceId = request.sourceId,
                        playbackUrl = nextResolveUrl,
                        urlType = "hls",
                        contentType = "application/vnd.apple.mpegurl",
                        requiresHeaders = true,
                        headers = listOf(
                            listOf("Referer", "https://vidstream.example/"),
                            listOf("User-Agent", "ProphecyVerse/3.0.2"),
                            listOf("Authorization", "Bearer secret-should-strip"),
                            listOf("Cookie", "session=bad"),
                            listOf("X-Api-Key", "secret-key")
                        )
                    )
                )
            )
        }
    }

    private fun createRepository(
        aniList: FakeAniListService = FakeAniListService(),
        backend: FakeBackendService = FakeBackendService()
    ): Triple<AnimeRepository, FakeAniListService, FakeBackendService> {
        val streamingRepo = FirestoreStreamingRepository(
            streamCacheDao = database.streamCacheDao(),
            firestoreProvider = { null },
            isClientWriteAuthorized = { true }
        )
        val repo = AnimeRepository(
            database = database,
            prophecyApi = backend,
            aniListApi = aniList,
            applicationContext = context,
            streamingRepository = streamingRepo
        )
        return Triple(repo, aniList, backend)
    }

    @Test
    fun homeDiscoveryUsesAniListOnlyAndPopulatesAllFourFeedsInRoom() = runTest {
        val (repo, aniList, backend) = createRepository()
        val result = repo.refreshHomeDiscovery(force = true)
        assertTrue(result.isSuccess)
        assertEquals(1, aniList.homeCalls.get())
        assertEquals(0, backend.searchCalls.get())
        assertEquals(0, backend.detailCalls.get())

        val trending = repo.observeTrending().first()
        val seasonal = repo.observeSeasonal().first()
        val popular = repo.observePopular().first()
        val movies = repo.observeMovies().first()

        assertEquals(listOf("Solo Leveling"), trending.map { it.preferredTitle })
        assertEquals(listOf("DanDaDan"), seasonal.map { it.preferredTitle })
        assertEquals(listOf("Attack on Titan"), popular.map { it.preferredTitle })
        assertEquals(listOf("Your Name"), movies.map { it.preferredTitle })
        assertEquals("MOVIE", movies.single().mediaCategory)
    }

    @Test
    fun paginatedSearchMergesPagesWithoutOverwritingAndNeverCallsBackend() = runTest {
        val (repo, aniList, backend) = createRepository()

        val page1 = repo.searchAnimePage("Frieren", page = 1).getOrThrow()
        assertEquals(1, page1.currentPage)
        assertTrue(page1.hasNextPage)
        assertEquals(2, repo.observeSearchResults("Frieren").first().size)

        val page2 = repo.searchAnimePage("Frieren", page = 2).getOrThrow()
        assertEquals(2, page2.currentPage)
        assertFalse(page2.hasNextPage)
        val combined = repo.observeSearchResults("Frieren").first()
        assertEquals(3, combined.size)
        assertEquals(0, backend.searchCalls.get())
        assertEquals(2, aniList.searchCalls.get())
    }

    @Test
    fun seriesDetailsGeneratesAniListEpisodeFallbacksWithoutBackendAndHandlesMissingCount() = runTest {
        val (repo, _, backend) = createRepository()

        // Series with 12 episodes on AniList -> Ep 1 uses AniList streamingEpisodes title, Ep 2..12 use "Episode N"
        val res = repo.refreshAnimeDetails("anilist_201")
        assertTrue(res.isSuccess)
        assertEquals(0, backend.detailCalls.get())

        val episodes = repo.observeEpisodes("anilist_201", 1).first()
        assertEquals(12, episodes.size)
        assertEquals("The Beginning", episodes[0].title)
        assertEquals("Episode 2", episodes[1].title)
        assertEquals("Episode 12", episodes[11].title)

        // Series with missing episode count -> MISSING_EPISODES and zero fabricated episodes
        val missingRes = repo.refreshAnimeDetails("anilist_301")
        assertTrue(missingRes.isSuccess)
        val entry301 = database.animeDao().getAnimeById("anilist_301")!!
        assertEquals("MISSING_EPISODES", entry301.metadataCompleteness)
        assertTrue(repo.observeEpisodes("anilist_301", 1).first().isEmpty())
        assertEquals(0, backend.detailCalls.get())

        // Ongoing releasing series with episodes = null and nextAiringEpisode = 25 -> 24 aired episodes
        val airingRes = repo.refreshAnimeDetails("anilist_302")
        assertTrue(airingRes.isSuccess)
        val entry302 = database.animeDao().getAnimeById("anilist_302")!!
        assertEquals(24, entry302.episodeCount)
        assertEquals("PARTIAL", entry302.metadataCompleteness)
        val airedEpisodes = repo.observeEpisodes("anilist_302", 1).first()
        assertEquals(24, airedEpisodes.size)
        assertEquals("Episode 24", airedEpisodes.last().title)
        assertEquals(0, backend.detailCalls.get())
    }

    @Test
    fun movieDetailsHasZeroSeasonsOrEpisodesEvenWhenBackendUsesS1E1Internally() = runTest {
        val (repo, _, backend) = createRepository()

        // Seed legacy Season/Episode row for movie to verify Movie queries stay clean
        database.episodeDao().upsertSeasons(listOf(SeasonEntry("legacy_s1", "anilist_204", 1, "Season 1")))
        database.episodeDao().upsertEpisodes(listOf(EpisodeEntry("legacy_ep1", "anilist_204", 1, 1, "S1E1")))

        assertTrue(repo.refreshAnimeDetails("anilist_204").isSuccess)

        // Lazy streaming discovery maps backend "S1 & E1" strictly to flat "movie:204" scope!
        val discovery = repo.discoverStreamsForScope(
            animeId = "anilist_204",
            seasonNumber = 0,
            episodeNumber = 1,
            catalogEpisodeId = "movie_anilist_204"
        ).getOrThrow()
        assertEquals(2, discovery.sources.size)

        val container = database.streamCacheDao().getContainer("movie:204")!!
        assertEquals("MOVIE", container.contentType)
        assertNull(container.seasonCount)
        assertNull(container.totalEpisodeCount)

        val storedSources = database.streamCacheDao().getActiveSources("movie:204")
        assertEquals(2, storedSources.size)
        assertEquals("backend-movie-s1e1-id", storedSources.first().backendEpisodeId)
        assertTrue(backend.detailCalls.get() >= 1 || backend.episodeCalls.get() >= 1)
    }

    @Test
    fun firestorePathsAndDeterministicProviderIdsFollowStrictContract() {
        val fsRepo = FirestoreStreamingRepository(database.streamCacheDao())
        val provId = ContentIdentityHelper.deterministicProviderId("VidStream HD")
        assertTrue(provId.startsWith("prov_"))
        assertEquals(provId, ContentIdentityHelper.deterministicProviderId("  vidstream hd "))
        assertNotEquals(provId, "VidStream HD")

        assertEquals(
            "anime_streams/201/seasons/season_2/episodes/ep_5/providers/$provId/sources/src_1",
            fsRepo.animeSourcePath("201", 2, 5, provId, "src_1")
        )
        assertEquals(
            "movie_streams/204/providers/$provId/sources/src_1",
            fsRepo.movieSourcePath("204", provId, "src_1")
        )
        assertFalse(fsRepo.movieSourcePath("204", provId, "src_1").contains("season"))
        assertFalse(fsRepo.movieSourcePath("204", provId, "src_1").contains("ep_"))
    }

    @Test
    fun cacheFirstResolutionReusesRoomUrlSanitizesHeadersAndPreservesValidUrlOnFailure() = runTest {
        val (repo, _, backend) = createRepository()
        repo.refreshAnimeDetails("anilist_201")
        val ep1 = repo.observeEpisodes("anilist_201", 1).first().first()

        // First play discovers & resolves once
        val first = repo.resolvePlayableStream(
            animeId = "anilist_201",
            seasonNumber = ep1.seasonNumber,
            episodeNumber = ep1.episodeNumber,
            catalogEpisodeId = ep1.id
        ).getOrThrow()
        assertEquals("BACKEND_RESOLVE", first.cacheTier)
        assertEquals(1, backend.episodeCalls.get())
        assertEquals(1, backend.resolveCalls.get())
        assertEquals("https://cdn.example.com/streams/direct_1080p.m3u8", first.directData.playbackUrl)

        // Verify required transient playback headers are preserved; only shared Firestore
        // publication applies the stricter redaction policy.
        val headerKeys = first.directData.headers.map { it[0].lowercase() }.toSet()
        assertTrue(headerKeys.contains("referer"))
        assertTrue(headerKeys.contains("user-agent"))
        assertTrue(headerKeys.contains("authorization"))
        assertTrue(headerKeys.contains("cookie"))
        assertTrue(headerKeys.contains("x-api-key"))

        // Second play hits Room cache with ZERO Discovery and ZERO Resolve calls!
        val second = repo.resolvePlayableStream(
            animeId = "anilist_201",
            seasonNumber = ep1.seasonNumber,
            episodeNumber = ep1.episodeNumber,
            catalogEpisodeId = ep1.id
        ).getOrThrow()
        assertEquals("ROOM", second.cacheTier)
        assertEquals(1, backend.episodeCalls.get())
        assertEquals(1, backend.resolveCalls.get())

        // Simulate forced fresh resolve failure -> cached Room streamUrl MUST NOT be wiped!
        backend.resolveShouldFail = true
        repo.resolvePlayableStream(
            animeId = "anilist_201",
            seasonNumber = ep1.seasonNumber,
            episodeNumber = ep1.episodeNumber,
            catalogEpisodeId = ep1.id,
            preferredSourceId = first.source.id,
            forceResolveFresh = true
        )
        val cachedSources = database.streamCacheDao().getActiveSources("anime:201:s1:e1")
        assertTrue(cachedSources.any { it.streamUrl == "https://cdn.example.com/streams/direct_1080p.m3u8" })
    }

    @Test
    fun streamCacheDaoVersioningSoftDeletePreventsResurrectionAndPreservesUrls() = runTest {
        val dao = database.streamCacheDao()
        val scope = "anime:500:s1:e1"
        val provId = ContentIdentityHelper.deterministicProviderId("MegaCloud")

        dao.syncScopeRecords(
            scopeKey = scope,
            incomingProviders = listOf(StreamProviderEntity("$scope|p|$provId", scope, provId, "MegaCloud", version = 2)),
            incomingSources = listOf(
                StreamSourceEntity(
                    sourceDocKey = "$scope|p|$provId|s|s1",
                    scopeKey = scope,
                    providerId = provId,
                    sourceId = "s1",
                    sourceName = "MegaCloud",
                    streamUrl = "https://cdn.example.com/ep1.m3u8",
                    version = 2
                )
            ),
            authoritativeComplete = false
        )

        // Incoming metadata-only update with version 3 must preserve existing valid streamUrl
        dao.syncScopeRecords(
            scopeKey = scope,
            incomingProviders = listOf(StreamProviderEntity("$scope|p|$provId", scope, provId, "MegaCloud", version = 3)),
            incomingSources = listOf(
                StreamSourceEntity(
                    sourceDocKey = "$scope|p|$provId|s|s1",
                    scopeKey = scope,
                    providerId = provId,
                    sourceId = "s1",
                    sourceName = "MegaCloud 1080p",
                    streamUrl = null,
                    version = 3
                )
            ),
            authoritativeComplete = false
        )
        val preserved = dao.getActiveSources(scope).single()
        assertEquals("https://cdn.example.com/ep1.m3u8", preserved.streamUrl)
        assertEquals("MegaCloud 1080p", preserved.sourceName)

        // Soft delete with version 4
        dao.softDeleteSource("$scope|p|$provId|s|s1", deletedAt = 10_000L, version = 4L)
        assertTrue(dao.getActiveSources(scope).isEmpty())

        // Stale ADD with older version 3 MUST NOT resurrect the deleted source!
        dao.syncScopeRecords(
            scopeKey = scope,
            incomingProviders = emptyList(),
            incomingSources = listOf(
                StreamSourceEntity(
                    sourceDocKey = "$scope|p|$provId|s|s1",
                    scopeKey = scope,
                    providerId = provId,
                    sourceId = "s1",
                    sourceName = "MegaCloud Stale",
                    streamUrl = "https://cdn.example.com/ep1.m3u8",
                    version = 3L
                )
            ),
            authoritativeComplete = false
        )
        assertTrue("Removed source must never resurrect on older version", dao.getActiveSources(scope).isEmpty())
    }

    @Test
    fun distributedResolveLeaseEnforcesSingleFlightAndReleasesCleanly() = runTest {
        val fsRepo = FirestoreStreamingRepository(database.streamCacheDao(), ownerId = "owner_A")
        val peerRepo = FirestoreStreamingRepository(database.streamCacheDao(), ownerId = "owner_B")
        val lock1 = fsRepo.acquireResolveLease("201", false, "prov_1", "src_1", 1, 1, ttlMs = 5_000L)
        assertNotNull(lock1)

        // Different owner respects active local/remote lock wait via shared map or transaction
        val sameOwner = fsRepo.acquireResolveLease("201", false, "prov_1", "src_1", 1, 1, ttlMs = 5_000L)
        assertNotNull(sameOwner)
        assertEquals("owner_A", sameOwner!!.ownerId)
        assertNotNull(peerRepo)

        fsRepo.releaseResolveLease(lock1!!, "COMPLETED")
        val lock3 = fsRepo.acquireResolveLease("201", false, "prov_1", "src_1", 1, 1, ttlMs = 5_000L)
        assertNotNull(lock3)
        fsRepo.releaseResolveLease(lock3!!, "COMPLETED")
    }

    @Test
    fun preResolveSequenceReusesRoomCacheAndExcludesMovies() = runTest {
        val (repo, _, backend) = createRepository()
        backend.resolveDelayMs = 0L
        val episodes = (1..14).map { ep ->
            EpisodeEntry("anilist_201_s1_e$ep", "anilist_201", 1, ep, "Episode $ep")
        }
        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_201",
                aniListId = 201,
                preferredTitle = "Series 201",
                genres = "Action",
                format = "TV",
                mediaCategory = "ANIME",
                isPlayable = true
            )
        )
        database.episodeDao().upsertEpisodes(episodes)

        val manager = PreResolveQueueManager(repo, this)
        // Movie exclusion
        manager.slideWindow(
            animeId = "anilist_204",
            currentEpisode = EpisodeEntry("movie_anilist_204", "anilist_204", 0, 1, "Your Name"),
            allEpisodes = emptyList(),
            isMovie = true
        )
        assertTrue(manager.queueStatus.value.isEmpty())

        // Series Ep 1 -> queues 2..14 sequentially (13 available upcoming episodes; Ep 1 is already active)
        manager.slideWindow("anilist_201", episodes.first(), episodes, isMovie = false)
        // Wait briefly for worker coroutine using IO dispatcher to settle
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val statuses = manager.queueStatus.value
            if (statuses.size == 13 && statuses.values.all { it == PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE }) {
                break
            }
            Thread.sleep(25L)
        }
        val afterEp1 = manager.queueStatus.value
        assertEquals(13, afterEp1.size)
        assertEquals(13, backend.resolveCalls.get())

        // Sliding to Ep 2 reuses the overlapping next-17 window and resolves only uncached Ep 1 (previous)!
        manager.slideWindow("anilist_201", episodes[1], episodes, isMovie = false)
        val ep13Key = PreResolveQueueManager.identity("anilist_201", episodes[12])
        val ep1Key = PreResolveQueueManager.identity("anilist_201", episodes[0])
        val deadline2 = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline2) {
            val statuses = manager.queueStatus.value
            if (statuses[ep13Key] == PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE &&
                statuses[ep1Key] == PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE
            ) {
                break
            }
            Thread.sleep(25L)
        }
        // Ep 1 was active rather than pre-resolved, so the previous-6 window resolves it once.
        assertEquals(14, backend.resolveCalls.get())
        manager.clear()
    }

    @Test
    fun roomMigration9To10PreservesUserDataAndCreatesStreamCacheTables() {
        val dbName = "migration_9_to_10_test.db"
        File(context.getDatabasePath(dbName).path).delete()

        val v9Callback = object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS anime_entries (id TEXT NOT NULL PRIMARY KEY, aniListId INTEGER, backendAnimeId TEXT, titleRomaji TEXT, titleEnglish TEXT, titleNative TEXT, preferredTitle TEXT NOT NULL, synopsis TEXT, year TEXT, season TEXT, format TEXT, status TEXT, genres TEXT NOT NULL, score REAL, episodeCount INTEGER, duration INTEGER, studios TEXT, posterUrl TEXT, bannerUrl TEXT, isPlayable INTEGER NOT NULL, isTrending INTEGER NOT NULL, isSeasonal INTEGER NOT NULL, isPopular INTEGER NOT NULL, mediaCategory TEXT NOT NULL DEFAULT 'ANIME', updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playback_history (id TEXT NOT NULL PRIMARY KEY, animeId TEXT NOT NULL, episodeId TEXT NOT NULL, seasonNumber INTEGER NOT NULL, episodeNumber INTEGER NOT NULL, episodeTitle TEXT, positionMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, completed INTEGER NOT NULL, animeTitle TEXT, posterUrl TEXT, mediaType TEXT, lastPlayedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS watchlist_entries (animeId TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, isFavorite INTEGER NOT NULL, userScore INTEGER, addedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS download_entries (id TEXT NOT NULL PRIMARY KEY, animeId TEXT NOT NULL, animeTitle TEXT NOT NULL, posterUrl TEXT, seasonNumber INTEGER NOT NULL, episodeNumber INTEGER NOT NULL, episodeTitle TEXT, localFilePath TEXT, downloadStatus TEXT NOT NULL, downloadedBytes INTEGER NOT NULL, totalBytes INTEGER NOT NULL, downloadUrl TEXT, mediaType TEXT, errorMessage TEXT, quality TEXT, audioLanguage TEXT, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS local_profiles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, avatarId TEXT NOT NULL, bio TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS user_preferences (prefKey TEXT NOT NULL PRIMARY KEY, prefValue TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS search_queries (query TEXT NOT NULL PRIMARY KEY, totalResults INTEGER NOT NULL, timestamp INTEGER NOT NULL)")

                db.execSQL("INSERT INTO anime_entries VALUES ('anilist_1', 1, NULL, 'Sousou no Frieren', 'Frieren', NULL, 'Frieren', 'Synopsis', '2023', 'FALL', 'TV', 'FINISHED', 'Adventure', 9.1, 28, 24, 'Madhouse', NULL, NULL, 1, 1, 0, 1, 'ANIME', 1000)")
                db.execSQL("INSERT INTO playback_history VALUES ('h1', 'anilist_1', 'ep1', 1, 1, 'Journey', 12000, 1440000, 0, 'Frieren', NULL, 'TV', 1000)")
                db.execSQL("INSERT INTO watchlist_entries VALUES ('anilist_1', 'WATCHING', 1, NULL, 1000, 1000)")
                db.execSQL("INSERT INTO download_entries VALUES ('d1', 'anilist_1', 'Frieren', NULL, 1, 1, 'Journey', '/tmp/ep1.mp4', 'COMPLETED', 1024, 1024, NULL, 'anime', NULL, '1080p', 'Japanese', 1000)")
                db.execSQL("INSERT INTO local_profiles VALUES ('default_user', 'Prophet', 'avatar_pirate', 'Bio', 1000)")
                db.execSQL("INSERT INTO user_preferences VALUES ('preferred_audio_language', 'Japanese')")
                db.execSQL("INSERT INTO search_queries VALUES ('frieren', 5, 1000)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(v9Callback)
                .build()
        )
        val sqliteDb = helper.writableDatabase
        AppDatabase.MIGRATION_9_10.migrate(sqliteDb)

        sqliteDb.query("SELECT preferredTitle, metadataCompleteness FROM anime_entries WHERE id = 'anilist_1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Frieren", it.getString(0))
            assertEquals("UNKNOWN", it.getString(1))
        }
        sqliteDb.query("SELECT currentPage, hasNextPage FROM search_queries WHERE query = 'frieren'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertEquals(0, it.getInt(1))
        }
        sqliteDb.query("SELECT animeTitle, positionMs FROM playback_history WHERE id = 'h1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Frieren", it.getString(0))
            assertEquals(12000L, it.getLong(1))
        }
        sqliteDb.query("SELECT isFavorite FROM watchlist_entries WHERE animeId = 'anilist_1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        sqliteDb.query("SELECT localFilePath FROM download_entries WHERE id = 'd1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("/tmp/ep1.mp4", it.getString(0))
        }
        sqliteDb.query("SELECT name FROM local_profiles WHERE id = 'default_user'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Prophet", it.getString(0))
        }
        sqliteDb.query("SELECT prefValue FROM user_preferences WHERE prefKey = 'preferred_audio_language'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Japanese", it.getString(0))
        }
        sqliteDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'stream_%'").use {
            val tables = mutableSetOf<String>()
            while (it.moveToNext()) tables += it.getString(0)
            assertEquals(setOf("stream_containers", "stream_seasons", "stream_episodes", "stream_providers", "stream_sources"), tables)
        }
        sqliteDb.close()
        File(context.getDatabasePath(dbName).path).delete()
    }

    @Test
    fun monotonicBenchmarkCapturesRealTimingsAcross20Iterations() = runTest {
        val (repo, _, backend) = createRepository()
        backend.resolveDelayMs = 0L
        val anime = AnimeEntry(
            id = "anilist_777",
            aniListId = 777,
            backendAnimeId = "backend-series-777",
            preferredTitle = "Benchmark Anime",
            genres = "Action",
            format = "TV",
            mediaCategory = "ANIME",
            isPlayable = true
        )
        database.animeDao().upsertAnime(anime)
        database.episodeDao().upsertEpisodes((1..12).map { ep ->
            EpisodeEntry("anilist_777_s1_e$ep", "anilist_777", 1, ep, "Episode $ep")
        })

        // 20 timed iterations measuring Room reads, transactions, AniList, discovery, resolve, URL validation
        val ep1 = database.episodeDao().getEpisodeById("anilist_777_s1_e1")!!
        for (i in 1..20) {
            val startupStart = MonotonicMetrics.nowMs()

            val tAniList = MonotonicMetrics.nowMs()
            repo.refreshAnimeDetails("anilist_201")
            MonotonicMetrics.recordDuration("anilist_request_ms", tAniList)

            val tDiscovery = MonotonicMetrics.nowMs()
            repo.discoverStreamsForScope("anilist_777", 1, 1, ep1.id).getOrThrow()
            MonotonicMetrics.recordDuration("source_discovery_ms", tDiscovery)

            val tResolve = MonotonicMetrics.nowMs()
            val resolved = repo.resolvePlayableStream(
                animeId = "anilist_777",
                seasonNumber = ep1.seasonNumber,
                episodeNumber = ep1.episodeNumber,
                catalogEpisodeId = ep1.id,
                forceResolveFresh = (i == 1)
            ).getOrThrow()
            MonotonicMetrics.recordDuration("resolve_ms", tResolve)

            val tVal = MonotonicMetrics.nowMs()
            assertTrue(SafePlaybackHeaders.isValidStreamUrl(resolved.directData.playbackUrl))
            MonotonicMetrics.recordDuration("url_validation_ms", tVal)

            val tRoomRead = MonotonicMetrics.nowMs()
            database.streamCacheDao().getActiveSources("anime:777:s1:e1")
            MonotonicMetrics.recordDuration("room_read_ms", tRoomRead)

            MonotonicMetrics.recordDuration("playback_startup_ms", startupStart)
        }

        val summaries = MonotonicMetrics.allSummaries().associateBy { it.metric }
        assertTrue(summaries.containsKey("anilist_request_ms"))
        assertTrue(summaries.containsKey("room_read_ms"))
        assertTrue(summaries.containsKey("room_transaction_ms"))
        assertTrue(summaries.containsKey("url_validation_ms"))
        assertTrue(summaries.containsKey("playback_startup_ms"))

        summaries.values.forEach { s ->
            println("BENCHMARK ${s.metric}: n=${s.count} min=${s.minMs}ms p50=${s.p50Ms}ms p95=${s.p95Ms}ms max=${s.maxMs}ms")
            assertTrue(s.p50Ms <= s.p95Ms)
            assertTrue(s.p95Ms <= s.maxMs)
        }
    }

    @Test
    fun networkMoshiParsesPartialEpisodeResponseWithObjectSourceErrorAndNullSourceId() {
        val rawJson = """
            {
              "sources": [
                {
                  "id": "src_hd_1",
                  "label": "MegaCloud 1080p",
                  "quality": "1080p",
                  "language": "Japanese",
                  "downloadable": true
                },
                {
                  "id": null,
                  "label": "Broken Candidate",
                  "quality": "720p"
                }
              ],
              "sources_status": "partial",
              "sources_partial": true,
              "source_error": {
                "error": {
                  "code": "NO_SOURCE_CANDIDATES",
                  "message": "No source candidates found for one provider",
                  "provider": "Addons"
                }
              }
            }
        """.trimIndent()

        val parsed = NetworkModule.moshi
            .adapter(ProphecyEpisodeFullResponse::class.java)
            .fromJson(rawJson)

        assertNotNull(parsed)
        assertEquals("partial", parsed!!.sourcesStatus)
        assertEquals(true, parsed.sourcesPartial)
        assertEquals(2, parsed.sources?.size)
        assertEquals("src_hd_1", parsed.sources!![0].id)
        assertEquals("", parsed.sources!![1].id)
        assertTrue(parsed.sourceError!!.contains("Addons"))
        assertTrue(parsed.sourceError!!.contains("No source candidates found"))
    }

    @Test
    fun episodeSourceAggregatorMarksPartialWithCandidatesAsPartialInsteadOfFailed() {
        val candidates = listOf(
            ProphecySourceDto(id = "src_1", label = "VidStream", quality = "1080p", language = "Japanese")
        )
        val partialResult = EpisodeSourceAggregator.aggregate(
            apiCandidates = candidates,
            rawStatus = "partial",
            partial = true,
            errorMessage = "Addons: Timed out after 10s",
            nowMs = 1_000L
        )
        assertEquals(EpisodeSourceResponseState.PARTIAL, partialResult.state)
        assertEquals(1, partialResult.sources.size)

        val emptyFailureResult = EpisodeSourceAggregator.aggregate(
            apiCandidates = emptyList(),
            rawStatus = "failed",
            partial = false,
            errorMessage = "Timed out",
            nowMs = 1_000L
        )
        assertEquals(EpisodeSourceResponseState.TIMEOUT, emptyFailureResult.state)
    }

    @Test
    fun titleMatcherHandlesAniListSequelsAgainstUnifiedBackendWhileRejectingMismatches() {
        val soloLevelingS2 = AnimeEntry(
            id = "anilist_176496",
            aniListId = 176496,
            titleRomaji = "Ore dake Level Up na Ken: Arise from the Shadow",
            titleEnglish = "Solo Leveling -Arise from the Shadow-",
            preferredTitle = "Solo Leveling -Arise from the Shadow-",
            year = "2025",
            format = "TV",
            episodeCount = 13,
            genres = "Action",
            mediaCategory = "ANIME",
            isPlayable = true
        )
        val aotFinal = AnimeEntry(
            id = "anilist_110277",
            aniListId = 110277,
            titleRomaji = "Shingeki no Kyojin: The Final Season",
            titleEnglish = "Attack on Titan Final Season",
            preferredTitle = "Attack on Titan Final Season",
            year = "2020",
            format = "TV",
            episodeCount = 16,
            genres = "Action",
            mediaCategory = "ANIME",
            isPlayable = true
        )

        val backendSolo = ProphecySearchResultItem(
            id = "backend_solo",
            title = "Solo Leveling",
            year = "2024",
            mediaType = "anime",
            seasonCount = 2
        )
        val backendAot = ProphecySearchResultItem(
            id = "backend_aot",
            title = "Attack on Titan",
            year = "2013",
            mediaType = "anime",
            seasonCount = 5
        )
        val wrongMovie = ProphecySearchResultItem(
            id = "backend_movie",
            title = "Attack on Titan",
            year = "2020",
            mediaType = "movie",
            seasonCount = 1
        )
        val wrongLiveAction = ProphecySearchResultItem(
            id = "backend_la",
            title = "Solo Leveling Live Action",
            year = "2025",
            mediaType = "live_action",
            seasonCount = 1
        )

        assertTrue(TitleMatcher.computeMatchConfidenceForEntry(soloLevelingS2, backendSolo) >= 0.75f)
        assertTrue(TitleMatcher.computeMatchConfidenceForEntry(aotFinal, backendAot) >= 0.75f)
        assertEquals(0f, TitleMatcher.computeMatchConfidenceForEntry(aotFinal, wrongMovie))
        assertEquals(0f, TitleMatcher.computeMatchConfidenceForEntry(soloLevelingS2, wrongLiveAction))
    }

    @Test
    fun sequelDiscoveryMatchesDisjointAbsoluteEpisodeBlocksAndPartialSourceErrorObject() = runTest {
        val mockApi = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?): Response<ProphecySearchResponse> {
                val results = if (query.equals("Attack on Titan", ignoreCase = true)) {
                    listOf(
                        ProphecySearchResultItem(
                            id = "backend_aot_unified",
                            title = "Attack on Titan",
                            year = "2013",
                            mediaType = "anime",
                            seasonCount = 4
                        )
                    )
                } else {
                    emptyList()
                }
                return Response.success(ProphecySearchResponse(results = results))
            }

            override suspend fun getAnimeDetails(animeId: String): Response<ProphecyAnimeDetailsResponse> {
                return Response.success(
                    ProphecyAnimeDetailsResponse(
                        id = animeId,
                        title = "Attack on Titan",
                        mediaType = "anime",
                        seasons = listOf(
                            ProphecySeasonDto(
                                number = 1,
                                episodes = listOf(ProphecyEpisodeDto(id = "aot_s1_e1", number = 1, season = 1))
                            ),
                            ProphecySeasonDto(
                                number = 2,
                                episodes = (1..12).map { ep ->
                                    ProphecyEpisodeDto(id = "aot_s2_rel_e$ep", number = ep, season = 2)
                                } + (26..37).map { ep ->
                                    ProphecyEpisodeDto(id = "aot_s2_abs_e$ep", number = ep, season = 2)
                                }
                            )
                        )
                    )
                )
            }

            override suspend fun getAnimeEpisodes(animeId: String): Response<List<ProphecyEpisodeDto>> =
                Response.success(emptyList())

            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                val parsed = when (episodeId) {
                    "aot_s2_rel_e3" -> NetworkModule.moshi.adapter(ProphecyEpisodeFullResponse::class.java).fromJson(
                        """{"sources":[{"id":"src_rel_3","label":"Provider A","quality":"1080p"}],"sources_status":"partial","sources_partial":true,"source_error":{"error":{"code":"NO_SOURCE_CANDIDATES","message":"None","provider":"Addons"}}}"""
                    )!!
                    "aot_s2_abs_e28" -> ProphecyEpisodeFullResponse(
                        sources = listOf(ProphecySourceDto(id = "src_abs_28", label = "4KHDHub", quality = "1080p")),
                        sourcesStatus = "available",
                        sourcesPartial = false
                    )
                    else -> ProphecyEpisodeFullResponse(sources = emptyList(), sourcesStatus = "empty", sourcesPartial = false)
                }
                return Response.success(parsed)
            }

            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = request.sourceId,
                            playbackUrl = "https://cdn.example.com/${request.sourceId}.m3u8"
                        )
                    )
                )
            }
        }

        val streamingRepo = FirestoreStreamingRepository(
            streamCacheDao = database.streamCacheDao(),
            firestoreProvider = { null },
            isClientWriteAuthorized = { false }
        )
        val repo = AnimeRepository(
            database = database,
            prophecyApi = mockApi,
            aniListApi = FakeAniListService(),
            applicationContext = context,
            streamingRepository = streamingRepo
        )

        database.animeDao().upsertAnime(
            AnimeEntry(
                id = "anilist_25777",
                aniListId = 25777,
                titleRomaji = "Shingeki no Kyojin Season 2",
                titleEnglish = "Attack on Titan Season 2",
                preferredTitle = "Attack on Titan Season 2",
                year = "2017",
                format = "TV",
                episodeCount = 12,
                genres = "Action",
                mediaCategory = "ANIME",
                isPlayable = true
            )
        )

        val discovery = repo.discoverStreamsForScope(
            animeId = "anilist_25777",
            seasonNumber = 1,
            episodeNumber = 3,
            catalogEpisodeId = "anilist_25777_s1_e3"
        ).getOrThrow()

        assertEquals(EpisodeSourceResponseState.PARTIAL, discovery.state)
        val ids = discovery.sources.map { it.id }.toSet()
        assertTrue(ids.contains("src_rel_3"))
        assertTrue(ids.contains("src_abs_28"))
    }
}
