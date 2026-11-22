package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.identity.EpisodeSourceEpisodeIds
import com.example.data.local.AppDatabase
import com.example.data.local.EpisodeEntry
import com.example.data.remote.AniListCoverImage
import com.example.data.remote.AniListData
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLResponse
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.AniListMedia
import com.example.data.remote.AniListPage
import com.example.data.remote.AniListTitle
import com.example.data.remote.DirectPlayData
import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.DirectPlayResponse
import com.example.data.remote.NetworkModule
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
import com.example.data.repository.EpisodeCatalogState
import com.example.data.repository.EpisodeSourceResponseState
import com.example.ui.viewmodels.DetailViewModel
import com.example.ui.viewmodels.PlayerViewModel
import com.example.ui.viewmodels.SearchViewModel
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Response
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2ApiAndEpisodeSourcesTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            runCatching { FirebaseApp.initializeApp(context) }
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        setRepositorySingleton(null)
    }

    private fun setRepositorySingleton(instance: AnimeRepository?) {
        val field = AnimeRepository::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, instance)
    }

    private class RecordingBackendApi : LegacyFixtureApi {
        val searchQueries = CopyOnWriteArrayList<Triple<String, Int?, String?>>()
        val detailCalls = CopyOnWriteArrayList<String>()
        val episodeListCalls = CopyOnWriteArrayList<String>()
        val episodeFullCalls = CopyOnWriteArrayList<Pair<String, String>>()
        val directPlayRequests = CopyOnWriteArrayList<String>()

        var omitInlineEpisodesInDetails = false

        override suspend fun searchAnime(query: String, limit: Int?, cursor: String?): Response<ProphecySearchResponse> {
            searchQueries += Triple(query, limit, cursor)
            return when {
                query.contains("Dandadan", ignoreCase = true) -> Response.success(
                    ProphecySearchResponse(
                        results = listOf(
                            ProphecySearchResultItem(
                                id = "be_anime_dandadan",
                                canonicalId = null,
                                title = "Dandadan",
                                mediaType = "anime",
                                year = "2024",
                                posterUrl = "https://cdn.example/dandadan.jpg",
                                seasonCount = 2,
                                episodeCount = 12,
                                status = "FINISHED"
                            )
                        ),
                        nextCursor = null
                    )
                )
                query.contains("Frieren", ignoreCase = true) && cursor == null -> Response.success(
                    ProphecySearchResponse(
                        results = listOf(
                            ProphecySearchResultItem(
                                id = "be_anime_frieren",
                                canonicalId = "154587",
                                title = "Frieren: Beyond Journey's End",
                                mediaType = "anime",
                                year = "2023",
                                seasonCount = 1,
                                providerMappings = listOf(
                                    ProphecyProviderMappingDto(id = "154587", provider = "anilist", value = "154587")
                                )
                            )
                        ),
                        nextCursor = "cursor_frieren_2"
                    )
                )
                query.contains("Frieren", ignoreCase = true) && cursor == "cursor_frieren_2" -> Response.success(
                    ProphecySearchResponse(
                        results = listOf(
                            ProphecySearchResultItem(
                                id = "be_anime_frieren_movie",
                                title = "Frieren Recap",
                                mediaType = "movie"
                            )
                        ),
                        nextCursor = null
                    )
                )
                else -> Response.success(ProphecySearchResponse(results = emptyList(), nextCursor = null))
            }
        }

        override suspend fun getAnimeDetails(animeId: String): Response<ProphecyAnimeDetailsResponse> {
            detailCalls += animeId
            val seasons = if (omitInlineEpisodesInDetails) {
                listOf(ProphecySeasonDto(id = "be_season_2", number = 2, title = "Season 2", episodes = null))
            } else {
                listOf(
                    ProphecySeasonDto(
                        id = "be_season_1",
                        canonicalId = "canon_season_1",
                        number = 1,
                        title = "First Cour",
                        episodes = listOf(
                            ProphecyEpisodeDto(
                                id = "be_ep_101",
                                canonicalId = "canon_ep_101",
                                season = 1,
                                number = 1,
                                title = "That's How the Alien Encounter Started",
                                overview = "Initial details overview"
                            )
                        )
                    ),
                    ProphecySeasonDto(
                        id = "be_season_2",
                        canonicalId = "canon_season_2",
                        number = 2,
                        title = "Evil Eye Arc",
                        episodes = listOf(
                            ProphecyEpisodeDto(
                                id = "be_ep_201",
                                canonicalId = "canon_ep_201",
                                season = 2,
                                number = 1,
                                title = "Season 2 Premiere"
                            )
                        )
                    )
                )
            }
            return Response.success(
                ProphecyAnimeDetailsResponse(
                    id = animeId,
                    title = "Dandadan",
                    titleEnglish = "Dan Da Dan",
                    mediaType = "anime",
                    year = "2024",
                    description = "Backend synopsis for Dandadan",
                    genres = listOf("Action", "Comedy"),
                    status = "FINISHED",
                    seasonCount = seasons.size,
                    episodeCount = 24,
                    seasons = seasons,
                    partial = false
                )
            )
        }

        override suspend fun getAnimeEpisodes(animeId: String): Response<List<ProphecyEpisodeDto>> {
            episodeListCalls += animeId
            return Response.success(
                listOf(
                    ProphecyEpisodeDto(
                        id = "be_ep_fallback_s2_e1",
                        season = 2,
                        number = 1,
                        title = "Fallback Episode 1",
                        overview = "Fetched when details omitted inline episodes"
                    )
                )
            )
        }

        override suspend fun getEpisodeFull(animeId: String, episodeId: String): Response<ProphecyEpisodeFullResponse> {
            episodeFullCalls += animeId to episodeId
            return when (episodeId) {
                "be_ep_201", "canon_ep_201" -> Response.success(
                    ProphecyEpisodeFullResponse(
                        selectedEpisode = ProphecyEpisodeDto(
                            id = "be_ep_201",
                            canonicalId = "canon_ep_201",
                            season = 2,
                            number = 1,
                            title = "Evil Eye Awakens (Full)",
                            overview = "Detailed synopsis retrieved directly from /episodes/{id}/full"
                        ),
                        sources = listOf(
                            ProphecySourceDto(
                                id = "src_primary_201",
                                label = "VidStream 1080p",
                                quality = "1080p",
                                codec = "H264",
                                language = "Japanese",
                                sizeBytes = 420_000_000L,
                                downloadable = true,
                                expiresAt = "2099-01-01T00:00:00Z"
                            ),
                            ProphecySourceDto(
                                id = "src_alt_201",
                                label = "MegaCloud Dub",
                                quality = "720p",
                                codec = "H265",
                                language = "English",
                                sizeBytes = 250_000_000L,
                                downloadable = false,
                                expiresAt = "2099-01-01T00:00:00Z"
                            )
                        ),
                        sourcesStatus = "available",
                        sourcesPartial = false
                    )
                )
                else -> Response.success(
                    ProphecyEpisodeFullResponse(
                        selectedEpisode = ProphecyEpisodeDto(
                            id = episodeId,
                            season = 1,
                            number = 1,
                            title = "Episode 1 Title"
                        ),
                        sources = listOf(
                            ProphecySourceDto(
                                id = "src_default_101",
                                label = "DefaultCDN",
                                quality = "1080p",
                                language = "Japanese",
                                expiresAt = "2099-01-01T00:00:00Z"
                            )
                        ),
                        sourcesStatus = "available",
                        sourcesPartial = false
                    )
                )
            }
        }

        override suspend fun resolveDirectPlay(request: DirectPlayRequest): Response<DirectPlayResponse> {
            directPlayRequests += request.sourceId
            return Response.success(
                DirectPlayResponse(
                    success = true,
                    data = DirectPlayData(
                        mode = "direct",
                        sourceId = request.sourceId,
                        playbackUrl = "https://cdn.example/play/${request.sourceId}.m3u8",
                        urlType = "hls",
                        contentType = "application/vnd.apple.mpegurl",
                        expiresAt = "2099-01-01T00:00:00Z"
                    )
                )
            )
        }
    }

    private class RecordingAniListApi : AniListGraphQLService {
        val homeCalls = AtomicInteger(0)
        val searchCalls = AtomicInteger(0)
        val detailCalls = AtomicInteger(0)

        override suspend fun postQuery(request: AniListGraphQLRequest): Response<AniListGraphQLResponse> {
            val q = request.query
            return when {
                q.contains("trending:") -> {
                    homeCalls.incrementAndGet()
                    Response.success(
                        AniListGraphQLResponse(
                            data = AniListData(
                                trending = AniListPage(
                                    media = listOf(
                                        AniListMedia(
                                            id = 999,
                                            title = AniListTitle(romaji = "Home Only Title", english = "Home Only Title"),
                                            coverImage = AniListCoverImage(large = "https://img.anilist.co/999.jpg"),
                                            format = "TV",
                                            status = "FINISHED",
                                            episodes = 12
                                        )
                                    )
                                )
                            )
                        )
                    )
                }
                q.contains("Media(id:") -> {
                    detailCalls.incrementAndGet()
                    Response.success(AniListGraphQLResponse())
                }
                else -> {
                    searchCalls.incrementAndGet()
                    Response.success(AniListGraphQLResponse())
                }
            }
        }
    }

    private fun createRepo(
        backend: RecordingBackendApi = RecordingBackendApi(),
        aniList: RecordingAniListApi = RecordingAniListApi(),
        database: AppDatabase = db
    ): Triple<AnimeRepository, RecordingBackendApi, RecordingAniListApi> {
        val repo = AnimeRepository(
            database = database,
            prophecyApi = LegacyFixtureToV3Adapter(backend),
            aniListApi = aniList,
            applicationContext = context,
            d1DataSource = createTestD1DataSource()
        )
        return Triple(repo, backend, aniList)
    }

    @Test
    fun fix1_searchAndDetailsUseProphecyBackend_whileHomePreservesAniListExclusively() = runTest {
        val (repo, backend, aniList) = createRepo()

        // Home/Discovery uses AniList exclusively
        repo.refreshHomeDiscovery(force = true).getOrThrow()
        assertEquals(1, aniList.homeCalls.get())
        assertEquals(0, backend.searchQueries.size)
        assertEquals(0, backend.detailCalls.size)

        // Search uses GET /api/v1/anime/search without AniList
        val frierenP1 = repo.searchAnimePage("Frieren", page = 1).getOrThrow()
        assertFalse(frierenP1.hasNextPage)
        assertEquals(Triple("Frieren", 25, null), backend.searchQueries[0])
        assertEquals(0, aniList.searchCalls.get())

        val frierenResults = repo.observeSearchResults("Frieren").first()
        assertEquals(1, frierenResults.size)
        // Canonical AniList mapping persisted cleanly without overwriting entry ID flow
        val mapped = db.animeDao().getMappingForAniList(154587)
        assertEquals("be_anime_frieren", mapped?.backendAnimeId)

        // Search -> Details passes Anime ID cleanly and uses GET /api/v1/anime/{id}
        repo.searchAnimePage("Dandadan", page = 1).getOrThrow()
        val dandadan = repo.observeSearchResults("Dandadan").first().single()
        assertEquals("be_anime_dandadan", dandadan.id)
        assertEquals("be_anime_dandadan", dandadan.backendAnimeId)

        val status = repo.refreshAnimeDetails(dandadan.id).getOrThrow()
        assertEquals(EpisodeCatalogState.COMPLETE, status.state)
        assertEquals(listOf("be_anime_dandadan"), backend.detailCalls.toList())
        assertEquals(0, backend.episodeListCalls.size)
        assertEquals(0, aniList.detailCalls.get())

        // Verify Season IDs and nested Episode IDs mapped cleanly into Room
        val seasons = repo.observeSeasons(dandadan.id).first()
        assertEquals(listOf(1, 2), seasons.map { it.seasonNumber })
        assertEquals("canon_season_2", seasons[1].backendSeasonId)
        assertEquals("Evil Eye Arc", seasons[1].title)

        val s2Episodes = repo.observeEpisodes(dandadan.id, 2).first()
        assertEquals(1, s2Episodes.size)
        assertEquals("be_ep_201", s2Episodes.single().id)
        assertEquals("Season 2 Premiere", s2Episodes.single().title)
        assertEquals(listOf("be_ep_201"), EpisodeSourceEpisodeIds.read(s2Episodes.single()))
    }

    @Test
    fun fix1_detailsFallsBackToEpisodesEndpointOnlyWhenNestedEpisodesOmitted() = runTest {
        val backend = RecordingBackendApi().apply { omitInlineEpisodesInDetails = true }
        val (repo, _, _) = createRepo(backend = backend)

        repo.searchAnimePage("Dandadan", page = 1).getOrThrow()
        val status = repo.refreshAnimeDetails("be_anime_dandadan").getOrThrow()
        assertEquals(EpisodeCatalogState.COMPLETE, status.state)
        assertEquals(listOf("be_anime_dandadan"), backend.detailCalls.toList())
        assertEquals(listOf("be_anime_dandadan"), backend.episodeListCalls.toList())

        val s2Eps = repo.observeEpisodes("be_anime_dandadan", 2).first()
        assertEquals("be_ep_fallback_s2_e1", s2Eps.single().id)
        assertEquals("Fallback Episode 1", s2Eps.single().title)
    }

    @Test
    fun fix2_episodeFullRetrievesSelectedEpisodeDetailsAndSourcesWithoutDuplicateDetailsCalls() = runTest {
        val (repo, backend, _) = createRepo()

        repo.searchAnimePage("Dandadan", page = 1).getOrThrow()
        repo.refreshAnimeDetails("be_anime_dandadan").getOrThrow()
        assertEquals(1, backend.detailCalls.size)

        val s2Ep = repo.observeEpisodes("be_anime_dandadan", 2).first().single()

        // Discover Episode Sources -> uses /episodes/{episode_id}/full directly without duplicate getAnimeDetails/getAnimeEpisodes
        val snapshot = repo.discoverStreamsForScope(
            animeId = "be_anime_dandadan",
            seasonNumber = s2Ep.seasonNumber,
            episodeNumber = s2Ep.episodeNumber,
            catalogEpisodeId = s2Ep.id
        ).getOrThrow()

        assertEquals(1, backend.detailCalls.size)
        assertEquals(0, backend.episodeListCalls.size)
        assertEquals(listOf("be_anime_dandadan" to "be_ep_201"), backend.episodeFullCalls.toList())
        assertEquals(EpisodeSourceResponseState.AVAILABLE, snapshot.state)
        assertEquals(listOf("src_primary_201", "src_alt_201"), snapshot.sources.map { it.id })

        // Room Episode row preserves canonical title from seasons/episodes
        val persistedEp = db.episodeDao().getEpisodeById("be_ep_201")!!
        assertEquals("Season 2 Premiere", persistedEp.title)

        // Selecting a specific source passes its exact Source ID to /api/v1/play/direct
        val resolved = repo.resolvePlayableStream(
            animeId = "be_anime_dandadan",
            seasonNumber = 2,
            episodeNumber = 1,
            catalogEpisodeId = "be_ep_201",
            preferredSourceId = "src_alt_201"
        ).getOrThrow()
        assertEquals("src_alt_201", resolved.source.id)
        assertEquals(listOf("src_alt_201"), backend.directPlayRequests.toList())
    }

    @Test
    fun fix2_moshiParsesFallbackSourceKeysAndSelectedEpisodeMetadata() {
        val rawJson = """
            {
              "selected_episode": {
                "id": "ep_raw_9",
                "season": 2,
                "number": 9,
                "title": "Crimson Clash",
                "overview": "Episode 9 full overview"
              },
              "sources": [
                {
                  "source_id": "src_fallback_9",
                  "provider_name": "FastMirror CDN",
                  "quality": "1080p",
                  "codec": "HEVC",
                  "audio_language": "Japanese",
                  "size_bytes": 512000000,
                  "downloadable": true
                }
              ],
              "sources_status": "available",
              "sources_partial": false
            }
        """.trimIndent()

        val parsed = NetworkModule.moshi
            .adapter(ProphecyEpisodeFullResponse::class.java)
            .fromJson(rawJson)!!

        assertEquals("ep_raw_9", parsed.selectedEpisode?.id)
        assertEquals("Crimson Clash", parsed.selectedEpisode?.title)
        assertEquals("Episode 9 full overview", parsed.selectedEpisode?.overview)
        val source = parsed.sources!!.single()
        assertEquals("src_fallback_9", source.id)
        assertEquals("FastMirror CDN", source.displayProviderName)
        assertEquals("1080p", source.quality)
        assertEquals("HEVC", source.codec)
        assertEquals("Japanese", source.displayAudioLanguage)
        assertEquals(512000000L, source.sizeBytes)
        assertEquals(true, source.downloadable)
    }

    @Test
    fun fix2_viewModelsFlowFromDebouncedSearchToEpisodeSourcesAndPreferredSourcePlayer() {
        val singletonDb = AppDatabase.getInstance(context)
        val backend = RecordingBackendApi()
        val aniList = RecordingAniListApi()
        val (repo, _, _) = createRepo(backend = backend, aniList = aniList, database = singletonDb)
        setRepositorySingleton(repo)

        val searchVm = SearchViewModel(context.applicationContext as Application)
        searchVm.onQueryChange("D")
        searchVm.onQueryChange("Da")
        searchVm.onQueryChange("Dandadan")

        val searchDeadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < searchDeadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(300L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (backend.searchQueries.isNotEmpty() && !searchVm.isSearching.value) break
            Thread.sleep(25L)
        }
        // Debounce prevented keystroke request spam ("D", "Da")
        assertEquals(listOf("Dandadan"), backend.searchQueries.map { it.first })

        // DetailViewModel -> refreshDetails & openSourceChooser
        val detailVm = DetailViewModel(context.applicationContext as Application, "be_anime_dandadan", initialSeason = 2)
        val epDeadline = System.currentTimeMillis() + 3_000L
        var targetEp: EpisodeEntry? = null
        while (System.currentTimeMillis() < epDeadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            targetEp = runBlocking { singletonDb.episodeDao().getEpisodeById("be_ep_201") }
            if (targetEp != null) break
            Thread.sleep(25L)
        }
        assertNotNull(targetEp)

        detailVm.openSourceChooser(targetEp!!)
        val srcDeadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < srcDeadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (detailVm.episodeSources.value.isNotEmpty() && !detailVm.isLoadingSources.value) break
            Thread.sleep(25L)
        }
        assertEquals(listOf("src_primary_201", "src_alt_201"), detailVm.episodeSources.value.map { it.id })
        assertEquals("Season 2 Premiere", detailVm.selectedEpisodeForSources.value?.title)

        // Selecting backup source ID routes to PlayerViewModel and preserves initialPreferredSourceId
        val playerVm = PlayerViewModel(
            application = context.applicationContext as Application,
            animeId = "be_anime_dandadan",
            initialEpisodeId = "be_ep_201",
            initialPreferredSourceId = "src_alt_201"
        )
        val playDeadline = System.currentTimeMillis() + 4_000L
        while (System.currentTimeMillis() < playDeadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (backend.directPlayRequests.isNotEmpty() &&
                !playerVm.isResolving.value &&
                !com.example.data.repository.EpisodeResolutionCoordinator.isForegroundResolutionActive()
            ) {
                break
            }
            Thread.sleep(25L)
        }
        assertEquals("be_ep_201", playerVm.currentEpisode.value?.id)
        assertEquals("src_alt_201", backend.directPlayRequests.firstOrNull())

        playerVm.playerManager.release()
        playerVm.preResolveManager.clear()
        runBlocking {
            singletonDb.episodeDao().deleteEpisodesForAnime("be_anime_dandadan")
            singletonDb.episodeDao().deleteSeasonsForAnime("be_anime_dandadan")
            singletonDb.openHelper.writableDatabase.execSQL(
                "DELETE FROM anime_entries WHERE id IN ('be_anime_dandadan', 'be_anime_frieren', 'be_anime_frieren_movie')"
            )
            singletonDb.openHelper.writableDatabase.execSQL(
                "DELETE FROM catalog_mappings WHERE aniListId = 154587"
            )
        }
    }
}
