package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1AnimeEntryDto
import com.example.data.d1.D1BooleanAdapterFactory
import com.example.data.d1.D1EpisodeEntryDto
import com.example.data.d1.D1StreamSourceDto
import com.example.data.d1.toD1MetadataDto
import com.example.data.local.AnimeAlias
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
import com.example.data.local.StreamSourceEntity
import com.example.data.metrics.DatabaseMetricsTracker
import com.example.data.remote.AniListCoverImage
import com.example.data.remote.AniListData
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLResponse
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.AniListMedia
import com.example.data.remote.AniListTitle
import com.example.data.remote.DirectPlayData
import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.DirectPlayResponse
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.repository.EpisodeCatalogState
import com.example.data.streaming.FirestoreStreamingRepository
import com.example.data.streaming.StreamRecordStatus
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
import java.net.URLDecoder
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

    private fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null
                else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
            .toMap()
    }

    @Test
    fun d1BooleanAdapter_andSourceCredentialStripping_workExactly() {
        val animeAdapter = moshi.adapter(D1AnimeEntryDto::class.java)
        val rawSqlJson = """
            {
              "id": "anilist_21",
              "aniListId": 21,
              "preferredTitle": "One Piece",
              "genres": "Action, Adventure",
              "isPlayable": 1,
              "isTrending": 0,
              "isSeasonal": "1",
              "isPopular": true,
              "mediaCategory": "ANIME",
              "metadataCompleteness": "COMPLETE",
              "updatedAt": 1700000000000
            }
        """.trimIndent()
        val parsedAnime = animeAdapter.fromJson(rawSqlJson)
        assertNotNull(parsedAnime)
        assertTrue(parsedAnime!!.isPlayable)
        assertFalse(parsedAnime.isTrending)
        assertTrue(parsedAnime.isSeasonal)
        assertTrue(parsedAnime.isPopular)

        val serializedJson = animeAdapter.toJson(parsedAnime)
        assertTrue(serializedJson.contains(""""isPlayable":1"""))
        assertTrue(serializedJson.contains(""""isTrending":0"""))

        val srcAdapter = moshi.adapter(D1StreamSourceDto::class.java)
        val parsedSourceWithNullBool = srcAdapter.fromJson(
            """
            {
              "sourceDocKey": "anime:21:s1:e1|p|pahe|s|s1",
              "scopeKey": "anime:21:s1:e1",
              "providerId": "pahe",
              "sourceId": "s1",
              "sourceName": "Pahe 1080p",
              "downloadable": 1,
              "streamUrl": "https://secret.cdn.invalid/signed.m3u8?token=SECRET",
              "safeHeadersJson": "{\"Authorization\":\"Bearer SECRET\"}",
              "expiresAt": "2099-01-01T00:00:00Z",
              "status": "AVAILABLE",
              "updatedAt": 1700000000000
            }
            """.trimIndent()
        )!!
        assertEquals(true, parsedSourceWithNullBool.downloadable)

        // D1-provided temporary URLs must NOT be trusted directly as AVAILABLE local URLs
        val roomEntityFromD1 = parsedSourceWithNullBool.toRoomEntity(existingLocal = null)
        assertNull(roomEntityFromD1.streamUrl)
        assertNull(roomEntityFromD1.safeHeadersJson)
        assertEquals(StreamRecordStatus.MISSING_URL.name, roomEntityFromD1.status)

        // Writing source metadata to D1 must strip temporary signed URL, headers, and volatile expiry
        val localSource = StreamSourceEntity(
            sourceDocKey = "anime:21:s1:e1|p|pahe|s|s1",
            scopeKey = "anime:21:s1:e1",
            providerId = "pahe",
            sourceId = "s1",
            sourceName = "Pahe 1080p",
            backendEpisodeId = "ep-1",
            streamUrl = "https://signed.cdn.example/master.m3u8?sig=abc",
            urlType = "hls",
            contentType = "application/x-mpegURL",
            quality = "1080p",
            codec = "EAC3",
            audioLanguage = "ja",
            downloadable = true,
            sizeBytes = 123456L,
            safeHeadersJson = """{"Referer":"https://private.example/"}""",
            expiresAt = "2099-01-01T00:00:00Z",
            status = StreamRecordStatus.AVAILABLE.name,
            updatedAt = 1700000000000L
        )
        val strippedDto = localSource.toD1MetadataDto()
        assertNull(strippedDto.streamUrl)
        assertNull(strippedDto.safeHeadersJson)
        assertNull(strippedDto.expiresAt)
        assertEquals("1080p", strippedDto.quality)
        assertEquals("EAC3", strippedDto.codec)
        assertEquals(StreamRecordStatus.AVAILABLE.name, strippedDto.status)
    }

    @Test
    fun d1DataSource_pagination_retry_404_coalescing_aliasDedup_andDiffWriteSkip() = runTest {
        val requests = CopyOnWriteArrayList<String>()
        val animeUpserts = AtomicInteger(0)
        val aliasPosts = AtomicInteger(0)
        val homeGetAttempts = AtomicInteger(0)
        val storedAliases = CopyOnWriteArrayList<String>()

        val api = startMockD1Server { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            val query = queryParams(exchange.requestURI.rawQuery)
            requests.add("$method $path ${exchange.requestURI.rawQuery.orEmpty()}")

            when {
                method == "GET" && path == "/v1/tables" -> {
                    sendJson(exchange, 200, """{"success":true,"count":10,"tables":["anime_entries","anime_aliases"]}""")
                }
                method == "GET" && path == "/v1/tables/anime_entries" && query["where"]?.contains("isTrending") == true -> {
                    val attempt = homeGetAttempts.incrementAndGet()
                    when (attempt) {
                        1 -> sendJson(exchange, 429, """{"success":false,"error":"Rate limited"}""")
                        2 -> sendJson(exchange, 503, """{"success":false,"error":"Transient"}""")
                        else -> sendJson(
                            exchange,
                            200,
                            """
                            {
                              "success":true,
                              "table":"anime_entries",
                              "total":1,
                              "limit":25,
                              "offset":0,
                              "count":1,
                              "data":[
                                {"id":"anilist_101","aniListId":101,"preferredTitle":"Solo Leveling","genres":"Action","isPlayable":1,"isTrending":1,"isSeasonal":0,"isPopular":1,"mediaCategory":"ANIME","metadataCompleteness":"COMPLETE","updatedAt":1700000000000}
                              ]
                            }
                            """.trimIndent()
                        )
                    }
                }
                method == "GET" && path == "/v1/tables/anime_entries/missing_id" -> {
                    sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                }
                method == "GET" && path == "/v1/tables/anime_entries/bad_id" -> {
                    sendJson(exchange, 400, """{"success":false,"error":"Invalid column"}""")
                }
                method == "POST" && path == "/v1/tables/anime_entries/upsert" -> {
                    animeUpserts.incrementAndGet()
                    sendJson(exchange, 200, """{"success":true,"message":"Upserted"}""")
                }
                method == "GET" && path == "/v1/tables/anime_aliases" -> {
                    sendJson(
                        exchange,
                        200,
                        """
                        {
                          "success":true,
                          "table":"anime_aliases",
                          "count":1,
                          "data":[{"aliasId":1,"animeId":"anilist_101","alias":"solo leveling","originalAlias":"Solo Leveling"}]
                        }
                        """.trimIndent()
                    )
                }
                method == "POST" && path == "/v1/tables/anime_aliases" -> {
                    aliasPosts.incrementAndGet()
                    val body = exchange.requestBody.bufferedReader().readText()
                    storedAliases.add(body)
                    sendJson(exchange, 200, """{"success":true,"message":"Created"}""")
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Unhandled"}""")
            }
        }

        val d1 = CloudflareD1DataSource(api, metricsTracker)
        assertTrue(d1.verifyHealth())

        // Retry 429 + 503 -> 200 and coalesce concurrent calls
        val homeResults = listOf(
            async { d1.fetchHomeSection("isTrending", limit = 25, offset = 0) },
            async { d1.fetchHomeSection("isTrending", limit = 25, offset = 0) },
            async { d1.fetchHomeSection("isTrending", limit = 25, offset = 0) }
        ).awaitAll()
        assertEquals(3, homeResults.size)
        homeResults.forEach { list ->
            assertEquals(1, list.size)
            assertEquals("Solo Leveling", list.first().preferredTitle)
        }
        // Only 1 logical flight executed (3 attempts for retry) while 2 duplicates were prevented
        assertEquals(3, homeGetAttempts.get())
        assertTrue(metricsTracker.snapshot().duplicateRequestsPrevented >= 2L)

        // 404 and 400 do not crash or retry endlessly
        assertNull(d1.fetchAnimeById("missing_id"))
        assertNull(d1.fetchAnimeById("bad_id"))

        // Diff-checked write: already remembered from fetchHomeSection -> skipped!
        val sameEntry = AnimeEntry(
            id = "anilist_101",
            aniListId = 101,
            preferredTitle = "Solo Leveling",
            genres = "Action",
            isPlayable = true,
            isTrending = true,
            isSeasonal = false,
            isPopular = true,
            mediaCategory = "ANIME",
            metadataCompleteness = "COMPLETE",
            updatedAt = 1700000000000L
        )
        assertTrue(d1.upsertAnimeIfChanged(sameEntry))
        assertEquals(0, animeUpserts.get())
        assertTrue(metricsTracker.snapshot().d1WriteSkipsUnchanged >= 1L)

        // Changed field triggers real upsert, then second identical call is skipped
        assertTrue(d1.upsertAnimeIfChanged(sameEntry.copy(episodeCount = 12)))
        assertEquals(1, animeUpserts.get())
        assertTrue(d1.upsertAnimeIfChanged(sameEntry.copy(episodeCount = 12)))
        assertEquals(1, animeUpserts.get())

        // Alias deduplication skips existing alias and only inserts missing one, then skips repeat
        val aliases = listOf(
            AnimeAlias(animeId = "anilist_101", alias = "solo leveling", originalAlias = "Solo Leveling"),
            AnimeAlias(animeId = "anilist_101", alias = "ore dake level up na ken", originalAlias = "Ore dake Level Up na Ken")
        )
        assertEquals(1, d1.insertMissingAliases("anilist_101", aliases))
        assertEquals(1, aliasPosts.get())
        assertEquals(0, d1.insertMissingAliases("anilist_101", aliases))
        assertEquals(1, aliasPosts.get())
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

        // Separate query keys hit independently
        assertEquals("Anime 1", cache.getSearchPage("frieren", 1)?.results?.single()?.preferredTitle)
        assertEquals("Anime 2", cache.getSearchPage("bleach", 1)?.results?.single()?.preferredTitle)
        assertNull(cache.getSearchPage("frieren", 2))

        // TTL expiry
        fakeNow += 6_000L
        assertNull(cache.getSearchPage("frieren", 1))

        // Valid playback URL capped cleanly and invalid/expired rejected
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

        fakeNow += 16_000L // past playbackUrlMaxTtlMs (15_000L)
        assertNull(cache.getResolvedPlayback("src_live"))

        val expiredPlay = validPlay.copy(sourceId = "src_old", expiresAt = "2020-01-01T00:00:00Z")
        cache.putResolvedPlayback("src_old", expiredPlay)
        assertNull(cache.getResolvedPlayback("src_old"))

        // Explicit invalidation
        cache.putAnime(anime1)
        assertNotNull(cache.getAnime("a1"))
        cache.invalidateAnime("a1")
        assertNull(cache.getAnime("a1"))
    }

    @Test
    fun repositoryEndToEnd_d1Primary_lazyPaging_directPlay_andOfflineFallback() = runTest {
        val d1AnimeGets = AtomicInteger(0)
        val d1EpisodeQueries = CopyOnWriteArrayList<Int>()
        val d1StreamSourceWrites = AtomicInteger(0)
        val aniListCalls = AtomicInteger(0)
        val directPlayCalls = AtomicInteger(0)
        val now = System.currentTimeMillis()
        val simulateD1Down = AtomicBoolean(false)

        val d1Api = startMockD1Server { exchange ->
            if (simulateD1Down.get()) {
                sendJson(exchange, 503, """{"success":false,"error":"D1 unavailable"}""")
                return@startMockD1Server
            }
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            val query = queryParams(exchange.requestURI.rawQuery)
            when {
                method == "GET" && path == "/v1/tables/anime_entries" && query["where"]?.contains("isTrending") == true -> {
                    sendJson(
                        exchange, 200,
                        """{"success":true,"data":[{"id":"anilist_21","aniListId":21,"backendAnimeId":"op-1","preferredTitle":"One Piece","genres":"Action","isPlayable":1,"isTrending":1,"isSeasonal":0,"isPopular":1,"mediaCategory":"ANIME","metadataCompleteness":"COMPLETE","updatedAt":$now}]}"""
                    )
                }
                method == "GET" && path == "/v1/tables/anime_entries" && query["where"]?.contains("isSeasonal") == true -> {
                    sendJson(exchange, 200, """{"success":true,"data":[]}""")
                }
                method == "GET" && path == "/v1/tables/anime_entries" && query["where"]?.contains("isPopular") == true -> {
                    sendJson(exchange, 200, """{"success":true,"data":[]}""")
                }
                method == "GET" && path == "/v1/tables/anime_entries" && query["where"]?.contains("MOVIE") == true -> {
                    sendJson(exchange, 200, """{"success":true,"data":[]}""")
                }
                method == "GET" && path == "/v1/tables/anime_entries/anilist_500" -> {
                    d1AnimeGets.incrementAndGet()
                    sendJson(
                        exchange, 200,
                        """{"success":true,"data":{"id":"anilist_500","aniListId":500,"backendAnimeId":"be-500","preferredTitle":"Frieren","genres":"Fantasy","episodeCount":65,"isPlayable":1,"isTrending":0,"isSeasonal":0,"isPopular":1,"mediaCategory":"ANIME","metadataCompleteness":"COMPLETE","updatedAt":$now}}"""
                    )
                }
                method == "GET" && path == "/v1/tables/season_entries" -> {
                    sendJson(
                        exchange, 200,
                        """{"success":true,"data":[{"id":"anilist_500_s1","animeId":"anilist_500","seasonNumber":1},{"id":"anilist_500_s2","animeId":"anilist_500","seasonNumber":2}]}"""
                    )
                }
                method == "GET" && path == "/v1/tables/episode_entries" -> {
                    val offset = query["offset"]?.toIntOrNull() ?: 0
                    val limit = query["limit"]?.toIntOrNull() ?: 30
                    d1EpisodeQueries.add(offset)
                    val epJson = if (offset == 0) {
                        """[{"id":"ep_s1_1","animeId":"anilist_500","seasonNumber":1,"episodeNumber":1,"title":"The Journey's End","isAvailable":1}]"""
                    } else {
                        """[{"id":"ep_s1_31","animeId":"anilist_500","seasonNumber":1,"episodeNumber":31,"title":"Episode 31","isAvailable":1}]"""
                    }
                    sendJson(exchange, 200, """{"success":true,"limit":$limit,"offset":$offset,"data":$epJson}""")
                }
                method == "GET" && path.startsWith("/v1/tables/stream_containers/") -> {
                    sendJson(exchange, 200, """{"success":true,"data":{"scopeKey":"anime:500","contentKey":"500","anilistId":500,"contentType":"ANIME","backendId":"be-500","discoveryStatus":"COMPLETED","version":1,"updatedAt":$now}}""")
                }
                method == "GET" && path == "/v1/tables/stream_seasons" -> {
                    sendJson(exchange, 200, """{"success":true,"data":[{"seasonDocKey":"500:s1","contentKey":"500","seasonId":"season_1","seasonNumber":1,"episodeCount":1,"discoveryStatus":"COMPLETED","version":1,"updatedAt":$now}]}""")
                }
                method == "GET" && path == "/v1/tables/stream_episodes" -> {
                    sendJson(exchange, 200, """{"success":true,"data":[{"episodeDocKey":"500:s1:e1","contentKey":"500","seasonId":"season_1","episodeId":"ep_s1_1","seasonNumber":1,"episodeNumber":1,"backendEpisodeId":"be-ep-1","providerCount":1,"sourceCount":1,"discoveryStatus":"COMPLETED","version":1,"updatedAt":$now}]}""")
                }
                method == "GET" && path == "/v1/tables/stream_providers" -> {
                    sendJson(exchange, 200, """{"success":true,"data":[{"providerDocKey":"anime:500:s1:e1|p|pahe","scopeKey":"anime:500:s1:e1","providerId":"pahe","providerName":"Pahe","status":"AVAILABLE","sourceCount":1,"version":1,"updatedAt":$now}]}""")
                }
                method == "GET" && path == "/v1/tables/stream_sources" -> {
                    sendJson(exchange, 200, """{"success":true,"data":[{"sourceDocKey":"anime:500:s1:e1|p|pahe|s|src_d1_1","scopeKey":"anime:500:s1:e1","providerId":"pahe","sourceId":"src_d1_1","sourceName":"Pahe 1080p","backendEpisodeId":"be-ep-1","urlType":"direct","quality":"1080p","codec":"HEVC","audioLanguage":"ja","downloadable":1,"status":"AVAILABLE","version":1,"updatedAt":$now}]}""")
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    d1StreamSourceWrites.incrementAndGet()
                    sendJson(exchange, 200, """{"success":true,"message":"Upserted"}""")
                }
                method == "POST" -> {
                    sendJson(exchange, 200, """{"success":true,"message":"Upserted"}""")
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
            }
        }

        val aniList = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> {
                aniListCalls.incrementAndGet()
                return Response.success(
                    AniListGraphQLResponse(
                        AniListData(
                            media = AniListMedia(
                                id = 500,
                                title = AniListTitle(romaji = "Sousou no Frieren", english = "Frieren"),
                                format = "TV",
                                status = "FINISHED",
                                episodes = 28,
                                coverImage = AniListCoverImage(large = "https://img.example/cover.jpg")
                            )
                        )
                    )
                )
            }
        }

        val prophecyApi = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse(emptyList()))
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = animeId, title = "Frieren"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(emptyList<ProphecyEpisodeDto>())
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(ProphecyEpisodeFullResponse())
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                directPlayCalls.incrementAndGet()
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
        val streamingRepo = FirestoreStreamingRepository(
            streamCacheDao = db.streamCacheDao(),
            firestoreProvider = { null },
            isClientWriteAuthorized = { false },
            cloudStoreProvider = null,
            d1DataSource = d1,
            metricsTracker = metricsTracker
        )
        assertTrue(streamingRepo.usesD1Primary)

        val repo = AnimeRepository(
            database = db,
            prophecyApi = prophecyApi,
            aniListApi = aniList,
            applicationContext = context,
            streamingRepository = streamingRepo,
            d1DataSource = d1,
            memoryCache = memCache,
            metricsTracker = metricsTracker
        )
        assertFalse(repo.usesFirestoreStreamingRealtime)

        // 1. Details first load: hits D1 (1 anime + page 0 of episodes), ZERO AniList calls
        val firstDetails = repo.refreshAnimeDetails("anilist_500", forceRefresh = false).getOrThrow()
        assertEquals(EpisodeCatalogState.COMPLETE, firstDetails.state)
        assertEquals(1, d1AnimeGets.get())
        assertEquals(listOf(0), d1EpisodeQueries.toList())
        assertEquals(0, aniListCalls.get())

        // 2. Re-opening Details hits Memory/Room cache -> ZERO extra D1 reads!
        val secondDetails = repo.refreshAnimeDetails("anilist_500", forceRefresh = false).getOrThrow()
        assertEquals(EpisodeCatalogState.COMPLETE, secondDetails.state)
        assertEquals(1, d1AnimeGets.get())
        assertTrue(metricsTracker.snapshot().roomCacheHits >= 1L)

        // 3. Navigating to page 1 (offset 30) lazy-loads only that page from D1
        val page1 = repo.loadEpisodePageFromD1("anilist_500", seasonNumber = 1, offset = 30, limit = 30)
        assertEquals(1, page1.size)
        assertEquals(31, page1.first().episodeNumber)
        assertEquals(listOf(0, 30), d1EpisodeQueries.toList())

        // 4. Explicit forceRefresh bypasses cache and hits AniList
        repo.refreshAnimeDetails("anilist_500", forceRefresh = true).getOrThrow()
        assertEquals(1, aniListCalls.get())

        // 5. Stream discovery reads D1 shared metadata with zero Firestore reads;
        //    resolvePlayableStream invokes POST /api/v1/play/direct and caches only in memory
        val ep = EpisodeEntry(id = "ep_s1_1", animeId = "anilist_500", seasonNumber = 1, episodeNumber = 1, title = "Ep 1")
        val sourcesSnap = repo.discoverStreamsForScope("anilist_500", 1, 1, ep.id).getOrThrow()
        assertEquals(1, sourcesSnap.sources.size)
        assertEquals("src_d1_1", sourcesSnap.sources.first().id)

        val resolvedFirst = repo.resolvePlayableStream(
            animeId = "anilist_500",
            seasonNumber = 1,
            episodeNumber = 1,
            catalogEpisodeId = ep.id,
            preferredSourceId = "src_d1_1"
        ).getOrThrow()
        assertEquals("https://signed.example/play/src_d1_1.mp4", resolvedFirst.directData.playbackUrl)
        assertEquals(1, directPlayCalls.get())
        assertEquals(0L, metricsTracker.snapshot().firestoreReads)
        assertEquals(0L, metricsTracker.snapshot().firestoreWrites)

        // Second resolve hits bounded memory playback cache without duplicate /api/v1/play/direct call
        val resolvedSecond = repo.resolvePlayableStream(
            animeId = "anilist_500",
            seasonNumber = 1,
            episodeNumber = 1,
            catalogEpisodeId = ep.id,
            preferredSourceId = "src_d1_1"
        ).getOrThrow()
        assertEquals(resolvedFirst.directData.playbackUrl, resolvedSecond.directData.playbackUrl)
        assertEquals(1, directPlayCalls.get())

        // 6. Home feed from D1 -> Room -> offline Room fallback when D1 fails
        assertTrue(repo.refreshHomeDiscovery(force = false).isSuccess)
        assertTrue(db.animeDao().getTrendingOnce().isNotEmpty())
        memCache.invalidateHome()
        simulateD1Down.set(true)
        val offlineRes = repo.refreshHomeDiscovery(force = false)
        assertTrue(offlineRes.isSuccess)
        assertTrue(db.animeDao().getTrendingOnce().isNotEmpty())
        assertTrue(metricsTracker.snapshot().overallCacheHitRate > 0.0)
    }
}
