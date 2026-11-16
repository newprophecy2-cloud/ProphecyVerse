package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1BooleanAdapterFactory
import com.example.data.d1.SharedPreferencesD1DigestStore
import com.example.data.local.AppDatabase
import com.example.data.metrics.DatabaseMetricsTracker
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

    @Test
    fun fix5_d1DigestPersistsAcrossRestart_andSkipsUnchangedWritesAfterRemoteVerification() = runBlocking {
        val streamUrlWrites = AtomicInteger(0)
        val remoteRows = ConcurrentHashMap<String, String>()

        val api = startMockD1Server { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            when {
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    streamUrlWrites.incrementAndGet()
                    val body = exchange.requestBody.bufferedReader().readText()
                    val id = Regex(""""sourceDocKey"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
                    remoteRows[id] = body
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$body}""")
                }
                method == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val id = path.removePrefix("/v1/tables/stream_sources/")
                    val row = remoteRows[id]
                    if (row == null) {
                        sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                    } else {
                        sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$row}""")
                    }
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
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
        // Remote row is still present and matches digest -> skips redundant POST upsert
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
        assertEquals("Restart with persisted digest and verified remote row must not re-write unchanged stream URL", 1, streamUrlWrites.get())
        assertTrue(metricsTracker.snapshot().d1WriteSkipsUnchanged >= 1)

        // Mutating streamUrl still triggers an updated D1 write
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
        val remoteRows = ConcurrentHashMap<String, String>()
        val recId = CloudflareD1DataSource.deterministicRecordId("be_502", "ep_502_1", "s1")
        remoteRows[recId] = """{"sourceDocKey":"$recId","scopeKey":"be_502","providerId":"direct","sourceId":"s1","sourceName":"s1","backendEpisodeId":"ep_502_1","streamUrl":"https://cdn.example.com/a.m3u8","urlType":"hls","expiresAt":"2099-01-01T00:00:00Z","status":"AVAILABLE","version":1,"updatedAt":1800000000000,"deletedAt":null}"""

        val api = startMockD1Server { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            when {
                method == "GET" && path == "/v1/tables/stream_sources/$recId" -> {
                    Thread.sleep(20)
                    streamUrlReads.incrementAndGet()
                    val row = remoteRows[recId]
                    sendJson(
                        exchange,
                        200,
                        """{"success":true,"table":"stream_sources","data":$row}"""
                    )
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    Thread.sleep(15)
                    streamUrlWrites.incrementAndGet()
                    val body = exchange.requestBody.bufferedReader().readText()
                    remoteRows[recId] = body
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$body}""")
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
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
        assertEquals("Concurrent identical D1 stream_sources writes must coalesce to 1 POST", 1, streamUrlWrites.get())
    }
}
