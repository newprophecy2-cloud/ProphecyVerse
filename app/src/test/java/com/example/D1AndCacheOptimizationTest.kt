package com.example

import android.content.Context
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
import com.example.data.remote.ProphecySeasonDto
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.repository.EpisodeCatalogState
import com.example.data.repository.SourceIdentityValidator
import com.example.data.repository.SourceValidationResult
import com.google.firebase.FirebaseApp
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class D1AndCacheOptimizationTest {

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
        val retrofit = Retrofit.Builder()
            .baseUrl("http://127.0.0.1:$port/v1/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        return retrofit.create(CloudflareD1ApiService::class.java)
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    fun d1StreamSourceRecordDto_serialization_andIdentityExpiryValidation() {
        val adapter = moshi.adapter(D1StreamSourceRecordDto::class.java)
        val raw = """
            {
              "sourceDocKey": "rec_21",
              "scopeKey": "be_21",
              "providerId": "direct",
              "sourceId": "src_1080p",
              "sourceName": "src_1080p",
              "backendEpisodeId": "be_ep_1",
              "streamUrl": "https://cdn.example/master.m3u8",
              "urlType": "hls",
              "expiresAt": "2099-01-01T00:00:00Z",
              "status": "AVAILABLE",
              "version": 1,
              "updatedAt": 1800000000000,
              "deletedAt": null
            }
        """.trimIndent()
        val parsed = adapter.fromJson(raw)!!
        assertEquals("be_21", parsed.scopeKey)
        assertEquals("be_ep_1", parsed.backendEpisodeId)
        assertEquals("src_1080p", parsed.sourceId)
        assertEquals("https://cdn.example/master.m3u8", parsed.streamUrl)
        assertEquals("2099-01-01T00:00:00Z", parsed.expiresAt)

        assertTrue(
            SourceIdentityValidator.validateD1StreamSourceRecord(parsed, "be_21", "be_ep_1", "src_1080p").isValid
        )
        assertTrue(
            SourceIdentityValidator.validateD1StreamSourceRecord(
                parsed.copy(expiresAt = null), "be_21", "be_ep_1", "src_1080p"
            ).isValid
        )
        assertTrue(
            SourceIdentityValidator.validateD1StreamSourceRecord(
                parsed.copy(expiresAt = "2020-01-01T00:00:00Z"), "be_21", "be_ep_1", "src_1080p"
            ) is SourceValidationResult.Invalid
        )
        assertTrue(
            SourceIdentityValidator.validateD1StreamSourceRecord(
                parsed.copy(streamUrl = "ftp://bad-scheme.example/video.mp4"), "be_21", "be_ep_1", "src_1080p"
            ) is SourceValidationResult.Invalid
        )
        assertTrue(
            SourceIdentityValidator.validateD1StreamSourceRecord(
                parsed, "be_other", "be_ep_1", "src_1080p"
            ) is SourceValidationResult.Invalid
        )
    }

    @Test
    fun d1DataSource_streamUrlRetry429And503_coalescing_404_optionalExpiry_andDiffWriteSkip() = runTest {
        val getAttempts = AtomicInteger(0)
        val upsertPosts = AtomicInteger(0)
        val capturedPosts = CopyOnWriteArrayList<String>()
        val recId = CloudflareD1DataSource.deterministicRecordId("be_101", "ep_101_1", "src_1")
        val missingRecId = CloudflareD1DataSource.deterministicRecordId("be_404", "ep_404_1", "src_missing")
        val badRecId = CloudflareD1DataSource.deterministicRecordId("be_400", "ep_400_1", "src_bad")

        val store = ConcurrentHashMap<String, String>()
        val initialRec = """{"sourceDocKey":"$recId","scopeKey":"be_101","providerId":"direct","sourceId":"src_1","sourceName":"src_1","backendEpisodeId":"ep_101_1","streamUrl":"https://cdn.example/101.m3u8","urlType":"hls","expiresAt":"2099-01-01T00:00:00Z","status":"AVAILABLE","version":1,"updatedAt":1800000000000,"deletedAt":null}"""

        val api = startMockD1Server { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            when {
                method == "GET" && path == "/v1/tables" -> {
                    sendJson(exchange, 200, """{"success":true,"count":1,"tables":["stream_sources"]}""")
                }
                method == "GET" && path == "/v1/tables/stream_sources" -> {
                    sendJson(exchange, 200, """{"success":true,"table":"stream_sources","total":${store.size},"limit":1,"offset":0,"count":0,"data":[]}""")
                }
                method == "GET" && path == "/v1/tables/stream_sources/$recId" -> {
                    val attempt = getAttempts.incrementAndGet()
                    when (attempt) {
                        1 -> sendJson(exchange, 429, """{"success":false,"error":"Rate limited"}""")
                        2 -> sendJson(exchange, 503, """{"success":false,"error":"Transient"}""")
                        else -> {
                            val current = store[recId] ?: initialRec
                            store.putIfAbsent(recId, initialRec)
                            sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$current}""")
                        }
                    }
                }
                method == "GET" && path == "/v1/tables/stream_sources/$missingRecId" -> {
                    sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                }
                method == "GET" && path == "/v1/tables/stream_sources/$badRecId" -> {
                    sendJson(exchange, 400, """{"success":false,"error":"Bad request"}""")
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    upsertPosts.incrementAndGet()
                    val body = exchange.requestBody.bufferedReader().readText()
                    capturedPosts.add(body)
                    store[recId] = body
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$body}""")
                }
                method == "DELETE" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val delId = path.removePrefix("/v1/tables/stream_sources/")
                    store.remove(delId)
                    sendJson(exchange, 200, """{"success":true,"message":"Record deleted","table":"stream_sources","changes":1}""")
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Unhandled"}""")
            }
        }

        val d1 = CloudflareD1DataSource(api, metricsTracker, SharedPreferencesD1DigestStore(context))
        assertTrue(d1.verifyHealth())

        // Retry 429 + 503 -> 200 and single-flight coalesce concurrent identical lookups
        val hits = listOf(
            async { d1.getValidStreamUrl("be_101", "ep_101_1", "src_1") },
            async { d1.getValidStreamUrl("be_101", "ep_101_1", "src_1") },
            async { d1.getValidStreamUrl("be_101", "ep_101_1", "src_1") }
        ).awaitAll()
        assertEquals(3, hits.size)
        hits.forEach { hit ->
            assertNotNull(hit)
            assertEquals("https://cdn.example/101.m3u8", hit!!.streamUrl)
        }
        assertEquals(3, getAttempts.get())
        assertTrue(metricsTracker.snapshot().duplicateRequestsPrevented >= 2L)

        // 404 and 400 degrade gracefully without infinite retries
        assertNull(d1.getValidStreamUrl("be_404", "ep_404_1", "src_missing"))
        assertNull(d1.getValidStreamUrl("be_400", "ep_400_1", "src_bad"))

        // Diff-checked write: unchanged record confirmed present remotely is skipped
        assertTrue(d1.saveStreamUrl("be_101", "ep_101_1", "src_1", "https://cdn.example/101.m3u8", "2099-01-01T00:00:00Z"))
        assertEquals(0, upsertPosts.get())
        assertTrue(metricsTracker.snapshot().d1WriteSkipsUnchanged >= 1L)

        // New URL without known expiry saves with explicit null expiresAt (never fabricated), and repeat write is skipped when remote confirms
        assertTrue(d1.saveStreamUrl("be_101", "ep_101_1", "src_1", "https://cdn.example/101_new.mp4", expiresAt = null))
        assertEquals(1, upsertPosts.get())
        assertTrue(capturedPosts.single().contains(""""expiresAt":null"""))
        assertTrue(d1.saveStreamUrl("be_101", "ep_101_1", "src_1", "https://cdn.example/101_new.mp4", expiresAt = null))
        assertEquals(1, upsertPosts.get())

        // Invalidate deletes from D1 cleanly
        assertTrue(d1.invalidateStreamUrl("be_101", "ep_101_1", "src_1"))
    }

    @Test
    fun memoryCacheManager_respectsTtl_lruEviction_playbackBounds_andInvalidation() {
        var fakeNow = System.currentTimeMillis() + 60_000L
        val cache = MemoryCacheManager(
            metricsTracker = metricsTracker,
            homeTtlMs = 10_000L,
            searchTtlMs = 5_000L,
            animeTtlMs = 20_000L,
            playbackUrlMaxTtlMs = 15_000L,
            clockMs = { fakeNow }
        )

        val anime1 = AnimeEntry(id = "a1", preferredTitle = "Anime 1")
        val anime2 = AnimeEntry(id = "a2", preferredTitle = "Anime 2")
        cache.putSearchPage("frieren", 1, MemoryCacheManager.SearchPageCacheValue(listOf(anime1), 1, false))
        cache.putSearchPage("bleach", 1, MemoryCacheManager.SearchPageCacheValue(listOf(anime2), 1, true))

        assertEquals("Anime 1", cache.getSearchPage("frieren", 1)?.results?.single()?.preferredTitle)
        assertEquals("Anime 2", cache.getSearchPage("bleach", 1)?.results?.single()?.preferredTitle)
        assertNull(cache.getSearchPage("frieren", 2))

        fakeNow += 6_000L
        assertNull(cache.getSearchPage("frieren", 1))

        val futureIso = java.time.Instant.ofEpochMilli(fakeNow + 60_000L).toString()
        val validPlay = DirectPlayData(
            mode = "direct",
            sourceId = "src_live",
            playbackUrl = "https://cdn.example/video.mp4",
            urlType = "direct",
            expiresAt = futureIso
        )
        cache.putResolvedPlayback("src_live", validPlay)
        assertEquals("src_live", cache.getResolvedPlayback("src_live")?.sourceId)

        fakeNow += 16_000L
        assertNull(cache.getResolvedPlayback("src_live"))

        val expiredPlay = validPlay.copy(sourceId = "src_old", expiresAt = "2020-01-01T00:00:00Z")
        cache.putResolvedPlayback("src_old", expiredPlay)
        assertNull(cache.getResolvedPlayback("src_old"))

        cache.putAnime(anime1)
        assertNotNull(cache.getAnime("a1"))
        cache.invalidateAnime("a1")
        assertNull(cache.getAnime("a1"))
    }

    @Test
    fun repositoryEndToEnd_roomMetadataOnly_d1StreamUrlCache_andDirectPlayWithExactIds() = runTest {
        val d1Store = ConcurrentHashMap<String, String>()
        val d1GetCalls = AtomicInteger(0)
        val d1PostCalls = AtomicInteger(0)
        val aniListCalls = AtomicInteger(0)
        val backendDetailCalls = AtomicInteger(0)
        val directPlayCalls = AtomicInteger(0)
        var capturedRequest: DirectPlayRequest? = null
        val simulateD1Down = AtomicBoolean(false)

        val d1Api = startMockD1Server { exchange ->
            if (simulateD1Down.get()) {
                sendJson(exchange, 503, """{"success":false,"error":"D1 unavailable"}""")
                return@startMockD1Server
            }
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            when {
                method == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    d1GetCalls.incrementAndGet()
                    val recId = path.removePrefix("/v1/tables/stream_sources/")
                    val raw = d1Store[recId]
                    if (raw == null) sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                    else sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$raw}""")
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    d1PostCalls.incrementAndGet()
                    val body = exchange.requestBody.bufferedReader().readText()
                    val recId = Regex(""""sourceDocKey"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
                    if (recId.isNotEmpty()) d1Store[recId] = body
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$body}""")
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
            }
        }

        val aniList = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> {
                aniListCalls.incrementAndGet()
                return Response.success(AniListGraphQLResponse(AniListData(null)))
            }
        }

        val prophecyApi = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse(emptyList()))
            override suspend fun getAnimeDetails(animeId: String): Response<ProphecyAnimeDetailsResponse> {
                backendDetailCalls.incrementAndGet()
                return Response.success(
                    ProphecyAnimeDetailsResponse(
                        id = "be-500",
                        title = "Frieren",
                        partial = false,
                        seasons = listOf(
                            ProphecySeasonDto(
                                number = 1,
                                episodes = (1..31).map { n ->
                                    ProphecyEpisodeDto(
                                        id = "be-ep-$n",
                                        number = n,
                                        season = 1,
                                        title = "Episode $n"
                                    )
                                }
                            )
                        )
                    )
                )
            }
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(emptyList<ProphecyEpisodeDto>())
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(
                            ProphecySourceDto(id = "src_d1_1", label = "Pahe 1080p", quality = "1080p")
                        )
                    )
                )
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                directPlayCalls.incrementAndGet()
                capturedRequest = request
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            mode = "direct",
                            sourceId = request.sourceId,
                            playbackUrl = "https://signed.example/play/${request.sourceId}.mp4",
                            urlType = "direct",
                            contentType = "video/mp4",
                            expiresAt = "2099-01-01T00:00:00Z"
                        )
                    )
                )
            }
        }

        val d1 = CloudflareD1DataSource(d1Api, metricsTracker)
        val memCache = MemoryCacheManager(metricsTracker = metricsTracker)

        val repo = AnimeRepository(
            database = db,
            prophecyApi = prophecyApi,
            aniListApi = aniList,
            applicationContext = context,
            d1DataSource = d1,
            memoryCache = memCache,
            metricsTracker = metricsTracker
        )

        db.animeDao().upsertAnime(
            AnimeEntry(id = "anilist_500", aniListId = 500, backendAnimeId = "be-500", preferredTitle = "Frieren", format = "TV", isPlayable = true)
        )

        // 1. Details first load hits Backend (never D1 metadata or AniList) and stores metadata in Room
        val firstDetails = repo.refreshAnimeDetails("anilist_500", forceRefresh = false).getOrThrow()
        assertEquals(EpisodeCatalogState.COMPLETE, firstDetails.state)
        assertEquals(1, backendDetailCalls.get())
        assertEquals(0, aniListCalls.get())

        // 2. Room episode paging loads page 1 (offset 30) cleanly without D1 catalog queries
        val page1 = repo.loadEpisodePage("anilist_500", seasonNumber = 1, offset = 30, limit = 30)
        assertEquals(1, page1.size)
        assertEquals(31, page1.first().episodeNumber)

        // 3. First resolve checks D1 (miss), calls POST /api/v1/play/direct with (anime_id, episode_id, source_id), and saves to D1
        val ep = db.episodeDao().getEpisodesForAnime("anilist_500").first { it.episodeNumber == 1 }
        val resolvedFirst = repo.resolvePlayableStream(
            animeId = "anilist_500",
            seasonNumber = 1,
            episodeNumber = 1,
            catalogEpisodeId = ep.id,
            preferredSourceId = "src_d1_1"
        ).getOrThrow()
        assertEquals("BACKEND_RESOLVE", resolvedFirst.cacheTier)
        assertEquals("https://signed.example/play/src_d1_1.mp4", resolvedFirst.directData.playbackUrl)
        assertEquals(1, directPlayCalls.get())
        assertEquals("be-500", capturedRequest?.animeId)
        assertEquals("be-ep-1", capturedRequest?.episodeId)
        assertEquals("src_d1_1", capturedRequest?.sourceId)

        val saveDeadline = System.currentTimeMillis() + 2000L
        while (d1Store.isEmpty() && System.currentTimeMillis() < saveDeadline) Thread.sleep(20L)
        assertTrue("D1 stream_sources must contain newly resolved URL", d1Store.isNotEmpty())

        // 4. Clearing Memory -> next resolve hits D1 with ZERO additional Backend direct-play calls!
        memCache.invalidatePlayback("src_d1_1", "anime:500:s1:e1")
        val resolvedFromD1 = repo.resolvePlayableStream(
            animeId = "anilist_500",
            seasonNumber = 1,
            episodeNumber = 1,
            catalogEpisodeId = ep.id,
            preferredSourceId = "src_d1_1"
        ).getOrThrow()
        assertEquals("D1", resolvedFromD1.cacheTier)
        assertEquals("https://signed.example/play/src_d1_1.mp4", resolvedFromD1.directData.playbackUrl)
        assertEquals(1, directPlayCalls.get())

        // 5. If D1 is unreachable and Memory misses, Backend fallback resolves cleanly without crashing
        memCache.invalidatePlayback("src_d1_1", "anime:500:s1:e1")
        simulateD1Down.set(true)
        val fallbackResolved = repo.resolvePlayableStream(
            animeId = "anilist_500",
            seasonNumber = 1,
            episodeNumber = 1,
            catalogEpisodeId = ep.id,
            preferredSourceId = "src_d1_1"
        ).getOrThrow()
        assertEquals("BACKEND_RESOLVE", fallbackResolved.cacheTier)
        assertEquals(2, directPlayCalls.get())
    }
}
