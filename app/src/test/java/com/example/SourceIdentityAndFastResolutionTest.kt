package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.d1.CloudflareD1ApiService
import com.example.data.d1.CloudflareD1DataSource
import com.example.data.d1.D1BooleanAdapterFactory
import com.example.data.d1.D1StreamSourceRecordDto
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
import com.example.data.local.SeasonEntry
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
import com.example.data.remote.ProphecyProviderMappingDto
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecySearchResultItem
import com.example.data.remote.ProphecySeasonDto
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.repository.EpisodeResolutionCoordinator
import com.example.data.repository.SourceDiagnosticCode
import com.example.data.repository.SourceIdentityValidator
import com.example.data.repository.SourceResolutionDiagnosticException
import com.example.data.repository.SourceValidationResult
import com.example.data.repository.resolveVerifiedPlaybackForEpisode
import com.example.data.streaming.MonotonicMetrics
import com.example.player.PreResolveQueueManager
import com.example.ui.viewmodels.PlayerViewModel
import com.google.firebase.FirebaseApp
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceIdentityAndFastResolutionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var metricsTracker: DatabaseMetricsTracker
    private lateinit var memoryCache: MemoryCacheManager
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
        memoryCache = MemoryCacheManager(metricsTracker = metricsTracker)
        moshi = Moshi.Builder()
            .add(D1BooleanAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
        SourceIdentityValidator.clearDiagnosticsForTests()
        MonotonicMetrics.clear()
        while (EpisodeResolutionCoordinator.isForegroundResolutionActive()) {
            EpisodeResolutionCoordinator.endForegroundResolution()
        }
    }

    @After
    fun tearDown() {
        mockServer?.stop(0)
        mockServer = null
        db.close()
        setRepositorySingleton(null)
    }

    private fun setRepositorySingleton(instance: AnimeRepository?) {
        val field = AnimeRepository::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, instance)
    }

    private fun noOpAniList(): AniListGraphQLService = object : AniListGraphQLService {
        override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
            Response.success(AniListGraphQLResponse(AniListData(null)))
    }

    private fun createRepo(
        api: ProphecyVerseApiService,
        database: AppDatabase = db,
        d1: CloudflareD1DataSource = createTestD1DataSource(metricsTracker = metricsTracker)
    ): AnimeRepository {
        return AnimeRepository(
            database = database,
            prophecyApi = api,
            aniListApi = noOpAniList(),
            applicationContext = context,
            d1DataSource = d1,
            memoryCache = memoryCache,
            metricsTracker = metricsTracker
        )
    }

    private fun startMockD1Server(handler: (HttpExchange) -> Unit): CloudflareD1DataSource {
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
        val api = retrofit.create(CloudflareD1ApiService::class.java)
        return CloudflareD1DataSource(api, metricsTracker)
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // Scenario 1: Exact match playback ("The Eminence in Shadow" S1E1 -> zero mismatch error)
    // Scenario 4: Provider-specific source ID distinct from canonical episode ID
    @Test
    fun scenario1And4_exactMatchAndProviderSpecificSourceId_succeedWithoutMismatch() = runTest {
        val animeId = "anilist_130298"
        val canonicalEpId = "ep_eminence_s1_1"
        val providerSpecificSourceId = "prov_src_mega_9981_1080p"

        db.animeDao().upsertAnime(
            AnimeEntry(
                id = animeId,
                aniListId = 130298,
                backendAnimeId = "be_eminence_shadow",
                titleEnglish = "The Eminence in Shadow",
                preferredTitle = "The Eminence in Shadow",
                format = "TV",
                mediaCategory = "ANIME",
                episodeCount = 20,
                isPlayable = true
            )
        )
        val ep1 = EpisodeEntry(
            id = canonicalEpId,
            animeId = animeId,
            seasonNumber = 1,
            episodeNumber = 1,
            title = "The Hated Classmate"
        )
        db.episodeDao().upsertEpisodes(listOf(ep1))

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())

            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(
                    ProphecyAnimeDetailsResponse(
                        id = "be_eminence_shadow",
                        canonicalId = "anilist_130298",
                        title = "The Eminence in Shadow",
                        mediaType = "anime",
                        seasons = listOf(
                            ProphecySeasonDto(
                                number = 1,
                                episodes = listOf(
                                    ProphecyEpisodeDto(id = "be_ep_ei_1", number = 1, season = 1, title = "Episode 1")
                                )
                            )
                        )
                    )
                )

            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_ep_ei_1", number = 1, season = 1)))

            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        anime = ProphecyAnimeDetailsResponse(
                            id = "be_eminence_shadow",
                            canonicalId = "anilist_130298",
                            title = "The Eminence in Shadow",
                            mediaType = "anime"
                        ),
                        selectedEpisode = ProphecyEpisodeDto(
                            id = "be_ep_ei_1",
                            canonicalId = canonicalEpId,
                            number = 1,
                            season = 1
                        ),
                        sources = listOf(
                            ProphecySourceDto(
                                id = "  $providerSpecificSourceId  ",
                                label = "MegaCloud",
                                quality = "1080p",
                                codec = "H264",
                                language = "Japanese",
                                downloadable = true,
                                expiresAt = "2099-01-01T00:00:00Z"
                            )
                        ),
                        sourcesStatus = "available",
                        sourcesPartial = false
                    )
                )

            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                assertEquals(providerSpecificSourceId, request.sourceId)
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = " $providerSpecificSourceId ",
                            playbackUrl = "https://cdn.example/eminence/s1e1.m3u8",
                            urlType = "hls",
                            contentType = "application/vnd.apple.mpegurl",
                            expiresAt = "2099-01-01T00:00:00Z"
                        )
                    )
                )
            }
        }

        val repo = createRepo(api)
        val verified = repo.resolveVerifiedPlaybackForEpisode(animeId, ep1).getOrThrow()

        assertEquals(providerSpecificSourceId, verified.source.id)
        assertEquals(providerSpecificSourceId, verified.directData.sourceId)
        assertEquals("https://cdn.example/eminence/s1e1.m3u8", verified.directData.playbackUrl)
        assertTrue(SourceIdentityValidator.recentDiagnostics().isEmpty())
    }

    // Scenario 2: Sequel / season mapping (Season 2 mapped episode IDs & canonical anime ID)
    @Test
    fun scenario2_sequelAndSeason2MappedEpisodeIds_validateAndResolveCorrectly() = runTest {
        val canonicalId = "anilist_97940"
        val sequelId = "anilist_195604"
        db.animeDao().upsertAnime(
            AnimeEntry(
                id = canonicalId,
                aniListId = 97940,
                backendAnimeId = "be_bc_unified",
                preferredTitle = "Black Clover",
                format = "TV",
                mediaCategory = "ANIME",
                isPlayable = true
            )
        )
        db.animeDao().upsertAnime(
            AnimeEntry(
                id = sequelId,
                aniListId = 195604,
                backendAnimeId = "be_bc_unified",
                preferredTitle = "Black Clover Season 2",
                canonicalAnimeId = 97940,
                canonicalTitle = "Black Clover",
                canonicalSeasonNumber = 2,
                format = "TV",
                mediaCategory = "ANIME",
                isPlayable = true
            )
        )
        val s2Ep1 = EpisodeEntry(
            id = "ep_bc_s2_e1",
            animeId = sequelId,
            seasonNumber = 2,
            episodeNumber = 1,
            title = "Return"
        )
        db.episodeDao().upsertSeasons(listOf(SeasonEntry("${sequelId}_s2", sequelId, 2, "Season 2")))
        db.episodeDao().upsertEpisodes(listOf(s2Ep1))

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())

            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(
                    ProphecyAnimeDetailsResponse(
                        id = "be_bc_unified",
                        canonicalId = canonicalId,
                        title = "Black Clover",
                        mediaType = "anime",
                        seasons = listOf(
                            ProphecySeasonDto(
                                number = 2,
                                episodes = listOf(
                                    ProphecyEpisodeDto(
                                        id = "be_bc_s2_ep1",
                                        canonicalId = "ep_bc_s2_e1",
                                        providerMappings = listOf(ProphecyProviderMappingDto(id = "prov_bc_201")),
                                        season = 2,
                                        number = 1
                                    )
                                )
                            )
                        )
                    )
                )

            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_bc_s2_ep1", season = 2, number = 1)))

            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        anime = ProphecyAnimeDetailsResponse(id = "be_bc_unified", title = "Black Clover", mediaType = "anime"),
                        selectedEpisode = ProphecyEpisodeDto(
                            id = episodeId,
                            providerMappings = listOf(ProphecyProviderMappingDto(value = "prov_bc_201")),
                            season = 2,
                            number = 1
                        ),
                        sources = listOf(ProphecySourceDto(id = "src_bc_s2_1", label = "VidStream", quality = "1080p"))
                    )
                )

            override suspend fun resolveDirectPlay(request: DirectPlayRequest) =
                Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = request.sourceId,
                            playbackUrl = "https://cdn.example/bc_s2_e1.mp4",
                            urlType = "direct"
                        )
                    )
                )
        }

        val repo = createRepo(api)
        val res = repo.resolveVerifiedPlaybackForEpisode(sequelId, s2Ep1).getOrThrow()
        assertEquals("src_bc_s2_1", res.source.id)
        assertEquals("https://cdn.example/bc_s2_e1.mp4", res.directData.playbackUrl)
    }

    // Scenario 3: Movie flat S1E1 compatibility
    @Test
    fun scenario3_movieFlatS1E1Compatibility_acceptsS1E1AndRejectsInvalidSeason() = runTest {
        val movieId = "anilist_204"
        db.animeDao().upsertAnime(
            AnimeEntry(
                id = movieId,
                aniListId = 204,
                backendAnimeId = "be_movie_204",
                preferredTitle = "Your Name",
                format = "MOVIE",
                mediaCategory = "MOVIE",
                isPlayable = true
            )
        )

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())

            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(
                    ProphecyAnimeDetailsResponse(
                        id = "be_movie_204",
                        title = "Your Name",
                        mediaType = "movie",
                        seasons = listOf(
                            ProphecySeasonDto(
                                number = 1,
                                episodes = listOf(ProphecyEpisodeDto(id = "be_movie_s1e1", season = 1, number = 1, title = "S1 & E1"))
                            )
                        )
                    )
                )

            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_movie_s1e1", season = 1, number = 1, title = "S1 & E1")))

            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        anime = ProphecyAnimeDetailsResponse(id = "be_movie_204", title = "Your Name", mediaType = "movie"),
                        selectedEpisode = ProphecyEpisodeDto(id = "be_movie_s1e1", season = 1, number = 1, title = "S1 & E1"),
                        sources = listOf(ProphecySourceDto(id = "src_movie_4k", label = "MegaCloud", quality = "1080p"))
                    )
                )

            override suspend fun resolveDirectPlay(request: DirectPlayRequest) =
                Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = request.sourceId,
                            playbackUrl = "https://cdn.example/movie_204.mp4"
                        )
                    )
                )
        }

        val repo = createRepo(api)
        val resolved = repo.resolvePlayableStream(
            animeId = movieId,
            seasonNumber = 0,
            episodeNumber = 1,
            catalogEpisodeId = "movie_$movieId"
        ).getOrThrow()
        assertEquals("https://cdn.example/movie_204.mp4", resolved.directData.playbackUrl)
        assertTrue(memoryCache.getStreamSnapshot("movie:204")?.sources?.isNotEmpty() == true)
        assertTrue(db.episodeDao().getSeasonsForAnime(movieId).isEmpty())
        assertTrue(db.episodeDao().getEpisodesForAnime(movieId).isEmpty())

        val invalidSeasonResp = ProphecyEpisodeFullResponse(
            selectedEpisode = ProphecyEpisodeDto(id = "be_movie_s1e1", season = 2, number = 1)
        )
        val validation = SourceIdentityValidator.validateEpisodeFullResponse(
            body = invalidSeasonResp,
            animeEntry = db.animeDao().getAnimeById(movieId),
            requestedBackendId = "be_movie_204",
            requestedBackendEpisodeId = "be_movie_s1e1",
            isMovie = true,
            requestedSeason = 0,
            effectiveBackendSeason = 1,
            allowedEpisodeNumbers = setOf(1)
        )
        assertTrue(validation is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.SEASON_MISMATCH, (validation as SourceValidationResult.Invalid).code)
    }

    // Scenario 5: Stale cached URL mismatch from another episode rejected
    @Test
    fun scenario5_staleCachedUrlFromAnotherEpisode_isRejectedAndDoesNotLeak() = runTest {
        val animeId = "anilist_100"
        val sharedSourceId = "shared_provider_source"
        val scopeEp1 = "anime:100:s1:e1"
        val scopeEp2 = "anime:100:s1:e2"
        val scopeS2Ep1 = "anime:100:s2:e1"

        val ep2Record = D1StreamSourceRecordDto(
            sourceDocKey = CloudflareD1DataSource.deterministicRecordId("be_100", "be_100_e2", sharedSourceId),
            scopeKey = "be_100",
            providerId = "direct",
            sourceId = sharedSourceId,
            sourceName = sharedSourceId,
            backendEpisodeId = "be_100_e2",
            streamUrl = "https://cdn.example/wrong_ep2.mp4",
            urlType = "direct",
            expiresAt = "2099-01-01T00:00:00Z",
            updatedAt = 1800000000000L
        )

        val wrongEpCheck = SourceIdentityValidator.validateD1StreamSourceRecord(ep2Record, "be_100", "be_100_e1", sharedSourceId)
        assertTrue(wrongEpCheck is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.EPISODE_ID_MISMATCH, (wrongEpCheck as SourceValidationResult.Invalid).code)

        val wrongAnimeCheck = SourceIdentityValidator.validateD1StreamSourceRecord(ep2Record, "be_other", "be_100_e2", sharedSourceId)
        assertTrue(wrongAnimeCheck is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.ANIME_ID_MISMATCH, (wrongAnimeCheck as SourceValidationResult.Invalid).code)

        // Store cached playback in Ep 2 scope in Memory & EpisodeDao snapshot and ensure Ep 1 ignores it
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 100, backendAnimeId = "be_100", preferredTitle = "Show 100", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_100_1", animeId, 1, 1, "Ep 1")))
        seedEpisodeSourceSnapshot(
            memoryCache = memoryCache,
            scopeKey = scopeEp2,
            seasonNumber = 1,
            episodeNumber = 2,
            sources = listOf(ProphecySourceDto(id = sharedSourceId, label = "Pahe"))
        )
        memoryCache.putResolvedPlayback(
            sourceId = sharedSourceId,
            data = DirectPlayData(sourceId = sharedSourceId, playbackUrl = "https://cdn.example/wrong_ep2.mp4"),
            scopeKey = scopeEp2
        )

        val directCalls = AtomicInteger(0)
        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_100", title = "Show 100"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_100_e1", season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(ProphecySourceDto(id = sharedSourceId, label = "Pahe"))
                    )
                )
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                directCalls.incrementAndGet()
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = sharedSourceId,
                            playbackUrl = "https://cdn.example/correct_ep1.mp4"
                        )
                    )
                )
            }
        }

        val repo = createRepo(api)
        val resolvedEp1 = repo.resolvePlayableStream(animeId, 1, 1, "ep_100_1", preferredSourceId = sharedSourceId).getOrThrow()
        assertEquals("https://cdn.example/correct_ep1.mp4", resolvedEp1.directData.playbackUrl)
        assertEquals(1, directCalls.get())
    }

    // Scenario 6: Missing optional metadata validates & resolves cleanly
    @Test
    fun scenario6_missingOptionalSourceMetadata_validatesAndResolvesCleanly() = runTest {
        val animeId = "anilist_600"
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 600, backendAnimeId = "be_600", preferredTitle = "Minimal Metadata Show", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_600_1", animeId, 1, 1)))

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_600", title = "Minimal Metadata Show"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_600_1", season = null, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        anime = null,
                        selectedEpisode = null,
                        sources = listOf(
                            ProphecySourceDto(
                                id = "src_minimal_only",
                                label = null,
                                quality = null,
                                codec = null,
                                language = null,
                                sizeBytes = null,
                                downloadable = null,
                                expiresAt = null
                            )
                        )
                    )
                )
            override suspend fun resolveDirectPlay(request: DirectPlayRequest) =
                Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = "src_minimal_only",
                            playbackUrl = "https://cdn.example/minimal_play.mp4",
                            expiresAt = null
                        )
                    )
                )
        }

        val repo = createRepo(api)
        val res = repo.resolvePlayableStream(animeId, 1, 1, "ep_600_1").getOrThrow()
        assertEquals("src_minimal_only", res.source.id)
        assertEquals("https://cdn.example/minimal_play.mp4", res.directData.playbackUrl)
    }

    // Scenario 7: Conflicting candidate rejected cleanly with diagnostic logged while continuing to valid source
    @Test
    fun scenario7_conflictingCandidateRejectedWithDiagnostic_pipelineContinuesToValidSource() = runTest {
        val animeId = "anilist_700"
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 700, backendAnimeId = "be_700", preferredTitle = "Split Source Anime", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_700_1", animeId, 1, 1)))

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_700", title = "Split Source Anime"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_700_1", season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        anime = ProphecyAnimeDetailsResponse(id = "be_700", title = "Split Source Anime"),
                        selectedEpisode = ProphecyEpisodeDto(id = "be_700_1", season = 1, number = 1),
                        sources = listOf(
                            ProphecySourceDto(id = "src_bad_identity", label = "CorruptProvider", quality = "1080p"),
                            ProphecySourceDto(id = "src_good_2", label = "ValidProvider", quality = "720p")
                        )
                    )
                )

            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                return if (request.sourceId == "src_bad_identity") {
                    Response.success(
                        DirectPlayResponse(
                            success = true,
                            data = DirectPlayData(
                                sourceId = "src_DIFFERENT_ANIME_LEAK",
                                playbackUrl = "https://secret.cdn.invalid/leaked.m3u8?token=SECRET_TOKEN"
                            )
                        )
                    )
                } else {
                    Response.success(
                        DirectPlayResponse(
                            success = true,
                            data = DirectPlayData(
                                sourceId = "src_good_2",
                                playbackUrl = "https://cdn.example/valid_src_2.mp4"
                            )
                        )
                    )
                }
            }
        }

        val repo = createRepo(api)
        val disc = repo.discoverStreamsForScope(animeId, 1, 1, "ep_700_1").getOrThrow()
        assertEquals("Discovery must return 2 sources, got ${disc.sources.map { it.id }} (${disc.errorMessage})", 2, disc.sources.size)
        val res = repo.resolvePlayableStream(animeId, 1, 1, "ep_700_1").getOrThrow()

        assertEquals("src_good_2", res.source.id)
        assertEquals("https://cdn.example/valid_src_2.mp4", res.directData.playbackUrl)
        // Candidate ordering places the confirmed winner first so PlayerViewModel does not mismatch!
        assertEquals("src_good_2", res.candidates.first().id)

        val diagnostics = SourceIdentityValidator.recentDiagnostics()
        assertTrue(diagnostics.any { it.code == SourceDiagnosticCode.PROVIDER_ID_MISMATCH && it.sourceId == "src_bad_identity" })
        // Sensitive URL query strings must be redacted from diagnostics
        assertTrue(diagnostics.none { it.detail.contains("SECRET_TOKEN") || it.detail.contains("https://") })

        // Also verify real ProphecyVerse unified search ID vs stripped /full ID matches cleanly,
        // while a genuinely different anime ("Naruto" / different ID) still rejects with ANIME_ID_MISMATCH.
        val unifiedSearchId = "eyJwIjoiZm91cmtoZGh1YiIsInYiOiIvdGhlLWVtaW5lbmNlLWluLXNoYWRvdy1zZXJpZXMtMzEwNS8iLCJzIjowLCJlIjowLCJjYW5vbmljYWxfaWQiOiJsb2NhbC10ZXN0IiwidW5pZmllZCI6dHJ1ZX0"
        val strippedFullId = "eyJwIjoiZm91cmtoZGh1YiIsInYiOiIvdGhlLWVtaW5lbmNlLWluLXNoYWRvdy1zZXJpZXMtMzEwNS8iLCJzIjowLCJlIjowfQ"
        val eminenceEntry = AnimeEntry(
            id = "anilist_130298",
            aniListId = 130298,
            backendAnimeId = unifiedSearchId,
            preferredTitle = "The Eminence in Shadow",
            format = "TV",
            isPlayable = true
        )
        assertTrue(
            SourceIdentityValidator.validateEpisodeFullResponse(
                body = ProphecyEpisodeFullResponse(
                    anime = ProphecyAnimeDetailsResponse(id = strippedFullId, title = "The Eminence in Shadow", mediaType = "series"),
                    selectedEpisode = ProphecyEpisodeDto(id = "ep_real_1", season = 1, number = 1)
                ),
                animeEntry = eminenceEntry,
                requestedBackendId = unifiedSearchId,
                requestedBackendEpisodeId = "ep_real_1",
                isMovie = false,
                requestedSeason = 1,
                effectiveBackendSeason = 1,
                allowedEpisodeNumbers = setOf(1)
            ).isValid
        )
        val mismatchRes = SourceIdentityValidator.validateEpisodeFullResponse(
            body = ProphecyEpisodeFullResponse(
                anime = ProphecyAnimeDetailsResponse(id = "unrelated_anime_999", title = "Completely Unrelated Show", mediaType = "series"),
                selectedEpisode = ProphecyEpisodeDto(id = "ep_real_1", season = 1, number = 1)
            ),
            animeEntry = eminenceEntry,
            requestedBackendId = unifiedSearchId,
            requestedBackendEpisodeId = "ep_real_1",
            isMovie = false,
            requestedSeason = 1,
            effectiveBackendSeason = 1,
            allowedEpisodeNumbers = setOf(1)
        )
        assertTrue(mismatchRes is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.ANIME_ID_MISMATCH, (mismatchRes as SourceValidationResult.Invalid).code)
    }

    // Scenario 8: Cache-first hit from Memory and D1 (stream_urls) -> zero redundant backend calls
    @Test
    fun scenario8_cacheFirstHitsMemoryThenD1_withZeroExtraHttpCalls() = runTest {
        val animeId = "anilist_800"
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 800, backendAnimeId = "be_800", preferredTitle = "Cache Show", format = "TV", isPlayable = true)
        )
        val ep1 = EpisodeEntry("ep_800_1", animeId, 1, 1)
        db.episodeDao().upsertEpisodes(listOf(ep1))

        val d1Store = ConcurrentHashMap<String, String>()
        val d1 = startMockD1Server { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            when {
                method == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val id = path.removePrefix("/v1/tables/stream_sources/")
                    val raw = d1Store[id]
                    if (raw == null) sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                    else sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$raw}""")
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    val body = exchange.requestBody.bufferedReader().readText()
                    val recId = Regex(""""sourceDocKey"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
                    if (recId.isNotEmpty()) d1Store[recId] = body
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$body}""")
                }
                else -> sendJson(exchange, 404, """{"success":false}""")
            }
        }

        val directCalls = AtomicInteger(0)
        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_800", title = "Cache Show"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_800_1", season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(ProphecySourceDto(id = "src_800_1", label = "VidStream", expiresAt = "2099-01-01T00:00:00Z"))
                    )
                )
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                directCalls.incrementAndGet()
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = "src_800_1",
                            playbackUrl = "https://cdn.example/cache_800.mp4",
                            expiresAt = "2099-01-01T00:00:00Z"
                        )
                    )
                )
            }
        }

        val repo = createRepo(api, d1 = d1)
        val first = repo.resolvePlayableStream(animeId, 1, 1, ep1.id, preferredSourceId = "src_800_1").getOrThrow()
        println("SCENARIO8 checkpoint=first tier=${first.cacheTier} directCalls=${directCalls.get()}")
        assertEquals("BACKEND_RESOLVE", first.cacheTier)
        assertEquals(1, directCalls.get())

        // Wait briefly for non-blocking D1 save to complete
        val saveDeadline = System.currentTimeMillis() + 2000L
        while (d1Store.isEmpty() && System.currentTimeMillis() < saveDeadline) {
            Thread.sleep(20L)
        }
        println("SCENARIO8 checkpoint=d1-store size=${d1Store.size}")
        assertTrue("D1 stream_urls must cache newly resolved URL", d1Store.isNotEmpty())

        // 2nd direct playback resolves strictly from Memory LRU
        val hitsBefore = metricsTracker.snapshot().memoryCacheHits
        val memHit = repo.resolveDirectPlayback("src_800_1", scopeKey = "anime:800:s1:e1").getOrThrow()
        println("SCENARIO8 checkpoint=memory url=${memHit.playbackUrl} directCalls=${directCalls.get()} memoryHitsBefore=$hitsBefore memoryHitsAfter=${metricsTracker.snapshot().memoryCacheHits}")
        assertEquals("https://cdn.example/cache_800.mp4", memHit.playbackUrl)
        assertTrue(metricsTracker.snapshot().memoryCacheHits > hitsBefore)
        assertEquals(1, directCalls.get())

        // Invalidate Memory -> 3rd resolvePlayableStream hits D1 stream_urls cleanly with ZERO Backend calls!
        memoryCache.invalidatePlayback("src_800_1", "anime:800:s1:e1")
        val d1Hit = repo.resolvePlayableStream(animeId, 1, 1, ep1.id, preferredSourceId = "src_800_1").getOrThrow()
        println("SCENARIO8 checkpoint=d1 tier=${d1Hit.cacheTier} url=${d1Hit.directData.playbackUrl} directCalls=${directCalls.get()}")
        assertEquals("D1", d1Hit.cacheTier)
        assertEquals("https://cdn.example/cache_800.mp4", d1Hit.directData.playbackUrl)
        assertEquals(1, directCalls.get())
    }

    // Scenario 9: Cached URL failure invalidates broken URL across Memory & D1 and resolves fresh URL cleanly
    @Test
    fun scenario9_cachedUrlPlaybackFailure_invalidatesBadUrlAndUpdatesD1WithFreshUrl() = runTest {
        val animeId = "anilist_900"
        val scope = "anime:900:s1:e1"
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 900, backendAnimeId = "be_900", preferredTitle = "Refresh Show", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_900_1", animeId, 1, 1)))

        val d1Store = ConcurrentHashMap<String, String>()
        val d1 = startMockD1Server { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            when {
                method == "GET" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val id = path.removePrefix("/v1/tables/stream_sources/")
                    val raw = d1Store[id]
                    if (raw == null) sendJson(exchange, 404, """{"success":false,"error":"Record not found"}""")
                    else sendJson(exchange, 200, """{"success":true,"table":"stream_sources","data":$raw}""")
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    val body = exchange.requestBody.bufferedReader().readText()
                    val recId = Regex(""""sourceDocKey"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
                    if (recId.isNotEmpty()) d1Store[recId] = body
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$body}""")
                }
                method == "DELETE" && path.startsWith("/v1/tables/stream_sources/") -> {
                    val id = path.removePrefix("/v1/tables/stream_sources/")
                    d1Store.remove(id)
                    sendJson(exchange, 200, """{"success":true,"message":"Record deleted","table":"stream_sources","changes":1}""")
                }
                else -> sendJson(exchange, 404, """{"success":false}""")
            }
        }

        val resolveVersion = AtomicInteger(0)
        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_900", title = "Refresh Show"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_900_1", season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(ProphecyEpisodeFullResponse(sources = listOf(ProphecySourceDto(id = "src_900_1", label = "Pahe"))))
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                val n = resolveVersion.incrementAndGet()
                val url = if (n == 1) "https://cdn.example/broken_v1.mp4" else "https://cdn.example/fresh_v2.mp4"
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(sourceId = "src_900_1", playbackUrl = url, expiresAt = "2099-01-01T00:00:00Z")
                    )
                )
            }
        }

        val repo = createRepo(api, d1 = d1)
        val initial = repo.resolvePlayableStream(animeId, 1, 1, "ep_900_1", preferredSourceId = "src_900_1").getOrThrow()
        assertEquals("https://cdn.example/broken_v1.mp4", initial.directData.playbackUrl)

        val save1Deadline = System.currentTimeMillis() + 2000L
        while (d1Store.isEmpty() && System.currentTimeMillis() < save1Deadline) Thread.sleep(20L)

        // Simulate ExoPlayer playback failure -> invalidate cached URL in Memory + D1
        repo.invalidateFailedCachedPlayback(animeId, 1, 1, "src_900_1")
        assertNull(memoryCache.getResolvedPlayback("src_900_1", scope))
        assertTrue("Failed cached URL must be deleted from D1", d1Store.isEmpty())

        // Next resolution fetches fresh URL cleanly and updates D1 cache
        val refreshed = repo.resolvePlayableStream(animeId, 1, 1, "ep_900_1", preferredSourceId = "src_900_1").getOrThrow()
        assertEquals("BACKEND_RESOLVE", refreshed.cacheTier)
        assertEquals("https://cdn.example/fresh_v2.mp4", refreshed.directData.playbackUrl)
        assertEquals(2, resolveVersion.get())

        val save2Deadline = System.currentTimeMillis() + 2000L
        while (d1Store.values.none { it.contains("fresh_v2.mp4") } && System.currentTimeMillis() < save2Deadline) {
            Thread.sleep(20L)
        }
        assertTrue(d1Store.values.any { it.contains("fresh_v2.mp4") })
    }

    // Scenario 10: Concurrent bounded resolution beats slow/failing providers and cancels slower siblings
    @Test
    fun scenario10_boundedConcurrentResolution_winsFastAndCancelsSlowProviders() = runTest {
        val animeId = "anilist_1000"
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 1000, backendAnimeId = "be_1000", preferredTitle = "Speed Show", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_1000_1", animeId, 1, 1)))

        val inFlight = AtomicInteger(0)
        val maxConcurrentObserved = AtomicInteger(0)
        val cancelledCount = AtomicInteger(0)
        val startedBatch2Count = AtomicInteger(0)

        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_1000", title = "Speed Show"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_1000_1", season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(
                            ProphecySourceDto(id = "src_1_slow", label = "SlowProv", quality = "1080p"),
                            ProphecySourceDto(id = "src_2_fast", label = "FastProv", quality = "1080p"),
                            ProphecySourceDto(id = "src_3_slow", label = "SlowProv2", quality = "1080p"),
                            ProphecySourceDto(id = "src_4_batch2", label = "Batch2Prov", quality = "480p"),
                            ProphecySourceDto(id = "src_5_batch2", label = "Batch2Prov2", quality = "480p")
                        )
                    )
                )

            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                val active = inFlight.incrementAndGet()
                maxConcurrentObserved.updateAndGet { prev -> max(prev, active) }
                try {
                    if (request.sourceId.startsWith("src_4") || request.sourceId.startsWith("src_5")) {
                        startedBatch2Count.incrementAndGet()
                    }
                    return when (request.sourceId) {
                        "src_2_fast" -> {
                            delay(25L)
                            Response.success(
                                DirectPlayResponse(
                                    success = true,
                                    data = DirectPlayData(
                                        sourceId = "src_2_fast",
                                        playbackUrl = "https://cdn.example/fast_src_2.mp4"
                                    )
                                )
                            )
                        }
                        else -> {
                            delay(2_500L)
                            Response.success(
                                DirectPlayResponse(
                                    success = true,
                                    data = DirectPlayData(
                                        sourceId = request.sourceId,
                                        playbackUrl = "https://cdn.example/${request.sourceId}.mp4"
                                    )
                                )
                            )
                        }
                    }
                } catch (c: CancellationException) {
                    cancelledCount.incrementAndGet()
                    throw c
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }

        val repo = createRepo(api)
        val wallStartedMs = System.nanoTime() / 1_000_000
        val resolved = repo.resolvePlayableStream(animeId, 1, 1, "ep_1000_1").getOrThrow()
        val elapsedWallMs = (System.nanoTime() / 1_000_000) - wallStartedMs

        assertEquals("src_2_fast", resolved.source.id)
        assertEquals("src_2_fast", resolved.candidates.first().id)
        assertEquals("https://cdn.example/fast_src_2.mp4", resolved.directData.playbackUrl)
        assertTrue("Expected fast win << 1500ms, took ${elapsedWallMs}ms", elapsedWallMs < 1_500L)
        assertTrue("Expected bounded batch concurrency <= 3, saw ${maxConcurrentObserved.get()}", maxConcurrentObserved.get() <= 3)
        assertEquals("Batch 2 must not start after Batch 1 already won", 0, startedBatch2Count.get())
        assertTrue("Expected slow sibling tasks to cancel cleanly", cancelledCount.get() >= 1)
    }

    // Scenario 11: Request coalescing & PlayerViewModel foreground generation guard
    @Test
    fun scenario11_requestCoalescing_sharesSingleBackendDiscoveryAndDirectPlayFlight() = runTest {
        val animeId = "anilist_1100"
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 1100, backendAnimeId = "be_1100", preferredTitle = "Coalesce Show", format = "TV", isPlayable = true)
        )
        val ep1 = EpisodeEntry("ep_1100_1", animeId, 1, 1)
        db.episodeDao().upsertEpisodes(listOf(ep1))

        val discoveryCalls = AtomicInteger(0)
        val directCalls = AtomicInteger(0)
        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_1100", title = "Coalesce Show"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_1100_1", season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                discoveryCalls.incrementAndGet()
                delay(40L)
                return Response.success(
                    ProphecyEpisodeFullResponse(sources = listOf(ProphecySourceDto(id = "src_1100", label = "Pahe")))
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                directCalls.incrementAndGet()
                delay(40L)
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(sourceId = request.sourceId, playbackUrl = "https://cdn.example/coalesce.mp4")
                    )
                )
            }
        }

        val repo = createRepo(api)
        val results = (1..6).map {
            async { repo.resolvePlayableStream(animeId, 1, 1, ep1.id) }
        }.awaitAll()

        results.forEach { res ->
            assertTrue(res.isSuccess)
            assertEquals("https://cdn.example/coalesce.mp4", res.getOrNull()?.directData?.playbackUrl)
        }
        assertEquals("Simultaneous callers must share ONE discovery flight", 1, discoveryCalls.get())
        assertEquals("Simultaneous callers must share ONE direct-play flight", 1, directCalls.get())
        assertTrue(metricsTracker.snapshot().duplicateRequestsPrevented >= 1L)

        // Also verify EpisodeResolutionCoordinator single-flight coalescing produces 0 additional HTTP calls
        val coordinatorResults = (1..4).map {
            async { repo.resolveVerifiedPlaybackForEpisode(animeId, ep1) }
        }.awaitAll()
        coordinatorResults.forEach { res ->
            assertTrue(res.isSuccess)
            assertEquals("https://cdn.example/coalesce.mp4", res.getOrNull()?.directData?.playbackUrl)
        }
        assertEquals(1, discoveryCalls.get())
        assertEquals(1, directCalls.get())
    }

    // Scenario 12: Expired D1 stream_urls entry is treated as cache miss, sends (anime_id, episode_id, source_id) to Backend, and non-blocking D1 save does not delay return
    @Test
    fun scenario12_expiredD1StreamUrlTreatsAsMiss_sendsCorrectDirectPlayRequest_andNonBlockingD1Save() = runTest {
        val d1UpsertCalls = AtomicInteger(0)
        val d1Reads = AtomicInteger(0)
        val recId = CloudflareD1DataSource.deterministicRecordId("be_1200", "be_ep_1200", "src_d1_only")

        val d1 = startMockD1Server { exchange ->
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            when {
                method == "GET" && path == "/v1/tables/stream_sources/$recId" -> {
                    d1Reads.incrementAndGet()
                    // Return an expired streamUrl -> client MUST reject it and resolve via Backend!
                    sendJson(
                        exchange, 200,
                        """{"success":true,"table":"stream_sources","data":{"sourceDocKey":"$recId","scopeKey":"be_1200","providerId":"direct","sourceId":"src_d1_only","sourceName":"src_d1_only","backendEpisodeId":"be_ep_1200","streamUrl":"https://untrusted.d1.url/expired.mp4","urlType":"direct","expiresAt":"2020-01-01T00:00:00Z","status":"AVAILABLE","version":1,"updatedAt":1800000000000,"deletedAt":null}}"""
                    )
                }
                method == "POST" && path == "/v1/tables/stream_sources/upsert" -> {
                    d1UpsertCalls.incrementAndGet()
                    val body = exchange.requestBody.bufferedReader().readText()
                    sendJson(exchange, 200, """{"success":true,"message":"Record saved","table":"stream_sources","data":$body}""")
                }
                else -> sendJson(exchange, 404, """{"success":false,"error":"Not found"}""")
            }
        }

        val animeId = "anilist_1200"
        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 1200, backendAnimeId = "be_1200", preferredTitle = "D1 Show", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_1200_1", animeId, 1, 1, sourceEpisodeIds = "10:be_ep_1200")))

        val directCalls = AtomicInteger(0)
        val fullCalls = AtomicInteger(0)
        var capturedRequest: DirectPlayRequest? = null
        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_1200", title = "D1 Show"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_ep_1200", season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                fullCalls.incrementAndGet()
                return Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(ProphecySourceDto(id = "src_d1_only", label = "Pahe 1080p", quality = "1080p"))
                    )
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                directCalls.incrementAndGet()
                capturedRequest = request
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(
                            sourceId = request.sourceId,
                            playbackUrl = "https://verified.direct.example/1200.mp4"
                        )
                    )
                )
            }
        }

        val repo = createRepo(api, d1 = d1)
        val discovery = repo.discoverStreamsForScope(animeId, 1, 1, "ep_1200_1").getOrThrow()
        assertEquals(listOf("src_d1_only"), discovery.sources.map { it.id })
        assertEquals(1, fullCalls.get())
        assertEquals(0, directCalls.get())

        val resolved = repo.resolvePlayableStream(animeId, 1, 1, "ep_1200_1", preferredSourceId = "src_d1_only").getOrThrow()

        assertEquals(1, directCalls.get())
        assertEquals("be_1200", capturedRequest?.animeId)
        assertEquals("be_ep_1200", capturedRequest?.episodeId)
        assertEquals("src_d1_only", capturedRequest?.sourceId)
        assertEquals("https://verified.direct.example/1200.mp4", resolved.directData.playbackUrl)
        assertEquals("D1 must be checked before Backend resolution", 1, d1Reads.get())
        assertEquals("Resolved URL must be saved to D1 before returning to player", 1, d1UpsertCalls.get())
    }

    // Scenario 13 (+ PlayerViewModel generation guard): Resolution failure cleanly exits resolving state
    @Test
    fun scenario13_playerViewModelFailureCleanlyExitsResolvingState_andGenerationGuardPreventsOverwrite() {
        val singletonDb = AppDatabase.getInstance(context)
        val failAnimeId = "anilist_fail_1300"
        val ep1 = EpisodeEntry("ep_fail_1", failAnimeId, 1, 1, "Episode 1")
        val ep2 = EpisodeEntry("ep_fail_2", failAnimeId, 1, 2, "Episode 2")

        kotlinx.coroutines.runBlocking {
            singletonDb.animeDao().upsertAnime(
                AnimeEntry(
                    id = failAnimeId,
                    aniListId = 1300,
                    backendAnimeId = "be_fail_1300",
                    preferredTitle = "Failure Test Anime",
                    format = "TV",
                    isPlayable = true
                )
            )
            singletonDb.episodeDao().upsertEpisodes(listOf(ep1, ep2))
        }

        val failApi = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_fail_1300", title = "Failure Test Anime"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(
                    listOf(
                        ProphecyEpisodeDto(id = "be_ep_1", season = 1, number = 1),
                        ProphecyEpisodeDto(id = "be_ep_2", season = 1, number = 2)
                    )
                )
            override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
                if (episodeId == "be_ep_1") {
                    kotlinx.coroutines.delay(150L)
                }
                return Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(ProphecySourceDto(id = "src_broken_$episodeId", label = "BrokenProvider"))
                    )
                )
            }
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> =
                Response.success(DirectPlayResponse(success = false, data = null))
        }

        val customRepo = createRepo(failApi, database = singletonDb)
        setRepositorySingleton(customRepo)

        val vm = PlayerViewModel(
            application = context.applicationContext as Application,
            animeId = failAnimeId,
            initialEpisodeId = ep1.id
        )

        // Rapidly switch to Ep 2 so generation guard supersedes Ep 1
        vm.startPlaybackForEpisode(ep2)
        assertEquals(ep2.id, vm.currentEpisode.value?.id)

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (!vm.isResolving.value &&
                vm.resolutionError.value != null &&
                !EpisodeResolutionCoordinator.isForegroundResolutionActive()
            ) {
                break
            }
            Thread.sleep(25L)
        }

        assertEquals(ep2.id, vm.currentEpisode.value?.id)
        assertFalse("Resolving spinner must exit cleanly on failure", vm.isResolving.value)
        assertEquals(PlayerViewModel.ResolutionStage.IDLE, vm.resolutionStage.value)
        assertFalse("Player buffering must not stay stuck true on failure", vm.playerManager.isBuffering.value)
        assertNotNull("User must see actionable resolution error", vm.resolutionError.value)
        assertFalse(EpisodeResolutionCoordinator.isForegroundResolutionActive())

        vm.playerManager.release()
        vm.preResolveManager.clear()
        kotlinx.coroutines.runBlocking<Unit> {
            singletonDb.episodeDao().deleteEpisodesForAnime(failAnimeId)
            singletonDb.openHelper.writableDatabase.execSQL(
                "DELETE FROM anime_entries WHERE id = ?",
                arrayOf(failAnimeId)
            )
        }
    }
}
