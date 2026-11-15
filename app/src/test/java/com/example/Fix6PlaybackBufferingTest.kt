package com.example

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
import com.example.data.local.StreamProviderEntity
import com.example.data.local.StreamSourceEntity
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
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.streaming.ContentIdentityHelper
import com.example.data.streaming.FirestoreStreamingRepository
import com.example.data.streaming.StreamRecordStatus
import com.example.player.PlayerManager
import com.example.player.PreResolveQueueManager
import com.google.firebase.FirebaseApp
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Fix6PlaybackBufferingTest {

    private lateinit var context: Context
    private var mockServer: HttpServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            runCatching { FirebaseApp.initializeApp(context) }
        }
    }

    @After
    fun tearDown() {
        mockServer?.stop(0)
        mockServer = null
    }

    @Test
    fun fix6_diagnosticHttpDataSource_capturesTtfbThroughputAndByteRangeHeaders() {
        val payload = ByteArray(4096) { (it % 127).toByte() }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/range.mp4") { exchange ->
            val slice = payload.copyOfRange(0, 1024)
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Range", "bytes 0-1023/${payload.size}")
            exchange.sendResponseHeaders(206, slice.size.toLong())
            exchange.responseBody.use { it.write(slice) }
        }
        server.createContext("/norange.mp4") { exchange ->
            val slice = payload.copyOfRange(0, 512)
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("Accept-Ranges", "none")
            exchange.sendResponseHeaders(200, slice.size.toLong())
            exchange.responseBody.use { it.write(slice) }
        }
        server.start()
        mockServer = server
        val port = server.address.port

        // 1. Range-capable endpoint (HTTP 206)
        val rangeTracker = PlayerManager.PlaybackNetworkTracker()
        val rawFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(PlayerManager.HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(PlayerManager.HTTP_READ_TIMEOUT_MS)
        val diagFactory = PlayerManager.DiagnosticHttpDataSourceFactory(rawFactory, rangeTracker)

        val ds1 = diagFactory.createDataSource()
        val spec1 = DataSpec.Builder()
            .setUri(Uri.parse("http://127.0.0.1:$port/range.mp4"))
            .setPosition(0)
            .setLength(1024)
            .build()
        ds1.open(spec1)
        val buf1 = ByteArray(1024)
        var readTotal = 0
        while (readTotal < buf1.size) {
            val r = ds1.read(buf1, readTotal, buf1.size - readTotal)
            if (r <= 0) break
            readTotal += r
        }
        ds1.close()

        val snap1 = rangeTracker.snapshot()
        assertEquals(206, snap1.lastHttpStatusCode)
        assertEquals(true, snap1.supportsByteRanges)
        assertEquals(1024L, snap1.bytesTransferred)
        assertNotNull(snap1.ttfbMs)
        assertTrue(snap1.ttfbMs!! >= 0L)
        assertNotNull(snap1.throughputKbps)
        assertTrue(snap1.throughputKbps!! > 0L)

        // 2. Non-range endpoint (Accept-Ranges: none)
        val noRangeTracker = PlayerManager.PlaybackNetworkTracker()
        val ds2 = PlayerManager.DiagnosticHttpDataSourceFactory(rawFactory, noRangeTracker).createDataSource()
        ds2.open(DataSpec(Uri.parse("http://127.0.0.1:$port/norange.mp4")))
        val buf2 = ByteArray(512)
        while (ds2.read(buf2, 0, buf2.size) > 0) {
            // drain
        }
        ds2.close()
        val snap2 = noRangeTracker.snapshot()
        assertEquals(200, snap2.lastHttpStatusCode)
        assertEquals(false, snap2.supportsByteRanges)
        assertEquals(512L, snap2.bytesTransferred)
    }

    @Test
    fun fix6_transferAwareStallWatchdog_andDiagnosticsContract() {
        assertEquals(12_000, PlayerManager.HTTP_CONNECT_TIMEOUT_MS)
        assertEquals(20_000, PlayerManager.HTTP_READ_TIMEOUT_MS)

        // Dead socket / 0-byte stall triggers timely recovery at 12s
        assertFalse(PlayerManager.shouldTriggerBufferingStallFallback(bufferingSeconds = 11, hasRecentTransfer = false))
        assertTrue(PlayerManager.shouldTriggerBufferingStallFallback(bufferingSeconds = 12, hasRecentTransfer = false))

        // Active high-bitrate download must NOT trigger spurious fallback at 12s or 15s
        assertFalse(PlayerManager.shouldTriggerBufferingStallFallback(bufferingSeconds = 12, hasRecentTransfer = true))
        assertFalse(PlayerManager.shouldTriggerBufferingStallFallback(bufferingSeconds = 15, hasRecentTransfer = true))
        assertFalse(PlayerManager.shouldTriggerBufferingStallFallback(bufferingSeconds = 29, hasRecentTransfer = true))
        assertTrue(PlayerManager.shouldTriggerBufferingStallFallback(bufferingSeconds = 30, hasRecentTransfer = true))

        // Redirect-sensitive headers remain filtered
        assertTrue(PlayerManager.isRedirectSensitiveHeader("Authorization"))
        assertTrue(PlayerManager.isRedirectSensitiveHeader("Cookie"))
        assertTrue(PlayerManager.isRedirectSensitiveHeader("X-Auth-Token"))
        assertFalse(PlayerManager.isRedirectSensitiveHeader("Referer"))
        assertFalse(PlayerManager.isRedirectSensitiveHeader("User-Agent"))

        // Backwards-compatible defaults on PlaybackDiagnostics
        val defaults = PlayerManager.PlaybackDiagnostics(sourceQuality = "1080p", resolveDurationMs = 250L)
        assertEquals("1080p", defaults.sourceQuality)
        assertEquals(250L, defaults.resolveDurationMs)
        assertEquals(0L, defaults.bytesTransferred)
        assertEquals(0, defaults.retryCount)
    }

    @Test
    fun fix6_backgroundPreResolutionPausesWhilePlayerBuffering_andResumesAfter() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tracker = DatabaseMetricsTracker()
        val animeId = "anilist_601"
        val providerId = ContentIdentityHelper.deterministicProviderId("AnimePahe")
        val resolveCalls = AtomicInteger(0)

        try {
            db.animeDao().upsertAnime(
                AnimeEntry(id = animeId, aniListId = 601, backendAnimeId = "b_601", titleRomaji = "Fix6 Show", preferredTitle = "Fix6 Show")
            )
            val ep1 = EpisodeEntry(id = "ep_601_1", animeId = animeId, seasonNumber = 1, episodeNumber = 1, title = "Ep 1")
            val ep2 = EpisodeEntry(id = "ep_601_2", animeId = animeId, seasonNumber = 1, episodeNumber = 2, title = "Ep 2")
            db.episodeDao().upsertEpisodes(listOf(ep1, ep2))
            val scopeKey2 = "anime:601:s1:e2"
            db.streamCacheDao().upsertProviders(
                listOf(StreamProviderEntity("$scopeKey2|p|$providerId", scopeKey2, providerId, "AnimePahe", sourceCount = 1, updatedAt = 1000L))
            )
            db.streamCacheDao().upsertSource(
                StreamSourceEntity("$scopeKey2|p|$providerId|s|src2", scopeKey2, providerId, "src2", "AnimePahe 1080p", status = StreamRecordStatus.MISSING_URL.name, updatedAt = 1000L)
            )

            val api = object : ProphecyVerseApiService {
                override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) = Response.success(ProphecySearchResponse())
                override suspend fun getAnimeDetails(animeId: String) = Response.success(ProphecyAnimeDetailsResponse(id = animeId, title = "Fix6 Show"))
                override suspend fun getAnimeEpisodes(animeId: String) = Response.success(emptyList<ProphecyEpisodeDto>())
                override suspend fun getEpisodeFull(animeId: String, episodeId: String) = Response.success(ProphecyEpisodeFullResponse())
                override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                    resolveCalls.incrementAndGet()
                    return Response.success(
                        DirectPlayResponse(
                            success = true,
                            data = DirectPlayData(
                                sourceId = request.sourceId,
                                playbackUrl = "https://cdn.example.com/fix6/${request.sourceId}.mp4",
                                urlType = "direct",
                                expiresAt = "2099-01-01T00:00:00Z"
                            )
                        )
                    )
                }
            }
            val aniList = object : AniListGraphQLService {
                override suspend fun postQuery(request: AniListGraphQLRequest) =
                    Response.success(AniListGraphQLResponse(AniListData(null)))
            }
            val streamingRepo = FirestoreStreamingRepository(
                streamCacheDao = db.streamCacheDao(),
                firestoreProvider = { null },
                isClientWriteAuthorized = { false },
                cloudStoreProvider = { null },
                d1DataSource = null,
                metricsTracker = tracker
            )
            val repo = AnimeRepository(db, api, aniList, context, streamingRepo, null, MemoryCacheManager(metricsTracker = tracker), tracker)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val queueManager = PreResolveQueueManager(repo, scope)
                // Player begins actively buffering -> background pre-resolution must yield
                queueManager.setPlaybackBuffering(true)
                queueManager.slideWindow(animeId, ep1, listOf(ep1, ep2))
                delay(200)
                assertEquals("Background pre-resolve must stay paused while player buffers", 0, resolveCalls.get())

                // Player reaches STATE_READY (no longer buffering) -> background pre-resolve resumes
                queueManager.setPlaybackBuffering(false)
                var waited = 0
                while (resolveCalls.get() == 0 && waited < 40) {
                    delay(50)
                    waited++
                }
                assertTrue("Background pre-resolve must resume when player finishes buffering", resolveCalls.get() >= 1)
                queueManager.clear()
            } finally {
                scope.cancel()
            }
        } finally {
            db.close()
        }
    }
}
