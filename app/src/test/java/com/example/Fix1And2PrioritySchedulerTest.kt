package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
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
import com.example.data.repository.EpisodeResolutionCoordinator
import com.example.data.repository.SourceIdentityValidator
import com.example.data.repository.resolveVerifiedPlaybackForEpisode
import com.example.data.streaming.MonotonicMetrics
import com.example.player.PreResolvePlan
import com.example.player.PreResolveQueueManager
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Fix1And2PrioritySchedulerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var metricsTracker: DatabaseMetricsTracker
    private lateinit var memoryCache: MemoryCacheManager

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
        memoryCache = MemoryCacheManager(metricsTracker = metricsTracker)
        SourceIdentityValidator.clearDiagnosticsForTests()
        MonotonicMetrics.clear()
        while (EpisodeResolutionCoordinator.isForegroundResolutionActive()) {
            EpisodeResolutionCoordinator.endForegroundResolution()
        }
    }

    @After
    fun tearDown() {
        while (EpisodeResolutionCoordinator.isForegroundResolutionActive()) {
            EpisodeResolutionCoordinator.endForegroundResolution()
        }
        db.close()
    }

    private fun noOpAniList(): AniListGraphQLService = object : AniListGraphQLService {
        override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
            Response.success(AniListGraphQLResponse(AniListData(null)))
    }

    private fun createRepo(api: ProphecyVerseApiService): AnimeRepository {
        return AnimeRepository(
            database = db,
            prophecyApi = api,
            aniListApi = noOpAniList(),
            applicationContext = context,
            d1DataSource = createTestD1DataSource(metricsTracker),
            memoryCache = memoryCache,
            metricsTracker = metricsTracker
        )
    }

    private suspend fun seedSeries(animeId: String, backendId: String, episodeCount: Int): List<EpisodeEntry> {
        db.animeDao().upsertAnime(
            AnimeEntry(
                id = animeId,
                aniListId = animeId.removePrefix("anilist_").toIntOrNull(),
                backendAnimeId = backendId,
                preferredTitle = "Test Series $animeId",
                format = "TV",
                mediaCategory = "ANIME",
                isPlayable = true
            )
        )
        val eps = (1..episodeCount).map { num ->
            EpisodeEntry("${animeId}_s1_e$num", animeId, 1, num, "Episode $num")
        }
        db.episodeDao().upsertEpisodes(eps)
        return eps
    }

    // -------------------------------------------------------------------------
    // FIX 1 TESTS
    // -------------------------------------------------------------------------

    @Test
    fun fix1_currentEpisodeResolvesImmediatelyWhileBackgroundSlotsAreSaturated() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val bgJobs = (1..4).map { idx ->
            async(Dispatchers.IO) {
                EpisodeResolutionCoordinator.resolve(
                    key = "fix1:bg-saturated-$idx",
                    priority = EpisodeResolutionCoordinator.Priority.BACKGROUND
                ) {
                    gate.await()
                    "bg-$idx"
                }
            }
        }
        delay(80L)

        val startedAt = System.nanoTime() / 1_000_000
        val fgResult = EpisodeResolutionCoordinator.resolve(
            key = "fix1:fg-immediate",
            priority = EpisodeResolutionCoordinator.Priority.FOREGROUND
        ) {
            "fg-ready"
        }
        val elapsedMs = (System.nanoTime() / 1_000_000) - startedAt

        assertEquals("fg-ready", fgResult)
        assertTrue("Foreground resolution must not wait behind saturated background slots ($elapsedMs ms)", elapsedMs < 500L)

        gate.complete(Unit)
        bgJobs.forEach { it.await() }
    }

    @Test
    fun fix1_foregroundPromotesPausedOrInFlightBackgroundRequest_andPreventsDuplicateRequests() = runBlocking {
        EpisodeResolutionCoordinator.beginForegroundResolution()
        val execCalls = AtomicInteger(0)
        val bgJob = async(Dispatchers.IO) {
            EpisodeResolutionCoordinator.resolve(
                key = "fix1:promote-shared",
                priority = EpisodeResolutionCoordinator.Priority.BACKGROUND
            ) {
                execCalls.incrementAndGet()
                delay(50L)
                "shared-url"
            }
        }
        delay(80L)
        // Background flight is paused because another foreground flag is active.
        assertEquals("Paused background flight must not execute yet", 0, execCalls.get())

        // Foreground asks for the SAME key -> must promote & wake it immediately even though outer foreground flag is still active!
        val fgResult = EpisodeResolutionCoordinator.resolve(
            key = "fix1:promote-shared",
            priority = EpisodeResolutionCoordinator.Priority.FOREGROUND
        ) {
            execCalls.incrementAndGet()
            "duplicate-should-not-run"
        }
        EpisodeResolutionCoordinator.endForegroundResolution()

        assertEquals("shared-url", fgResult)
        assertEquals("shared-url", bgJob.await())
        assertEquals("Exactly ONE execution must run for background + promoted foreground", 1, execCalls.get())
    }

    @Test
    fun fix1_backgroundCancellationDoesNotCancelRequestNeededByForeground() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()

        val bgJob = async(Dispatchers.IO) {
            EpisodeResolutionCoordinator.resolve(
                key = "fix1:cancel-guard",
                priority = EpisodeResolutionCoordinator.Priority.BACKGROUND
            ) {
                started.complete(Unit)
                proceed.await()
                "survivor-url"
            }
        }
        started.await()

        val fgJob = async(Dispatchers.IO) {
            EpisodeResolutionCoordinator.resolve(
                key = "fix1:cancel-guard",
                priority = EpisodeResolutionCoordinator.Priority.FOREGROUND
            ) {
                "unexpected-duplicate"
            }
        }
        delay(60L)

        // Cancel the background waiter (simulating slideWindow cancelling workerJob)
        bgJob.cancel()
        delay(60L)

        // Now allow underlying resolution to finish -> foreground waiter must still receive it!
        proceed.complete(Unit)
        assertEquals("survivor-url", fgJob.await())
    }

    @Test
    fun fix1_backgroundWaitsPastOldThreePointFiveSecondWindowWhenForegroundIsActive() = runBlocking {
        val animeId = "anilist_7101"
        val eps = seedSeries(animeId, "be_7101", 3)
        val preResolveCalls = Collections.synchronizedList(mutableListOf<String>())

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_7101", title = "Test"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(eps.map { ProphecyEpisodeDto(id = it.id, season = 1, number = it.episodeNumber) })
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                preResolveCalls.add(episodeId)
                return Response.success(
                    ProphecyEpisodeFullResponse(sources = listOf(ProphecySourceDto(id = "src_$episodeId", label = "Fast")))
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) =
                Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(sourceId = request.sourceId, playbackUrl = "https://cdn.example/${request.sourceId}.mp4")
                    )
                )
        }

        val repo = createRepo(api)
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = PreResolveQueueManager(repo, managerScope)

        EpisodeResolutionCoordinator.beginForegroundResolution()
        manager.slideWindow(animeId, eps[0], eps, isMovie = false)

        // Hold foreground active for 3.7 seconds (longer than the old 3.5s polling bypass)
        delay(3_700L)
        assertTrue(
            "Background pre-resolution must NOT bypass foreground lock after 3.5s, got $preResolveCalls",
            preResolveCalls.isEmpty()
        )

        // Release foreground -> background resumes immediately
        EpisodeResolutionCoordinator.endForegroundResolution()
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline && preResolveCalls.size < 2) {
            delay(30L)
        }
        assertEquals(2, preResolveCalls.size)

        manager.clear()
        managerScope.cancel()
    }

    @Test
    fun fix1_slowProviderDoesNotUnnecessarilyDelayFastProvider() = runBlocking {
        val animeId = "anilist_7102"
        val eps = seedSeries(animeId, "be_7102", 1)

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_7102", title = "Provider Race"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = eps[0].id, season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(
                            ProphecySourceDto(id = "src_slow", label = "SlowProvider", quality = "1080p"),
                            ProphecySourceDto(id = "src_fast", label = "FastProvider", quality = "720p")
                        )
                    )
                )
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                return if (request.sourceId == "src_slow") {
                    delay(5_000L)
                    Response.success(
                        DirectPlayResponse(success = true, data = DirectPlayData(sourceId = "src_slow", playbackUrl = "https://cdn.example/slow.mp4"))
                    )
                } else {
                    delay(40L)
                    Response.success(
                        DirectPlayResponse(success = true, data = DirectPlayData(sourceId = "src_fast", playbackUrl = "https://cdn.example/fast.mp4"))
                    )
                }
            }
        }

        val repo = createRepo(api)
        val startedAt = System.nanoTime() / 1_000_000
        val res = repo.resolveVerifiedPlaybackForEpisode(animeId, eps[0], isForeground = true)
        val elapsedMs = (System.nanoTime() / 1_000_000) - startedAt

        assertTrue(res.isSuccess)
        assertEquals("https://cdn.example/fast.mp4", res.getOrThrow().directData.playbackUrl)
        assertTrue("Fast provider should finish well before 5s slow provider ($elapsedMs ms)", elapsedMs < 2_000L)
    }

    // -------------------------------------------------------------------------
    // FIX 2 TESTS
    // -------------------------------------------------------------------------

    @Test
    fun fix2_nextFourStartsHighestPriority_thenFollowingSix_withNoPreviousOrCurrent() = runBlocking {
        val animeId = "anilist_7201"
        val eps = seedSeries(animeId, "be_7201", 30)
        val resolveStartOrder = Collections.synchronizedList(mutableListOf<Int>())

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_7201", title = "Order Show"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(eps.map { ProphecyEpisodeDto(id = it.id, season = 1, number = it.episodeNumber) })
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                val epNum = episodeId.substringAfterLast("_e").toInt()
                resolveStartOrder.add(epNum)
                delay(15L)
                return Response.success(
                    ProphecyEpisodeFullResponse(sources = listOf(ProphecySourceDto(id = "src_$epNum", label = "Pahe")))
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) =
                Response.success(
                    DirectPlayResponse(success = true, data = DirectPlayData(sourceId = request.sourceId, playbackUrl = "https://cdn.example/${request.sourceId}.mp4"))
                )
        }

        val repo = createRepo(api)
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = PreResolveQueueManager(repo, managerScope)

        // Current is Ep 7 -> Next 10 = 8..17 (Fast 4 = 8..11, Background 6 = 12..17)
        val current = eps[6]
        manager.slideWindow(animeId, current, eps, isMovie = false)

        val expectedTotal = 10
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val statuses = manager.queueStatus.value
            if (statuses.size == expectedTotal && statuses.values.all { it == PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE }) {
                break
            }
            delay(30L)
        }

        assertEquals(expectedTotal, resolveStartOrder.size)
        val overlapMargin = PreResolveQueueManager.MAX_BACKGROUND_CONCURRENCY - 1
        assertTrue(
            "Fast 4 (8..11) must start ahead of background tier, got order=$resolveStartOrder",
            resolveStartOrder.take(4 + overlapMargin).containsAll((8..11).toList())
        )
        assertTrue(
            "Next 10 (8..17) must all be prepared, got order=$resolveStartOrder",
            resolveStartOrder.containsAll((8..17).toList())
        )
        assertTrue(
            "Current and previous episodes (1..7) must not be queued, got order=$resolveStartOrder",
            (1..7).none { it in resolveStartOrder }
        )

        manager.clear()
        managerScope.cancel()
    }

    @Test
    fun fix2_slowEpisodeInNextFourDoesNotStallRemainingEpisodesOrTiers() = runBlocking {
        val animeId = "anilist_7202"
        val eps = seedSeries(animeId, "be_7202", 14)
        val slowEpGate = CompletableDeferred<Unit>()
        val resolvedEps = ConcurrentHashMap.newKeySet<Int>()

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_7202", title = "Non-Blocking Tier"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(eps.map { ProphecyEpisodeDto(id = it.id, season = 1, number = it.episodeNumber) })
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                val epNum = episodeId.substringAfterLast("_e").toInt()
                if (epNum == 2) {
                    slowEpGate.await()
                }
                resolvedEps.add(epNum)
                return Response.success(
                    ProphecyEpisodeFullResponse(sources = listOf(ProphecySourceDto(id = "src_$epNum", label = "Pahe")))
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) =
                Response.success(
                    DirectPlayResponse(success = true, data = DirectPlayData(sourceId = request.sourceId, playbackUrl = "https://cdn.example/${request.sourceId}.mp4"))
                )
        }

        val repo = createRepo(api)
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = PreResolveQueueManager(repo, managerScope)

        // Current Ep 1 -> Next 10 window is 2..11. Ep 2 is slow; Ep 3..11 MUST NOT wait for Ep 2!
        manager.slideWindow(animeId, eps[0], eps, isMovie = false)

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if ((3..11).all { it in resolvedEps }) break
            delay(25L)
        }

        assertTrue("Ep 3..11 must all finish even while Ep 2 is still blocked: resolved=$resolvedEps", (3..11).all { it in resolvedEps })
        assertFalse("Ep 2 gate is still blocked", 2 in resolvedEps)

        slowEpGate.complete(Unit)
        val ep2Deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < ep2Deadline && 2 !in resolvedEps) {
            delay(20L)
        }
        assertTrue(2 in resolvedEps)

        manager.clear()
        managerScope.cancel()
    }

    @Test
    fun fix2_expiredCacheEvictedAndReresolved_invalidIdsIgnored_clearCancelsCleanly() = runBlocking {
        val animeId = "anilist_7203"
        val eps = seedSeries(animeId, "be_7203", 5)
        val invalidBlankEp = EpisodeEntry("", animeId, 1, 99, "Blank")
        val invalidMovieEp = EpisodeEntry("movie_7203", animeId, 1, 100, "Movie")

        // Plan must ignore blank / movie IDs
        val planned = PreResolvePlan.upcoming(eps[0], eps + listOf(invalidBlankEp, invalidMovieEp))
        assertEquals(listOf("anilist_7203_s1_e2", "anilist_7203_s1_e3", "anilist_7203_s1_e4", "anilist_7203_s1_e5"), planned.map { it.id })

        val resolveCalls = ConcurrentHashMap<String, AtomicInteger>()
        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_7203", title = "Cache Expiry"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(eps.map { ProphecyEpisodeDto(id = it.id, season = 1, number = it.episodeNumber) })
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                resolveCalls.computeIfAbsent(episodeId) { AtomicInteger(0) }.incrementAndGet()
                return Response.success(
                    ProphecyEpisodeFullResponse(sources = listOf(ProphecySourceDto(id = "src_$episodeId", label = "Pahe")))
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) =
                Response.success(
                    DirectPlayResponse(success = true, data = DirectPlayData(sourceId = request.sourceId, playbackUrl = "https://cdn.example/${request.sourceId}.mp4"))
                )
        }

        val repo = createRepo(api)
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = PreResolveQueueManager(repo, managerScope)

        // Inject an expired prepared entry into preparedCache for Ep 2 directly via discard/reflection test setup
        val ep2Key = PreResolveQueueManager.identity(animeId, eps[1])
        val field = PreResolveQueueManager::class.java.getDeclaredField("preparedCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cacheMap = field.get(manager) as ConcurrentHashMap<String, PreResolveQueueManager.PreparedEpisode>
        cacheMap[ep2Key] = PreResolveQueueManager.PreparedEpisode(
            backendAnimeId = animeId,
            episodeId = eps[1].id,
            seasonNumber = 1,
            source = ProphecySourceDto(id = "src_expired", label = "Pahe"),
            directData = DirectPlayData(
                sourceId = "src_expired",
                playbackUrl = "https://cdn.example/expired.mp4",
                expiresAt = "2020-01-01T00:00:00Z"
            ),
            resolvedAtMs = System.currentTimeMillis() - 60_000L
        )
        assertNull("Expired prepared entry must not be returned", manager.getPreparedSource(animeId, eps[1]))

        manager.slideWindow(animeId, eps[0], eps, isMovie = false)
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (manager.queueStatus.value[ep2Key] == PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE &&
                manager.queueStatus.value[PreResolveQueueManager.identity(animeId, eps[4])] == PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE
            ) {
                break
            }
            delay(25L)
        }

        assertNotNull("Ep 2 must have been re-resolved cleanly after expiry", manager.getPreparedSource(animeId, eps[1]))
        assertEquals(1, resolveCalls[eps[1].id]?.get())

        manager.clear()
        assertTrue(manager.queueStatus.value.isEmpty())
        assertNull(manager.getPreparedSource(animeId, eps[1]))
        managerScope.cancel()
    }

    @Test
    fun fix2_slideWindowFromEp1ToEp2ReusesPreparedCacheAndReprioritizesWithoutDuplicateCalls() = runBlocking {
        val animeId = "anilist_7204"
        val eps = seedSeries(animeId, "be_7204", 12)
        val resolveCounts = ConcurrentHashMap<String, AtomicInteger>()

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_7204", title = "Slide Reuse"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(eps.map { ProphecyEpisodeDto(id = it.id, season = 1, number = it.episodeNumber) })
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                resolveCounts.computeIfAbsent(episodeId) { AtomicInteger(0) }.incrementAndGet()
                return Response.success(
                    ProphecyEpisodeFullResponse(sources = listOf(ProphecySourceDto(id = "src_$episodeId", label = "Pahe")))
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) =
                Response.success(
                    DirectPlayResponse(success = true, data = DirectPlayData(sourceId = request.sourceId, playbackUrl = "https://cdn.example/${request.sourceId}.mp4"))
                )
        }

        val repo = createRepo(api)
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = PreResolveQueueManager(repo, managerScope)

        // Ep 1 playing -> Next 10 window is Ep 2..11
        manager.slideWindow(animeId, eps[0], eps, isMovie = false)
        val ep2Key = PreResolveQueueManager.identity(animeId, eps[1])
        val ep9Key = PreResolveQueueManager.identity(animeId, eps[8])
        val ep12Key = PreResolveQueueManager.identity(animeId, eps[11])
        val deadline1 = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline1) {
            if ((2..11).all { n -> manager.queueStatus.value[PreResolveQueueManager.identity(animeId, eps[n - 1])] == PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE }) {
                break
            }
            delay(25L)
        }
        assertEquals(1, resolveCounts[eps[2].id]?.get())
        assertEquals(1, resolveCounts[eps[8].id]?.get())
        assertEquals(0, resolveCounts[eps[11].id]?.get() ?: 0)

        // Slide from Ep 1 -> Ep 2: Next 10 window is Ep 3..12; Ep 3..11 are reused and only Ep 12 resolves newly
        manager.slideWindow(animeId, eps[1], eps, isMovie = false)
        val deadline2 = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline2) {
            if (manager.queueStatus.value[ep12Key] == PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE) {
                break
            }
            delay(25L)
        }

        // Overlapping prepared episodes must NOT be re-resolved over network
        for (idx in 2..11) {
            assertEquals("Ep ${idx + 1} should only be resolved once across slideWindow", 1, resolveCounts[eps[idx].id]?.get())
        }
        assertEquals(PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE, manager.queueStatus.value[ep9Key])
        assertEquals(PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE, manager.queueStatus.value[ep12Key])
        assertNotNull("Ep 2 cached entry remains valid when accessed", manager.getPreparedSource(animeId, eps[1]))
        assertEquals(null, manager.queueStatus.value[ep2Key])

        manager.clear()
        managerScope.cancel()
    }

    @Test
    fun fix2_failedRetryDoesNotBlockOtherSlotsAndSucceedsOnSecondAttempt() = runBlocking {
        val animeId = "anilist_7205"
        val eps = seedSeries(animeId, "be_7205", 5)
        val attempts = ConcurrentHashMap<String, AtomicInteger>()

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_7205", title = "NonBlocking Retry"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(eps.map { ProphecyEpisodeDto(id = it.id, season = 1, number = it.episodeNumber) })
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                val count = attempts.computeIfAbsent(episodeId) { AtomicInteger(0) }.incrementAndGet()
                if (episodeId == eps[1].id && count == 1) {
                    throw java.io.IOException("Transient error on ep2 attempt 1")
                }
                return Response.success(
                    ProphecyEpisodeFullResponse(sources = listOf(ProphecySourceDto(id = "src_$episodeId", label = "Pahe")))
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) =
                Response.success(
                    DirectPlayResponse(success = true, data = DirectPlayData(sourceId = request.sourceId, playbackUrl = "https://cdn.example/${request.sourceId}.mp4"))
                )
        }

        val repo = createRepo(api)
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = PreResolveQueueManager(repo, managerScope)

        val startedAt = System.nanoTime() / 1_000_000
        manager.slideWindow(animeId, eps[0], eps, isMovie = false)

        // Ep 3, 4, 5 MUST finish long before Ep 2's 3-second retry delay elapses!
        val fastDeadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < fastDeadline) {
            val s = manager.queueStatus.value
            if (listOf(eps[2], eps[3], eps[4]).all {
                    s[PreResolveQueueManager.identity(animeId, it)] == PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE
                }
            ) {
                break
            }
            delay(25L)
        }
        val siblingsElapsedMs = (System.nanoTime() / 1_000_000) - startedAt
        assertTrue("Ep 3..5 must complete quickly ($siblingsElapsedMs ms) without waiting for Ep 2 retry", siblingsElapsedMs < 2_000L)
        assertEquals(
            PreResolveQueueManager.ResolutionStatus.RETRY_PENDING,
            manager.queueStatus.value[PreResolveQueueManager.identity(animeId, eps[1])]
        )

        // Now wait for Ep 2's delayed retry to succeed on attempt 2
        val retryDeadline = System.currentTimeMillis() + 6_000L
        val ep2Key = PreResolveQueueManager.identity(animeId, eps[1])
        while (System.currentTimeMillis() < retryDeadline &&
            manager.queueStatus.value[ep2Key] != PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE
        ) {
            delay(40L)
        }
        assertEquals(PreResolveQueueManager.ResolutionStatus.VERIFIED_PLAYABLE, manager.queueStatus.value[ep2Key])
        assertEquals(2, attempts[eps[1].id]?.get())

        manager.clear()
        managerScope.cancel()
    }
}
