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
import com.example.data.local.StreamContainerEntity
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
import com.example.data.repository.SourceIdentityValidator
import com.example.data.streaming.ContentIdentityHelper
import com.example.data.streaming.DiscoveryStatus
import com.example.data.streaming.FirestoreStreamingRepository
import com.example.data.streaming.StreamRecordStatus
import com.example.data.streaming.StreamingCloudStore
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
        object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = animeId, title = "Show"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(emptyList<ProphecyEpisodeDto>())
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(ProphecyEpisodeFullResponse())
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) = directResolver(request)
        }

    private class FakeCloudStore : StreamingCloudStore {
        val docs = ConcurrentHashMap<String, Map<String, Any?>>()
        override suspend fun getDoc(operation: String, path: String, preferCache: Boolean): StreamingCloudStore.RemoteSnapshot? {
            val data = docs[path] ?: return StreamingCloudStore.RemoteSnapshot(path.substringAfterLast('/'), path, false, emptyMap())
            return StreamingCloudStore.RemoteSnapshot(path.substringAfterLast('/'), path, true, data)
        }
        override suspend fun listCollection(operation: String, collectionPath: String, preferCache: Boolean): List<StreamingCloudStore.RemoteSnapshot> {
            val prefix = "$collectionPath/"
            return docs.entries
                .filter { it.key.startsWith(prefix) && !it.key.removePrefix(prefix).contains('/') }
                .map { StreamingCloudStore.RemoteSnapshot(it.key.removePrefix(prefix), it.key, true, it.value) }
        }
        override suspend fun commitBatch(operation: String, writes: List<StreamingCloudStore.DocWrite>) {
            writes.forEach { docs[it.path] = it.data }
        }
        override suspend fun mergeDoc(operation: String, path: String, data: Map<String, Any?>) {
            val existing = docs[path].orEmpty().toMutableMap()
            existing.putAll(data)
            docs[path] = existing
        }
        override suspend fun <T> runTx(operation: String, readPath: String, block: (StreamingCloudStore.RemoteSnapshot?) -> Pair<T, List<StreamingCloudStore.DocWrite>>): T {
            val snap = docs[readPath]?.let { StreamingCloudStore.RemoteSnapshot(readPath.substringAfterLast('/'), readPath, true, it) }
            val (result, writes) = block(snap)
            writes.forEach { docs[it.path] = it.data }
            return result
        }
        override fun observeDoc(path: String, onUpdate: (StreamingCloudStore.RemoteSnapshot?) -> Unit, onError: (Throwable) -> Unit): () -> Unit = {}
        override fun observeCollection(collectionPath: String, onUpdate: (List<StreamingCloudStore.RemoteSnapshot>) -> Unit, onError: (Throwable) -> Unit): () -> Unit = {}
    }

    @Test
    fun fix4_sensitiveHeadersKeptInMemoryOnly_roomMetadataOnly_andD1NeverStoresHeaders() = runTest {
        val animeId = "anilist_401"
        val scopeKey = "anime:401:s1:e1"
        val providerId = ContentIdentityHelper.deterministicProviderId("AnimePahe")
        val now = System.currentTimeMillis()
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 401, backendAnimeId = "b_401", titleRomaji = "Test 401", preferredTitle = "Test 401")
        )
        db.streamCacheDao().upsertProviders(
            listOf(
                StreamProviderEntity(
                    providerDocKey = "$scopeKey|p|$providerId",
                    scopeKey = scopeKey,
                    providerId = providerId,
                    providerName = "AnimePahe",
                    sourceCount = 1,
                    updatedAt = now
                )
            )
        )
        db.streamCacheDao().upsertSource(
            StreamSourceEntity(
                sourceDocKey = "$scopeKey|p|$providerId|s|src_401",
                scopeKey = scopeKey,
                providerId = providerId,
                sourceId = "src_401",
                sourceName = "AnimePahe 1080p",
                status = StreamRecordStatus.MISSING_URL.name,
                updatedAt = now
            )
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
            val path = exchange.requestURI.path
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

        val cloudStore = FakeCloudStore()
        val streamingRepo = FirestoreStreamingRepository(
            streamCacheDao = db.streamCacheDao(),
            firestoreProvider = { null },
            isClientWriteAuthorized = { true },
            cloudStoreProvider = { cloudStore },
            d1DataSource = null,
            metricsTracker = metricsTracker
        )
        val repo = AnimeRepository(db, api, noOpAniList(), context, streamingRepo, d1, memoryCache, metricsTracker)

        val resolved = repo.resolvePlayableStream(animeId = animeId, seasonNumber = 1, episodeNumber = 1)
        assertTrue(resolved.isSuccess)
        val directData = resolved.getOrThrow().directData
        val memHeaderNames = directData.headers.map { it[0] }
        assertTrue("In-memory playback keeps Referer", "Referer" in memHeaderNames)
        assertTrue("In-memory playback keeps Authorization", "Authorization" in memHeaderNames)
        assertTrue("In-memory playback keeps Cookie", "Cookie" in memHeaderNames)

        // Wait briefly for async D1 save
        val deadline = System.currentTimeMillis() + 2000L
        while (capturedD1Bodies.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        val d1Joined = capturedD1Bodies.joinToString("\n")
        assertTrue("D1 stream_urls must store resolved stream_url", d1Joined.contains("https://cdn.example.com/secure/stream.m3u8"))
        assertFalse("D1 stream_urls must never persist Authorization", d1Joined.contains("top-secret-jwt"))
        assertFalse("D1 stream_urls must never persist Cookie", d1Joined.contains("secret_cookie"))
        assertFalse("D1 stream_urls must never persist Referer header", d1Joined.contains("kwik.si"))
        assertFalse("D1 stream_urls must never persist Session-Id", d1Joined.contains("sess_999"))

        val fsDoc = cloudStore.docs["anime_streams/401/seasons/season_1/episodes/ep_1/providers/$providerId/sources/src_401"]
        assertNotNull("Firestore source doc should be written", fsDoc)
        val fsSerialized = fsDoc.toString()
        assertFalse("Firestore must never store secret JWT", fsSerialized.contains("top-secret-jwt"))
        assertFalse("Firestore must never store Cookie", fsSerialized.contains("secret_cookie"))
        assertFalse("Firestore must never store Session-Id", fsSerialized.contains("sess_999"))
    }

    @Test
    fun fix4_expiredUrlsEvictedAcrossMemoryAndD1_andFailedResolveNeverWipesValidD1Url() = runTest {
        val animeId = "anilist_402"
        val scopeKey = "anime:402:s1:e1"
        val providerId = ContentIdentityHelper.deterministicProviderId("MegaCloud")
        val now = System.currentTimeMillis()
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 402, backendAnimeId = "b_402", titleRomaji = "Test 402", preferredTitle = "Test 402")
        )
        db.streamCacheDao().upsertProviders(
            listOf(StreamProviderEntity("$scopeKey|p|$providerId", scopeKey, providerId, "MegaCloud", sourceCount = 1, updatedAt = now))
        )
        db.streamCacheDao().upsertSource(
            StreamSourceEntity(
                sourceDocKey = "$scopeKey|p|$providerId|s|src_402",
                scopeKey = scopeKey,
                providerId = providerId,
                sourceId = "src_402",
                sourceName = "MegaCloud 1080p",
                expiresAt = "2020-01-01T00:00:00Z",
                status = StreamRecordStatus.AVAILABLE.name,
                updatedAt = now
            )
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
        d1Store[recId] = """{"id":"$recId","anime_id":"b_402","episode_id":"402_s1_e1","source_id":"src_402","stream_url":"https://stale.cdn.example/expired.mp4","expires_at":"2020-01-01T00:00:00Z"}"""

        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/v1/") { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            when {
                method == "GET" && path.startsWith("/v1/tables/stream_urls/") -> {
                    val key = path.removePrefix("/v1/tables/stream_urls/")
                    val raw = d1Store[key]
                    if (raw == null) {
                        val b = """{"success":false,"error":"Not found"}""".toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(404, b.size.toLong())
                        exchange.responseBody.use { it.write(b) }
                    } else {
                        val b = """{"success":true,"data":$raw}""".toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, b.size.toLong())
                        exchange.responseBody.use { it.write(b) }
                    }
                }
                method == "POST" && path == "/v1/tables/stream_urls/upsert" -> {
                    val body = exchange.requestBody.bufferedReader().readText()
                    val idMatch = Regex(""""id"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: recId
                    d1Store[idMatch] = body
                    val b = """{"success":true}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, b.size.toLong())
                    exchange.responseBody.use { it.write(b) }
                }
                method == "DELETE" && path.startsWith("/v1/tables/stream_urls/") -> {
                    val key = path.removePrefix("/v1/tables/stream_urls/")
                    d1Store.remove(key)
                    val b = """{"success":true}""".toByteArray()
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
        val streamingRepo = FirestoreStreamingRepository(
            streamCacheDao = db.streamCacheDao(),
            firestoreProvider = { null },
            isClientWriteAuthorized = { false },
            cloudStoreProvider = null,
            d1DataSource = null,
            metricsTracker = metricsTracker
        )
        val repo = AnimeRepository(db, api, noOpAniList(), context, streamingRepo, d1, memoryCache, metricsTracker)

        // First call: expired D1 entry must be treated as cache miss & trigger fresh backend resolve
        val res1 = repo.resolvePlayableStream(animeId, 1, 1)
        assertTrue(res1.isSuccess)
        assertEquals("https://fresh.cdn.example/working.m3u8", res1.getOrThrow().directData.playbackUrl)
        assertEquals(1, resolveCalls.get())

        val deadline = System.currentTimeMillis() + 2000L
        while (d1Store[recId]?.contains("working.m3u8") != true && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
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
    fun fix4_firestoreExpiredRemoteDocRejectedOnHydration() = runBlocking {
        val cloudStore = FakeCloudStore()
        val scopePath = "anime_streams/404/seasons/season_1/episodes/ep_1"
        val provId = "prov_test404"
        cloudStore.docs["anime_streams/404"] = mapOf(
            "anilist_id" to 404L,
            "content_type" to "ANIME",
            "discovery_status" to DiscoveryStatus.COMPLETED.name,
            "version" to 2L
        )
        cloudStore.docs[scopePath] = mapOf(
            "discovery_status" to DiscoveryStatus.COMPLETED.name,
            "version" to 2L
        )
        cloudStore.docs["$scopePath/providers/$provId"] = mapOf(
            "provider_id" to provId,
            "provider_name" to "TestProv",
            "status" to "AVAILABLE",
            "source_count" to 1L
        )
        cloudStore.docs["$scopePath/providers/$provId/sources/src_404"] = mapOf(
            "provider_id" to provId,
            "source_id" to "src_404",
            "source_name" to "TestProv 1080p",
            "expires_at" to "2020-05-01T00:00:00Z",
            "status" to "AVAILABLE",
            "version" to 2L
        )

        val streamingRepo = FirestoreStreamingRepository(
            streamCacheDao = db.streamCacheDao(),
            firestoreProvider = { null },
            isClientWriteAuthorized = { true },
            cloudStoreProvider = { cloudStore },
            d1DataSource = null,
            metricsTracker = metricsTracker
        )
        val outcome = streamingRepo.fetchAndSyncScopeWithOutcome("404", isMovie = false, seasonNumber = 1, episodeNumber = 1, requirePlayableUrl = true)
        assertNotNull("status=${outcome.status}, msg=${outcome.message}", outcome.data)
        val parsed = outcome.data!!.sources.single()
        assertEquals(StreamRecordStatus.EXPIRED.name, parsed.status)
    }

    @Test
    fun fix4_d1WritesOnlyStoreStreamUrlRecordFieldsAndOmitUnknownExpiry() = runBlocking {
        val capturedBodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/v1/") { exchange ->
            val bodyText = exchange.requestBody.bufferedReader().readText()
            capturedBodies.add(bodyText)
            val json = """{"success":true,"data":{"ok":true}}"""
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

        // Unknown/unparseable expiry must NOT be persisted in expires_at
        d1.saveStreamUrl(
            animeId = "be_405",
            episodeId = "ep_405_1",
            sourceId = "src_405",
            streamUrl = "https://cdn.example/ep1.m3u8",
            expiresAt = "not-a-valid-date"
        )
        assertTrue(capturedBodies.isNotEmpty())
        val payload = capturedBodies.single()
        assertTrue(payload.contains(""""anime_id":"be_405""""))
        assertTrue(payload.contains(""""episode_id":"ep_405_1""""))
        assertTrue(payload.contains(""""source_id":"src_405""""))
        assertTrue(payload.contains(""""stream_url":"https://cdn.example/ep1.m3u8""""))
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
