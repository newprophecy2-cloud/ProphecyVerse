package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.remote.AniListData
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLResponse
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.remote.V3AnimeDetailsResponse
import com.example.data.remote.V3BackendGateway
import com.example.data.remote.V3EpisodeDto
import com.example.data.remote.V3EpisodesResponse
import com.example.data.remote.V3ProviderSourceGroupDto
import com.example.data.remote.V3ResolveRequest
import com.example.data.remote.V3ResolveResponse
import com.example.data.remote.V3SearchResponse
import com.example.data.remote.V3SearchResultItem
import com.example.data.remote.V3SeasonDto
import com.example.data.remote.V3SeasonsResponse
import com.example.data.remote.V3SourceItemDto
import com.example.data.remote.V3SourcesResponse
import com.example.data.remote.session.PvSessionManager
import com.example.data.repository.AnimeRepository
import com.example.playback.core.PlaybackEngineState
import com.example.playback.core.PlaybackFailureCategory
import com.example.playback.core.PlaybackRequest
import com.example.playback.libvlc.LibVlcEngineAdapter
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class LibVlcAndMovieRepairTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        PvSessionManager.clearSession()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        PvSessionManager.clearSession()
        db.close()
    }

    @Test
    fun homeEntryAutoMintsSessionWhenNullAndRetriesOnceOn401() = runTest {
        val searchCalls = AtomicInteger(0)
        val detailCalls = AtomicInteger(0)
        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String): Response<V3SearchResponse> {
                searchCalls.incrementAndGet()
                return Response.success(
                    V3SearchResponse(
                        status = "success",
                        sessionId = "minted-sess-${searchCalls.get()}",
                        query = query,
                        results = emptyList()
                    )
                )
            }

            override suspend fun getAnimeDetails(animeId: String): Response<V3AnimeDetailsResponse> {
                val n = detailCalls.incrementAndGet()
                // Simulate expired session on first attempt after minting
                if (n == 1) {
                    return Response.error(401, """{"error":"SESSION_EXPIRED"}""".toResponseBody(null))
                }
                return Response.success(
                    V3AnimeDetailsResponse(
                        status = "success",
                        animeId = animeId,
                        title = "Bleach",
                        totalSeasons = 1,
                        verifiedEpisodes = 366
                    )
                )
            }

            override suspend fun getAnimeSeasons(animeId: String) =
                Response.success(V3SeasonsResponse(animeId = animeId, seasons = emptyList()))

            override suspend fun getSeasonEpisodes(seasonId: String) =
                Response.success(V3EpisodesResponse(seasonId = seasonId, episodes = emptyList()))

            override suspend fun getEpisodeSources(episodeId: String) =
                Response.success(V3SourcesResponse(episodeId = episodeId))

            override suspend fun resolveEpisode(episodeId: String, request: V3ResolveRequest) =
                Response.error<V3ResolveResponse>(404, "".toResponseBody(null))
        }

        val gateway = V3BackendGateway(api)
        val resp = gateway.getDetailsCompat("NP-A-BLEACH")
        assertTrue(resp.isSuccessful)
        assertEquals("NP-A-BLEACH", resp.body()?.id)
        assertEquals(2, searchCalls.get())
        assertEquals(2, detailCalls.get())
        assertEquals("minted-sess-2", PvSessionManager.currentSessionId())
    }

    @Test
    fun movieRefreshPersistsCanonicalEpisodeAndDiscoversSources() = runTest {
        val api = object : ProphecyVerseApiService {
            override suspend fun searchAnime(query: String) = Response.success(
                V3SearchResponse(
                    status = "success",
                    sessionId = "movie-sess",
                    query = query,
                    results = listOf(
                        V3SearchResultItem(
                            animeId = "NP-A-0673EC",
                            title = "Your Name.",
                            mediaType = "movie",
                            year = 2016,
                            totalSeasons = 1,
                            verifiedEpisodes = 1
                        )
                    )
                )
            )

            override suspend fun getAnimeDetails(animeId: String) = Response.success(
                V3AnimeDetailsResponse(
                    status = "success",
                    animeId = "NP-A-0673EC",
                    title = "Your Name.",
                    mediaType = "movie",
                    year = 2016,
                    totalSeasons = 1,
                    verifiedEpisodes = 1
                )
            )

            override suspend fun getAnimeSeasons(animeId: String) = Response.success(
                V3SeasonsResponse(
                    status = "success",
                    animeId = "NP-A-0673EC",
                    totalSeasons = 1,
                    verifiedEpisodes = 1,
                    seasons = listOf(V3SeasonDto(seasonId = "NP-S-0673EC-001", seasonNumber = 1, verifiedEpisodes = 1))
                )
            )

            override suspend fun getSeasonEpisodes(seasonId: String) = Response.success(
                V3EpisodesResponse(
                    status = "success",
                    seasonId = seasonId,
                    totalEpisodes = 1,
                    verifiedEpisodes = 1,
                    episodes = listOf(
                        V3EpisodeDto(
                            episodeId = "NP-E-0673EC-0001",
                            episodeNumber = 1,
                            title = "Your Name.",
                            verification = "verified"
                        )
                    )
                )
            )

            override suspend fun getEpisodeSources(episodeId: String) = Response.success(
                V3SourcesResponse(
                    status = "success",
                    episodeId = episodeId,
                    totalSources = 1,
                    providers = listOf(
                        V3ProviderSourceGroupDto(
                            provider = "MovieBox",
                            sources = listOf(
                                V3SourceItemDto(
                                    sourceId = "src-1",
                                    provider = "MovieBox",
                                    streamUrl = "https://cdn.example.com/your-name.mp4",
                                    quality = "1080p",
                                    playabilityStatus = "manifest_validated",
                                    available = true
                                )
                            )
                        )
                    )
                )
            )

            override suspend fun resolveEpisode(episodeId: String, request: V3ResolveRequest) = Response.success(
                V3ResolveResponse(
                    status = "success",
                    episodeId = episodeId,
                    sourceId = request.sourceId,
                    streamUrl = "https://cdn.example.com/your-name.mp4",
                    playabilityStatus = "manifest_validated"
                )
            )
        }

        val aniList = object : AniListGraphQLService {
            override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> =
                Response.success(AniListGraphQLResponse(data = AniListData()))
        }

        val repo = AnimeRepository(
            database = db,
            prophecyApi = api,
            aniListApi = aniList,
            applicationContext = ApplicationProvider.getApplicationContext()
        )
        db.animeDao().upsertAnime(
            AnimeEntry(
                id = "NP-A-0673EC",
                backendAnimeId = "NP-A-0673EC",
                preferredTitle = "Your Name.",
                format = "MOVIE",
                mediaCategory = "MOVIE",
                isPlayable = true
            )
        )

        repo.refreshAnimeDetails("NP-A-0673EC").getOrThrow()

        val sources = repo.discoverStreamsForScope(
            animeId = "NP-A-0673EC",
            seasonNumber = 0,
            episodeNumber = 1,
            catalogEpisodeId = "movie_NP-A-0673EC"
        ).getOrThrow()
        assertEquals(1, sources.sources.size)
        assertEquals("src-1", sources.sources[0].id)
    }

    @Test
    fun libVlcAdapterRejectsInvalidMediaUrlCleanly() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val adapter = LibVlcEngineAdapter(ctx)
        adapter.prepare(PlaybackRequest(mediaUri = "javascript:alert(1)"))
        assertEquals(PlaybackEngineState.ERROR, adapter.state.value)
        assertEquals(PlaybackFailureCategory.INVALID_MEDIA_URL, adapter.error.value?.category)
        adapter.release()
        assertEquals(PlaybackEngineState.RELEASED, adapter.state.value)
    }
}
