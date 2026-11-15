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
    fun fix4_sensitiveHeadersKeptInMemoryOnly_strippedBeforeRoomAndFirestoreAndD1() = runTest {
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

        val cloudStore = FakeCloudStore()
        val streamingRepo = FirestoreStreamingRepository(
            streamCacheDao = db.streamCacheDao(),
            firestoreProvider = { null },
            isClientWriteAuthorized = { true },
            cloudStoreProvider = { cloudStore },
            d1DataSource = null,
            metricsTracker = metricsTracker
        )
        val repo = AnimeRepository(db, api, noOpAniList(), context, streamingRepo, null, memoryCache, metricsTracker)

        val resolved = repo.resolvePlayableStream(animeId = animeId, seasonNumber = 1, episodeNumber = 1)
        assertTrue(resolved.isSuccess)
        val directData = resolved.getOrThrow().directData
        val memHeaderNames = directData.headers.map { it[0] }
        assertTrue("In-memory playback keeps Referer", "Referer" in memHeaderNames)
        assertTrue("In-memory playback keeps Authorization", "Authorization" in memHeaderNames)
        assertTrue("In-memory playback keeps Cookie", "Cookie" in memHeaderNames)

        val persistedRoom = db.streamCacheDao().getLatestActiveSourceForProviderInScope(scopeKey, providerId, "src_401")!!
        val roomHeadersJson = persistedRoom.safeHeadersJson.orEmpty()
        assertTrue("Room keeps safe Referer header", roomHeadersJson.contains("Referer"))
        assertFalse("Room must never persist Authorization", roomHeadersJson.contains("top-secret-jwt"))
        assertFalse("Room must never persist Cookie", roomHeadersJson.contains("secret_cookie"))
        assertFalse("Room must never persist X-Auth-Token", roomHeadersJson.contains("secret_token"))
        assertFalse("Room must never persist X-Signature", roomHeadersJson.contains("hmac_sig"))
        assertFalse("Room must never persist Session-Id", roomHeadersJson.contains("sess_999"))

        val fsDoc = cloudStore.docs["anime_streams/401/seasons/season_1/episodes/ep_1/providers/$providerId/sources/src_401"]
        assertNotNull("Firestore source doc should be written", fsDoc)
        val fsSerialized = fsDoc.toString()
        assertTrue("Firestore keeps safe Referer header", fsSerialized.contains("Referer"))
        assertFalse("Firestore must never store secret JWT", fsSerialized.contains("top-secret-jwt"))
        assertFalse("Firestore must never store Cookie", fsSerialized.contains("secret_cookie"))
        assertFalse("Firestore must never store Session-Id", fsSerialized.contains("sess_999"))
    }

    @Test
    fun fix4_expiredUrlsEvictedOnReadAcrossMemoryRoomAndFirestore_andFailedResolveNeverWipesValidUrl() = runTest {
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
        // Seed expired Room record marked AVAILABLE -> must be evicted to EXPIRED & streamUrl=null on read
        db.streamCacheDao().upsertSource(
            StreamSourceEntity(
                sourceDocKey = "$scopeKey|p|$providerId|s|src_402",
                scopeKey = scopeKey,
                providerId = providerId,
                sourceId = "src_402",
                sourceName = "MegaCloud 1080p",
                streamUrl = "https://stale.cdn.example/expired.mp4",
                safeHeadersJson = """[["Referer","https://stale.example/"]]""",
                expiresAt = "2020-01-01T00:00:00Z",
                status = StreamRecordStatus.AVAILABLE.name,
                updatedAt = now
            )
        )
        // Seed expired entry in memory cache too
        memoryCache.putResolvedPlayback(
            "src_402",
            DirectPlayData("src_402", "https://stale.cdn.example/mem_expired.mp4", "direct", expiresAt = "2020-01-01T00:00:00Z"),
            scopeKey,
            providerId
        )
        assertNull("Expired entry in MemoryCache must be rejected", memoryCache.getResolvedPlayback("src_402", scopeKey, providerId))

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
        val repo = AnimeRepository(db, api, noOpAniList(), context, streamingRepo, null, memoryCache, metricsTracker)

        // First call: expired Room entity must be skipped & evicted, triggering fresh backend resolve
        val res1 = repo.resolvePlayableStream(animeId, 1, 1)
        assertTrue(res1.isSuccess)
        assertEquals("https://fresh.cdn.example/working.m3u8", res1.getOrThrow().directData.playbackUrl)
        assertEquals(1, resolveCalls.get())

        // Verify forced resolve failure does NOT wipe the now-valid Room URL
        shouldFailResolve = true
        val failedRefresh = repo.resolvePlayableStream(animeId, 1, 1, forceResolveFresh = true)
        assertTrue(failedRefresh.isFailure)
        val afterFailedRefresh = db.streamCacheDao().getLatestActiveSourceForProviderInScope(scopeKey, providerId, "src_402")!!
        assertEquals("https://fresh.cdn.example/working.m3u8", afterFailedRefresh.streamUrl)
        assertEquals(StreamRecordStatus.AVAILABLE.name, afterFailedRefresh.status)

        // Check direct eviction when resolving another expired Room row while backend fails
        val expScope = "anime:403:s1:e1"
        db.animeDao().upsertAnime(
            AnimeEntry(id = "anilist_403", aniListId = 403, backendAnimeId = "b_403", titleRomaji = "Test 403", preferredTitle = "Test 403")
        )
        db.streamCacheDao().upsertProviders(
            listOf(StreamProviderEntity("$expScope|p|$providerId", expScope, providerId, "MegaCloud", sourceCount = 1, updatedAt = now))
        )
        db.streamCacheDao().upsertSource(
            StreamSourceEntity(
                sourceDocKey = "$expScope|p|$providerId|s|src_403",
                scopeKey = expScope,
                providerId = providerId,
                sourceId = "src_403",
                sourceName = "MegaCloud",
                streamUrl = "https://stale.cdn.example/old403.mp4",
                safeHeadersJson = """[["Referer","https://old.example/"]]""",
                expiresAt = "2021-01-01T00:00:00Z",
                status = StreamRecordStatus.AVAILABLE.name,
                updatedAt = now
            )
        )
        val failed403 = repo.resolvePlayableStream("anilist_403", 1, 1)
        assertTrue(failed403.isFailure)
        val evicted403 = db.streamCacheDao().getLatestActiveSourceForProviderInScope(expScope, providerId, "src_403")!!
        assertNull("Expired cached URL must be evicted to null on read", evicted403.streamUrl)
        assertNull("Expired cached safeHeadersJson must be cleared on eviction", evicted403.safeHeadersJson)
        assertEquals(StreamRecordStatus.EXPIRED.name, evicted403.status)
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
            "stream_url" to "https://expired.firestore.cdn/video.m3u8",
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
        assertNull("Expired Firestore URL must not enter Room", parsed.streamUrl)
        assertEquals(StreamRecordStatus.EXPIRED.name, parsed.status)
    }

    @Test
    fun fix4_d1WritesNeverIncludeTransientStreamUrlsHeadersOrExpiry() = runBlocking {
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

        val now = System.currentTimeMillis()
        val scopeKey = "anime:405:s1:e1"
        val src = StreamSourceEntity(
            sourceDocKey = "$scopeKey|p|prov_405|s|src_405",
            scopeKey = scopeKey,
            providerId = "prov_405",
            sourceId = "src_405",
            sourceName = "Provider405",
            streamUrl = "https://private.signed.cdn/ep1.m3u8?token=SECRET_XYZ",
            safeHeadersJson = """[["Referer","https://private.host/"]]""",
            expiresAt = "2099-12-31T23:59:59Z",
            status = StreamRecordStatus.AVAILABLE.name,
            updatedAt = now
        )
        d1.publishScopedStreamingIfChanged(
            container = StreamContainerEntity("anime:405", "405", 405, "ANIME", updatedAt = now),
            season = null,
            episode = null,
            providers = listOf(StreamProviderEntity("$scopeKey|p|prov_405", scopeKey, "prov_405", "Provider405", updatedAt = now)),
            sources = listOf(src)
        )
        d1.reportPlaybackSuccessMetadata(src)

        assertTrue(capturedBodies.isNotEmpty())
        val joined = capturedBodies.joinToString("\n")
        assertFalse("D1 payload must never contain transient streamUrl", joined.contains("private.signed.cdn"))
        assertFalse("D1 payload must never contain token", joined.contains("SECRET_XYZ"))
        assertFalse("D1 payload must never contain headers", joined.contains("private.host"))
        assertFalse("D1 payload must never contain expiresAt", joined.contains("2099-12-31T23:59:59Z"))
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
