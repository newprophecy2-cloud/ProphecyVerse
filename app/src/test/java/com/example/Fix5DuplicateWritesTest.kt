package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1BooleanAdapterFactory
import com.example.data.d1.SharedPreferencesD1DigestStore
import com.example.data.local.AnimeAlias
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.CatalogMapping
import com.example.data.local.EpisodeEntry
import com.example.data.local.SeasonEntry
import com.example.data.local.StreamContainerEntity
import com.example.data.local.StreamEpisodeEntity
import com.example.data.local.StreamProviderEntity
import com.example.data.local.StreamSeasonEntity
import com.example.data.local.StreamSourceEntity
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.streaming.ContentIdentityHelper
import com.example.data.streaming.DiscoveryStatus
import com.example.data.streaming.FirestoreStreamingRepository
import com.example.data.streaming.StreamRecordStatus
import com.example.data.streaming.StreamingCloudStore
import com.google.firebase.FirebaseApp
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Fix5DuplicateWritesTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var metricsTracker: DatabaseMetricsTracker
    private lateinit var moshi: Moshi
    private var mockServer: HttpServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            runCatching { FirebaseApp.initializeApp(context) }
        }
        context.getSharedPreferences(SharedPreferencesD1DigestStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
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
        mockServer?.stop(0)
        mockServer = null
        db.close()
    }

    private fun startMockD1Server(handler: (HttpExchange) -> Unit): CloudflareD1ApiService {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/v1/") { exchange ->
            try {
                handler(exchange)
            } catch (t: Throwable) {
                sendJson(exchange, 500, """{"success":false,"error":"${t.message}"}""")
            }
        }
        server.start()
        mockServer = server
        val port = server.address.port
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
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
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private class CountingCloudStore : StreamingCloudStore {
        val docs = ConcurrentHashMap<String, Map<String, Any?>>()
        val batchCommits = AtomicInteger(0)
        val mergeCalls = AtomicInteger(0)
        val txCalls = AtomicInteger(0)

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
            batchCommits.incrementAndGet()
            Thread.sleep(10)
            writes.forEach { docs[it.path] = it.data }
        }

        override suspend fun mergeDoc(operation: String, path: String, data: Map<String, Any?>) {
            mergeCalls.incrementAndGet()
            Thread.sleep(10)
            val existing = docs[path].orEmpty().toMutableMap()
            existing.putAll(data)
            docs[path] = existing
        }

        override suspend fun <T> runTx(operation: String, readPath: String, block: (StreamingCloudStore.RemoteSnapshot?) -> Pair<T, List<StreamingCloudStore.DocWrite>>): T {
            txCalls.incrementAndGet()
            Thread.sleep(10)
            val snap = docs[readPath]?.let { StreamingCloudStore.RemoteSnapshot(readPath.substringAfterLast('/'), readPath, true, it) }
            val (result, writes) = block(snap)
            writes.forEach { docs[it.path] = it.data }
            return result
        }

        override fun observeDoc(path: String, onUpdate: (StreamingCloudStore.RemoteSnapshot?) -> Unit, onError: (Throwable) -> Unit): () -> Unit = {}
        override fun observeCollection(collectionPath: String, onUpdate: (List<StreamingCloudStore.RemoteSnapshot>) -> Unit, onError: (Throwable) -> Unit): () -> Unit = {}
    }

    @Test
    fun fix5_d1DigestPersistsAcrossRestart_andSkipsUnchangedWrites() = runBlocking {
        val streamUrlWrites = AtomicInteger(0)

        val api = startMockD1Server { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            when {
                method == "POST" && path == "/v1/tables/stream_urls/upsert" -> {
                    streamUrlWrites.incrementAndGet()
                    sendJson(exchange, 200, """{"success":true}""")
                }
                else -> sendJson(exchange, 200, """{"success":true,"data":[]}""")
            }
        }

        val store1 = SharedPreferencesD1DigestStore(context)
        val ds1 = CloudflareD1DataSource(api, metricsTracker, store1)
        assertTrue(
            ds1.saveStreamUrl(
                animeId = "be_501",
                episodeId = "ep_501_1",
                sourceId = "src_501",
                streamUrl = "https://cdn.example.com/501/ep1.m3u8",
                expiresAt = "2099-01-01T00:00:00Z"
            )
        )
        assertEquals(1, streamUrlWrites.get())

        // Simulate fresh app restart: brand new CloudflareD1DataSource backed by SharedPreferencesD1DigestStore
        val store2 = SharedPreferencesD1DigestStore(context)
        val ds2 = CloudflareD1DataSource(api, metricsTracker, store2)
        assertTrue(
            ds2.saveStreamUrl(
                animeId = "be_501",
                episodeId = "ep_501_1",
                sourceId = "src_501",
                streamUrl = "https://cdn.example.com/501/ep1.m3u8",
                expiresAt = "2099-01-01T00:00:00Z"
            )
        )
        assertEquals("Restart with persisted digest must not re-write unchanged stream URL", 1, streamUrlWrites.get())
        assertTrue(metricsTracker.snapshot().d1WriteSkipsUnchanged >= 1)

        // Mutating stream_url still triggers an updated D1 write
        assertTrue(
            ds2.saveStreamUrl(
                animeId = "be_501",
                episodeId = "ep_501_1",
                sourceId = "src_501",
                streamUrl = "https://cdn.example.com/501/ep1_v2.m3u8",
                expiresAt = "2099-01-01T00:00:00Z"
            )
        )
        assertEquals(2, streamUrlWrites.get())

        // Clearing digest cache resets persisted digests cleanly
        ds2.clearDigestCache()
        assertTrue(
            ds2.saveStreamUrl(
                animeId = "be_501",
                episodeId = "ep_501_1",
                sourceId = "src_501",
                streamUrl = "https://cdn.example.com/501/ep1_v2.m3u8",
                expiresAt = "2099-01-01T00:00:00Z"
            )
        )
        assertEquals(3, streamUrlWrites.get())
    }

    @Test
    fun fix5_concurrentIdenticalD1ReadsAndWritesCoalesceUnderPerKeyLock() = runBlocking {
        val streamUrlReads = AtomicInteger(0)
        val streamUrlWrites = AtomicInteger(0)
        val recId = CloudflareD1DataSource.deterministicRecordId("be_502", "ep_502_1", "s1")

        val api = startMockD1Server { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            when {
                method == "GET" && path == "/v1/tables/stream_urls/$recId" -> {
                    Thread.sleep(20)
                    streamUrlReads.incrementAndGet()
                    sendJson(
                        exchange,
                        200,
                        """{"success":true,"data":{"id":"$recId","anime_id":"be_502","episode_id":"ep_502_1","source_id":"s1","stream_url":"https://cdn.example.com/a.m3u8","expires_at":"2099-01-01T00:00:00Z"}}"""
                    )
                }
                method == "POST" && path == "/v1/tables/stream_urls/upsert" -> {
                    Thread.sleep(15)
                    streamUrlWrites.incrementAndGet()
                    sendJson(exchange, 200, """{"success":true}""")
                }
                else -> sendJson(exchange, 200, """{"success":true}""")
            }
        }

        val ds = CloudflareD1DataSource(api, metricsTracker, SharedPreferencesD1DigestStore(context))

        // Fire 6 parallel reads & 6 parallel identical writes for the same (anime_id, episode_id, source_id)
        val readResults = (1..6).map { async { ds.getValidStreamUrl("be_502", "ep_502_1", "s1") } }.awaitAll()
        assertTrue(readResults.all { it?.streamUrl == "https://cdn.example.com/a.m3u8" })
        assertEquals("Concurrent D1 GETs must single-flight coalesce to 1 request", 1, streamUrlReads.get())

        val writeResults = (1..6).map {
            async {
                ds.saveStreamUrl(
                    animeId = "be_502",
                    episodeId = "ep_502_1",
                    sourceId = "s1",
                    streamUrl = "https://cdn.example.com/b.m3u8",
                    expiresAt = "2099-01-01T00:00:00Z"
                )
            }
        }.awaitAll()
        assertTrue(writeResults.all { it })
        assertEquals("Concurrent identical D1 stream_url writes must coalesce to 1 POST", 1, streamUrlWrites.get())
    }

    @Test
    fun fix5_concurrentAndRepeatedRoomFirestoreWrites_coalesceAndSkipDuplicates() = runBlocking {
        val cloudStore = CountingCloudStore()
        val streamingRepo = FirestoreStreamingRepository(
            streamCacheDao = db.streamCacheDao(),
            firestoreProvider = { null },
            isClientWriteAuthorized = { true },
            cloudStoreProvider = { cloudStore },
            d1DataSource = null,
            metricsTracker = metricsTracker
        )
        val contentKey = "503"
        val scopeKey = "anime:503:s1:e1"
        val providerId = ContentIdentityHelper.deterministicProviderId("AnimePahe")
        val providers = listOf(
            StreamProviderEntity("$scopeKey|p|$providerId", scopeKey, providerId, "AnimePahe", sourceCount = 1, updatedAt = 1000L)
        )
        val sources = listOf(
            StreamSourceEntity("$scopeKey|p|$providerId|s|src503", scopeKey, providerId, "src503", "AnimePahe 1080p", updatedAt = 1000L)
        )

        // 5 parallel identical discovery publishes -> only 1 Firestore batch commit, subsequent skipped via Room diff
        val discoveryResults = (1..5).map {
            async {
                streamingRepo.publishDiscoveryAndSyncRoom(
                    contentKey = contentKey,
                    anilistId = 503,
                    isMovie = false,
                    backendId = "b_503",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    providers = providers,
                    sources = sources,
                    discoveryStatus = DiscoveryStatus.COMPLETED,
                    canonicalTitle = "Dedupe Anime"
                )
            }
        }.awaitAll()
        assertTrue(discoveryResults.all { it })
        assertEquals("Concurrent identical discovery calls must coalesce to 1 Firestore batch", 1, cloudStore.batchCommits.get())

        // Repeat discovery publish sequentially -> skipped cleanly with zero additional batch commits
        assertTrue(
            streamingRepo.publishDiscoveryAndSyncRoom(
                contentKey = contentKey,
                anilistId = 503,
                isMovie = false,
                backendId = "b_503",
                seasonNumber = 1,
                episodeNumber = 1,
                providers = providers,
                sources = sources,
                discoveryStatus = DiscoveryStatus.COMPLETED,
                canonicalTitle = "Dedupe Anime"
            )
        )
        assertEquals(1, cloudStore.batchCommits.get())
        assertTrue(metricsTracker.snapshot().firestoreWriteSkipsUnchanged >= 1)

        // 4 parallel identical resolved URL writes -> only 1 Firestore transaction
        val resolvedSource = sources.first()
        val resolveResults = (1..4).map {
            async {
                streamingRepo.publishResolvedStreamUrl(
                    sourceEntity = resolvedSource,
                    contentKey = contentKey,
                    isMovie = false,
                    seasonNumber = 1,
                    episodeNumber = 1,
                    streamUrl = "https://cdn.example.com/503/master.m3u8",
                    urlType = "hls",
                    contentType = "application/x-mpegURL",
                    safeHeaders = listOf(listOf("Referer", "https://kwik.si/")),
                    expiresAt = "2099-01-01T00:00:00Z",
                    verifiedAt = 5000L,
                    canonicalTitle = "Dedupe Anime",
                    backendId = "b_503"
                )
            }
        }.awaitAll()
        assertTrue(resolveResults.all { it })
        assertEquals("Concurrent identical resolved URL publishes must coalesce to 1 Firestore tx", 1, cloudStore.txCalls.get())

        // Repeat identical resolved URL publish -> skipped cleanly
        assertTrue(
            streamingRepo.publishResolvedStreamUrl(
                sourceEntity = resolvedSource,
                contentKey = contentKey,
                isMovie = false,
                seasonNumber = 1,
                episodeNumber = 1,
                streamUrl = "https://cdn.example.com/503/master.m3u8",
                urlType = "hls",
                contentType = "application/x-mpegURL",
                safeHeaders = listOf(listOf("Referer", "https://kwik.si/")),
                expiresAt = "2099-01-01T00:00:00Z",
                verifiedAt = 6000L,
                canonicalTitle = "Dedupe Anime",
                backendId = "b_503"
            )
        )
        assertEquals(1, cloudStore.txCalls.get())

        // Concurrent playback success writes -> cooldown re-checked inside per-source lock, only 1 mergeDoc
        val latestEntity = db.streamCacheDao().getLatestActiveSourceForProviderInScope(scopeKey, providerId, "src503")!!
        val playbackResults = (1..4).map {
            async {
                streamingRepo.recordPlaybackSuccess(
                    sourceEntity = latestEntity,
                    contentKey = contentKey,
                    isMovie = false,
                    seasonNumber = 1,
                    episodeNumber = 1,
                    canonicalTitle = "Dedupe Anime",
                    backendId = "b_503"
                )
            }
        }.awaitAll()
        assertTrue(playbackResults.all { it })
        assertEquals("Concurrent playback success writes within cooldown should execute at most 1 Room update + skip duplicates", 1, cloudStore.mergeCalls.get())
    }
}
