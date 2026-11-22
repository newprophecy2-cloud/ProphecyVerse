package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1BooleanAdapterFactory
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
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
import com.example.data.repository.SourceIdentityValidator
import com.example.data.streaming.ContentIdentityHelper
import com.google.firebase.FirebaseApp
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Fix4CacheSecurityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var metricsTracker: DatabaseMetricsTracker
    private lateinit var memoryCache: MemoryCacheManager
    private var mockServer: HttpServer? = null

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
    }

    @After
    fun tearDown() {
        mockServer?.stop(0)
        mockServer = null
        db.close()
    }

    private fun noOpAniList(): AniListGraphQLService = object : AniListGraphQLService {
        override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
            Response.success(AniListGraphQLResponse(AniListData(null)))
    }

    private fun noOpApi(directResolver: suspend (DirectPlayRequest) -> Response<DirectPlayResponse>): ProphecyVerseApiService =
        LegacyFixtureToV3Adapter(object : LegacyFixtureApi {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = animeId, title = "Show"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(emptyList<ProphecyEpisodeDto>())
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(ProphecyEpisodeFullResponse())
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) = directResolver(request)
        })

    @Test
    fun fix4_sensitiveHeadersKeptInMemoryOnly_roomMetadataOnly_andD1NeverStoresHeaders() = runTest {
        val animeId = "anilist_401"
        val scopeKey = "anime:401:s1:e1"
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 401, backendAnimeId = "b_401", titleRomaji = "Test 401", preferredTitle = "Test 401")
        )
        seedEpisodeSourceSnapshot(
            memoryCache = memoryCache,
            scopeKey = scopeKey,
            seasonNumber = 1,
            episodeNumber = 1,
            sources = listOf(ProphecySourceDto(id = "src_401", label = "AnimePahe", quality = "1080p"))
        )

        val api = noOpApi { req ->
            Response.success(
                DirectPlayResponse(
                    success = true,
                    data = DirectPlayData(
                        sourceId = req.sourceId,
                        playbackUrl = "https://cdn.example.com/secure/stream.m3u8",
                        urlType = "hls",
                        requiresHeaders = true,
                        headers = listOf(
                            listOf("Referer", "https://kwik.si/"),
                            listOf("User-Agent", "ProphecyPlayer/1.0"),
                            listOf("Authorization", "Bearer top-secret-jwt"),
                            listOf("Cookie", "cf_clearance=secret_cookie"),
                            listOf("X-Auth-Token", "secret_token"),
                            listOf("X-Signature", "hmac_sig"),
                            listOf("Session-Id", "sess_999")
                        ),
                        expiresAt = "2099-01-01T00:00:00Z"
                    )
                )
            )
        }

        val capturedD1Bodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/v1/") { exchange ->
            val method = exchange.requestMethod
            if (method == "POST") {
                capturedD1Bodies.add(exchange.requestBody.bufferedReader().readText())
                val bytes = """{"success":true,"data":{"ok":true}}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val bytes = """{"success":false,"error":"Not found"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(404, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        mockServer = server
        val moshi = Moshi.Builder().add(D1BooleanAdapterFactory).addLast(KotlinJsonAdapterFactory()).build()
        val d1Api = Retrofit.Builder()
            .baseUrl("http://127.0.0.1:${server.address.port}/v1/")
            .client(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CloudflareD1ApiService::class.java)
        val d1 = CloudflareD1DataSource(d1Api, metricsTracker)

        val repo = AnimeRepository(db, api, noOpAniList(), context, d1, memoryCache, metricsTracker)

        val resolved = repo.resolvePlayableStream(animeId = animeId, seasonNumber = 1, episodeNumber = 1)
        assertTrue(resolved.isSuccess)
        val directData = resolved.getOrThrow().directData
        val memHeaderNames = directData.headers.map { it[0] }
        assertTrue("In-memory playback keeps Referer", "Referer" in memHeaderNames)
        assertTrue("In-memory playback keeps Authorization", "Authorization" in memHeaderNames)
        assertTrue("In-memory playback keeps Cookie", "Cookie" in memHeaderNames)

        // D1 write is awaited inline before returning resolved playback
        assertTrue("D1 save should have completed before return", capturedD1Bodies.isNotEmpty())
        val d1Joined = capturedD1Bodies.joinToString("\n")
        assertTrue("D1 stream_sources must store resolved streamUrl", d1Joined.contains("https://cdn.example.com/secure/stream.m3u8"))
        assertTrue("D1 stream_sources persists non-sensitive Referer in safeHeadersJson", d1Joined.contains("safeHeadersJson") && d1Joined.contains("kwik.si"))
        assertTrue("D1 stream_sources persists non-sensitive User-Agent in safeHeadersJson", d1Joined.contains("User-Agent") && d1Joined.contains("ProphecyPlayer"))
        assertFalse("D1 stream_sources must never persist Authorization", d1Joined.contains("top-secret-jwt"))
        assertFalse("D1 stream_sources must never persist Cookie", d1Joined.contains("secret_cookie"))
        assertFalse("D1 stream_sources must never persist X-Auth-Token", d1Joined.contains("secret_token"))
        assertFalse("D1 stream_sources must never persist X-Signature", d1Joined.contains("hmac_sig"))
        assertFalse("D1 stream_sources must never persist Session-Id", d1Joined.contains("sess_999"))
    }

    @Test
    fun fix4_expiredUrlsEvictedAcrossMemoryAndD1_andFailedResolveNeverWipesValidD1Url() = runTest {
        val animeId = "anilist_402"
        val scopeKey = "anime:402:s1:e1"
        val providerId = ContentIdentityHelper.deterministicProviderId("MegaCloud")
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 402, backendAnimeId = "b_402", titleRomaji = "Test 402", preferredTitle = "Test 402")
        )
        seedEpisodeSourceSnapshot(
            memoryCache = memoryCache,
            scopeKey = scopeKey,
            seasonNumber = 1,
            episodeNumber = 1,
            sources = listOf(ProphecySourceDto(id = "src_402", label = "MegaCloud", quality = "1080p"))
        )
        // Seed expired entry in memory cache
        memoryCache.putResolvedPlayback(
            "src_402",
            DirectPlayData("src_402", "https://stale.cdn.example/mem_expired.mp4", "direct", expiresAt = "2020-01-01T00:00:00Z"),
            scopeKey,
            providerId
        )
        assertNull("Expired entry in MemoryCache must be rejected", memoryCache.getResolvedPlayback("src_402", scopeKey, providerId))

        // Seed expired entry in mock D1 store
        val d1Store = ConcurrentHashMap<String, String>()
        val recId = CloudflareD1DataSource.deterministicRecordId("b_402", "402_s1_e1", "src_402")
        d1Store[recId] = """{"sourceDocKey":"$recId","scopeKey":"b_402","providerId":"direct","sourceId":"src_402","sourceName":"src_402","backendEpisodeId":"402_s1_e1","streamUrl":"https://stale.cdn.example/expired.mp4","urlType":"direct","expiresAt":"2020-01-01T00:00:00Z","status":"AVAILABLE","version":1,"updatedAt":1800000000000,"deletedAt":null}"""

        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/v1/") { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            when {
                method == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val key = path.removePrefix("/v1/tables/stream_sources/")
                    val raw = d1Store[key]
                    if (raw == null) {
                        val b = """{"success":false,"error":"Not found"}""".toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(404, b.size.toLong())
                        exchange.responseBody.use { it.write(b) }
                    } else {
                        val b = """{"success":true,"table":"stream_sources","data":$raw}""".toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, b.size.toLong())
                        exchange.responseBody.use { it.write(b) }
                    }
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    val body = exchange.requestBody.bufferedReader().readText()
                    val idMatch = Regex(""""sourceDocKey"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: recId
                    d1Store[idMatch] = body
                    val b = """{"success":true,"message":"Record saved","table":"stream_sources","data":$body}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, b.size.toLong())
                    exchange.responseBody.use { it.write(b) }
                }
                method == "DELETE" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val key = path.removePrefix("/v1/tables/stream_sources/")
                    d1Store.remove(key)
                    val b = """{"success":true,"message":"Record deleted","table":"stream_sources","changes":1}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, b.size.toLong())
                    exchange.responseBody.use { it.write(b) }
                }
                else -> {
                    val b = """{"success":false}""".toByteArray()
                    exchange.sendResponseHeaders(404, b.size.toLong())
                    exchange.responseBody.use { it.write(b) }
                }
            }
        }
        server.start()
        mockServer = server
        val moshi = Moshi.Builder().add(D1BooleanAdapterFactory).addLast(KotlinJsonAdapterFactory()).build()
        val d1Api = Retrofit.Builder()
            .baseUrl("http://127.0.0.1:${server.address.port}/v1/")
            .client(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CloudflareD1ApiService::class.java)
        val d1 = CloudflareD1DataSource(d1Api, metricsTracker)

        val resolveCalls = AtomicInteger(0)
        var shouldFailResolve = false
        val api = noOpApi { req ->
            resolveCalls.incrementAndGet()
            if (shouldFailResolve) {
                Response.error(503, "unavailable".toResponseBody(null))
            } else {
                Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = req.sourceId,
                            playbackUrl = "https://fresh.cdn.example/working.m3u8",
                            urlType = "hls",
                            expiresAt = "2099-01-01T00:00:00Z"
                        )
                    )
                )
            }
        }
        val repo = AnimeRepository(db, api, noOpAniList(), context, d1, memoryCache, metricsTracker)

        // First call: expired D1 entry must be treated as cache miss & trigger fresh backend resolve
        val res1 = repo.resolvePlayableStream(animeId, 1, 1)
        assertTrue(res1.isSuccess)
        assertEquals("https://fresh.cdn.example/working.m3u8", res1.getOrThrow().directData.playbackUrl)
        assertEquals(1, resolveCalls.get())

        assertTrue("D1 must be updated with fresh working URL", d1Store[recId]?.contains("working.m3u8") == true)

        // Verify forced resolve failure does NOT wipe the now-valid D1 cached URL
        shouldFailResolve = true
        val failedRefresh = repo.resolvePlayableStream(animeId, 1, 1, forceResolveFresh = true)
        assertTrue(failedRefresh.isFailure)
        val afterFailedRefresh = d1.getValidStreamUrl("b_402", "402_s1_e1", "src_402")
        assertNotNull("Valid D1 record must remain intact after failed force-refresh", afterFailedRefresh)
        assertEquals("https://fresh.cdn.example/working.m3u8", afterFailedRefresh!!.streamUrl)
    }

    @Test
    fun fix4_d1ExpiredRemoteRecordRejectedOnRead() = runBlocking {
        val fakeD1 = FakeInMemoryD1Api()
        val recId = CloudflareD1DataSource.deterministicRecordId("be_404", "ep_404_1", "src_404")
        fakeD1.records[recId] = com.example.data.d1.D1StreamSourceRecordDto(
            sourceDocKey = recId,
            scopeKey = "be_404",
            providerId = "direct",
            sourceId = "src_404",
            sourceName = "src_404",
            backendEpisodeId = "ep_404_1",
            streamUrl = "https://cdn.example/expired.m3u8",
            urlType = "hls",
            expiresAt = "2020-05-01T00:00:00Z",
            updatedAt = 1_800_000_000_000L
        )
        val d1 = createTestD1DataSource(metricsTracker, fakeD1)
        assertNull("Expired D1 record must be rejected as null", d1.getValidStreamUrl("be_404", "ep_404_1", "src_404"))
    }

    @Test
    fun fix4_d1WritesOnlyStoreStreamUrlRecordFieldsAndOmitUnknownExpiry() = runBlocking {
        val capturedBodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/v1/") { exchange ->
            val bodyText = exchange.requestBody.bufferedReader().readText()
            capturedBodies.add(bodyText)
            val json = """{"success":true,"message":"Record saved","table":"stream_sources","data":$bodyText}"""
            val bytes = json.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        mockServer = server

        val moshi = Moshi.Builder().add(D1BooleanAdapterFactory).addLast(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl("http://127.0.0.1:${server.address.port}/v1/")
            .client(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CloudflareD1ApiService::class.java)
        val d1 = CloudflareD1DataSource(api, metricsTracker)

        // Unknown/unparseable expiry must NOT be persisted in expiresAt
        assertTrue(
            d1.saveStreamUrl(
                animeId = "be_405",
                episodeId = "ep_405_1",
                sourceId = "src_405",
                streamUrl = "https://cdn.example/ep1.m3u8",
                expiresAt = "not-a-valid-date"
            )
        )
        assertTrue(capturedBodies.isNotEmpty())
        val payload = capturedBodies.single()
        assertTrue(payload.contains(""""scopeKey":"be_405""""))
        assertTrue(payload.contains(""""backendEpisodeId":"ep_405_1""""))
        assertTrue(payload.contains(""""sourceId":"src_405""""))
        assertTrue(payload.contains(""""streamUrl":"https://cdn.example/ep1.m3u8""""))
        assertTrue(payload.contains(""""expiresAt":null"""))
        assertFalse("Unknown expiry must be omitted rather than fabricated", payload.contains("not-a-valid-date"))
    }

    @Test
    fun fix4_backupRulesAndDataExtractionRulesExcludeRoomDatabaseAndDigestPrefs() {
        val backupFile = listOf(
            File("src/main/res/xml/backup_rules.xml"),
            File("app/src/main/res/xml/backup_rules.xml"),
            File("ProphecyVerse/app/src/main/res/xml/backup_rules.xml")
        ).first { it.exists() }
        val extractionFile = listOf(
            File("src/main/res/xml/data_extraction_rules.xml"),
            File("app/src/main/res/xml/data_extraction_rules.xml"),
            File("ProphecyVerse/app/src/main/res/xml/data_extraction_rules.xml")
        ).first { it.exists() }

        val backupXml = backupFile.readText()
        val extractionXml = extractionFile.readText()
        for (xml in listOf(backupXml, extractionXml)) {
            assertTrue(xml.contains("prophecyverse.db"))
            assertTrue(xml.contains("prophecyverse.db-wal"))
            assertTrue(xml.contains("prophecyverse.db-shm"))
            assertTrue(xml.contains("prophecy_install_prefs.xml"))
            assertTrue(xml.contains("prophecy_d1_digests.xml"))
        }
    }
}
