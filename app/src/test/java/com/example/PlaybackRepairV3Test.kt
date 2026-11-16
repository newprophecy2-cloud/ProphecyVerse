package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1ListResponse
import com.example.data.d1.D1RecordResponse
import com.example.data.d1.D1StreamSourceRecordDto
import com.example.data.d1.D1TablesResponse
import com.example.data.d1.InMemoryD1DigestStore
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.metrics.PlaybackDiagnosticEventType
import com.example.data.metrics.PlaybackDiagnosticsLogger
import com.example.data.remote.AniListData
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLResponse
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.DirectPlayData
import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.DirectPlayResponse
import com.example.data.remote.NetworkModule
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.repository.EpisodeSourceAggregator
import com.example.data.repository.EpisodeSourceResponseState
import com.example.player.LogicalPlaybackSessionRetryTracker
import com.example.player.PlaybackFailureReason
import com.example.player.PlaybackHealthMonitor
import com.example.player.PlaybackHealthState
import com.example.player.PlaybackRecoveryCoordinator
import com.example.player.PlaybackRecoveryKey
import com.example.player.PlayerManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackRepairV3Test {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PlaybackDiagnosticsLogger.clearForTests()
    }

    @Test
    fun retryTracker_neverResetsOnReady_andResetsOnlyOnNewSession() {
        val tracker = LogicalPlaybackSessionRetryTracker(maxSessionRetries = 2)
        assertEquals(0, tracker.beginNewSession("anime1:ep1:src1"))
        assertTrue(tracker.canRetry())
        assertEquals(1, tracker.consumeRetry())

        // Reaching READY mid-session must preserve consumed retry count
        tracker.onReadyStateReached()
        assertEquals(1, tracker.currentRetryCount())
        assertTrue(tracker.canRetry())

        assertEquals(2, tracker.consumeRetry())
        assertFalse(tracker.canRetry())
        assertNull("Third retry in the same session must be rejected", tracker.consumeRetry())

        // Switching episode/source creates a fresh session budget
        assertEquals(0, tracker.beginNewSession("anime1:ep2:src1"))
        assertTrue(tracker.canRetry())
        assertEquals(1, tracker.consumeRetry())
    }

    @Test
    fun healthMonitor_keepsAliveDuringSlowProgress_andTriggersOnlyOnZeroProgressStall() {
        val monitor = PlaybackHealthMonitor(
            scopeKey = "anime1:ep1",
            sourceId = "src1",
            noProgressStallThresholdSec = 12,
            recentTransferWindowMs = 5_000L
        )
        var bytes = 10_000L
        var bufferedMs = 1_000L
        val now = 100_000L

        // Slow-but-progressing download over 35 ticks must NEVER trigger stall recovery
        for (sec in 1..35) {
            bytes += 512L
            bufferedMs += 100L
            val eval = monitor.evaluateTick(
                isPlaying = false,
                isBuffering = true,
                bytesTransferred = bytes,
                bufferedPositionMs = bufferedMs,
                lastTransferAtMs = now - 6_000L, // old lastTransferAtMs, but bytes/buffer progressed!
                nowMs = now + sec * 1_000L
            )
            assertTrue(eval.isProgressing)
            assertEquals(0, eval.noProgressSeconds)
            assertFalse("Slow progress at ${sec}s must not trigger stall recovery", eval.shouldTriggerStallRecovery)
        }

        // Now freeze bytesTransferred and bufferedPositionMs with stale transfer window
        var stallTriggerCount = 0
        for (sec in 1..15) {
            val eval = monitor.evaluateTick(
                isPlaying = false,
                isBuffering = true,
                bytesTransferred = bytes,
                bufferedPositionMs = bufferedMs,
                lastTransferAtMs = 0L,
                nowMs = now + 40_000L + sec * 1_000L
            )
            if (eval.shouldTriggerStallRecovery) stallTriggerCount++
            if (sec < 12) {
                assertEquals(sec, eval.noProgressSeconds)
                assertFalse(eval.shouldTriggerStallRecovery)
            } else if (sec == 12) {
                assertEquals(PlaybackHealthState.NO_PROGRESS, eval.state)
                assertTrue(eval.shouldTriggerStallRecovery)
            } else {
                assertFalse("Stall trigger must fire only once per buffering stall", eval.shouldTriggerStallRecovery)
            }
        }
        assertEquals(1, stallTriggerCount)

        // Error classification accuracy
        val (expiredState, expiredReason) = monitor.classifyError(PlayerManager.PlaybackFailureCategory.HTTP, 403)
        assertEquals(PlaybackHealthState.EXPIRED_URL, expiredState)
        assertEquals(PlaybackFailureReason.HTTP_EXPIRED_OR_UNAUTHORIZED, expiredReason)

        val (decoderState, decoderReason) = monitor.classifyError(PlayerManager.PlaybackFailureCategory.DECODER, null)
        assertEquals(PlaybackHealthState.DECODER_FAILURE, decoderState)
        assertEquals(PlaybackFailureReason.DECODER_OR_UNSUPPORTED, decoderReason)

        val (timeoutState, timeoutReason) = monitor.classifyError(PlayerManager.PlaybackFailureCategory.NETWORK_TIMEOUT, null)
        assertEquals(PlaybackHealthState.CONFIRMED_FAILURE, timeoutState)
        assertEquals(PlaybackFailureReason.TRANSIENT_NETWORK_ERROR, timeoutReason)
    }

    @Test
    fun recoveryCoordinator_coalescesSameKey_cancelsOldGeneration_andEnforcesBound() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = PlaybackRecoveryCoordinator(
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            maxSessionRecoveries = 2
        )
        coordinator.beginGeneration(1L)

        val keyGen1 = PlaybackRecoveryKey("a1", "ep1", "src1", 1L)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var execCount = 0

        // First trigger starts recovery
        assertTrue(
            coordinator.triggerRecovery(keyGen1, PlaybackFailureReason.HTTP_EXPIRED_OR_UNAUTHORIZED) {
                execCount++
                started.complete(Unit)
                release.await()
            }
        )
        advanceUntilIdle()
        assertTrue(started.isCompleted)

        // Concurrent duplicate trigger for exact same key is coalesced
        assertFalse(
            coordinator.triggerRecovery(keyGen1, PlaybackFailureReason.STALL_NO_PROGRESS) {
                execCount++
            }
        )

        // Stale generation trigger is ignored
        assertFalse(
            coordinator.triggerRecovery(
                PlaybackRecoveryKey("a1", "ep1", "src1", 0L),
                PlaybackFailureReason.TRANSIENT_NETWORK_ERROR
            ) { execCount++ }
        )

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, execCount)

        // Second distinct source on same generation succeeds
        assertTrue(
            coordinator.triggerRecovery(
                PlaybackRecoveryKey("a1", "ep1", "src2", 1L),
                PlaybackFailureReason.TRANSIENT_NETWORK_ERROR
            ) { execCount++ }
        )
        advanceUntilIdle()
        assertEquals(2, execCount)

        // Third recovery on gen 1 hits maxSessionRecoveries=2 and calls onBudgetExhausted
        var exhaustedCalled = false
        assertFalse(
            coordinator.triggerRecovery(
                PlaybackRecoveryKey("a1", "ep1", "src3", 1L),
                PlaybackFailureReason.TRANSIENT_NETWORK_ERROR,
                onBudgetExhausted = { exhaustedCalled = true }
            ) { execCount++ }
        )
        assertTrue(exhaustedCalled)
        assertEquals(2, execCount)

        // New generation cancels in-flight job from previous generation cleanly
        var oldGenCancelled = false
        coordinator.beginGeneration(2L)
        coordinator.triggerRecovery(
            PlaybackRecoveryKey("a1", "ep2", "src1", 2L),
            PlaybackFailureReason.TRANSIENT_NETWORK_ERROR
        ) {
            try {
                delay(10_000L)
            } catch (e: kotlinx.coroutines.CancellationException) {
                oldGenCancelled = true
                throw e
            }
        }
        testScheduler.runCurrent()
        coordinator.beginGeneration(3L)
        advanceUntilIdle()
        assertTrue("Switching generation must cancel active prior recovery job", oldGenCancelled)
    }

    @Test
    fun moshiAdapterAndAggregator_handleProviderStatusUnavailableAndObjectSourceError() {
        val json = """
            {
              "sources_status": "unavailable",
              "sources_partial": true,
              "source_error": {"error": {"provider": "pahe", "detail": "Upstream HTTP 503"}},
              "provider_status": {
                "pahe": {"status": "failed", "message": "503"},
                "hianime": "ok"
              },
              "sources": [
                {"id": "src_valid", "label": "HiAnime", "quality": null, "codec": null, "language": null, "expires_at": null},
                {"id": "src_expired", "label": "Pahe", "expires_at": "2020-01-01T00:00:00Z"}
              ]
            }
        """.trimIndent()

        val parsed = NetworkModule.moshi.adapter(ProphecyEpisodeFullResponse::class.java).fromJson(json)
        assertNotNull(parsed)
        assertEquals("unavailable", parsed!!.sourcesStatus)
        assertEquals("failed", parsed.providerStatus?.get("pahe"))
        assertEquals("ok", parsed.providerStatus?.get("hianime"))
        assertEquals("pahe: Upstream HTTP 503", parsed.sourceError)
        assertEquals(2, parsed.sources?.size)

        val aggWithValid = EpisodeSourceAggregator.aggregate(
            apiCandidates = parsed.sources,
            rawStatus = parsed.sourcesStatus,
            partial = parsed.sourcesPartial,
            errorMessage = parsed.sourceError,
            nowMs = 1_800_000_000_000L
        )
        assertEquals(EpisodeSourceResponseState.PARTIAL, aggWithValid.state)
        assertEquals(listOf("src_valid"), aggWithValid.sources.map { it.id })
        assertEquals(1, aggWithValid.expiredCandidateCount)
        assertTrue(
            PlaybackDiagnosticsLogger.recentEvents(PlaybackDiagnosticEventType.SOURCE_CANDIDATE_REJECTED)
                .any { it.sourceId == "src_expired" }
        )

        // When sources_status="unavailable" with 0 candidates and no errorMessage -> EMPTY (not AVAILABLE)
        val aggUnavailableEmpty = EpisodeSourceAggregator.aggregate(
            apiCandidates = emptyList(),
            rawStatus = "unavailable",
            partial = false,
            errorMessage = null,
            nowMs = 1_800_000_000_000L
        )
        assertEquals(EpisodeSourceResponseState.EMPTY, aggUnavailableEmpty.state)
    }

    @Test
    fun repository_preservesEmptyRejectionDiagnostics_andIsolatesBackgroundBackoffFromForeground() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.animeDao().upsertAnime(
                AnimeEntry(id = "anilist_99", aniListId = 99, backendAnimeId = "be_99", preferredTitle = "Test Anime")
            )
            db.episodeDao().upsertEpisodes(
                listOf(
                    com.example.data.local.EpisodeEntry("ep_99_1", "anilist_99", 1, 1, "Ep 1", sourceEpisodeIds = "[\"be_ep_1\"]"),
                    com.example.data.local.EpisodeEntry("ep_99_2", "anilist_99", 1, 2, "Ep 2", sourceEpisodeIds = "[\"be_ep_2\"]")
                )
            )
            var episodeFullScenario = 1
            var directPlayShouldFail503 = true
            var directPlayCalls = 0
            val api = object : ProphecyVerseApiService {
                override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                    Response.success(ProphecySearchResponse(emptyList()))
                override suspend fun getAnimeDetails(animeId: String) =
                    Response.success(ProphecyAnimeDetailsResponse(id = animeId, title = "Test Anime"))
                override suspend fun getAnimeEpisodes(animeId: String) =
                    Response.success(
                        listOf(
                            ProphecyEpisodeDto(id = "be_ep_1", season = 1, number = 1, title = "Ep 1"),
                            ProphecyEpisodeDto(id = "be_ep_2", season = 1, number = 2, title = "Ep 2")
                        )
                    )
                override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> =
                    when (episodeFullScenario) {
                        1 -> Response.success(
                            ProphecyEpisodeFullResponse(
                                sourcesStatus = "ok",
                                sourcesPartial = false,
                                sources = listOf(ProphecySourceDto(id = "", label = "BrokenProvider"))
                            )
                        )
                        else -> Response.success(
                            ProphecyEpisodeFullResponse(
                                sourcesStatus = "ok",
                                sourcesPartial = false,
                                sources = listOf(ProphecySourceDto(id = "src_good", label = "Pahe", quality = "1080p"))
                            )
                        )
                    }
                override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                    directPlayCalls++
                    return if (directPlayShouldFail503) {
                        Response.error(503, "Temp upstream fail".toResponseBody(null))
                    } else {
                        Response.success(
                            DirectPlayResponse(
                                success = true,
                                data = DirectPlayData(
                                    mode = "direct",
                                    sourceId = request.sourceId,
                                    playbackUrl = "https://cdn.example.com/stream.m3u8?token=secret123",
                                    urlType = "hls"
                                )
                            )
                        )
                    }
                }
            }
            val aniList = object : AniListGraphQLService {
                override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
                    Response.success(AniListGraphQLResponse(AniListData(null)))
            }
            val repo = AnimeRepository(
                database = db,
                prophecyApi = api,
                aniListApi = aniList,
                applicationContext = context,
                d1DataSource = createTestD1DataSource(),
                memoryCache = MemoryCacheManager()
            )

            // 1. All-blank-source-ID discovery sets FAILED state, keeps actionable errorMessage, and logs diagnostics
            val badDisc = repo.discoverStreamsForScope("anilist_99", 1, 1, forceRefresh = true, allowBypassBackoff = true)
            assertTrue(badDisc.isSuccess)
            val badSnap = badDisc.getOrThrow()
            assertTrue(badSnap.sources.isEmpty())
            assertEquals(EpisodeSourceResponseState.FAILED, badSnap.state)
            assertNotNull(badSnap.errorMessage)
            assertTrue(badSnap.errorMessage!!.contains("without usable source IDs"))
            assertTrue(PlaybackDiagnosticsLogger.recentEvents(PlaybackDiagnosticEventType.SOURCE_CANDIDATE_REJECTED).isNotEmpty())
            assertTrue(PlaybackDiagnosticsLogger.recentEvents(PlaybackDiagnosticEventType.SOURCE_DISCOVERY_EMPTY).isNotEmpty())

            // 2. Background resolution records backoff on 503; foreground call bypasses backoff and succeeds
            episodeFullScenario = 2
            val goodDisc = repo.discoverStreamsForScope("anilist_99", 1, 2, forceRefresh = true, allowBypassBackoff = true)
            assertEquals(1, goodDisc.getOrThrow().sources.size)

            directPlayShouldFail503 = true
            val bgFail = repo.resolvePlayableStream("anilist_99", 1, 2, allowBypassBackoff = false)
            assertTrue(bgFail.isFailure)

            // Another background call must stay backed off without hitting backend
            val callsBeforeBackedOff = directPlayCalls
            val bgBackedOff = repo.resolvePlayableStream("anilist_99", 1, 2, allowBypassBackoff = false)
            assertTrue(bgBackedOff.isFailure)
            assertEquals(callsBeforeBackedOff, directPlayCalls)

            // Foreground call MUST bypass backoff immediately and succeed
            directPlayShouldFail503 = false
            val fgSuccess = repo.resolvePlayableStream("anilist_99", 1, 2, allowBypassBackoff = true)
            assertTrue("Foreground resolution must bypass background backoff", fgSuccess.isSuccess)
            assertEquals("https://cdn.example.com/stream.m3u8?token=secret123", fgSuccess.getOrThrow().directData.playbackUrl)

            // Verify diagnostic logger never leaks raw secret tokens or URLs
            val allDiagText = PlaybackDiagnosticsLogger.recentEvents().joinToString("\n") { "${it.scopeKey} ${it.detail}" }
            assertFalse(allDiagText.contains("secret123"))
            assertFalse(allDiagText.contains("cdn.example.com"))
        } finally {
            db.close()
        }
    }

    @Test
    fun d1DataSource_clearsDigestOn404_recoversFromStalePersistedDigest_andValidatesHealthTable() = runTest {
        val fakeApi = FakeInMemoryD1Api()
        val digestStore = InMemoryD1DigestStore()
        val d1 = CloudflareD1DataSource(api = fakeApi, digestStore = digestStore)

        // 1. Write succeeds and caches digest
        val now = 1_800_000_000_000L
        assertTrue(
            d1.saveStreamUrl(
                animeId = "be_1",
                episodeId = "ep_1",
                sourceId = "src_1",
                streamUrl = "https://cdn.example/video.m3u8",
                expiresAt = "2099-01-01T00:00:00Z",
                nowMs = now
            )
        )
        assertEquals(1, fakeApi.writeCount)

        // Simulate D1 table cleanup on server while digest remains in memory/prefs
        fakeApi.records.clear()

        // 404 read miss MUST remove the digest so next save writes cleanly!
        assertNull(d1.getValidStreamUrl("be_1", "ep_1", "src_1", nowMs = now + 1_000L))
        assertTrue(
            d1.saveStreamUrl(
                animeId = "be_1",
                episodeId = "ep_1",
                sourceId = "src_1",
                streamUrl = "https://cdn.example/video.m3u8",
                expiresAt = "2099-01-01T00:00:00Z",
                nowMs = now + 2_000L
            )
        )
        assertEquals("After 404 miss cleared digest, identical URL must be written back to D1", 2, fakeApi.writeCount)

        // 2. Remote deletion WITHOUT prior GET (even while in-memory digest is hot!) MUST still re-save to D1
        fakeApi.records.clear()
        assertTrue(
            d1.saveStreamUrl(
                animeId = "be_1",
                episodeId = "ep_1",
                sourceId = "src_1",
                streamUrl = "https://cdn.example/video.m3u8",
                expiresAt = "2099-01-01T00:00:00Z",
                nowMs = now + 3_000L
            )
        )
        assertEquals("Hot local digest must not block re-saving after remote delete", 3, fakeApi.writeCount)
        assertTrue(fakeApi.records.containsKey(CloudflareD1DataSource.deterministicRecordId("be_1", "ep_1", "src_1")))

        // 3. Cold restart with persisted digest older than 10 minutes must NOT skip write
        fakeApi.records.clear()
        val coldD1 = CloudflareD1DataSource(api = fakeApi, digestStore = digestStore)
        assertTrue(
            coldD1.saveStreamUrl(
                animeId = "be_1",
                episodeId = "ep_1",
                sourceId = "src_1",
                streamUrl = "https://cdn.example/video.m3u8",
                expiresAt = "2099-01-01T00:00:00Z",
                nowMs = now + 15 * 60 * 1_000L
            )
        )
        assertEquals("Stale persisted digest (>10m) must force a fresh D1 write", 4, fakeApi.writeCount)

        // 4. verifyHealth checks real queryability of canonical 'stream_sources' table
        val missingTableApi = object : CloudflareD1ApiService by fakeApi {
            override suspend fun listTables(): Response<D1TablesResponse> =
                Response.success(D1TablesResponse(success = true, count = 1, tables = listOf("stream_urls")))
        }
        assertFalse(CloudflareD1DataSource(api = missingTableApi).verifyHealth())
        val failingProbeApi = object : CloudflareD1ApiService by fakeApi {
            override suspend fun listStreamSources(where: String?, limit: Int?, offset: Int?) =
                Response.error<com.example.data.d1.D1ListResponse<com.example.data.d1.D1StreamSourceRecordDto>>(
                    404,
                    """{"success":false,"error":"Table not found","table":"stream_sources"}""".toResponseBody(null)
                )
        }
        assertFalse(CloudflareD1DataSource(api = failingProbeApi).verifyHealth())
        assertTrue(d1.verifyHealth())
    }

    @Test
    fun diagnosticsLogger_redactsSensitiveUrlsTokensAndHeaders() {
        val sanitized = PlaybackDiagnosticsLogger.sanitize(
            "failed fetching https://secret.cdn/stream.m3u8?token=abc12345 authorization: Bearer xyz999 cookie=sid_000"
        )
        assertFalse(sanitized.contains("secret.cdn"))
        assertFalse(sanitized.contains("abc12345"))
        assertFalse(sanitized.contains("xyz999"))
        assertFalse(sanitized.contains("sid_000"))
        assertTrue(sanitized.contains("[redacted-url]"))
    }
}
