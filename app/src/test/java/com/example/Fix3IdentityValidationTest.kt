package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
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
import com.example.data.remote.NetworkModule
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecyEpisodeFullResponse
import com.example.data.remote.ProphecySearchResponse
import com.example.data.remote.ProphecySearchResultItem
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.repository.SourceDiagnosticCode
import com.example.data.repository.SourceIdentityValidator
import com.example.data.repository.SourceValidationResult
import com.example.data.repository.TitleMatcher
import com.example.data.streaming.ContentIdentityHelper
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Fix3IdentityValidationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var metricsTracker: DatabaseMetricsTracker
    private lateinit var memoryCache: MemoryCacheManager

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
        db.close()
    }

    private fun noOpAniList(): AniListGraphQLService = object : AniListGraphQLService {
        override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
            Response.success(AniListGraphQLResponse(AniListData(null)))
    }

    private fun createRepo(
        api: ProphecyVerseApiService,
        d1DataSource: com.example.data.d1.CloudflareD1DataSource = createTestD1DataSource(metricsTracker)
    ): AnimeRepository {
        return AnimeRepository(
            database = db,
            prophecyApi = api,
            aniListApi = noOpAniList(),
            applicationContext = context,
            d1DataSource = d1DataSource,
            memoryCache = memoryCache,
            metricsTracker = metricsTracker
        )
    }

    private fun noOpApi(directResolver: suspend (DirectPlayRequest) -> Response<DirectPlayResponse> = {
        Response.success(DirectPlayResponse(false, null))
    }): ProphecyVerseApiService = object : ProphecyVerseApiService {
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

    @Test
    fun fix3_sameSourceIdAcrossTwoProviders_doesNotCollideInMemoryRoomOrResolution() = runTest {
        val animeId = "anilist_3001"
        val scope = "anime:3001:s1:e1"
        val sharedSourceId = "shared_embed_1"
        val provA = ContentIdentityHelper.deterministicProviderId("Pahe")
        val provB = ContentIdentityHelper.deterministicProviderId("MegaCloud")

        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 3001, backendAnimeId = "be_3001", preferredTitle = "Collision Show", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_3001_1", animeId, 1, 1, "Ep 1", sourceEpisodeIds = "[\"be_ep_1\"]")))
        seedEpisodeSourceSnapshot(
            memoryCache = memoryCache,
            scopeKey = scope,
            seasonNumber = 1,
            episodeNumber = 1,
            sources = listOf(
                ProphecySourceDto(id = sharedSourceId, label = "Pahe", quality = "1080p", expiresAt = "2099-01-01T00:00:00Z"),
                ProphecySourceDto(id = sharedSourceId, label = "MegaCloud", quality = "1080p", expiresAt = "2099-01-01T00:00:00Z")
            )
        )

        // Memory cache keeps both isolated by providerId and rejects ambiguous lookup without providerId
        memoryCache.putResolvedPlayback(
            sourceId = sharedSourceId,
            data = DirectPlayData(sourceId = sharedSourceId, playbackUrl = "https://pahe.example/video.mp4"),
            scopeKey = scope,
            providerId = provA
        )
        memoryCache.putResolvedPlayback(
            sourceId = sharedSourceId,
            data = DirectPlayData(sourceId = sharedSourceId, playbackUrl = "https://megacloud.example/video.mp4"),
            scopeKey = scope,
            providerId = provB
        )
        assertEquals("https://pahe.example/video.mp4", memoryCache.getResolvedPlayback(sharedSourceId, scope, provA)?.playbackUrl)
        assertEquals("https://megacloud.example/video.mp4", memoryCache.getResolvedPlayback(sharedSourceId, scope, provB)?.playbackUrl)
        assertNull("Ambiguous lookup without providerId must return null", memoryCache.getResolvedPlayback(sharedSourceId, scope, null))

        // Repository resolution selects exact provider's URL without crossing
        val repo = createRepo(noOpApi())
        val resA = repo.resolvePlayableStream(animeId, 1, 1, "ep_3001_1", preferredSourceId = sharedSourceId, preferredProviderId = provA).getOrThrow()
        val resB = repo.resolvePlayableStream(animeId, 1, 1, "ep_3001_1", preferredSourceId = sharedSourceId, preferredProviderId = provB).getOrThrow()
        assertEquals("https://pahe.example/video.mp4", resA.directData.playbackUrl)
        assertEquals("https://megacloud.example/video.mp4", resB.directData.playbackUrl)
        assertEquals("https://pahe.example/video.mp4", repo.resolveDirectPlayback(sharedSourceId, scopeKey = scope, providerId = provA).getOrThrow().playbackUrl)
        assertEquals("https://megacloud.example/video.mp4", repo.resolveDirectPlayback(sharedSourceId, scopeKey = scope, providerId = provB).getOrThrow().playbackUrl)
    }

    @Test
    fun fix3_cachedUrlFromWrongAnimeEpisodeSourceOrExpired_isRejected() {
        val validD1 = com.example.data.d1.D1StreamUrlRecordDto(
            id = "r1",
            animeId = "be_3002",
            episodeId = "be_s1_e2",
            sourceId = "src_1",
            streamUrl = "https://cdn.example/s1e2.m3u8",
            expiresAt = "2099-01-01T00:00:00Z"
        )
        assertTrue(SourceIdentityValidator.validateD1StreamUrlRecord(validD1, "be_3002", "be_s1_e2", "src_1").isValid)

        val wrongAnime = SourceIdentityValidator.validateD1StreamUrlRecord(validD1, "be_9999", "be_s1_e2", "src_1")
        assertTrue(wrongAnime is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.ANIME_ID_MISMATCH, (wrongAnime as SourceValidationResult.Invalid).code)

        val wrongEp = SourceIdentityValidator.validateD1StreamUrlRecord(validD1, "be_3002", "be_s1_e1", "src_1")
        assertTrue(wrongEp is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.EPISODE_ID_MISMATCH, (wrongEp as SourceValidationResult.Invalid).code)

        val wrongSource = SourceIdentityValidator.validateD1StreamUrlRecord(validD1, "be_3002", "be_s1_e2", "src_other")
        assertTrue(wrongSource is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.PROVIDER_ID_MISMATCH, (wrongSource as SourceValidationResult.Invalid).code)

        val expired = SourceIdentityValidator.validateD1StreamUrlRecord(validD1.copy(expiresAt = "2020-01-01T00:00:00Z"), "be_3002", "be_s1_e2", "src_1")
        assertTrue(expired is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.SOURCE_EXPIRED, (expired as SourceValidationResult.Invalid).code)

        val badScheme = SourceIdentityValidator.validateD1StreamUrlRecord(validD1.copy(streamUrl = "ftp://bad.example/file"), "be_3002", "be_s1_e2", "src_1")
        assertTrue(badScheme is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.SOURCE_NOT_PLAYABLE, (badScheme as SourceValidationResult.Invalid).code)
    }

    @Test
    fun fix3_episodeFullAndDirectPlayValidationRejectsCrossBoundaryPayloads() {
        val resp = ProphecyEpisodeFullResponse(
            anime = ProphecyAnimeDetailsResponse(id = "other_anime", title = "Wrong Anime"),
            sources = listOf(ProphecySourceDto(id = "s1", label = "Pahe"))
        )
        val resAnime = SourceIdentityValidator.validateEpisodeFullResponse(
            body = resp,
            animeEntry = AnimeEntry(id = "anilist_3003", aniListId = 3003, backendAnimeId = "be_3003", preferredTitle = "Correct Show"),
            requestedBackendId = "be_3003",
            requestedBackendEpisodeId = "be_ep_3",
            isMovie = false,
            requestedSeason = 1,
            effectiveBackendSeason = 1,
            allowedEpisodeNumbers = setOf(1)
        )
        assertTrue(resAnime is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.ANIME_ID_MISMATCH, (resAnime as SourceValidationResult.Invalid).code)

        val directWrong = SourceIdentityValidator.validateDirectPlayData(
            DirectPlayData(sourceId = "wrong_src", playbackUrl = "https://cdn.example/video.mp4"),
            expectedSourceId = "src_3"
        )
        assertTrue(directWrong is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.PROVIDER_ID_MISMATCH, (directWrong as SourceValidationResult.Invalid).code)
    }

    @Test
    fun fix3_discoveryDoesNotMergeCachedDataAcrossDifferentProvidersEvenWhenSourceIdMatches() = runTest {
        val animeId = "anilist_3004"
        val scope = "anime:3004:s1:e1"
        val sharedSourceId = "dup_src_id"
        val oldProv = ContentIdentityHelper.deterministicProviderId("OldProvider")
        val newProv = ContentIdentityHelper.deterministicProviderId("NewProvider")

        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 3004, backendAnimeId = "be_3004", preferredTitle = "No Cross-Merge", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_3004_1", animeId, 1, 1, "Ep 1")))
        seedEpisodeSourceSnapshot(
            memoryCache = memoryCache,
            scopeKey = scope,
            seasonNumber = 1,
            episodeNumber = 1,
            sources = listOf(ProphecySourceDto(id = sharedSourceId, label = "OldProvider", expiresAt = "2099-01-01T00:00:00Z"))
        )
        memoryCache.putResolvedPlayback(sharedSourceId, DirectPlayData(sourceId = sharedSourceId, playbackUrl = "https://old-provider.example/old.mp4"), scope, oldProv)

        val directCalls = AtomicInteger(0)
        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String, limit: Int?, cursor: String?) =
                Response.success(ProphecySearchResponse())
            override suspend fun getAnimeDetails(animeId: String) =
                Response.success(ProphecyAnimeDetailsResponse(id = "be_3004", title = "No Cross-Merge"))
            override suspend fun getAnimeEpisodes(animeId: String) =
                Response.success(listOf(ProphecyEpisodeDto(id = "be_3004_e1", season = 1, number = 1)))
            override suspend fun getEpisodeFull(animeId: String, episodeId: String) =
                Response.success(
                    ProphecyEpisodeFullResponse(
                        sources = listOf(ProphecySourceDto(id = sharedSourceId, label = "NewProvider", quality = "1080p"))
                    )
                )
            override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
                directCalls.incrementAndGet()
                return Response.success(
                    DirectPlayResponse(
                        success = true,
                        data = DirectPlayData(sourceId = sharedSourceId, playbackUrl = "https://new-provider.example/fresh.mp4")
                    )
                )
            }
        }

        val repo = createRepo(api)
        val snap = repo.discoverStreamsForScope(animeId, 1, 1, "ep_3004_1", forceRefresh = true).getOrThrow()
        assertEquals("NewProvider", snap.sources.single().label)

        // Playing via NewProvider must resolve fresh URL, never OldProvider's cached URL
        val resolved = repo.resolvePlayableStream(animeId, 1, 1, "ep_3004_1", preferredSourceId = sharedSourceId, preferredProviderId = newProv).getOrThrow()
        assertEquals("https://new-provider.example/fresh.mp4", resolved.directData.playbackUrl)
        assertEquals(1, directCalls.get())
    }

    @Test
    fun fix3_invalidationRespectsProviderAndSourceScopeWithoutAffectingSiblingProvider() = runTest {
        val animeId = "anilist_3005"
        val scope = "anime:3005:s1:e1"
        val sharedSourceId = "shared_src"
        val provA = ContentIdentityHelper.deterministicProviderId("ProviderA")
        val provB = ContentIdentityHelper.deterministicProviderId("ProviderB")

        db.animeDao().upsertAnime(
            AnimeEntry(id = animeId, aniListId = 3005, backendAnimeId = "be_3005", preferredTitle = "Scoped Invalidate", format = "TV", isPlayable = true)
        )
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_3005_1", animeId, 1, 1, sourceEpisodeIds = "[\"be_ep_3005\"]")))
        seedEpisodeSourceSnapshot(
            memoryCache = memoryCache,
            scopeKey = scope,
            seasonNumber = 1,
            episodeNumber = 1,
            sources = listOf(
                ProphecySourceDto(id = sharedSourceId, label = "ProviderA"),
                ProphecySourceDto(id = sharedSourceId, label = "ProviderB")
            )
        )
        memoryCache.putResolvedPlayback(sharedSourceId, DirectPlayData(sourceId = sharedSourceId, playbackUrl = "https://provA.example/video.mp4"), scope, provA)
        memoryCache.putResolvedPlayback(sharedSourceId, DirectPlayData(sourceId = sharedSourceId, playbackUrl = "https://provB.example/video.mp4"), scope, provB)

        val repo = createRepo(noOpApi())
        repo.invalidateFailedCachedPlayback(animeId, 1, 1, sharedSourceId, providerId = provA)

        // ProviderA invalidated in Memory without disturbing ProviderB in Memory!
        assertNull(memoryCache.getResolvedPlayback(sharedSourceId, scope, provA))
        assertEquals("https://provB.example/video.mp4", memoryCache.getResolvedPlayback(sharedSourceId, scope, provB)?.playbackUrl)
    }

    @Test
    fun fix3_directPlayRequest_wireJsonContainsOnlySourceId() {
        val adapter = NetworkModule.moshi.adapter(DirectPlayRequest::class.java)
        val json = adapter.toJson(
            DirectPlayRequest(
                animeId = "be_1",
                episodeId = "ep_1",
                sourceId = "  src_1  "
            )
        )
        assertEquals("{\"source_id\":\"src_1\"}", json)
        assertFalse(json.contains("anime_id"))
        assertFalse(json.contains("episode_id"))
    }

    @Test
    fun fix3_validateDirectPlayData_acceptsSameEpisodeFallbackTokenAndRejectsMismatches() {
        fun encodeToken(json: String): String =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))

        val requestedSrc = encodeToken("""{"p":"dramachi","v":"123","s":1,"e":1}""")
        val fallbackSameEpSrc = encodeToken("""{"p":"fourkhdhub","v":"/show/","s":1,"e":1}""")
        val fallbackDifferentEpSrc = encodeToken("""{"p":"fourkhdhub","v":"/show/","s":1,"e":2}""")

        val acceptedFallback = SourceIdentityValidator.validateDirectPlayData(
            data = DirectPlayData(
                sourceId = fallbackSameEpSrc,
                playbackUrl = "https://cdn.example/video.mkv",
                message = "Fallback provider playback URL resolved."
            ),
            expectedSourceId = requestedSrc
        )
        assertTrue("Expected same-episode fallback token to validate", acceptedFallback.isValid)

        val rejectedCrossEpisode = SourceIdentityValidator.validateDirectPlayData(
            data = DirectPlayData(
                sourceId = fallbackDifferentEpSrc,
                playbackUrl = "https://cdn.example/video.mkv",
                message = "Fallback provider playback URL resolved."
            ),
            expectedSourceId = requestedSrc
        )
        assertTrue(rejectedCrossEpisode is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.PROVIDER_ID_MISMATCH, (rejectedCrossEpisode as SourceValidationResult.Invalid).code)

        val rejectedUnrelatedWithoutFallbackMsg = SourceIdentityValidator.validateDirectPlayData(
            data = DirectPlayData(
                sourceId = fallbackSameEpSrc,
                playbackUrl = "https://cdn.example/video.mkv",
                message = "Playback URL resolved."
            ),
            expectedSourceId = requestedSrc
        )
        assertTrue(rejectedUnrelatedWithoutFallbackMsg is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.PROVIDER_ID_MISMATCH, (rejectedUnrelatedWithoutFallbackMsg as SourceValidationResult.Invalid).code)

        val rejectedPlainString = SourceIdentityValidator.validateDirectPlayData(
            data = DirectPlayData(
                sourceId = "wrong_src",
                playbackUrl = "https://cdn.example/video.mkv",
                message = "Fallback provider playback URL resolved."
            ),
            expectedSourceId = "src_plain_1"
        )
        assertTrue(rejectedPlainString is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.PROVIDER_ID_MISMATCH, (rejectedPlainString as SourceValidationResult.Invalid).code)
    }

    @Test
    fun fix3_titleMatcher_stripsTrailingReleaseYearForSearchAndEntryConfidence() {
        val variants = TitleMatcher.buildSearchQueryVariants(listOf("Hunter x Hunter (2011)"))
        assertTrue("Expected query variants to include 'Hunter x Hunter'", variants.contains("Hunter x Hunter"))

        val entry = AnimeEntry(
            id = "anilist_11061",
            aniListId = 11061,
            preferredTitle = "Hunter x Hunter (2011)",
            titleEnglish = "Hunter x Hunter (2011)",
            titleRomaji = "HUNTER×HUNTER (2011)",
            year = "2011",
            format = "TV"
        )
        val backendCandidate = ProphecySearchResultItem(
            id = "be_hxh_2011",
            title = "Hunter x Hunter",
            mediaType = "tv",
            year = "2011"
        )
        val confidence = TitleMatcher.computeMatchConfidenceForEntry(entry, backendCandidate)
        assertTrue("Expected high match confidence for 'Hunter x Hunter (2011)' vs 'Hunter x Hunter', got $confidence", confidence >= 0.90f)
    }
}
