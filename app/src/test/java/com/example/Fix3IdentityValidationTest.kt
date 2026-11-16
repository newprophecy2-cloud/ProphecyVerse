package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.MemoryCacheManager
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
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
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.repository.AnimeRepository
import com.example.data.repository.SourceDiagnosticCode
import com.example.data.repository.SourceIdentityValidator
import com.example.data.repository.SourceValidationResult
import com.example.data.streaming.ContentIdentityHelper
import com.example.data.streaming.FirestoreStreamingRepository
import com.example.data.streaming.StreamRecordStatus
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun createRepo(api: ProphecyVerseApiService): AnimeRepository {
        val streamingRepo = FirestoreStreamingRepository(
            streamCacheDao = db.streamCacheDao(),
            firestoreProvider = { null },
            isClientWriteAuthorized = { false },
            cloudStoreProvider = null,
            d1DataSource = null,
            metricsTracker = metricsTracker
        )
        return AnimeRepository(
            database = db,
            prophecyApi = api,
            aniListApi = noOpAniList(),
            applicationContext = context,
            streamingRepository = streamingRepo,
            d1DataSource = null,
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

        val entityA = StreamSourceEntity(
            sourceDocKey = "$scope|p|$provA|s|$sharedSourceId",
            scopeKey = scope,
            providerId = provA,
            sourceId = sharedSourceId,
            sourceName = "Pahe",
            backendEpisodeId = "be_ep_1",
            status = StreamRecordStatus.MISSING_URL.name,
            expiresAt = "2099-01-01T00:00:00Z"
        )
        val entityB = StreamSourceEntity(
            sourceDocKey = "$scope|p|$provB|s|$sharedSourceId",
            scopeKey = scope,
            providerId = provB,
            sourceId = sharedSourceId,
            sourceName = "MegaCloud",
            backendEpisodeId = "be_ep_1",
            status = StreamRecordStatus.MISSING_URL.name,
            expiresAt = "2099-01-01T00:00:00Z"
        )
        db.streamCacheDao().upsertProviders(
            listOf(
                StreamProviderEntity("$scope|p|$provA", scope, provA, "Pahe", sourceCount = 1),
                StreamProviderEntity("$scope|p|$provB", scope, provB, "MegaCloud", sourceCount = 1)
            )
        )
        db.streamCacheDao().upsertSources(listOf(entityA, entityB))

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
    fun fix3_cachedUrlFromWrongEpisodeSeasonOrProvider_isRejected() {
        val scopeTarget = "anime:3002:s1:e2"
        val provPahe = ContentIdentityHelper.deterministicProviderId("Pahe")
        val provMega = ContentIdentityHelper.deterministicProviderId("MegaCloud")

        val base = StreamSourceEntity(
            sourceDocKey = "$scopeTarget|p|$provPahe|s|src_1",
            scopeKey = scopeTarget,
            providerId = provPahe,
            sourceId = "src_1",
            sourceName = "Pahe",
            backendEpisodeId = "be_s1_e2",
            status = StreamRecordStatus.AVAILABLE.name,
            expiresAt = "2099-01-01T00:00:00Z"
        )

        val wrongEp = base.copy(scopeKey = "anime:3002:s1:e1", sourceDocKey = "anime:3002:s1:e1|p|$provPahe|s|src_1")
        val wrongEpResult = SourceIdentityValidator.validateCachedSourceEntity(wrongEp, scopeTarget, "src_1")
        assertTrue(wrongEpResult is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.EPISODE_ID_MISMATCH, (wrongEpResult as SourceValidationResult.Invalid).code)

        val wrongSeason = base.copy(scopeKey = "anime:3002:s2:e2", sourceDocKey = "anime:3002:s2:e2|p|$provPahe|s|src_1")
        val wrongSeasonResult = SourceIdentityValidator.validateCachedSourceEntity(wrongSeason, scopeTarget, "src_1")
        assertTrue(wrongSeasonResult is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.SEASON_MISMATCH, (wrongSeasonResult as SourceValidationResult.Invalid).code)

        val wrongProviderResult = SourceIdentityValidator.validateCachedSourceEntity(base, scopeTarget, "src_1", expectedProviderId = provMega)
        assertTrue(wrongProviderResult is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.PROVIDER_ID_MISMATCH, (wrongProviderResult as SourceValidationResult.Invalid).code)

        // Validate D1 stream_urls identity checks (anime_id, episode_id, source_id, URL, expiry)
        val validD1 = com.example.data.d1.D1StreamUrlRecordDto(
            id = "r1",
            animeId = "be_3002",
            episodeId = "be_s1_e2",
            sourceId = "src_1",
            streamUrl = "https://cdn.example/s1e2.m3u8",
            expiresAt = "2099-01-01T00:00:00Z"
        )
        assertTrue(SourceIdentityValidator.validateD1StreamUrlRecord(validD1, "be_3002", "be_s1_e2", "src_1").isValid)
        assertTrue(SourceIdentityValidator.validateD1StreamUrlRecord(validD1, "be_9999", "be_s1_e2", "src_1") is SourceValidationResult.Invalid)
        assertTrue(SourceIdentityValidator.validateD1StreamUrlRecord(validD1, "be_3002", "be_s1_e1", "src_1") is SourceValidationResult.Invalid)
        assertTrue(SourceIdentityValidator.validateD1StreamUrlRecord(validD1, "be_3002", "be_s1_e2", "src_other") is SourceValidationResult.Invalid)
    }

    @Test
    fun fix3_missingOrMismatchedProviderIdSourceDocKeyOrBackendEpisodeId_rejectsCachedItem() {
        val scope = "anime:3003:s1:e1"
        val prov = "pahe"
        val valid = StreamSourceEntity(
            sourceDocKey = "$scope|p|$prov|s|src_3",
            scopeKey = scope,
            providerId = prov,
            sourceId = "src_3",
            sourceName = "Pahe",
            backendEpisodeId = "be_ep_3",
            status = StreamRecordStatus.MISSING_URL.name,
            expiresAt = "2099-01-01T00:00:00Z"
        )
        assertTrue(
            SourceIdentityValidator.validateCachedSourceEntity(
                entity = valid,
                expectedScopeKey = scope,
                expectedSourceId = "src_3",
                expectedProviderId = prov,
                expectedBackendEpisodeId = "be_ep_3"
            ).isValid
        )

        val blankProvider = valid.copy(providerId = "")
        val resBlankProv = SourceIdentityValidator.validateCachedSourceEntity(blankProvider, scope, "src_3")
        assertTrue(resBlankProv is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.PROVIDER_ID_MISMATCH, (resBlankProv as SourceValidationResult.Invalid).code)

        val badDocKey = valid.copy(sourceDocKey = "$scope|p|other_provider|s|src_3")
        val resBadDocKey = SourceIdentityValidator.validateCachedSourceEntity(badDocKey, scope, "src_3")
        assertTrue(resBadDocKey is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.PROVIDER_ID_MISMATCH, (resBadDocKey as SourceValidationResult.Invalid).code)

        val blankDocKey = valid.copy(sourceDocKey = "")
        assertTrue(SourceIdentityValidator.validateCachedSourceEntity(blankDocKey, scope, "src_3") is SourceValidationResult.Invalid)

        val missingBackendEp = valid.copy(backendEpisodeId = null)
        val resMissingBackendEp = SourceIdentityValidator.validateCachedSourceEntity(
            entity = missingBackendEp,
            expectedScopeKey = scope,
            expectedSourceId = "src_3",
            expectedProviderId = prov,
            expectedBackendEpisodeId = "be_ep_3"
        )
        assertTrue(resMissingBackendEp is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.EPISODE_ID_MISMATCH, (resMissingBackendEp as SourceValidationResult.Invalid).code)

        val mismatchedBackendEp = valid.copy(backendEpisodeId = "be_ep_999")
        val resMismatchBackendEp = SourceIdentityValidator.validateCachedSourceEntity(
            entity = mismatchedBackendEp,
            expectedScopeKey = scope,
            expectedSourceId = "src_3",
            expectedProviderId = prov,
            expectedBackendEpisodeId = "be_ep_3"
        )
        assertTrue(resMismatchBackendEp is SourceValidationResult.Invalid)
        assertEquals(SourceDiagnosticCode.EPISODE_ID_MISMATCH, (resMismatchBackendEp as SourceValidationResult.Invalid).code)
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
        // Seed OldProvider with shared sourceId in Room + Memory
        db.streamCacheDao().upsertProviders(listOf(StreamProviderEntity("$scope|p|$oldProv", scope, oldProv, "OldProvider", sourceCount = 1)))
        db.streamCacheDao().upsertSources(
            listOf(
                StreamSourceEntity(
                    sourceDocKey = "$scope|p|$oldProv|s|$sharedSourceId",
                    scopeKey = scope,
                    providerId = oldProv,
                    sourceId = sharedSourceId,
                    sourceName = "OldProvider",
                    backendEpisodeId = "old_be_ep",
                    status = StreamRecordStatus.MISSING_URL.name,
                    expiresAt = "2099-01-01T00:00:00Z"
                )
            )
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
        repo.discoverStreamsForScope(animeId, 1, 1, "ep_3004_1", forceRefresh = true).getOrThrow()

        val newEntity = db.streamCacheDao().getLatestActiveSourceForProviderInScope(scope, newProv, sharedSourceId)
        assertNotNull(newEntity)
        assertEquals("NewProvider", newEntity!!.sourceName)
        assertEquals(StreamRecordStatus.MISSING_URL.name, newEntity.status)

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
        db.episodeDao().upsertEpisodes(listOf(EpisodeEntry("ep_3005_1", animeId, 1, 1)))
        db.streamCacheDao().upsertSources(
            listOf(
                StreamSourceEntity(
                    sourceDocKey = "$scope|p|$provA|s|$sharedSourceId",
                    scopeKey = scope,
                    providerId = provA,
                    sourceId = sharedSourceId,
                    sourceName = "ProviderA",
                    backendEpisodeId = "be_ep_3005",
                    status = StreamRecordStatus.MISSING_URL.name,
                    expiresAt = "2099-01-01T00:00:00Z"
                ),
                StreamSourceEntity(
                    sourceDocKey = "$scope|p|$provB|s|$sharedSourceId",
                    scopeKey = scope,
                    providerId = provB,
                    sourceId = sharedSourceId,
                    sourceName = "ProviderB",
                    backendEpisodeId = "be_ep_3005",
                    status = StreamRecordStatus.MISSING_URL.name,
                    expiresAt = "2099-01-01T00:00:00Z"
                )
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
}
