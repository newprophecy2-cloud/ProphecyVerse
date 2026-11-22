package com.example

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1ListResponse
import com.example.data.d1.D1RecordResponse
import com.example.data.d1.D1StreamSourceRecordDto
import com.example.data.d1.D1TablesResponse
import com.example.data.firebase.CloudHistoryRepository
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
import com.example.data.local.WatchlistEntry
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.remote.AniListData
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLResponse
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.DirectPlayData
import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.DirectPlayResponse
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.player.PlayerManager
import com.example.player.PreResolvePlan
import com.example.player.PreResolveQueueManager
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NextTenPreloadAndOptimizationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var metricsTracker: DatabaseMetricsTracker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            runCatching { FirebaseApp.initializeApp(context) }
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        metricsTracker = DatabaseMetricsTracker()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedSeries(
        animeId: String,
        backendAnimeId: String,
        season1Count: Int,
        season2Count: Int = 0
    ): List<EpisodeEntry> {
        db.animeDao().upsertAnime(
            AnimeEntry(
                id = animeId,
                aniListId = animeId.removePrefix("anilist_").toIntOrNull(),
                backendAnimeId = backendAnimeId,
                preferredTitle = "Preload Series $animeId",
                format = "TV",
                mediaCategory = "ANIME",
                isPlayable = true
            )
        )
        val s1 = (1..season1Count).map { ep ->
            EpisodeEntry(
                id = "${backendAnimeId}_s1_e$ep",
                animeId = animeId,
                seasonNumber = 1,
                episodeNumber = ep,
                title = "S1 Ep $ep",
                isAvailable = true
            )
        }
        val s2 = (1..season2Count).map { ep ->
            EpisodeEntry(
                id = "${backendAnimeId}_s2_e$ep",
                animeId = animeId,
                seasonNumber = 2,
                episodeNumber = ep,
                title = "S2 Ep $ep",
                isAvailable = true
            )
        }
        val all = s1 + s2
        db.episodeDao().upsertEpisodes(all)
        return all
    }

    private fun createRepo(
        api: ProphecyVerseApiService,
        d1Api: CloudflareD1ApiService = FakeInMemoryD1Api(),
        memoryCache: MemoryCacheManager = MemoryCacheManager(metricsTracker = metricsTracker)
    ): AnimeRepository {
        val aniList = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest) =
                Response.success(AniListGraphQLResponse(AniListData(null)))
        }
        return AnimeRepository(
            database = db,
            prophecyApi = api,
            aniListApi = aniList,
            applicationContext = context,
            d1DataSource = createTestD1DataSource(metricsTracker, d1Api),
            memoryCache = memoryCache,
            metricsTracker = metricsTracker
        )
    }

    @Test
    fun next10Preload_ep1Prepares2To11_ep2Prepares3To12_ep3Prepares4To13_stopsAtSeasonBoundary() {
        val season1 = (1..15).map { EpisodeEntry("be_s1_e$it", "anilist_10", 1, it) }
        val season2 = (1..5).map { EpisodeEntry("be_s2_e$it", "anilist_10", 2, it) }
        val all = season1 + season2

        val w1 = PreResolvePlan.upcoming(season1[0], all)
        assertEquals((2..11).toList(), w1.map { it.episodeNumber })
        assertFalse("Current episode must never be in preload queue", season1[0] in w1)

        val w2 = PreResolvePlan.upcoming(season1[1], all)
        assertEquals((3..12).toList(), w2.map { it.episodeNumber })
        assertFalse(season1[1] in w2)

        val w3 = PreResolvePlan.upcoming(season1[2], all)
        assertEquals((4..13).toList(), w3.map { it.episodeNumber })
        assertFalse(season1[2] in w3)

        // Season 1 Ep 10 -> stops cleanly at Season 1 Ep 15 without crossing into Season 2
        val boundary = PreResolvePlan.upcoming(season1[9], all)
        assertEquals((11..15).toList(), boundary.map { it.episodeNumber })
        assertEquals(setOf(1), boundary.map { it.seasonNumber }.toSet())
    }

    @Test
    fun next10Preload_databaseFirstD1HitSkipsResolver_andMissResolvesAndSavesToD1() = runBlocking {
        val animeId = "anilist_9101"
        val backendId = "be_9101"
        val eps = seedSeries(animeId, backendId, 4)
        val fakeD1 = FakeInMemoryD1Api()
        val memoryCache = MemoryCacheManager(metricsTracker = metricsTracker)

        // Pre-seed Episode 2 source snapshot + D1 valid record (DB-first hit -> 0 resolveDirectPlay calls for Ep 2)
        val src2 = ProphecySourceDto(id = "src_ep2", label = "Pahe", quality = "1080p")
        seedEpisodeSourceSnapshot(memoryCache, "9101:s1:e2", 1, 2, listOf(src2))
        val d1Key2 = CloudflareD1DataSource.deterministicRecordId(backendId, eps[1].id, "src_ep2")
        fakeD1.records[d1Key2] = D1StreamSourceRecordDto(
            sourceDocKey = d1Key2,
            scopeKey = backendId,
            providerId = "pahe",
            sourceId = "src_ep2",
            sourceName = "src_ep2",
            backendEpisodeId = eps[1].id,
            streamUrl = "https://d1.example/ep2_cached.mp4",
            urlType = "direct",
            expiresAt = "2099-01-01T00:00:00Z",
            status = "AVAILABLE"
        )

        val resolvedSourceCalls = ConcurrentHashMap<String, AtomicInteger>()
        val api = LegacyFixtureToV3Adapter(object : LegacyFixtureApi {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = backendId, title = "D1 Hit Test"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(eps.map { ProphecyEpisodeDto(id = it.id, season = 1, number = it.episodeNumber) })
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                val n = episodeId.substringAfterLast("_e").toInt()
                return Response.success(
                    ProphecyEpisodeFullResponse(sources = listOf(ProphecySourceDto(id = "src_ep$n", label = "Pahe")))
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                resolvedSourceCalls.computeIfAbsent(request.sourceId) { AtomicInteger(0) }.incrementAndGet()
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = request.sourceId,
                            playbackUrl = "https://resolver.example/${request.sourceId}.mp4",
                            expiresAt = "2099-01-01T00:00:00Z"
                        )
                    )
                )
            }
        })

        val repo = createRepo(api, fakeD1, memoryCache)
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = PreResolveQueueManager(repo, managerScope, metricsTracker)

        manager.slideWindow(animeId, eps[0], eps, isMovie = false)

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if ((2..4).all { n ->
                    manager.queueStatus.value[PreResolveQueueManager.identity(animeId, eps[n - 1])] ==
                        PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE
                }
            ) break
            delay(25L)
        }

        // Ep 2 came directly from D1 -> zero resolver calls for src_ep2
        assertEquals(0, resolvedSourceCalls["src_ep2"]?.get() ?: 0)
        val preparedEp2 = manager.getPreparedSource(animeId, eps[1])
        assertNotNull(preparedEp2)
        assertEquals("https://d1.example/ep2_cached.mp4", preparedEp2!!.directData.playbackUrl)

        // Ep 3 & Ep 4 missed D1 -> resolver called once each and persisted to D1
        assertEquals(1, resolvedSourceCalls["src_ep3"]?.get())
        assertEquals(1, resolvedSourceCalls["src_ep4"]?.get())
        assertTrue(fakeD1.records.values.any { it.sourceId == "src_ep3" && it.streamUrl == "https://resolver.example/src_ep3.mp4" })
        assertTrue(fakeD1.records.values.any { it.sourceId == "src_ep4" && it.streamUrl == "https://resolver.example/src_ep4.mp4" })

        manager.clear()
        managerScope.cancel()
    }

    @Test
    fun next10Preload_cancelsObsoleteJobsAndRejectsStaleResultsOnSourceOrEpisodeSwitch() = runBlocking {
        val animeId = "anilist_9102"
        val backendId = "be_9102"
        val eps = seedSeries(animeId, backendId, 4)
        val ep2Gate = CompletableDeferred<Unit>()
        val sourceResolved = ConcurrentHashMap<String, String>()

        val api = LegacyFixtureToV3Adapter(object : LegacyFixtureApi {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = backendId, title = "Switch Guard"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(eps.map { ProphecyEpisodeDto(id = it.id, season = 1, number = it.episodeNumber) })
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                val n = episodeId.substringAfterLast("_e").toInt()
                return Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(
                            ProphecySourceDto(id = "src_a_$n", label = "Pahe", quality = "1080p"),
                            ProphecySourceDto(id = "src_b_$n", label = "Kiwi", quality = "720p")
                        )
                    )
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                if (request.sourceId == "src_a_2") {
                    ep2Gate.await()
                }
                val url = "https://cdn.example/${request.sourceId}.mp4"
                sourceResolved[request.sourceId] = url
                return Response.success(
                    DirectPlayResponse(success = true, data = DirectPlayData(sourceId = request.sourceId, playbackUrl = url))
                )
            }
        })

        val repo = createRepo(api)
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = PreResolveQueueManager(repo, managerScope, metricsTracker)

        // Start preload preferring src_a_1 while Ep 1 plays -> Ep 2 pauses on src_a_2 gate
        manager.slideWindow(animeId, eps[0], eps, isMovie = false, preferredSourceId = "src_a_2")
        delay(80L)

        // Immediately switch source preference to Kiwi / src_b_2
        manager.slideWindow(animeId, eps[0], eps, isMovie = false, preferredSourceId = "src_b_2")
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val prep = manager.getPreparedSource(animeId, eps[1], preferredSourceId = "src_b_2")
            if (prep != null) break
            delay(25L)
        }
        // Complete the old slow gate after generation already changed
        ep2Gate.complete(Unit)
        delay(100L)

        // Requesting preferred source src_b_2 returns src_b_2; requesting mismatched src_c_2 returns null
        val activePrepared = manager.getPreparedSource(animeId, eps[1], preferredSourceId = "src_b_2")
        assertNotNull(activePrepared)
        assertEquals("src_b_2", activePrepared!!.source.id)
        assertNull("Mismatched preferredSourceId must not return wrong source", manager.getPreparedSource(animeId, eps[1], preferredSourceId = "src_a_2"))

        manager.clear()
        managerScope.cancel()
    }

    @Test
    fun d1Optimization_404MissSkipsBlindListFallbackByDefault_andBatchAndCoalescingWork() = runBlocking {
        val getCalls = AtomicInteger(0)
        val listCalls = AtomicInteger(0)
        val singleFlightGate = CompletableDeferred<Unit>()

        val mockD1 = object : CloudflareD1ApiService {
            override suspend fun listTables() = Response.success(D1TablesResponse(true, 1, listOf("stream_sources")))
            override suspend fun listStreamSources(where: String?, limit: Int?, offset: Int?): Response<D1ListResponse<D1StreamSourceRecordDto>> {
                listCalls.incrementAndGet()
                val rec1 = D1StreamSourceRecordDto(
                    sourceDocKey = "rec_ep10_s1",
                    scopeKey = "be_99:s1:e10",
                    providerId = "pahe",
                    sourceId = "s1",
                    sourceName = "s1",
                    backendEpisodeId = "ep_10",
                    streamUrl = "https://d1.example/ep10_s1.mp4",
                    expiresAt = "2099-01-01T00:00:00Z",
                    status = "AVAILABLE"
                )
                return Response.success(
                    D1ListResponse(true, "stream_sources", 1, limit ?: 50, offset ?: 0, 1, listOf(rec1))
                )
            }
            override suspend fun getStreamSource(id: String): Response<D1RecordResponse<D1StreamSourceRecordDto>> {
                getCalls.incrementAndGet()
                return Response.error(404, """{"success":false,"error":"not found"}""".toResponseBody(null))
            }
            override suspend fun upsertStreamSource(record: D1StreamSourceRecordDto) =
                Response.error<D1RecordResponse<D1StreamSourceRecordDto>>(500, "".toResponseBody(null))
            override suspend fun deleteStreamSource(id: String) =
                Response.error<D1RecordResponse<D1StreamSourceRecordDto>>(500, "".toResponseBody(null))
        }

        val ds = createTestD1DataSource(metricsTracker, mockD1)

        // 1. Strict 404 lookup (allowCompositeFallback = false) makes only 1 GET, 0 list calls
        val miss = ds.getValidStreamUrl(
            animeId = "be_99:s1:e10",
            episodeId = "ep_10",
            sourceId = "missing",
            allowCompositeFallback = false
        )
        assertNull(miss)
        assertEquals(1, getCalls.get())
        assertEquals(0, listCalls.get())

        // 2. Concurrent getStreamUrlsForEpisode calls share 1 listStreamSources query via coalescing
        val d1Delayed = object : CloudflareD1ApiService by mockD1 {
            override suspend fun listStreamSources(where: String?, limit: Int?, offset: Int?): Response<D1ListResponse<D1StreamSourceRecordDto>> {
                singleFlightGate.await()
                return mockD1.listStreamSources(where, limit, offset)
            }
        }
        val listCounterBefore = listCalls.get()
        val coalescedDs = createTestD1DataSource(metricsTracker, d1Delayed)
        val jobs = (1..5).map {
            async(Dispatchers.IO) {
                coalescedDs.getStreamUrlsForEpisodeResult("be_99:s1:e10", "ep_10")
            }
        }
        delay(60L)
        singleFlightGate.complete(Unit)
        val results = jobs.awaitAll()
        assertEquals(1, listCalls.get() - listCounterBefore)
        assertTrue(results.all { it.querySucceeded && it.streams.size == 1 })
    }

    @Test
    fun memoryCache_completeFourPartIdentityIsolation_andProactiveExpiryEviction() {
        val fakeClock = AtomicLong(1_000_000L)
        val cache = MemoryCacheManager(metricsTracker = metricsTracker, clockMs = { fakeClock.get() })

        val d1 = DirectPlayData(sourceId = "sub_hd", playbackUrl = "https://cdn.example/s1_e1.mp4", expiresAt = "2099-01-01T00:00:00Z")
        val d2 = DirectPlayData(sourceId = "sub_hd", playbackUrl = "https://cdn.example/s1_e2.mp4", expiresAt = "2099-01-01T00:00:00Z")

        cache.putResolvedPlayback("sub_hd", d1, "be_500:s1:e1", providerId = "pahe", backendEpisodeId = "ep_a")
        cache.putResolvedPlayback("sub_hd", d2, "be_500:s1:e1", providerId = "pahe", backendEpisodeId = "ep_b")

        assertEquals("https://cdn.example/s1_e1.mp4", cache.getResolvedPlayback("sub_hd", "be_500:s1:e1", "pahe", "ep_a")?.playbackUrl)
        assertEquals("https://cdn.example/s1_e2.mp4", cache.getResolvedPlayback("sub_hd", "be_500:s1:e1", "pahe", "ep_b")?.playbackUrl)
        assertNull("Mismatched backendEpisodeId must not collide", cache.getResolvedPlayback("sub_hd", "be_500:s1:e1", "pahe", "ep_other"))

        // Advance past 20-min max TTL -> proactive eviction clears entries
        fakeClock.addAndGet(cache.playbackUrlMaxTtlMs + 10_000L)
        val evicted = cache.evictExpiredPlaybackEntries()
        assertEquals(2, evicted)
        assertNull(cache.getResolvedPlayback("sub_hd", "be_500:s1:e1", "pahe", "ep_a"))
    }

    @Test
    fun playerManager_reusesSingleExoPlayerInstanceAcrossEpisodeSwitch_andIsolatesStream() {
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val playerManager = PlayerManager(context, managerScope, metricsTracker)
        try {
            val stream1 = DirectPlayData(
                sourceId = "src_ep1",
                playbackUrl = "https://cdn.example/ep1.mp4",
                urlType = "direct",
                headers = listOf(
                    listOf("Referer", "https://ref1.example/"),
                    listOf("Authorization", "Bearer secret_1")
                )
            )
            val player1 = playerManager.initializePlayer(
                directData = stream1,
                onRetryNeeded = {},
                providerName = "Pahe",
                sessionIdentity = PlayerManager.PlaybackSessionIdentity("anilist_1", 1, "ep1", 1L)
            )
            assertEquals(listOf("Referer", "Origin", "User-Agent"), playerManager.playbackDiagnostics.value.configuredHeaderNames)
            assertFalse("Authorization must never be forwarded", "Authorization" in playerManager.playbackDiagnostics.value.configuredHeaderNames)

            // Instant switch stops active playback without releasing ExoPlayer, then initializes Ep 2
            playerManager.stopActivePlaybackForSwitch()
            val stream2 = DirectPlayData(
                sourceId = "src_ep2",
                playbackUrl = "https://cdn.example/ep2.m3u8",
                urlType = "hls",
                headers = listOf(listOf("Origin", "https://origin2.example"))
            )
            val player2 = playerManager.initializePlayer(
                directData = stream2,
                onRetryNeeded = {},
                providerName = "Kiwi",
                sessionIdentity = PlayerManager.PlaybackSessionIdentity("anilist_1", 1, "ep2", 2L)
            )

            assertSame("Same ExoPlayer instance must be reused across switches", player1, player2)
            assertEquals("Kiwi", playerManager.playbackDiagnostics.value.providerName)
            assertEquals("hls", playerManager.playbackDiagnostics.value.urlType)
            assertEquals(listOf("Origin", "User-Agent"), playerManager.playbackDiagnostics.value.configuredHeaderNames)
        } finally {
            playerManager.release()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            managerScope.cancel()
        }
    }

    @Test
    fun firestoreWatchlistMergePlan_usesIncrementalCursor_andMetricsTrackerTracksNewTelemetry() = runTest {
        val local = listOf(
            WatchlistEntry(animeId = "anilist_1", status = "WATCHING", isFavorite = true, addedAt = 100L, updatedAt = 500L),
            WatchlistEntry(animeId = "anilist_2", status = "COMPLETED", isFavorite = false, addedAt = 100L, updatedAt = 2_000L)
        )
        val cloudIncremental = listOf(
            // Unchanged locally for anilist_1 -> excluded because updatedAt 500 <= cursor 1000
            WatchlistEntry(animeId = "anilist_3", status = "PLANNING", isFavorite = false, addedAt = 1200L, updatedAt = 1_500L)
        )
        val plan = CloudHistoryRepository.computeWatchlistMergePlan(
            localEntries = local,
            cloudEntries = cloudIncremental,
            incrementalCursor = 1_000L
        )
        assertEquals(listOf("anilist_3"), plan.toUpsertLocal.map { it.animeId })
        assertEquals(listOf("anilist_2"), plan.toPushCloud.map { it.animeId })

        // Verify DatabaseMetricsTracker records new D1, Firestore, Preload & Playback telemetry
        metricsTracker.recordD1CacheHit()
        metricsTracker.recordD1CacheMiss()
        metricsTracker.recordD1FallbackLookup()
        metricsTracker.recordPreloadCycle(d1ReadsDelta = 2L)
        metricsTracker.recordResolverInvocation(45L)
        metricsTracker.recordFirestoreQuery(documentsReturned = 3L)
        metricsTracker.recordFirestoreListenerAttached()
        assertEquals(1L, metricsTracker.snapshot().firestoreActiveListeners)
        metricsTracker.recordFirestoreListenerEvent(2L)
        metricsTracker.recordFirestoreListenerDetached()
        metricsTracker.recordFirestoreIncrementalSync(documentsReturned = 4L)
        metricsTracker.recordFirestoreFullSync(documentsReturned = 5L)
        metricsTracker.recordFirestoreCacheHit()
        metricsTracker.recordReadyQueueHit()
        metricsTracker.recordReadyQueueMiss()
        metricsTracker.recordEpisodeSwitchPrep(12L)
        metricsTracker.recordPlaybackStartLatency(120L)
        metricsTracker.recordBufferingEvent(350L)

        val snap = metricsTracker.snapshot()
        assertEquals(1L, snap.d1CacheHits)
        assertEquals(1L, snap.d1CacheMisses)
        assertEquals(1L, snap.d1FallbackLookups)
        assertEquals(1L, snap.d1PreloadCycles)
        assertEquals(2L, snap.d1PreloadCycleReads)
        assertEquals(1L, snap.resolverInvocations)
        assertEquals(45L, snap.resolverLatencyTotalMs)
        assertEquals(1L, snap.firestoreQueryCount)
        assertEquals(14L, snap.firestoreDocumentsReturned) // 3 query + 2 listener + 4 inc + 5 full
        assertEquals(0L, snap.firestoreActiveListeners)
        assertEquals(1L, snap.firestoreListenerEvents)
        assertEquals(1L, snap.firestoreIncrementalSyncCount)
        assertEquals(1L, snap.firestoreFullSyncCount)
        assertEquals(1L, snap.firestoreCacheHits)
        assertEquals(1L, snap.readyQueueHits)
        assertEquals(1L, snap.readyQueueMisses)
        assertEquals(1L, snap.episodeSwitchPrepCount)
        assertEquals(12L, snap.episodeSwitchPrepTotalMs)
        assertEquals(120L, snap.playbackStartLatencyTotalMs)
        assertEquals(1L, snap.playbackStartLatencyCount)
        assertEquals(1L, snap.bufferingEvents)
        assertEquals(350L, snap.bufferingDurationTotalMs)
    }
}
