package com.example

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1BooleanAdapterFactory
import com.example.data.d1.D1StreamSourceRecordDto
import com.example.data.d1.SharedPreferencesD1DigestStore
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.metrics.PlaybackDiagnosticEvent
import com.example.data.metrics.PlaybackDiagnosticsLogger
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
import com.example.data.repository.ExpiryState
import com.example.data.repository.SourceExpiryTime
import com.example.data.repository.SourceIdentityValidator
import com.example.data.streaming.SafePlaybackHeaders
import com.example.player.PlaybackFailureReason
import com.example.player.PlaybackHealthMonitor
import com.example.player.PlaybackHealthState
import com.example.player.PlayerManager
import com.google.firebase.FirebaseApp
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
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
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class D1StreamPlaybackAndBufferingFixTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var metricsTracker: DatabaseMetricsTracker
    private lateinit var moshi: Moshi
    private val servers = CopyOnWriteArrayList<HttpServer>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            runCatching { FirebaseApp.initializeApp(context) }
        }
        context.getSharedPreferences(SharedPreferencesD1DigestStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PlaybackDiagnosticsLogger.clearForTests()
        SourceIdentityValidator.clearDiagnosticsForTests()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        metricsTracker = DatabaseMetricsTracker()
        moshi = Moshi.Builder()
            .add(D1BooleanAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop(0) } }
        servers.clear()
        db.close()
    }

    private fun startHttpServer(handler: (HttpExchange) -> Unit): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } catch (t: Throwable) {
                val err = """{"success":false,"error":"${t.message}"}""".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(500, err.size.toLong())
                exchange.responseBody.use { it.write(err) }
            }
        }
        server.start()
        servers.add(server)
        return server to server.address.port
    }

    private fun createMockD1Api(handler: (HttpExchange) -> Unit): CloudflareD1ApiService {
        val (_, port) = startHttpServer(handler)
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("http://127.0.0.1:$port/v1/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CloudflareD1ApiService::class.java)
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun createRepo(
        api: ProphecyVerseApiService,
        d1DataSource: CloudflareD1DataSource,
        memoryCache: MemoryCacheManager = MemoryCacheManager(metricsTracker = metricsTracker)
    ): AnimeRepository = AnimeRepository(
        database = db,
        prophecyApi = api,
        aniListApi = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
                Response.success(AniListGraphQLResponse(AniListData(null)))
        },
        applicationContext = context,
        d1DataSource = d1DataSource,
        memoryCache = memoryCache,
        metricsTracker = metricsTracker
    )

    private fun seedAnimeAndEpisode(
        animeId: String = "anilist_700",
        backendAnimeId: String = "be_700",
        episodeId: String = "ep_700_1",
        backendEpisodeId: String = "be_ep_700_1",
        sources: List<ProphecySourceDto> = listOf(ProphecySourceDto(id = "src_700", label = "Pahe 1080p", quality = "1080p"))
    ): LegacyFixtureToV3Adapter {
        kotlinx.coroutines.runBlocking {
            db.animeDao().upsertAnime(
                AnimeEntry(
                    id = animeId,
                    aniListId = animeId.removePrefix("anilist_").toIntOrNull(),
                    backendAnimeId = backendAnimeId,
                    preferredTitle = "Prophecy Test",
                    format = "TV",
                    isPlayable = true
                )
            )
            db.episodeDao().upsertEpisodes(
                listOf(EpisodeEntry(episodeId, animeId, 1, 1, sourceEpisodeIds = "1:$backendEpisodeId"))
            )
        }
        return LegacyFixtureToV3Adapter(object : LegacyFixtureApi {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = backendAnimeId, title = "Prophecy Test"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = backendEpisodeId, season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(ProphecyEpisodeFullResponse(sources = sources))
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) =
                Response.success(DirectPlayResponse(success = false, data = null))
        })
    }

    // Scenario 1 & 7: Valid D1 hit skips backend direct resolution AND restores safeHeadersJson & contentType
    @Test
    fun scenario01_07_validD1HitSkipsBackendAndRestoresSafeHeaders() = runTest {
        val backendDirectCalls = AtomicInteger(0)
        val recordKey = CloudflareD1DataSource.deterministicRecordId("be_700", "be_ep_700_1", "src_700")
        val safeHeaders = listOf(
            listOf("Referer", "https://kwik.example/"),
            listOf("Origin", "https://kwik.example"),
            listOf("User-Agent", "ProphecyTestUA/2.0"),
            listOf("Authorization", "Bearer top-secret-should-not-restore")
        )
        val storedSafeJson = SafePlaybackHeaders.toJson(safeHeaders)!!

        val d1Api = createMockD1Api { exchange ->
            val path = exchange.requestURI.path
            if (exchange.requestMethod == "GET" && path == "/v1/tables/stream_sources/$recordKey") {
                val row = JSONObject()
                    .put("sourceDocKey", recordKey)
                    .put("scopeKey", "be_700")
                    .put("providerId", "prov_pahe")
                    .put("sourceId", "src_700")
                    .put("sourceName", "Pahe 1080p")
                    .put("backendEpisodeId", "be_ep_700_1")
                    .put("streamUrl", "https://r2.example.com/hub/abcdef0123456789?X-Amz-Date=20990101T000000Z&X-Amz-Expires=28800&X-Amz-Signature=sig999")
                    .put("urlType", "direct")
                    .put("contentType", "video/x-matroska")
                    .put("safeHeadersJson", storedSafeJson)
                    .put("expiresAt", JSONObject.NULL)
                    .put("status", "AVAILABLE")
                    .put("version", 1)
                    .put("updatedAt", 1800000000000L)
                    .put("deletedAt", JSONObject.NULL)
                sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$row}""")
            } else {
                sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
            }
        }

        val baseApi = seedAnimeAndEpisode()
        val backendApi = LegacyFixtureToV3Adapter(object : LegacyFixtureApi by baseApi.legacy {
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                backendDirectCalls.incrementAndGet()
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(sourceId = request.sourceId, playbackUrl = "https://fallback.example/unused.mp4")
                    )
                )
            }
        })

        val repo = createRepo(backendApi, CloudflareD1DataSource(d1Api, metricsTracker))
        val resolved = repo.resolvePlayableStream("anilist_700", 1, 1, "ep_700_1", preferredSourceId = "src_700").getOrThrow()

        assertEquals("D1", resolved.cacheTier)
        assertTrue(resolved.fromCache)
        assertEquals(0, backendDirectCalls.get())
        assertEquals("video/x-matroska", resolved.directData.contentType)
        assertTrue(resolved.directData.requiresHeaders)
        assertEquals(
            listOf(
                listOf("Referer", "https://kwik.example/"),
                listOf("Origin", "https://kwik.example"),
                listOf("User-Agent", "ProphecyTestUA/2.0")
            ),
            resolved.directData.headers
        )
        assertEquals("2099-01-01T08:00:00Z", resolved.directData.expiresAt)
    }

    // Scenario 2 & 4: Soft-deleted (deletedAt != null) and unplayable status records are ignored cleanly
    @Test
    fun scenario02_04_deletedAndUnplayableD1RecordsIgnored_andFallBackToBackend() = runTest {
        val deletedKey = CloudflareD1DataSource.deterministicRecordId("be_701", "be_ep_701_1", "src_del")
        val failedKey = CloudflareD1DataSource.deterministicRecordId("be_701", "be_ep_701_1", "src_bad_status")
        val d1Api = FakeInMemoryD1Api()
        d1Api.records[deletedKey] = D1StreamSourceRecordDto(
            sourceDocKey = deletedKey,
            scopeKey = "be_701",
            sourceId = "src_del",
            sourceName = "Deleted Source",
            backendEpisodeId = "be_ep_701_1",
            streamUrl = "https://cdn.example/deleted.mp4",
            status = "AVAILABLE",
            updatedAt = 1800000000000L,
            deletedAt = 1800000000123L
        )
        d1Api.records[failedKey] = D1StreamSourceRecordDto(
            sourceDocKey = failedKey,
            scopeKey = "be_701",
            sourceId = "src_bad_status",
            sourceName = "Broken Source",
            backendEpisodeId = "be_ep_701_1",
            streamUrl = "https://cdn.example/broken.mp4",
            status = "TEMPORARILY_UNAVAILABLE",
            updatedAt = 1800000000000L,
            deletedAt = null
        )

        val d1 = CloudflareD1DataSource(d1Api, metricsTracker)
        assertNull("Soft-deleted record must be ignored", d1.getValidStreamUrl("be_701", "be_ep_701_1", "src_del"))
        assertNull("TEMPORARILY_UNAVAILABLE record must be ignored", d1.getValidStreamUrl("be_701", "be_ep_701_1", "src_bad_status"))
        assertNull("REMOVED record must be ignored", run {
            d1Api.records[failedKey] = d1Api.records[failedKey]!!.copy(status = "REMOVED")
            d1.getValidStreamUrl("be_701", "be_ep_701_1", "src_bad_status")
        })
    }

    // Scenario 3: SigV4 X-Amz-Date + X-Amz-Expires detection (even when expiresAt is null)
    @Test
    fun scenario03_sigV4PresignedUrlExpiryEvaluatedEvenWhenExplicitExpiresAtNull() = runTest {
        val nowMs = SourceExpiryTime.parseEpochMillis("2026-11-16T15:00:00Z")!!
        val expiredSigV4 = "https://r2.example.com/hub/abc?X-Amz-Date=20261116T060000Z&X-Amz-Expires=28800&X-Amz-Signature=deadbeef"
        val validSigV4 = "https://r2.example.com/hub/abc?X-Amz-Date=20261116T140000Z&X-Amz-Expires=28800&X-Amz-Signature=deadbeef"
        val plainUrl = "https://cdn.example.com/video.mp4"

        assertEquals(ExpiryState.EXPIRED, SourceExpiryTime.evaluateStreamExpiry(null, expiredSigV4, nowMs))
        assertEquals(ExpiryState.VALID, SourceExpiryTime.evaluateStreamExpiry(null, validSigV4, nowMs))
        assertEquals("2026-11-16T22:00:00Z", SourceExpiryTime.deriveIsoExpiryFromUrl(validSigV4))
        assertEquals(ExpiryState.MISSING, SourceExpiryTime.evaluateStreamExpiry(null, plainUrl, nowMs))

        val key = CloudflareD1DataSource.deterministicRecordId("be_702", "be_ep_702_1", "src_r2")
        val fakeApi = FakeInMemoryD1Api()
        fakeApi.records[key] = D1StreamSourceRecordDto(
            sourceDocKey = key,
            scopeKey = "be_702",
            sourceId = "src_r2",
            backendEpisodeId = "be_ep_702_1",
            streamUrl = expiredSigV4,
            expiresAt = null,
            status = "AVAILABLE",
            updatedAt = 1800000000000L,
            deletedAt = null
        )
        val d1 = CloudflareD1DataSource(fakeApi, metricsTracker)
        assertNull(
            "Expired SigV4 URL with null expiresAt must be rejected",
            d1.getValidStreamUrl("be_702", "be_ep_702_1", "src_r2", nowMs = nowMs)
        )
        fakeApi.records[key] = fakeApi.records[key]!!.copy(streamUrl = validSigV4)
        val hit = d1.getValidStreamUrl("be_702", "be_ep_702_1", "src_r2", nowMs = nowMs)
        assertNotNull("Valid SigV4 URL must hit D1", hit)
        assertEquals("2026-11-16T22:00:00Z", hit?.expiresAt)
    }

    // Scenario 5: Multiple sources for the same episode are preserved and exact requested sourceId is chosen
    @Test
    fun scenario05_multipleSourcesPreservedWithoutOverwriteAndExactSourceSelected() = runTest {
        val fakeApi = FakeInMemoryD1Api()
        val d1 = CloudflareD1DataSource(fakeApi, metricsTracker)

        assertTrue(d1.saveStreamUrl("be_703", "be_ep_703_1", "src_alpha", "https://cdn.example/alpha.mkv", "2099-01-01T00:00:00Z", quality = "1080p", headers = listOf(listOf("Referer", "https://a.example/"))))
        assertTrue(d1.saveStreamUrl("be_703", "be_ep_703_1", "src_beta", "https://cdn.example/beta.m3u8", "2099-01-01T00:00:00Z", quality = "720p", headers = listOf(listOf("Referer", "https://b.example/"))))
        assertTrue(d1.saveStreamUrl("be_703", "be_ep_703_1", "src_gamma", "https://cdn.example/gamma.mp4", "2099-01-01T00:00:00Z", quality = "480p"))

        assertEquals(3, fakeApi.records.size)
        val allForEp = d1.getStreamUrlsForEpisode("be_703", "be_ep_703_1")
        assertEquals(setOf("src_alpha", "src_beta", "src_gamma"), allForEp.map { it.sourceId }.toSet())

        val hitBeta = d1.getValidStreamUrl("be_703", "be_ep_703_1", "src_beta")
        assertEquals("https://cdn.example/beta.m3u8", hitBeta?.streamUrl)
        assertEquals("hls", hitBeta?.urlType)
        assertEquals("https://b.example/", hitBeta?.safeHeaders?.firstOrNull { it[0] == "Referer" }?.getOrNull(1))
    }

    // Scenario 6: Empty/malformed/500 D1 response safely falls back to backend resolution
    @Test
    fun scenario06_malformedOr500D1ResponseSafelyFallsBackToBackend() = runTest {
        val backendCalls = AtomicInteger(0)
        val d1Api = createMockD1Api { exchange ->
            if (exchange.requestMethod == "GET" && exchange.requestURI.path.startsWith("/v1/tables/stream_sources/")) {
                sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":null}""")
            } else if (exchange.requestMethod == "POST") {
                val raw = exchange.requestBody.bufferedReader().readText()
                sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$raw}""")
            } else {
                sendJson(exchange, 500, """not-valid-json""")
            }
        }
        val baseApi = seedAnimeAndEpisode("anilist_704", "be_704", "ep_704_1", "be_ep_704_1", listOf(ProphecySourceDto("src_704", "Kwik 1080p")))
        val backendApi = LegacyFixtureToV3Adapter(object : LegacyFixtureApi by baseApi.legacy {
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                backendCalls.incrementAndGet()
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(sourceId = request.sourceId, playbackUrl = "https://cdn.example/fallback_704.mp4")
                    )
                )
            }
        })
        val repo = createRepo(backendApi, CloudflareD1DataSource(d1Api, metricsTracker))
        val result = repo.resolvePlayableStream("anilist_704", 1, 1, "ep_704_1", preferredSourceId = "src_704").getOrThrow()
        assertEquals("BACKEND_RESOLVE", result.cacheTier)
        assertEquals(1, backendCalls.get())
        assertEquals("https://cdn.example/fallback_704.mp4", result.directData.playbackUrl)
    }

    // Scenario 8: Media3 OkHttpDataSource Range 206 + 302 Redirect + R2 Response-Content-Disposition MKV detection
    @Test
    fun scenario08_okHttpDataSourceRange206Redirect_andR2ContentDispositionMimeDetection() {
        val r2SignedUrl = "https://pub.r2.cloudflarestorage.com/hub/9d17b1bd0093b1fe2b1e7b09d447119d" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20990101T000000Z&X-Amz-Expires=28800" +
            "&response-content-disposition=attachment%3B%20filename%3D%22My%20Anime%20-%2001.mkv%22"
        assertEquals(
            MimeTypes.VIDEO_MATROSKA,
            PlayerManager.detectMimeType(DirectPlayData(sourceId = "s1", playbackUrl = r2SignedUrl, urlType = "direct", contentType = null))
        )
        // Regression check: generic ?file= query arg must still return null
        assertNull(
            PlayerManager.detectMimeType(DirectPlayData(sourceId = "s1", playbackUrl = "https://cdn.example/signed-object?file=video.mp4", urlType = "direct", contentType = null))
        )

        val payload = ByteArray(4096) { (it % 251).toByte() }
        val capturedHeaders = ConcurrentHashMap<String, String>()
        val (_, cdnPort) = startHttpServer { exchange ->
            exchange.requestHeaders.forEach { (k, v) -> capturedHeaders[k.lowercase()] = v.firstOrNull().orEmpty() }
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null && range.startsWith("bytes=0-1023")) {
                val slice = payload.copyOfRange(0, 1024)
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Range", "bytes 0-1023/${payload.size}")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            } else {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        val (_, redirectPort) = startHttpServer { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:$cdnPort/hub/stream")
            exchange.sendResponseHeaders(302, -1L)
            exchange.close()
        }

        val tracker = PlayerManager.PlaybackNetworkTracker()
        val factory = PlayerManager.buildStreamingHttpDataSourceFactory(
            headers = listOf(
                listOf("Referer", "https://player.example/watch"),
                listOf("Authorization", "Bearer leaked-secret"),
                listOf("Cookie", "session=bad")
            ),
            tracker = tracker
        )
        val ds = factory.createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("http://127.0.0.1:$redirectPort/start"))
            .setPosition(0)
            .setLength(1024)
            .build()
        ds.open(spec)
        val buf = ByteArray(1024)
        var total = 0
        while (total < buf.size) {
            val n = ds.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        ds.close()

        assertEquals(1024, total)
        assertEquals("https://player.example/watch", capturedHeaders["referer"])
        assertEquals("https://player.example", capturedHeaders["origin"])
        assertFalse("Sensitive auth header must not be sent", capturedHeaders.containsKey("authorization"))
        assertFalse("Sensitive cookie header must not be sent", capturedHeaders.containsKey("cookie"))

        val snap = tracker.snapshot()
        assertEquals(206, snap.lastHttpStatusCode)
        assertEquals(true, snap.supportsByteRanges)
        assertEquals("application/octet-stream", snap.responseContentType)
        assertEquals(1024L, snap.bytesTransferred)
        assertNotNull(snap.ttfbMs)
        assertNotNull(snap.throughputKbps)
    }

    // Scenario 9 & 10: LoadControl buffer configuration, rebuffer tracking, and error classification
    @Test
    fun scenario09_10_tunedLoadControl_rebufferMetrics_andErrorClassification() {
        val loadControl = PlayerManager.buildDefaultLoadControl()
        assertTrue(PlayerManager.MIN_BUFFER_MS >= 20_000)
        assertTrue(PlayerManager.MAX_BUFFER_MS >= 50_000)
        assertTrue(PlayerManager.BUFFER_FOR_PLAYBACK_MS >= 1_500)
        assertTrue(PlayerManager.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS >= 3_000)
        assertNotNull(loadControl)

        val monitor = PlaybackHealthMonitor(scopeKey = "be_1:s1:e1", sourceId = "src_1")
        monitor.markReady(nowMs = 1_000L)
        // Enter rebuffering after ready
        monitor.onPlayerBufferingState(nowMs = 2_000L)
        assertEquals(1, monitor.rebufferCount)
        // Active byte transfer keeps state ACTIVE_TRANSFER and does NOT stall
        val tick = monitor.evaluateTick(
            isPlaying = false,
            isBuffering = true,
            bytesTransferred = 512_000L,
            bufferedPositionMs = 2_500L,
            lastTransferAtMs = 3_400L,
            nowMs = 3_500L
        )
        assertEquals(PlaybackHealthState.ACTIVE_TRANSFER, tick.state)
        assertFalse(tick.shouldTriggerStallRecovery)
        monitor.markReady(nowMs = 4_200L)
        assertEquals(1, monitor.rebufferCount)
        assertEquals(2_200L, monitor.totalRebufferDurationMs)

        // Error classification checks
        val expiredHttp = PlaybackException(
            "403 Forbidden",
            HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), DataSpec(Uri.parse("https://cdn.example/v.mkv")), ByteArray(0)),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        val (health403, reason403) = monitor.classifyError(PlayerManager.classifyPlaybackError(expiredHttp), 403)
        assertEquals(PlaybackHealthState.EXPIRED_URL, health403)
        assertEquals(PlaybackFailureReason.HTTP_EXPIRED_OR_UNAUTHORIZED, reason403)

        val timeoutErr = PlaybackException("Timed out", SocketTimeoutException("read timed out"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val (healthTimeout, reasonTimeout) = monitor.classifyError(PlayerManager.classifyPlaybackError(timeoutErr), null)
        assertEquals(PlaybackHealthState.CONFIRMED_FAILURE, healthTimeout)
        assertEquals(PlaybackFailureReason.TRANSIENT_NETWORK_ERROR, reasonTimeout)
    }

    // Scenario 11 & 12: Backend resolve persists metadata + safe headers + derived SigV4 expiry to D1; diagnostics redact secrets & full URLs
    @Test
    fun scenario11_12_backendResolveSavesFullMetadataToD1_andDiagnosticsRedactSecrets() = runTest {
        val savedRows = ConcurrentHashMap<String, JSONObject>()
        val backendCalls = AtomicInteger(0)
        val signedSecretUrl = "https://r2.example.com/hub/8811aabbcc?X-Amz-Date=20990501T100000Z&X-Amz-Expires=3600&X-Amz-Signature=supersecret999&response-content-disposition=attachment%3B%20filename%3D%22ep1.mkv%22"

        val d1Api = createMockD1Api { exchange ->
            val path = exchange.requestURI.path
            when {
                exchange.requestMethod == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val key = path.removePrefix("/v1/tables/stream_sources/")
                    val row = savedRows[key]
                    if (row == null) sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
                    else sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$row}""")
                }
                exchange.requestMethod == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    val raw = exchange.requestBody.bufferedReader().readText()
                    val obj = JSONObject(raw)
                    savedRows[obj.getString("sourceDocKey")] = obj
                    sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$obj}""")
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
            }
        }

        val sourceDto = ProphecySourceDto(
            id = "src_705",
            label = "Pahe Sub",
            quality = "1080p",
            codec = "h264",
            language = "Japanese",
            sizeBytes = 931_000_000L,
            downloadable = true
        )
        val baseApi = seedAnimeAndEpisode("anilist_705", "be_705", "ep_705_1", "be_ep_705_1", listOf(sourceDto))
        val backendApi = LegacyFixtureToV3Adapter(object : LegacyFixtureApi by baseApi.legacy {
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                backendCalls.incrementAndGet()
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = request.sourceId,
                            playbackUrl = signedSecretUrl,
                            urlType = "direct",
                            requiresHeaders = true,
                            headers = listOf(
                                listOf("Referer", "https://kwik.example/watch/secret"),
                                listOf("Authorization", "Bearer my-private-jwt-token"),
                                listOf("Cookie", "cf_clearance=secret_cookie_val")
                            ),
                            contentType = "application/octet-stream",
                            expiresAt = null
                        )
                    )
                )
            }
        })

        val memCache = MemoryCacheManager(metricsTracker = metricsTracker)
        val repo = createRepo(backendApi, CloudflareD1DataSource(d1Api, metricsTracker), memCache)

        val first = repo.resolvePlayableStream("anilist_705", 1, 1, "ep_705_1", preferredSourceId = "src_705").getOrThrow()
        assertEquals("BACKEND_RESOLVE", first.cacheTier)
        assertEquals(1, backendCalls.get())
        assertEquals(MimeTypes.VIDEO_MATROSKA, PlayerManager.detectMimeType(first.directData))

        // Verify saved D1 record stripped auth/cookie, stored safe headers, metadata, and derived SigV4 ISO expiry
        assertEquals(1, savedRows.size)
        val row = savedRows.values.first()
        assertEquals("2099-05-01T11:00:00Z", row.getString("expiresAt"))
        assertEquals("application/octet-stream", row.getString("contentType"))
        assertEquals("1080p", row.optString("quality"))
        assertEquals("h264", row.optString("codec"))
        assertEquals("Japanese", row.optString("audioLanguage"))
        assertEquals(931_000_000L, row.optLong("sizeBytes"))
        val safeHeadersJson = row.getString("safeHeadersJson")
        assertTrue(safeHeadersJson.contains("Referer"))
        assertFalse("Auth header must never be stored in D1", safeHeadersJson.contains("Authorization"))
        assertFalse("Cookie header must never be stored in D1", safeHeadersJson.contains("Cookie"))
        assertFalse("Secret token must never be stored in D1", safeHeadersJson.contains("my-private-jwt-token"))

        // Second lookup (with Memory cache cleared) hits D1 directly and skips Backend
        memCache.invalidatePlayback("src_705", "anime:705:s1:e1")
        val second = repo.resolvePlayableStream("anilist_705", 1, 1, "ep_705_1", preferredSourceId = "src_705").getOrThrow()
        assertEquals("D1", second.cacheTier)
        assertEquals(1, backendCalls.get())
        assertEquals(signedSecretUrl, second.directData.playbackUrl)
        assertEquals(listOf(listOf("Referer", "https://kwik.example/watch/secret")), second.directData.headers)

        // Verify privacy-safe diagnostics contain ZERO URLs, signatures, cookies, or header secret values
        val allLogs = PlaybackDiagnosticsLogger.recentEvents()
        assertTrue(allLogs.any { it.type == PlaybackDiagnosticEvent.D1_CACHE_HIT })
        assertTrue(allLogs.any { it.type == PlaybackDiagnosticEvent.D1_CACHE_WRITE })
        allLogs.forEach { rec ->
            val blob = "${rec.scopeKey} ${rec.sourceId.orEmpty()} ${rec.providerLabel.orEmpty()} ${rec.detail}"
            assertFalse("Log leaked URL: $blob", blob.contains("r2.example.com"))
            assertFalse("Log leaked signature: $blob", blob.contains("supersecret999"))
            assertFalse("Log leaked JWT: $blob", blob.contains("my-private-jwt-token"))
            assertFalse("Log leaked cookie: $blob", blob.contains("secret_cookie_val"))
            assertFalse("Log leaked Referer URL value: $blob", blob.contains("kwik.example"))
        }
    }
}
