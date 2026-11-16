package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1BooleanAdapterFactory
import com.example.data.d1.D1StreamSourceRecordDto
import com.example.data.d1.InMemoryD1DigestStore
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
import com.example.data.remote.NetworkModule
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.repository.SourceDiagnosticCode
import com.example.data.repository.SourceIdentityValidator
import com.example.data.repository.SourceValidationResult
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
import org.junit.Assert.assertNotEquals
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class D1ContractAndPersistenceRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var metricsTracker: DatabaseMetricsTracker
    private lateinit var moshi: Moshi
    private var mockServer: HttpServer? = null

    private val allowedStreamSourcesColumns = setOf(
        "sourceDocKey",
        "scopeKey",
        "providerId",
        "sourceId",
        "sourceName",
        "backendEpisodeId",
        "streamUrl",
        "urlType",
        "contentType",
        "quality",
        "codec",
        "audioLanguage",
        "downloadable",
        "sizeBytes",
        "safeHeadersJson",
        "expiresAt",
        "status",
        "version",
        "lastVerifiedAt",
        "lastPlaybackSuccessAt",
        "updatedAt",
        "deletedAt"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            runCatching { FirebaseApp.initializeApp(context) }
        }
        context.getSharedPreferences(SharedPreferencesD1DigestStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PlaybackDiagnosticsLogger.clearForTests()
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

    private fun startMockWorkerServer(handler: (HttpExchange) -> Unit): CloudflareD1ApiService {
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

    private fun sendEmpty(exchange: HttpExchange, status: Int) {
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, 0L)
        exchange.responseBody.close()
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

    @Test
    fun t1_t2_t3_t4_canonicalTable_workerRoutes_requestJson_andResponseParsing() = runTest {
        assertEquals("stream_sources", CloudflareD1DataSource.CANONICAL_TABLE)

        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val capturedUpsertJson = CopyOnWriteArrayList<String>()
        val dbRows = ConcurrentHashMap<String, JSONObject>()

        val api = startMockWorkerServer { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            requests.add(method to if (query.isEmpty()) path else "$path?$query")

            when {
                method == "GET" && path == "/v1/tables" -> {
                    sendJson(
                        exchange,
                        200,
                        """{"success":true,"count":11,"tables":["_cf_KV","anime_aliases","anime_entries","catalog_mappings","episode_entries","season_entries","stream_containers","stream_episodes","stream_providers","stream_seasons","stream_sources"]}"""
                    )
                }
                method == "GET" && path == "/v1/tables/stream_sources" -> {
                    sendJson(
                        exchange,
                        200,
                        """{"success":true,"table":"stream_sources","total":${dbRows.size},"limit":1,"offset":0,"count":0,"data":[]}"""
                    )
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    val raw = exchange.requestBody.bufferedReader().readText()
                    capturedUpsertJson.add(raw)
                    val obj = JSONObject(raw)
                    val keys = obj.keys().asSequence().toSet()
                    val unknown = keys - allowedStreamSourcesColumns
                    if (unknown.isNotEmpty()) {
                        sendJson(exchange, 400, """{"success":false,"error":"Unknown column(s): ${unknown.joinToString()}"}""")
                        return@startMockWorkerServer
                    }
                    val pk = obj.optString("sourceDocKey", "")
                    if (pk.isBlank()) {
                        sendJson(exchange, 400, """{"success":false,"error":"Missing primary key field: sourceDocKey"}""")
                        return@startMockWorkerServer
                    }
                    dbRows[pk] = obj
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$obj}""")
                }
                method == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val id = path.removePrefix("/v1/tables/stream_sources/")
                    val row = dbRows[id]
                    if (row == null) {
                        sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                    } else {
                        sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$row}""")
                    }
                }
                method == "DELETE" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val id = path.removePrefix("/v1/tables/stream_sources/")
                    val removed = dbRows.remove(id)
                    if (removed == null) {
                        sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                    } else {
                        sendJson(exchange, 200, """{"success":true,"message":"Record deleted","table":"stream_sources","changes":1}""")
                    }
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Table not found"}""")
            }
        }

        val d1 = CloudflareD1DataSource(api = api, metricsTracker = metricsTracker)
        assertTrue(d1.verifyHealth())
        assertTrue(requests.contains("GET" to "/v1/tables"))
        assertTrue(requests.any { it.first == "GET" && it.second.startsWith("/v1/tables/stream_sources") })
        assertFalse(requests.any { it.second.contains("stream_urls") })

        assertTrue(
            d1.saveStreamUrl(
                animeId = "be_21",
                episodeId = "be_ep_1",
                sourceId = "src_pahe_1080",
                streamUrl = "https://cdn.example/master.m3u8",
                expiresAt = "2099-01-01T00:00:00Z"
            )
        )
        assertEquals(1, capturedUpsertJson.size)
        val sentJson = JSONObject(capturedUpsertJson.single())
        assertTrue(sentJson.has("sourceDocKey"))
        assertEquals("be_21", sentJson.getString("scopeKey"))
        assertEquals("be_ep_1", sentJson.getString("backendEpisodeId"))
        assertEquals("src_pahe_1080", sentJson.getString("sourceId"))
        assertEquals("https://cdn.example/master.m3u8", sentJson.getString("streamUrl"))
        assertEquals("2099-01-01T00:00:00Z", sentJson.getString("expiresAt"))
        assertTrue(sentJson.getLong("updatedAt") > 0L)
        assertFalse(sentJson.has("anime_id"))
        assertFalse(sentJson.has("episode_id"))
        assertFalse(sentJson.has("stream_url"))
        assertFalse(sentJson.has("expires_at"))

        val hit = d1.getValidStreamUrl("be_21", "be_ep_1", "src_pahe_1080")
        assertNotNull(hit)
        assertEquals("https://cdn.example/master.m3u8", hit?.streamUrl)

        // SQLite 0/1 boolean parsing via NetworkModule.moshi
        val parsedSqliteBool = NetworkModule.moshi.adapter(D1StreamSourceRecordDto::class.java).fromJson(
            """{"sourceDocKey":"k1","scopeKey":"a1","providerId":"direct","sourceId":"s1","sourceName":"s1","downloadable":1,"updatedAt":1000}"""
        )!!
        assertEquals(true, parsedSqliteBool.downloadable)

        assertTrue(d1.invalidateStreamUrl("be_21", "be_ep_1", "src_pahe_1080"))
        assertNull(d1.getValidStreamUrl("be_21", "be_ep_1", "src_pahe_1080"))
    }

    @Test
    fun t5_t6_t7_upsertConfirmation_rejectsNullBody_successFalse_missingData_andIdentityMismatch() = runTest {
        val digestStore = InMemoryD1DigestStore()
        var responseMode = "NULL_BODY"
        val upsertCalls = AtomicInteger(0)

        val api = object : CloudflareD1ApiService {
            override suspend fun listTables() = throw UnsupportedOperationException()
            override suspend fun listStreamSources(where: String?, limit: Int?, offset: Int?) =
                throw UnsupportedOperationException()
            override suspend fun getStreamSource(id: String) =
                Response.error<com.example.data.d1.D1RecordResponse<D1StreamSourceRecordDto>>(
                    404,
                    """{"success":false,"error":"Record not found"}""".toResponseBody(null)
                )
            override suspend fun deleteStreamSource(id: String) = throw UnsupportedOperationException()

            override suspend fun upsertStreamSource(
                record: D1StreamSourceRecordDto
            ): Response<com.example.data.d1.D1RecordResponse<D1StreamSourceRecordDto>> {
                upsertCalls.incrementAndGet()
                return when (responseMode) {
                    "NULL_BODY" -> Response.success(null)
                    "SUCCESS_FALSE" -> Response.success(
                        com.example.data.d1.D1RecordResponse(
                            success = false,
                            table = "stream_sources",
                            error = "Write rejected"
                        )
                    )
                    "MISSING_DATA" -> Response.success(
                        com.example.data.d1.D1RecordResponse(
                            success = true,
                            table = "stream_sources",
                            data = null
                        )
                    )
                    "WRONG_IDENTITY" -> Response.success(
                        com.example.data.d1.D1RecordResponse(
                            success = true,
                            table = "stream_sources",
                            data = record.copy(backendEpisodeId = "other_episode")
                        )
                    )
                    else -> Response.success(
                        com.example.data.d1.D1RecordResponse(
                            success = true,
                            message = "Record saved",
                            table = "stream_sources",
                            data = record
                        )
                    )
                }
            }
        }

        val d1 = CloudflareD1DataSource(api = api, metricsTracker = metricsTracker, digestStore = digestStore)

        // 6. HTTP 200 with null body must return false and NOT poison digest
        responseMode = "NULL_BODY"
        assertFalse(d1.saveStreamUrl("be_1", "ep_1", "src_1", "https://cdn.example/v.mp4", null))

        // Also verify real OkHttp HTTP 200 empty body via MockServer
        val httpApi = startMockWorkerServer { exchange ->
            if (exchange.requestMethod == "POST" && exchange.requestURI.path == "/v1/tables/stream_sources/upsert") {
                sendEmpty(exchange, 200)
            } else {
                sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
            }
        }
        val httpD1 = CloudflareD1DataSource(api = httpApi, metricsTracker = metricsTracker)
        assertFalse(httpD1.saveStreamUrl("be_1", "ep_1", "src_1", "https://cdn.example/v.mp4", null))

        // 7. HTTP 200 with success=false, data=null, or mismatched identity must return false
        responseMode = "SUCCESS_FALSE"
        assertFalse(d1.saveStreamUrl("be_1", "ep_1", "src_1", "https://cdn.example/v.mp4", null))

        responseMode = "MISSING_DATA"
        assertFalse(d1.saveStreamUrl("be_1", "ep_1", "src_1", "https://cdn.example/v.mp4", null))

        responseMode = "WRONG_IDENTITY"
        assertFalse(d1.saveStreamUrl("be_1", "ep_1", "src_1", "https://cdn.example/v.mp4", null))

        // 5. Confirmed response returns true and attempted write was NOT skipped by earlier failed digests
        responseMode = "CONFIRMED"
        assertTrue(d1.saveStreamUrl("be_1", "ep_1", "src_1", "https://cdn.example/v.mp4", null))
        assertEquals(5, upsertCalls.get())
    }

    @Test
    fun t8_t9_t10_t11_missing_existing_expired_andUnknownExpiryBehavior() = runTest {
        val dbRows = ConcurrentHashMap<String, JSONObject>()
        val api = startMockWorkerServer { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            when {
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    val raw = exchange.requestBody.bufferedReader().readText()
                    val obj = JSONObject(raw)
                    dbRows[obj.getString("sourceDocKey")] = obj
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$obj}""")
                }
                method == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val id = path.removePrefix("/v1/tables/stream_sources/")
                    val row = dbRows[id]
                    if (row == null) sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                    else sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$row}""")
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
            }
        }

        val d1 = CloudflareD1DataSource(api = api, metricsTracker = metricsTracker)

        // 8. Missing record -> null cache miss
        assertNull(d1.getValidStreamUrl("be_99", "ep_1", "src_missing"))

        // 9. Existing valid record -> hit
        assertTrue(
            d1.saveStreamUrl("be_99", "ep_1", "src_valid", "https://cdn.example/valid.m3u8", "2099-01-01T00:00:00Z")
        )
        val validHit = d1.getValidStreamUrl("be_99", "ep_1", "src_valid")
        assertNotNull(validHit)
        assertEquals("2099-01-01T00:00:00Z", validHit?.expiresAt)

        // 10. Expired record -> cache miss (and cannot save already-expired URL)
        assertFalse(
            d1.saveStreamUrl("be_99", "ep_1", "src_exp", "https://cdn.example/exp.m3u8", "2020-01-01T00:00:00Z")
        )
        val expKey = CloudflareD1DataSource.deterministicRecordId("be_99", "ep_1", "src_exp")
        dbRows[expKey] = JSONObject(
            """{"sourceDocKey":"$expKey","scopeKey":"be_99","providerId":"direct","sourceId":"src_exp","sourceName":"src_exp","backendEpisodeId":"ep_1","streamUrl":"https://cdn.example/exp.m3u8","urlType":"hls","expiresAt":"2020-01-01T00:00:00Z","status":"AVAILABLE","version":1,"updatedAt":1800000000000,"deletedAt":null}"""
        )
        assertNull(d1.getValidStreamUrl("be_99", "ep_1", "src_exp"))

        // 11. Unknown expiry -> expiresAt remains null (not fabricated), and overwriting valid expiry clears it
        assertTrue(
            d1.saveStreamUrl("be_99", "ep_1", "src_valid", "https://cdn.example/unknown_exp.mp4", expiresAt = null)
        )
        val storedObj = dbRows[CloudflareD1DataSource.deterministicRecordId("be_99", "ep_1", "src_valid")]!!
        assertTrue(storedObj.isNull("expiresAt"))
        val unknownHit = d1.getValidStreamUrl("be_99", "ep_1", "src_valid")
        assertNotNull(unknownHit)
        assertEquals("https://cdn.example/unknown_exp.mp4", unknownHit?.streamUrl)
        assertNull(unknownHit?.expiresAt)
    }

    @Test
    fun t12_t13_failedOrTimedOutD1Upsert_doesNotBreakPlaybackAndNeverClaimsCacheSuccess() = runTest {
        val animeId = "anilist_701"
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 701, backendAnimeId = "be_701", preferredTitle = "Failure Resilience", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_701_1", animeId, 1, 1, sourceEpisodeIds = "1:be_ep_701_1")))

        val apiMode = AtomicInteger(0) // 0 = HTTP 500 write failure, 1 = >4s timeout
        val d1Api = startMockWorkerServer { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            when {
                method == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    if (apiMode.get() == 0) {
                        sendJson(exchange, 500, """{"success":false,"error":"Database error"}""")
                    } else {
                        Thread.sleep(4_600L)
                        sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":null}""")
                    }
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
            }
        }

        val backendApi = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_701", title = "Failure Resilience"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_ep_701_1", season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(ProphecySourceDto(id = "src_701", label = "Pahe 1080p"))
                    )
                )
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) = Response.success(
                DirectPlayResponse(
                    success = true,
                    data = DirectPlayData(
                        sourceId = request.sourceId,
                        playbackUrl = "https://cdn.example/live_token.m3u8?token=super_secret_token",
                        expiresAt = "2099-01-01T00:00:00Z"
                    )
                )
            )
        }

        val repo = createRepo(backendApi, CloudflareD1DataSource(d1Api, metricsTracker))

        // 12. Failed D1 upsert -> playback still succeeds for current session, ZERO false D1_CACHE_WRITE events
        apiMode.set(0)
        val resOnFail = repo.resolvePlayableStream(animeId, 1, 1, "ep_701_1", preferredSourceId = "src_701").getOrThrow()
        assertEquals("https://cdn.example/live_token.m3u8?token=super_secret_token", resOnFail.directData.playbackUrl)
        assertFalse(PlaybackDiagnosticsLogger.recentEvents().any { it.type == PlaybackDiagnosticEvent.D1_CACHE_WRITE })

        // 13. D1 timeout (>4000ms) -> playback still succeeds for current session, ZERO false D1_CACHE_WRITE events
        PlaybackDiagnosticsLogger.clearForTests()
        apiMode.set(1)
        val resOnTimeout = repo.resolvePlayableStream(
            animeId, 1, 1, "ep_701_1", preferredSourceId = "src_701", forceResolveFresh = true
        ).getOrThrow()
        assertEquals("https://cdn.example/live_token.m3u8?token=super_secret_token", resOnTimeout.directData.playbackUrl)
        assertFalse(PlaybackDiagnosticsLogger.recentEvents().any { it.type == PlaybackDiagnosticEvent.D1_CACHE_WRITE })

        // Never leak playback URLs or tokens into diagnostics
        val diagText = PlaybackDiagnosticsLogger.recentEvents().joinToString("\n") { "${it.scopeKey} ${it.detail}" }
        assertFalse(diagText.contains("super_secret_token"))
        assertFalse(diagText.contains("cdn.example"))
    }

    @Test
    fun t14_remoteDeletionFollowedBySameRecordReSave_sendsNewUpsertAndPersistsRecord() = runTest {
        val dbRows = ConcurrentHashMap<String, JSONObject>()
        val upsertCount = AtomicInteger(0)

        val api = startMockWorkerServer { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            when {
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    upsertCount.incrementAndGet()
                    val raw = exchange.requestBody.bufferedReader().readText()
                    val obj = JSONObject(raw)
                    dbRows[obj.getString("sourceDocKey")] = obj
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$obj}""")
                }
                method == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val id = path.removePrefix("/v1/tables/stream_sources/")
                    val row = dbRows[id]
                    if (row == null) sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                    else sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$row}""")
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
            }
        }

        val prefsDigestStore = SharedPreferencesD1DigestStore(context)
        val d1 = CloudflareD1DataSource(api = api, metricsTracker = metricsTracker, digestStore = prefsDigestStore)
        val key = CloudflareD1DataSource.deterministicRecordId("be_50", "ep_50_1", "src_50")

        // Step 1: Save record
        assertTrue(d1.saveStreamUrl("be_50", "ep_50_1", "src_50", "https://cdn.example/50.m3u8", "2099-01-01T00:00:00Z"))
        assertEquals(1, upsertCount.get())
        assertTrue(dbRows.containsKey(key))

        // Step 2: Delete that record remotely (without any Android GET/invalidate call!)
        dbRows.clear()
        assertFalse(dbRows.containsKey(key))

        // Step 3: Attempt to save the exact same record again while local in-memory & SharedPrefs digest are still present
        assertTrue(d1.saveStreamUrl("be_50", "ep_50_1", "src_50", "https://cdn.example/50.m3u8", "2099-01-01T00:00:00Z"))

        // Step 4 & 5: Confirm Android sent a new upsert request and D1 contains the record afterward
        assertEquals("Android must send a new upsert request after remote deletion", 2, upsertCount.get())
        assertTrue("D1 must contain the re-saved record", dbRows.containsKey(key))
        val reFetched = d1.getValidStreamUrl("be_50", "ep_50_1", "src_50")
        assertNotNull(reFetched)
        assertEquals("https://cdn.example/50.m3u8", reFetched?.streamUrl)
    }

    @Test
    fun t15_compositeIdentityCollisionPrevention() {
        val k1 = CloudflareD1DataSource.deterministicRecordId("anime_1", "ep_1", "src_1")
        val k2 = CloudflareD1DataSource.deterministicRecordId("anime_1", "ep_2", "src_1")
        val k3 = CloudflareD1DataSource.deterministicRecordId("anime_2", "ep_1", "src_1")
        val k4 = CloudflareD1DataSource.deterministicRecordId("anime_1", "ep_1", "src_2")
        assertEquals(4, setOf(k1, k2, k3, k4).size)
        assertNotEquals(k1, k2)

        val record = D1StreamSourceRecordDto(
            sourceDocKey = k1,
            scopeKey = "anime_1",
            providerId = "direct",
            sourceId = "src_1",
            sourceName = "src_1",
            backendEpisodeId = "ep_1",
            streamUrl = "https://cdn.example/a1_e1.m3u8",
            urlType = "hls",
            expiresAt = "2099-01-01T00:00:00Z",
            updatedAt = 1800000000000L
        )
        assertTrue(SourceIdentityValidator.validateD1StreamSourceRecord(record, "anime_1", "ep_1", "src_1", k1).isValid)

        val badAnime = SourceIdentityValidator.validateD1StreamSourceRecord(record, "anime_2", "ep_1", "src_1")
        assertTrue(badAnime is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.ANIME_ID_MISMATCH, (badAnime as SourceValidationResult.Invalid).code)

        val badEpisode = SourceIdentityValidator.validateD1StreamSourceRecord(record, "anime_1", "ep_2", "src_1")
        assertTrue(badEpisode is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.EPISODE_ID_MISMATCH, (badEpisode as SourceValidationResult.Invalid).code)

        val badSource = SourceIdentityValidator.validateD1StreamSourceRecord(record, "anime_1", "ep_1", "src_2")
        assertTrue(badSource is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.PROVIDER_ID_MISMATCH, (badSource as SourceValidationResult.Invalid).code)

        val softDeleted = SourceIdentityValidator.validateD1StreamSourceRecord(
            record.copy(deletedAt = 1800000000001L), "anime_1", "ep_1", "src_1"
        )
        assertTrue(softDeleted is SourceValidationResult.Invalid)
    }

    @Test
    fun t16_t17_cacheMissResolvesViaBackendAndSavesToD1_thenCacheHitSkipsBackend() = runTest {
        val dbRows = ConcurrentHashMap<String, JSONObject>()
        val d1Gets = AtomicInteger(0)
        val d1Upserts = AtomicInteger(0)
        val backendDirectCalls = AtomicInteger(0)

        val d1Api = startMockWorkerServer { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            when {
                method == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    d1Gets.incrementAndGet()
                    val id = path.removePrefix("/v1/tables/stream_sources/")
                    val row = dbRows[id]
                    if (row == null) sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                    else sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$row}""")
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    d1Upserts.incrementAndGet()
                    val raw = exchange.requestBody.bufferedReader().readText()
                    val obj = JSONObject(raw)
                    dbRows[obj.getString("sourceDocKey")] = obj
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$obj}""")
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
            }
        }

        val backendApi = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_888", title = "Flow Show"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_ep_888_1", season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(ProphecySourceDto(id = "src_888", label = "Pahe 1080p"))
                    )
                )
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                backendDirectCalls.incrementAndGet()
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = request.sourceId,
                            playbackUrl = "https://cdn.example/888.m3u8",
                            expiresAt = "2099-01-01T00:00:00Z"
                        )
                    )
                )
            }
        }

        val animeId = "anilist_888"
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 888, backendAnimeId = "be_888", preferredTitle = "Flow Show", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_888_1", animeId, 1, 1, sourceEpisodeIds = "1:be_ep_888_1")))

        val memCache = MemoryCacheManager(metricsTracker = metricsTracker)
        val repo = createRepo(backendApi, CloudflareD1DataSource(d1Api, metricsTracker), memCache)

        // 17. Cache miss -> Backend direct resolution -> D1 save
        val first = repo.resolvePlayableStream(animeId, 1, 1, "ep_888_1", preferredSourceId = "src_888").getOrThrow()
        assertEquals("BACKEND_RESOLVE", first.cacheTier)
        assertEquals(1, backendDirectCalls.get())
        assertEquals(1, d1Gets.get())
        assertEquals(1, d1Upserts.get())
        assertTrue(dbRows.isNotEmpty())

        // 16. Clear memory cache -> D1 hit WITHOUT any Backend resolution call
        memCache.invalidatePlayback("src_888", "anime:888:s1:e1")
        val second = repo.resolvePlayableStream(animeId, 1, 1, "ep_888_1", preferredSourceId = "src_888").getOrThrow()
        assertEquals("D1", second.cacheTier)
        assertEquals("https://cdn.example/888.m3u8", second.directData.playbackUrl)
        assertEquals("D1 cache hit must not call Backend direct-play", 1, backendDirectCalls.get())
    }
}
