package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.firebase.SharedCatalogMetadata
import com.example.data.firebase.SharedCatalogRepository
import com.example.data.identity.EpisodeCanonicalizer
import com.example.data.identity.EpisodeSourceEpisodeIds
import com.example.data.identity.CanonicalAnimeIdentity
import com.example.data.identity.CanonicalAnimeRoute
import com.example.data.local.AnimeAlias
import com.example.data.local.AnimeDao
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.CatalogMapping
import com.example.data.local.EpisodeDao
import com.example.data.local.EpisodeEntry
import com.example.data.local.EpisodeSourceCandidateRecord
import com.example.data.local.EpisodeSourceSnapshotRecord
import com.example.data.local.PlaybackHistory
import com.example.data.local.PlaybackHistoryDao
import com.example.data.local.SearchDao
import com.example.data.local.SearchQuery
import com.example.data.local.SearchResultRef
import com.example.data.local.SeasonEntry
import com.example.data.local.StreamCacheDao
import com.example.data.local.StreamProviderEntity
import com.example.data.local.StreamSourceEntity
import com.example.data.local.WatchlistDao
import com.example.data.local.WatchlistEntry
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.AniListMedia
import com.example.data.remote.AniListQueries
import com.example.data.remote.AniListRelatedMediaNode
import com.example.data.remote.DirectPlayData
import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.NetworkModule
import com.example.data.remote.ProphecyAnimeDetailsResponse
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecySeasonDto
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import com.example.data.streaming.ContentIdentityHelper
import com.example.data.streaming.DiscoveryStatus
import com.example.data.streaming.FirestoreStreamingRepository
import com.example.data.streaming.MonotonicMetrics
import com.example.data.streaming.ResolvedStreamPlayback
import com.example.data.streaming.SafePlaybackHeaders
import com.example.data.streaming.StreamRecordStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

internal fun isValidPlaybackUrl(value: String): Boolean = SafePlaybackHeaders.isValidStreamUrl(value)

internal fun uniqueAniListMedia(items: List<AniListMedia>): List<AniListMedia> = items.distinctBy { it.id }

/** Map only explicit backend media types; unknown values remain unclassified. */
internal fun backendMediaCategory(mediaType: String?): String? = when (mediaType?.trim()?.lowercase()) {
    "anime" -> "ANIME"
    "movie", "film", "feature_film", "anime_movie" -> "MOVIE"
    "kdrama", "k-drama", "korean_drama" -> "KDRAMA"
    "series", "web_series", "tv_series" -> "SERIES"
    else -> null
}

internal fun preserveMovieMediaCategory(
    existingCategory: String?,
    existingFormat: String?,
    detailMediaType: String?
): String? {
    if (existingCategory.equals("MOVIE", ignoreCase = true) ||
        existingFormat.equals("MOVIE", ignoreCase = true) ||
        existingFormat.equals("FILM", ignoreCase = true)
    ) {
        return "MOVIE"
    }
    return backendMediaCategory(detailMediaType) ?: existingCategory
}

internal fun isAuthoritativeEpisodeSnapshot(
    partial: Boolean?,
    seasons: List<ProphecySeasonDto>?
): Boolean = partial == false && !seasons.isNullOrEmpty() && seasons.all { season ->
    season.number > 0 && season.episodes != null && season.episodes.all { episode ->
        episode.id.isNotBlank() && episode.number > 0 &&
            (episode.season == null || episode.season == season.number)
    }
}

class AnimeRepository(
    private val database: AppDatabase,
    private val prophecyApi: ProphecyVerseApiService = NetworkModule.prophecyVerseApi,
    private val aniListApi: AniListGraphQLService = NetworkModule.aniListApi,
    private val applicationContext: Context? = null,
    val streamingRepository: FirestoreStreamingRepository = FirestoreStreamingRepository(database.streamCacheDao())
) {
    private val animeDao: AnimeDao = database.animeDao()
    private val episodeDao: EpisodeDao = database.episodeDao()
    private val searchDao: SearchDao = database.searchDao()
    private val watchlistDao: WatchlistDao = database.watchlistDao()
    private val historyDao: PlaybackHistoryDao = database.playbackHistoryDao()
    private val streamCacheDao: StreamCacheDao = database.streamCacheDao()
    private val sharedCatalogRepository: SharedCatalogRepository? by lazy {
        runCatching { SharedCatalogRepository(database.sharedCatalogDao()) }.getOrNull()
    }
    private val sharedCatalogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    suspend fun processPendingSharedCatalogQueue(): Int =
        sharedCatalogRepository?.processPendingQueue() ?: 0
    private fun queueBackendCatalogMetadata(details: ProphecyAnimeDetailsResponse) {
        sharedCatalogScope.launch {
            runCatching {
                val repo = sharedCatalogRepository ?: return@launch
                SharedCatalogMetadata.fromDetail(details)?.let { repo.acceptBackendMetadata(it) }
                if (details.mediaType?.equals("movie", ignoreCase = true) != true) {
                    details.seasons.orEmpty().forEach { season ->
                    SharedCatalogMetadata.fromSeason(details.id, details.canonicalId, season)?.let { repo.acceptBackendMetadata(it) }
                    season.episodes.orEmpty().forEach { episode ->
                        val normalized = episode.copy(season = episode.season ?: season.number)
                        SharedCatalogMetadata.fromEpisode(details.id, details.canonicalId, season.number, normalized)?.let { repo.acceptBackendMetadata(it) }
                    }
                    }
                }
                details.genres.orEmpty().forEach { genre ->
                    SharedCatalogMetadata.fromGenre(genre)?.let { repo.acceptBackendMetadata(it) }
                }
            }.onFailure { error ->
                Log.w("AnimeRepository", "Shared catalog metadata queued locally but acceptance failed: ${error.message?.take(240)}")
            }
        }
    }

    data class SearchPageResult(
        val totalReturnedInPage: Int,
        val currentPage: Int,
        val hasNextPage: Boolean
    )

    data class EpisodeSourceSnapshot(
        val sources: List<ProphecySourceDto>,
        val state: EpisodeSourceResponseState,
        val backendStatus: String? = null,
        val errorMessage: String? = null,
        val fetchedAtMs: Long,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val apiCandidateCount: Int = sources.size,
        val parsedCandidateCount: Int = apiCandidateCount,
        val expiredCandidateCount: Int = 0,
        val exactDuplicateCount: Int = 0
    ) {
        val isStale: Boolean
            get() = state == EpisodeSourceResponseState.STALE ||
                System.currentTimeMillis() - fetchedAtMs > 2 * 60 * 1000L
    }

    private data class SourceCacheEntry(val snapshot: EpisodeSourceSnapshot)
    private data class SourceRetryState(val failures: Int, val retryAtMs: Long)
    private data class ResolvedCacheEntry(val data: DirectPlayData, val cachedAtMs: Long)

    private val memorySourcesCache = LinkedHashMap<String, SourceCacheEntry>(16, 0.75f, true)
    private val sourceCacheMutex = Mutex()
    private val resolvedPlaybackCache = ConcurrentHashMap<String, ResolvedCacheEntry>()
    private val directRetryMutex = Mutex()
    private val directRetryStates = ConcurrentHashMap<String, SourceRetryState>()
    private val sourceRetryMutex = Mutex()
    private val sourceRetryStates = ConcurrentHashMap<String, SourceRetryState>()
    private val resolveMutexes = ConcurrentHashMap<String, Mutex>()
    private val sourceCacheTtlMs = 5 * 60 * 1000L
    private val maxMemorySourceSnapshots = 120

    private val homeRefreshMutex = Mutex()
    private var lastHomeRefreshTimestamp: Long = 0L

    // Database observation flows (Room-First)
    fun observeTrending(): Flow<List<AnimeEntry>> = animeDao.observeTrending()
    fun observeSeasonal(): Flow<List<AnimeEntry>> = animeDao.observeSeasonal()
    fun observePopular(): Flow<List<AnimeEntry>> = animeDao.observePopular()
    fun observeKDramas(): Flow<List<AnimeEntry>> = animeDao.observeKDramas()
    fun observeMovies(): Flow<List<AnimeEntry>> = animeDao.observeMovies()
    fun observeWebSeries(): Flow<List<AnimeEntry>> = animeDao.observeWebSeries()
    fun observeAnimeById(id: String): Flow<AnimeEntry?> = animeDao.observeAnimeById(id)
    fun observeSeasons(animeId: String): Flow<List<SeasonEntry>> = episodeDao.observeSeasons(animeId)
    fun observeEpisodes(animeId: String, seasonNumber: Int): Flow<List<EpisodeEntry>> =
        episodeDao.observeEpisodes(animeId, seasonNumber).map {
            EpisodeCanonicalizer.canonicalize(emptyList(), it)
        }

    fun observeEpisodeWindow(
        animeId: String,
        seasonNumber: Int,
        firstEpisode: Int,
        episodeLimit: Int
    ): Flow<List<EpisodeEntry>> = episodeDao.observeEpisodeWindow(animeId, seasonNumber, firstEpisode, episodeLimit).map {
        EpisodeCanonicalizer.canonicalize(emptyList(), it)
    }

    fun observeAllEpisodes(animeId: String): Flow<List<EpisodeEntry>> =
        episodeDao.observeAllEpisodes(animeId).map { EpisodeCanonicalizer.canonicalize(emptyList(), it) }

    fun observeFirstEpisode(animeId: String): Flow<EpisodeEntry?> =
        observeAllEpisodes(animeId).map { it.firstOrNull() }

    fun observeSearchResults(query: String): Flow<List<AnimeEntry>> {
        val norm = TitleMatcher.normalize(query)
        return searchDao.observeSearchResults(norm)
    }

    fun observeLocalSearchFallback(query: String): Flow<List<AnimeEntry>> {
        val norm = TitleMatcher.normalize(query)
        return animeDao.searchLocal(norm)
    }

    /** Resolve only identities proven by AniList relation metadata; never guess from title text. */
    suspend fun resolveCanonicalRoute(animeId: String, requestedSeason: Int? = null): CanonicalAnimeRoute =
        withContext(Dispatchers.IO) {
            val source = animeDao.getAnimeById(animeId)
                ?: return@withContext CanonicalAnimeRoute(animeId, requestedSeason, false)
            val route = CanonicalAnimeIdentity.routeFor(source, animeId, requestedSeason)
            if (!route.resolved) return@withContext route
            migrateAliasToCanonical(source, route.animeId)
            route
        }

    private suspend fun migrateAliasToCanonical(source: AnimeEntry, canonicalId: String) {
        if (source.id == canonicalId) return
        database.withTransaction {
            if (animeDao.getAnimeById(canonicalId) == null) {
                val title = source.canonicalTitle?.takeIf { it.isNotBlank() } ?: source.preferredTitle
                animeDao.upsertAnime(source.copy(
                    id = canonicalId,
                    aniListId = canonicalId.removePrefix("anilist_").toIntOrNull() ?: source.aniListId,
                    preferredTitle = title,
                    titleEnglish = source.canonicalTitle ?: source.titleEnglish,
                    titleRomaji = source.canonicalTitle ?: source.titleRomaji,
                    canonicalAnimeId = null,
                    canonicalTitle = null,
                    canonicalSeasonNumber = null,
                    cumulativeEpisodeOffset = null,
                    isTrending = false,
                    isSeasonal = false,
                    isPopular = false,
                    updatedAt = System.currentTimeMillis()
                ))
            }
            episodeDao.rekeyAnime(source.id, canonicalId)
            database.downloadDao().rekeyAnime(source.id, canonicalId)
            val aliasWatchlist = watchlistDao.getWatchlistEntry(source.id)
            val canonicalWatchlist = watchlistDao.getWatchlistEntry(canonicalId)
            if (aliasWatchlist != null && (canonicalWatchlist == null || aliasWatchlist.updatedAt >= canonicalWatchlist.updatedAt)) {
                watchlistDao.upsertWatchlistEntry(aliasWatchlist.copy(animeId = canonicalId))
            }
            if (aliasWatchlist != null) watchlistDao.deleteWatchlistEntry(source.id)
            val aliasHistories = historyDao.getAllHistoryForAnime(source.id)
            for (aliasHistory in aliasHistories) {
                val canonicalHistory = historyDao.getHistoryForEpisode(canonicalId, aliasHistory.episodeId)
                if (canonicalHistory == null || aliasHistory.lastPlayedAt >= canonicalHistory.lastPlayedAt) {
                    historyDao.upsertHistory(
                        aliasHistory.copy(
                            id = "history_${canonicalId}_${aliasHistory.episodeId}",
                            animeId = canonicalId,
                            completed = aliasHistory.completed || (canonicalHistory?.completed == true),
                            animeTitle = aliasHistory.animeTitle ?: canonicalHistory?.animeTitle,
                            posterUrl = aliasHistory.posterUrl ?: canonicalHistory?.posterUrl,
                            mediaType = aliasHistory.mediaType ?: canonicalHistory?.mediaType
                        )
                    )
                } else if (!canonicalHistory.completed && aliasHistory.completed) {
                    historyDao.upsertHistory(canonicalHistory.copy(completed = true))
                }
            }
            if (aliasHistories.isNotEmpty()) historyDao.deleteHistoryForAnime(source.id)
            animeDao.demoteCanonicalAlias(source.id)
        }
    }

    fun observeWatchlist(): Flow<List<WatchlistEntry>> = watchlistDao.observeAllWatchlist()
    fun observeWatchlistByStatus(status: String): Flow<List<WatchlistEntry>> =
        watchlistDao.observeWatchlistByStatus(status)

    fun observeWatchlistEntry(animeId: String): Flow<WatchlistEntry?> =
        watchlistDao.observeWatchlistEntry(animeId)

    fun observeHistory(): Flow<List<PlaybackHistory>> = historyDao.observeHistory()
    fun observeContinueWatching(): Flow<List<PlaybackHistory>> = historyDao.observeContinueWatching()
    fun observeLastPlayedForAnime(animeId: String): Flow<PlaybackHistory?> =
        historyDao.observeLastPlayedForAnime(animeId)

    fun observeProfile(): Flow<com.example.data.local.LocalProfile?> = database.localProfileDao().observeProfile()
    suspend fun getProfile(): com.example.data.local.LocalProfile? = database.localProfileDao().getProfile()
    suspend fun updateProfile(name: String, avatarId: String, bio: String) {
        withContext(Dispatchers.IO) {
            val current = database.localProfileDao().getProfile() ?: com.example.data.local.LocalProfile()
            database.localProfileDao().upsertProfile(
                current.copy(
                    name = name,
                    avatarId = avatarId,
                    bio = bio,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    // ============================================================================================
    // 1. ANILIST CATALOG METADATA PIPELINE (Home, Paginated Search, Media Details)
    //    Strict Rule: Zero Backend metadata calls; zero Firestore metadata reads/writes.
    // ============================================================================================

    suspend fun refreshHomeDiscovery(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        homeRefreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && (now - lastHomeRefreshTimestamp < 5 * 60 * 1000L)) {
                return@withContext Result.success(Unit)
            }

            val reqStarted = MonotonicMetrics.nowMs()
            try {
                val cal = Calendar.getInstance()
                val year = cal.get(Calendar.YEAR)
                val month = cal.get(Calendar.MONTH)
                val season = when (month) {
                    11, 0, 1 -> "WINTER"
                    2, 3, 4 -> "SPRING"
                    5, 6, 7 -> "SUMMER"
                    else -> "FALL"
                }

                val response = aniListApi.postQuery(
                    AniListGraphQLRequest(
                        query = AniListQueries.DISCOVERY_FEEDS,
                        variables = mapOf("season" to season, "seasonYear" to year)
                    )
                )
                MonotonicMetrics.recordDuration("anilist_request_ms", reqStarted, "op=home_feeds status=${response.code()}")

                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("AniList home feeds failed: HTTP ${response.code()}"))
                }

                val data = response.body()?.data
                    ?: return@withContext Result.failure(IllegalStateException("AniList home feeds returned empty data"))

                val trendingMedia = data.trending?.media.orEmpty()
                val seasonalMedia = data.seasonal?.media.orEmpty()
                val popularMedia = data.popular?.media.orEmpty()
                val movieMedia = data.movies?.media.orEmpty()

                val allMedia = uniqueAniListMedia(trendingMedia + seasonalMedia + popularMedia + movieMedia)
                val trendingIds = trendingMedia.map { it.id }.toSet()
                val seasonalIds = seasonalMedia.map { it.id }.toSet()
                val popularIds = popularMedia.map { it.id }.toSet()
                val movieIds = movieMedia.map { it.id }.toSet()

                val entriesToUpsert = mutableListOf<AnimeEntry>()
                val aliasesToInsert = mutableListOf<AnimeAlias>()

                for (m in allMedia) {
                    val existing = animeDao.getAnimeByAniListId(m.id)
                        ?: animeDao.getAnimeById("anilist_${m.id}")
                    val mapping = animeDao.getMappingForAniList(m.id)
                    val preferredTitle = m.title?.english ?: m.title?.romaji ?: m.title?.native ?: "Anime #${m.id}"
                    val isMovieFormat = m.format.equals("MOVIE", ignoreCase = true) || m.id in movieIds
                    val canonicalInfo = inferShallowCanonicalInfo(m, existing)

                    val entry = AnimeEntry(
                        id = existing?.id ?: "anilist_${m.id}",
                        aniListId = m.id,
                        backendAnimeId = existing?.backendAnimeId ?: mapping?.backendAnimeId,
                        canonicalAnimeId = canonicalInfo.canonicalAnimeId ?: existing?.canonicalAnimeId,
                        canonicalTitle = canonicalInfo.canonicalTitle ?: existing?.canonicalTitle,
                        canonicalSeasonNumber = canonicalInfo.canonicalSeasonNumber ?: existing?.canonicalSeasonNumber,
                        cumulativeEpisodeOffset = canonicalInfo.cumulativeEpisodeOffset ?: existing?.cumulativeEpisodeOffset,
                        titleRomaji = m.title?.romaji,
                        titleEnglish = m.title?.english,
                        titleNative = m.title?.native,
                        preferredTitle = preferredTitle,
                        synopsis = m.description ?: existing?.synopsis,
                        year = (m.startDate?.year ?: m.seasonYear)?.toString() ?: existing?.year,
                        season = m.season ?: existing?.season,
                        format = m.format ?: existing?.format,
                        status = m.status ?: existing?.status,
                        genres = m.genres?.joinToString(", ") ?: existing?.genres.orEmpty(),
                        score = m.averageScore?.let { it / 10.0 } ?: existing?.score,
                        episodeCount = resolveKnownEpisodeCount(m, existing?.episodeCount),
                        duration = m.duration ?: existing?.duration,
                        studios = m.studios?.nodes?.firstOrNull()?.name ?: existing?.studios,
                        posterUrl = m.coverImage?.large ?: m.coverImage?.medium ?: existing?.posterUrl,
                        bannerUrl = m.bannerImage ?: existing?.bannerUrl,
                        isPlayable = true,
                        isTrending = m.id in trendingIds,
                        isSeasonal = m.id in seasonalIds,
                        isPopular = m.id in popularIds,
                        mediaCategory = if (isMovieFormat) "MOVIE" else "ANIME",
                        metadataCompleteness = existing?.metadataCompleteness ?: "UNKNOWN",
                        updatedAt = System.currentTimeMillis()
                    )
                    entriesToUpsert += entry
                    collectAliases(entry.id, m, aliasesToInsert)
                }

                val roomStarted = MonotonicMetrics.nowMs()
                animeDao.clearTrendingFlags()
                animeDao.clearSeasonalFlags()
                animeDao.clearPopularFlags()
                if (entriesToUpsert.isNotEmpty()) animeDao.upsertAnimeList(entriesToUpsert)
                if (aliasesToInsert.isNotEmpty()) {
                    animeDao.insertAliases(aliasesToInsert.distinctBy { it.animeId to it.alias })
                }
                MonotonicMetrics.recordDuration("room_transaction_ms", roomStarted, "op=home_feeds items=${entriesToUpsert.size}")

                lastHomeRefreshTimestamp = System.currentTimeMillis()
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                MonotonicMetrics.recordDuration("anilist_request_ms", reqStarted, "op=home_feeds error=${e::class.simpleName}")
                Result.failure(e)
            }
        }
    }

    suspend fun searchAnime(query: String): Result<Int> =
        searchAnimePage(query, page = 1).map { it.totalReturnedInPage }

    suspend fun searchAnimePage(query: String, page: Int = 1): Result<SearchPageResult> = withContext(Dispatchers.IO) {
        val normQuery = TitleMatcher.normalize(query)
        if (normQuery.isBlank()) return@withContext Result.success(SearchPageResult(0, 1, false))
        val targetPage = page.coerceAtLeast(1)

        try {
            searchMutexes.computeIfAbsent(normQuery) { Mutex() }.withLock {
                val reqStarted = MonotonicMetrics.nowMs()
                val resp = aniListApi.postQuery(
                    AniListGraphQLRequest(
                        query = AniListQueries.SEARCH_ANIME,
                        variables = mapOf("search" to query.trim(), "page" to targetPage, "perPage" to 25)
                    )
                )
                MonotonicMetrics.recordDuration("anilist_request_ms", reqStarted, "op=search page=$targetPage status=${resp.code()}")

                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("AniList search failed: HTTP ${resp.code()}"))
                }
                val pageData = resp.body()?.data?.page
                val uniqueMedia = uniqueAniListMedia(pageData?.media.orEmpty())
                val hasNext = pageData?.pageInfo?.hasNextPage ?: false
                val currentPage = pageData?.pageInfo?.currentPage ?: targetPage

                val entries = mutableListOf<AnimeEntry>()
                val aliases = mutableListOf<AnimeAlias>()
                val newRefs = mutableListOf<SearchResultRef>()

                val rankOffset = if (targetPage == 1) 0 else {
                    (searchDao.getSearchQuery(normQuery)?.totalResults ?: 0)
                }

                uniqueMedia.forEachIndexed { index, m ->
                    val existing = animeDao.getAnimeByAniListId(m.id)
                        ?: animeDao.getAnimeById("anilist_${m.id}")
                    val mapping = animeDao.getMappingForAniList(m.id)
                    val animeId = existing?.id ?: "anilist_${m.id}"
                    val title = m.title?.english ?: m.title?.romaji ?: m.title?.native ?: "Anime #${m.id}"
                    val isMovieFormat = m.format.equals("MOVIE", ignoreCase = true)
                    val canonicalInfo = inferShallowCanonicalInfo(m, existing)

                    entries += AnimeEntry(
                        id = animeId,
                        aniListId = m.id,
                        backendAnimeId = existing?.backendAnimeId ?: mapping?.backendAnimeId,
                        canonicalAnimeId = canonicalInfo.canonicalAnimeId ?: existing?.canonicalAnimeId,
                        canonicalTitle = canonicalInfo.canonicalTitle ?: existing?.canonicalTitle,
                        canonicalSeasonNumber = canonicalInfo.canonicalSeasonNumber ?: existing?.canonicalSeasonNumber,
                        cumulativeEpisodeOffset = canonicalInfo.cumulativeEpisodeOffset ?: existing?.cumulativeEpisodeOffset,
                        titleRomaji = m.title?.romaji ?: existing?.titleRomaji,
                        titleEnglish = m.title?.english ?: existing?.titleEnglish,
                        titleNative = m.title?.native ?: existing?.titleNative,
                        preferredTitle = title,
                        synopsis = m.description ?: existing?.synopsis,
                        year = (m.startDate?.year ?: m.seasonYear)?.toString() ?: existing?.year,
                        season = m.season ?: existing?.season,
                        format = m.format ?: existing?.format,
                        status = m.status ?: existing?.status,
                        genres = m.genres?.joinToString(", ") ?: existing?.genres.orEmpty(),
                        score = m.averageScore?.div(10.0) ?: existing?.score,
                        episodeCount = resolveKnownEpisodeCount(m, existing?.episodeCount),
                        duration = m.duration ?: existing?.duration,
                        studios = m.studios?.nodes?.firstOrNull()?.name ?: existing?.studios,
                        posterUrl = m.coverImage?.large ?: m.coverImage?.medium ?: existing?.posterUrl,
                        bannerUrl = m.bannerImage ?: existing?.bannerUrl,
                        isPlayable = true,
                        isTrending = existing?.isTrending ?: false,
                        isSeasonal = existing?.isSeasonal ?: false,
                        isPopular = existing?.isPopular ?: false,
                        mediaCategory = if (isMovieFormat) "MOVIE" else (existing?.mediaCategory ?: "ANIME"),
                        metadataCompleteness = existing?.metadataCompleteness ?: "UNKNOWN",
                        updatedAt = System.currentTimeMillis()
                    )
                    collectAliases(animeId, m, aliases)
                    newRefs += SearchResultRef(
                        normalizedQuery = normQuery,
                        animeId = animeId,
                        rankIndex = rankOffset + index
                    )
                }

                val roomStarted = MonotonicMetrics.nowMs()
                if (entries.isNotEmpty()) {
                    animeDao.upsertAnimeList(entries.distinctBy { it.id })
                }
                if (aliases.isNotEmpty()) {
                    animeDao.insertAliases(aliases.distinctBy { it.animeId to it.alias })
                }
                if (targetPage == 1) {
                    searchDao.clearSearchResultsForQuery(normQuery)
                }
                val distinctRefs = newRefs.distinctBy { it.animeId }
                if (distinctRefs.isNotEmpty()) {
                    searchDao.insertSearchResultRefs(distinctRefs)
                }
                val totalKnown = if (targetPage == 1) distinctRefs.size else rankOffset + distinctRefs.size
                searchDao.upsertSearchQuery(
                    SearchQuery(
                        query = normQuery,
                        totalResults = totalKnown,
                        currentPage = currentPage,
                        hasNextPage = hasNext
                    )
                )
                MonotonicMetrics.recordDuration("room_transaction_ms", roomStarted, "op=search_save results=${distinctRefs.size}")

                Result.success(SearchPageResult(uniqueMedia.size, currentPage, hasNext))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSearchQueryState(query: String): SearchQuery? = withContext(Dispatchers.IO) {
        val norm = TitleMatcher.normalize(query)
        if (norm.isBlank()) null else searchDao.getSearchQuery(norm)
    }

    /**
     * Refreshes Anime or Movie details strictly from AniList.
     * - For Movies: marks `NOT_APPLICABLE` for episode catalog; creates NO season/episode rows.
     * - For Series: creates/merges Room Season/Episode rows from AniList counts & streamingEpisodes
     *   using only `"Episode N"` fallback when AniList omits per-episode titles (never fabricates titles).
     * - Never loads metadata from Backend or Firestore.
     */
    private fun resolveKnownEpisodeCount(media: AniListMedia, fallback: Int? = null): Int? {
        val explicitTotal = media.episodes?.takeIf { it > 0 }
        if (explicitTotal != null) return explicitTotal
        val airedSoFar = media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }
        if (airedSoFar != null) return airedSoFar
        val streamingCount = media.streamingEpisodes?.size?.takeIf { it > 0 }
        return streamingCount ?: fallback
    }

    private suspend fun resolveAniListIdForEntry(animeId: String, entry: AnimeEntry?): Int? {
        entry?.aniListId?.takeIf { it > 0 }?.let { return it }
        animeId.takeIf { it.startsWith("anilist_") }?.removePrefix("anilist_")?.toIntOrNull()?.let { return it }
        animeId.toIntOrNull()?.takeIf { it > 0 }?.let { return it }

        val queryTitle = entry?.preferredTitle?.takeIf { it.isNotBlank() }
            ?: entry?.titleEnglish?.takeIf { it.isNotBlank() }
            ?: entry?.titleRomaji?.takeIf { it.isNotBlank() }
            ?: return null

        val searchResp = runCatching {
            aniListApi.postQuery(
                AniListGraphQLRequest(
                    query = AniListQueries.SEARCH_ANIME,
                    variables = mapOf("search" to queryTitle.trim(), "page" to 1, "perPage" to 5)
                )
            )
        }.getOrNull() ?: return null
        if (!searchResp.isSuccessful) return null
        val candidates = searchResp.body()?.data?.page?.media.orEmpty()
        if (candidates.isEmpty()) return null
        val bestMatch = candidates.minByOrNull { m ->
            TitleMatcher.rank(
                queryTitle,
                listOfNotNull(m.title?.english, m.title?.romaji, m.title?.native) + m.synonyms.orEmpty()
            )
        } ?: candidates.firstOrNull()
        return bestMatch?.id
    }

    private data class CanonicalFranchiseInfo(
        val canonicalAnimeId: Int?,
        val canonicalTitle: String?,
        val canonicalSeasonNumber: Int?,
        val cumulativeEpisodeOffset: Int?
    )

    private fun isSeriesAnimeFormat(format: String?, mediaType: String? = "ANIME"): Boolean {
        if (mediaType != null && !mediaType.equals("ANIME", ignoreCase = true)) return false
        val upper = format?.trim()?.uppercase()
        return upper == null || upper in setOf("TV", "TV_SHORT", "ONA")
    }

    private fun extractImmediateTvPrequel(media: AniListMedia): AniListRelatedMediaNode? {
        if (!isSeriesAnimeFormat(media.format)) return null
        return media.relations?.edges.orEmpty()
            .firstOrNull { edge ->
                edge.relationType.equals("PREQUEL", ignoreCase = true) &&
                    edge.node != null &&
                    isSeriesAnimeFormat(edge.node.format, edge.node.type)
            }
            ?.node
    }

    private fun nodeEpisodeCount(node: AniListRelatedMediaNode): Int? =
        node.episodes?.takeIf { it > 0 }
            ?: node.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }

    private suspend fun inferShallowCanonicalInfo(media: AniListMedia, existing: AnimeEntry?): CanonicalFranchiseInfo {
        if (ContentIdentityHelper.isMovie(existing) || media.format.equals("MOVIE", ignoreCase = true)) {
            return CanonicalFranchiseInfo(null, null, null, null)
        }
        val prequel = extractImmediateTvPrequel(media)
        if (prequel == null) {
            if (media.relations == null && existing != null) {
                return CanonicalFranchiseInfo(
                    canonicalAnimeId = existing.canonicalAnimeId,
                    canonicalTitle = existing.canonicalTitle,
                    canonicalSeasonNumber = existing.canonicalSeasonNumber,
                    cumulativeEpisodeOffset = existing.cumulativeEpisodeOffset
                )
            }
            return CanonicalFranchiseInfo(null, null, null, null)
        }
        val prequelEntry = animeDao.getAnimeByAniListId(prequel.id)
        val rootId = prequelEntry?.canonicalAnimeId ?: prequel.id
        val rootTitle = prequelEntry?.canonicalTitle
            ?: prequel.title?.english?.takeIf { it.isNotBlank() }
            ?: prequel.title?.romaji?.takeIf { it.isNotBlank() }
            ?: prequel.title?.native?.takeIf { it.isNotBlank() }
        val prequelCount = nodeEpisodeCount(prequel) ?: prequelEntry?.episodeCount ?: 0
        val offset = ((prequelEntry?.cumulativeEpisodeOffset ?: 0) + prequelCount).takeIf { it > 0 }
        val hintSeason = TitleMatcher.extractSeasonHint(
            listOfNotNull(media.title?.english, media.title?.romaji, media.title?.native)
        )
        val seasonNum = hintSeason?.takeIf { it > 1 }
            ?: prequelEntry?.canonicalSeasonNumber?.takeIf { it > 1 }?.plus(1)
            ?: 2
        return CanonicalFranchiseInfo(
            canonicalAnimeId = rootId,
            canonicalTitle = rootTitle,
            canonicalSeasonNumber = seasonNum,
            cumulativeEpisodeOffset = offset ?: existing?.cumulativeEpisodeOffset
        )
    }

    private suspend fun resolveCanonicalFranchiseChain(
        rootCandidateMedia: AniListMedia,
        existing: AnimeEntry?
    ): CanonicalFranchiseInfo {
        if (ContentIdentityHelper.isMovie(existing) || rootCandidateMedia.format.equals("MOVIE", ignoreCase = true)) {
            return CanonicalFranchiseInfo(null, null, null, null)
        }
        var currentPrequel = extractImmediateTvPrequel(rootCandidateMedia)
        if (currentPrequel == null) {
            if (rootCandidateMedia.relations == null && existing != null) {
                return CanonicalFranchiseInfo(
                    canonicalAnimeId = existing.canonicalAnimeId,
                    canonicalTitle = existing.canonicalTitle,
                    canonicalSeasonNumber = existing.canonicalSeasonNumber,
                    cumulativeEpisodeOffset = existing.cumulativeEpisodeOffset
                )
            }
            return CanonicalFranchiseInfo(null, null, null, null)
        }

        val visited = mutableSetOf(rootCandidateMedia.id)
        val chain = mutableListOf<Pair<Int, Pair<String?, Int>>>()
        var hop = 0
        while (currentPrequel != null && hop < 5 && visited.add(currentPrequel.id)) {
            hop++
            val pTitle = currentPrequel.title?.english?.takeIf { it.isNotBlank() }
                ?: currentPrequel.title?.romaji?.takeIf { it.isNotBlank() }
                ?: currentPrequel.title?.native?.takeIf { it.isNotBlank() }
            var pEpisodes = nodeEpisodeCount(currentPrequel) ?: 0
            var nextPrequel: AniListRelatedMediaNode? = null

            val cachedPrequelEntry = animeDao.getAnimeByAniListId(currentPrequel.id)
            if (pEpisodes == 0) {
                pEpisodes = cachedPrequelEntry?.episodeCount ?: 0
            }

            val prequelResp = runCatching {
                aniListApi.postQuery(
                    AniListGraphQLRequest(
                        query = AniListQueries.MEDIA_DETAILS,
                        variables = mapOf("id" to currentPrequel.id)
                    )
                )
            }.getOrNull()
            val prequelMedia = prequelResp?.takeIf { it.isSuccessful }?.body()?.data?.media
            if (prequelMedia != null) {
                if (pEpisodes == 0) {
                    pEpisodes = resolveKnownEpisodeCount(prequelMedia) ?: 0
                }
                nextPrequel = extractImmediateTvPrequel(prequelMedia)
            } else if (cachedPrequelEntry?.canonicalAnimeId != null && cachedPrequelEntry.canonicalAnimeId != currentPrequel.id) {
                val tailCount = cachedPrequelEntry.cumulativeEpisodeOffset ?: 0
                chain += currentPrequel.id to (pTitle to pEpisodes)
                val rootTitle = cachedPrequelEntry.canonicalTitle ?: pTitle
                val totalOffset = (chain.sumOf { it.second.second } + tailCount).takeIf { it > 0 }
                val hintSeason = TitleMatcher.extractSeasonHint(
                    listOfNotNull(
                        rootCandidateMedia.title?.english,
                        rootCandidateMedia.title?.romaji,
                        rootCandidateMedia.title?.native
                    )
                )
                val inferredSeason = hintSeason?.takeIf { it > 1 }
                    ?: (cachedPrequelEntry.canonicalSeasonNumber?.plus(chain.size) ?: (chain.size + 2))
                return CanonicalFranchiseInfo(
                    canonicalAnimeId = cachedPrequelEntry.canonicalAnimeId,
                    canonicalTitle = rootTitle,
                    canonicalSeasonNumber = inferredSeason,
                    cumulativeEpisodeOffset = totalOffset
                )
            }

            val finalTitle = pTitle
                ?: prequelMedia?.title?.english?.takeIf { it.isNotBlank() }
                ?: prequelMedia?.title?.romaji?.takeIf { it.isNotBlank() }
                ?: prequelMedia?.title?.native?.takeIf { it.isNotBlank() }
                ?: cachedPrequelEntry?.preferredTitle
            chain += currentPrequel.id to (finalTitle to pEpisodes)
            currentPrequel = nextPrequel
        }

        val rootPair = chain.last()
        val totalOffset = chain.sumOf { it.second.second }.takeIf { it > 0 }
        val hintSeason = TitleMatcher.extractSeasonHint(
            listOfNotNull(
                rootCandidateMedia.title?.english,
                rootCandidateMedia.title?.romaji,
                rootCandidateMedia.title?.native
            )
        )
        val seasonNum = hintSeason?.takeIf { it > 1 } ?: (chain.size + 1)
        return CanonicalFranchiseInfo(
            canonicalAnimeId = rootPair.first,
            canonicalTitle = rootPair.second.first ?: existing?.canonicalTitle,
            canonicalSeasonNumber = seasonNum,
            cumulativeEpisodeOffset = totalOffset ?: existing?.cumulativeEpisodeOffset
        )
    }

    private suspend fun seedFallbackEpisodesFromCount(
        animeId: String,
        totalCount: Int,
        parsedStreamingTitles: Map<Int, String> = emptyMap(),
        seasonNumber: Int = 1,
        pruneOtherSeasons: Boolean = false
    ) {
        if (totalCount <= 0) return
        val targetSeason = seasonNumber.coerceAtLeast(1)
        if (pruneOtherSeasons && targetSeason > 1) {
            episodeDao.deleteOtherSeasonEpisodes(animeId, targetSeason)
            episodeDao.deleteOtherSeasons(animeId, targetSeason)
        }
        val existingEpisodes = episodeDao.getEpisodesForAnime(animeId)
        val existingByNumber = existingEpisodes
            .filter { it.seasonNumber == targetSeason }
            .associateBy { it.episodeNumber }
        val seasons = listOf(
            SeasonEntry(
                id = "${animeId}_s$targetSeason",
                animeId = animeId,
                seasonNumber = targetSeason,
                backendSeasonId = null,
                title = null
            )
        )
        val generatedEpisodes = (1..totalCount).map { epNum ->
            val prev = existingByNumber[epNum]
            val officialTitle = parsedStreamingTitles[epNum]
                ?: prev?.title?.takeUnless { it == "Episode $epNum" }
            EpisodeEntry(
                id = prev?.id ?: "anilist_ep_${animeId}_s${targetSeason}_e$epNum",
                animeId = animeId,
                seasonNumber = targetSeason,
                episodeNumber = epNum,
                title = officialTitle ?: "Episode $epNum",
                overview = prev?.overview,
                isAvailable = true,
                sourceEpisodeIds = prev?.sourceEpisodeIds
            )
        }
        episodeDao.mergeEpisodeSnapshot(animeId, seasons, generatedEpisodes)
    }

    /**
     * Refreshes Anime or Movie details strictly from AniList.
     * - For Movies: marks `NOT_APPLICABLE` for episode catalog; creates NO season/episode rows.
     * - For Series: creates/merges Room Season/Episode rows from AniList counts, nextAiringEpisode,
     *   and streamingEpisodes using only `"Episode N"` fallback when AniList omits per-episode titles.
     * - Never loads metadata from Backend or Firestore.
     */
    suspend fun refreshAnimeDetails(animeId: String): Result<EpisodeCatalogRefreshStatus> = withContext(Dispatchers.IO) {
        detailRefreshMutexes.computeIfAbsent(animeId) { Mutex() }.withLock {
            try {
                var entry = animeDao.getAnimeById(animeId)
                val aniListId = resolveAniListIdForEntry(animeId, entry)

                if (aniListId == null) {
                    val isMovie = ContentIdentityHelper.isMovie(entry)
                    if (isMovie) {
                        return@withContext Result.success(
                            EpisodeCatalogRefreshStatus(EpisodeCatalogState.NOT_APPLICABLE, detailsEndpointSucceeded = true, episodeListEndpointSucceeded = false)
                        )
                    }
                    var cachedCount = episodeDao.getEpisodesForAnime(animeId).size
                    if (cachedCount == 0 && (entry?.episodeCount ?: 0) > 0) {
                        val fallbackSeason = ContentIdentityHelper.effectiveSeasonNumber(entry, 1, isMovie = false)
                        seedFallbackEpisodesFromCount(
                            animeId = animeId,
                            totalCount = entry!!.episodeCount!!,
                            seasonNumber = fallbackSeason,
                            pruneOtherSeasons = fallbackSeason > 1
                        )
                        cachedCount = episodeDao.getEpisodesForAnime(animeId).size
                    }
                    val state = if (cachedCount > 0) EpisodeCatalogState.PARTIAL else EpisodeCatalogState.UNKNOWN
                    return@withContext Result.success(EpisodeCatalogRefreshStatus(state, false, false))
                }

                val reqStarted = MonotonicMetrics.nowMs()
                val response = aniListApi.postQuery(
                    AniListGraphQLRequest(
                        query = AniListQueries.MEDIA_DETAILS,
                        variables = mapOf("id" to aniListId)
                    )
                )
                MonotonicMetrics.recordDuration("anilist_request_ms", reqStarted, "op=media_details id=$aniListId status=${response.code()}")

                if (!response.isSuccessful || response.body()?.data?.media == null) {
                    val isMovie = ContentIdentityHelper.isMovie(entry)
                    if (isMovie) {
                        return@withContext Result.success(
                            EpisodeCatalogRefreshStatus(EpisodeCatalogState.NOT_APPLICABLE, false, false)
                        )
                    }
                    var cachedCount = episodeDao.getEpisodesForAnime(animeId).size
                    if (cachedCount == 0 && (entry?.episodeCount ?: 0) > 0) {
                        val fallbackSeason = ContentIdentityHelper.effectiveSeasonNumber(entry, 1, isMovie = false)
                        seedFallbackEpisodesFromCount(
                            animeId = animeId,
                            totalCount = entry!!.episodeCount!!,
                            seasonNumber = fallbackSeason,
                            pruneOtherSeasons = fallbackSeason > 1
                        )
                        cachedCount = episodeDao.getEpisodesForAnime(animeId).size
                    }
                    val state = if (cachedCount > 0) EpisodeCatalogState.PARTIAL else EpisodeCatalogState.FAILED
                    return@withContext Result.success(
                        EpisodeCatalogRefreshStatus(
                            state = state,
                            detailsEndpointSucceeded = false,
                            episodeListEndpointSucceeded = false,
                            errorMessage = "Could not refresh metadata from AniList (HTTP ${response.code()})."
                        )
                    )
                }

                val media = response.body()!!.data!!.media!!
                val isMovie = media.format.equals("MOVIE", ignoreCase = true) || ContentIdentityHelper.isMovie(entry)
                val canonicalInfo = resolveCanonicalFranchiseChain(media, entry)
                val canonicalLookupId = canonicalInfo.canonicalAnimeId ?: aniListId
                val mapping = animeDao.getMappingForAniList(canonicalLookupId) ?: animeDao.getMappingForAniList(aniListId)
                val resolvedBackendId = entry?.backendAnimeId ?: mapping?.backendAnimeId
                val preferredTitle = media.title?.english ?: media.title?.romaji ?: media.title?.native
                    ?: entry?.preferredTitle ?: "Anime #$aniListId"

                val streamingEps = media.streamingEpisodes.orEmpty()
                val airedFromSchedule = (media.nextAiringEpisode?.episode?.minus(1))?.takeIf { it > 0 } ?: 0
                val totalCount = when {
                    isMovie -> 0
                    media.episodes != null && media.episodes > 0 -> media.episodes
                    airedFromSchedule > 0 || streamingEps.isNotEmpty() -> maxOf(airedFromSchedule, streamingEps.size)
                    (entry?.episodeCount ?: 0) > 0 -> entry!!.episodeCount!!
                    else -> 0
                }
                val metadataCompleteness = when {
                    isMovie -> "NOT_APPLICABLE"
                    totalCount > 0 && media.status.equals("FINISHED", ignoreCase = true) -> "COMPLETE"
                    totalCount > 0 -> "PARTIAL"
                    else -> "MISSING_EPISODES"
                }

                val updatedEntry = (entry ?: AnimeEntry(id = animeId, preferredTitle = preferredTitle)).copy(
                    aniListId = media.id,
                    backendAnimeId = resolvedBackendId,
                    canonicalAnimeId = if (isMovie) null else (canonicalInfo.canonicalAnimeId ?: entry?.canonicalAnimeId),
                    canonicalTitle = if (isMovie) null else (canonicalInfo.canonicalTitle ?: entry?.canonicalTitle),
                    canonicalSeasonNumber = if (isMovie) null else (canonicalInfo.canonicalSeasonNumber ?: entry?.canonicalSeasonNumber),
                    cumulativeEpisodeOffset = if (isMovie) null else (canonicalInfo.cumulativeEpisodeOffset ?: entry?.cumulativeEpisodeOffset),
                    titleRomaji = media.title?.romaji ?: entry?.titleRomaji,
                    titleEnglish = media.title?.english ?: entry?.titleEnglish,
                    titleNative = media.title?.native ?: entry?.titleNative,
                    preferredTitle = preferredTitle,
                    synopsis = media.description ?: entry?.synopsis,
                    year = (media.startDate?.year ?: media.seasonYear)?.toString() ?: entry?.year,
                    season = media.season ?: entry?.season,
                    format = media.format ?: entry?.format,
                    status = media.status ?: entry?.status,
                    genres = media.genres?.joinToString(", ") ?: entry?.genres.orEmpty(),
                    score = media.averageScore?.div(10.0) ?: entry?.score,
                    episodeCount = if (isMovie) 1 else (resolveKnownEpisodeCount(media, entry?.episodeCount) ?: totalCount.takeIf { it > 0 }),
                    duration = media.duration ?: entry?.duration,
                    studios = media.studios?.nodes?.firstOrNull()?.name ?: entry?.studios,
                    posterUrl = media.coverImage?.large ?: media.coverImage?.medium ?: entry?.posterUrl,
                    bannerUrl = media.bannerImage ?: entry?.bannerUrl,
                    isPlayable = true,
                    mediaCategory = if (isMovie) "MOVIE" else "ANIME",
                    metadataCompleteness = metadataCompleteness,
                    updatedAt = System.currentTimeMillis()
                )
                animeDao.upsertAnime(updatedEntry)
                entry = updatedEntry

                val aliases = mutableListOf<AnimeAlias>()
                collectAliases(animeId, media, aliases)
                if (aliases.isNotEmpty()) animeDao.insertAliases(aliases.distinctBy { it.animeId to it.alias })

                if (isMovie) {
                    // Movies MUST NEVER store SeasonEntry or EpisodeEntry rows in Room
                    return@withContext Result.success(
                        EpisodeCatalogRefreshStatus(
                            state = EpisodeCatalogState.NOT_APPLICABLE,
                            detailsEndpointSucceeded = true,
                            episodeListEndpointSucceeded = false
                        )
                    )
                }

                val targetSeasonNumber = canonicalInfo.canonicalSeasonNumber?.takeIf { it > 1 }
                    ?: TitleMatcher.extractSeasonHint(
                        listOfNotNull(preferredTitle, media.title?.romaji, media.title?.native)
                    )?.takeIf { it > 1 && canonicalInfo.canonicalAnimeId != null }
                    ?: 1

                if (totalCount > 0) {
                    val parsedStreamingTitles = parseAniListEpisodeTitles(streamingEps)
                    seedFallbackEpisodesFromCount(
                        animeId = animeId,
                        totalCount = totalCount,
                        parsedStreamingTitles = parsedStreamingTitles,
                        seasonNumber = targetSeasonNumber,
                        pruneOtherSeasons = (targetSeasonNumber > 1 && canonicalInfo.canonicalAnimeId != null)
                    )
                }

                val finalCount = episodeDao.getEpisodesForAnime(animeId).size
                val catalogState = when {
                    metadataCompleteness == "COMPLETE" && finalCount > 0 -> EpisodeCatalogState.COMPLETE
                    finalCount > 0 -> EpisodeCatalogState.PARTIAL
                    else -> EpisodeCatalogState.UNKNOWN
                }
                Result.success(
                    EpisodeCatalogRefreshStatus(
                        state = catalogState,
                        detailsEndpointSucceeded = true,
                        episodeListEndpointSucceeded = totalCount > 0,
                        currentRefreshEpisodeIds = episodeDao.getEpisodesForAnime(animeId).map { it.id }.toSet()
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun parseAniListEpisodeTitles(streamingEpisodes: List<com.example.data.remote.AniListStreamingEpisode>): Map<Int, String> {
        if (streamingEpisodes.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, String>()
        val prefixRegex = Regex("""^Episode\s+(\d+)\s*[-:–]\s*(.+)$""", RegexOption.IGNORE_CASE)
        streamingEpisodes.forEachIndexed { idx, ep ->
            val raw = ep.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            val match = prefixRegex.matchEntire(raw)
            if (match != null) {
                val epNum = match.groupValues[1].toIntOrNull()
                val title = match.groupValues[2].trim()
                if (epNum != null && title.isNotEmpty()) {
                    result[epNum] = title
                }
            } else {
                result[idx + 1] = raw
            }
        }
        return result
    }

    // ============================================================================================
    // 2. FIRESTORE + ROOM + BACKEND STREAMING DISCOVERY (Lazy; Separate from Catalog Metadata)
    // ============================================================================================

    /**
     * Ensures provider + source discovery exists in Room/Firestore for the given Anime Episode
     * or Movie. Reuses Room first, then Firestore, and calls Backend discovery ONLY when needed.
     */
    suspend fun discoverStreamsForScope(
        animeId: String,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        catalogEpisodeId: String? = null,
        forceRefresh: Boolean = false
    ): Result<EpisodeSourceSnapshot> = withContext(Dispatchers.IO) {
        val discoveryStarted = MonotonicMetrics.nowMs()
        val anime = animeDao.getAnimeById(animeId)
        val identity = ContentIdentityHelper.resolveCanonicalIdentity(animeId, anime, seasonNumber, episodeNumber)
        val isMovie = identity.isMovie
        val contentKey = identity.contentKey
        val effectiveSeason = identity.seasonNumber
        val effectiveEpisode = identity.episodeNumber
        val scopeKey = ContentIdentityHelper.scopeKey(contentKey, isMovie, effectiveSeason, effectiveEpisode)

        sourceFetchMutexes.computeIfAbsent(scopeKey) { Mutex() }.withLock {
            // Tier 1: Room cached providers/sources
            if (!forceRefresh) {
                val roomSnapshot = readRoomScopeSnapshot(scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode)
                if (roomSnapshot != null && roomSnapshot.sources.isNotEmpty() && !roomSnapshot.isStale) {
                    MonotonicMetrics.recordDuration("source_discovery_ms", discoveryStarted, "tier=ROOM count=${roomSnapshot.sources.size}")
                    return@withContext Result.success(roomSnapshot)
                }
            }

            // Tier 2: Firestore shared cache (reuse without repeating backend discovery)
            val readOutcome = streamingRepository.fetchAndSyncScopeWithOutcome(
                contentKey = contentKey,
                isMovie = isMovie,
                seasonNumber = effectiveSeason,
                episodeNumber = effectiveEpisode,
                catalogEpisodeId = catalogEpisodeId,
                preferServer = forceRefresh
            )
            val firestoreData = readOutcome.data
            if (!forceRefresh && firestoreData != null &&
                (firestoreData.discoveryStatus == DiscoveryStatus.COMPLETED.name ||
                    firestoreData.sources.any { it.status != "REMOVED" && it.deletedAt == null })
            ) {
                val synced = readRoomScopeSnapshot(scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode)
                if (synced != null && synced.sources.isNotEmpty()) {
                    MonotonicMetrics.recordDuration("source_discovery_ms", discoveryStarted, "tier=FIRESTORE count=${synced.sources.size}")
                    return@withContext Result.success(synced)
                }
            }
            if (!forceRefresh && readOutcome.isHardError) {
                val fallbackRoom = readRoomScopeSnapshot(scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode)
                if (fallbackRoom != null && fallbackRoom.sources.isNotEmpty()) {
                    Log.w("AnimeRepository", "Firestore read failed (${readOutcome.status}); returning cached Room scope cleanly.")
                    return@withContext Result.success(
                        fallbackRoom.copy(
                            state = EpisodeSourceResponseState.STALE,
                            errorMessage = readOutcome.message
                        )
                    )
                }
            }

            // Tier 3: Backend Provider/Source discovery (ONLY when Room/Firestore lack valid discovery)
            val cacheKey = sourceCacheKey(animeId, if (isMovie) null else effectiveSeason, catalogEpisodeId ?: scopeKey)
            val retryRemaining = sourceRetryRemainingMs(cacheKey)
            if (EpisodeSourceRetryBackoff.shouldDelay(forceRefresh, retryRemaining)) {
                val fallback = readRoomScopeSnapshot(scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode)
                if (fallback != null && fallback.sources.isNotEmpty()) {
                    return@withContext Result.success(fallback.copy(state = EpisodeSourceResponseState.STALE))
                }
                return@withContext Result.failure(
                    IllegalStateException("Source discovery retry is backed off for ${(retryRemaining + 999L) / 1000L} seconds.")
                )
            }

            var backendId = resolveBackendIdForDiscovery(animeId, anime)
                ?: return@withContext staleRoomOrFailure(
                    scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode,
                    "No streaming provider mapping was found for this title."
                )

            var backendEpisodeIds = resolveBackendEpisodeIdsForDiscovery(
                animeId = animeId,
                backendId = backendId,
                isMovie = isMovie,
                seasonNumber = effectiveSeason,
                episodeNumber = effectiveEpisode,
                catalogEpisodeId = catalogEpisodeId,
                cumulativeOffset = identity.cumulativeEpisodeOffset,
                allowCachedIds = !forceRefresh
            )

            val canSelfHealBackend = animeId.startsWith("anilist_") || (
                !isMovie && anime != null && (
                    effectiveSeason > 1 ||
                        anime.canonicalAnimeId != null ||
                        anime.canonicalTitle != null ||
                        TitleMatcher.hasSequelMarker(listOfNotNull(anime.preferredTitle, anime.titleEnglish, anime.titleRomaji))
                    )
                )

            if (backendEpisodeIds.isEmpty() && canSelfHealBackend) {
                val alternateId = resolveBackendIdForDiscovery(animeId, anime, excludeBackendIds = setOf(backendId))
                if (alternateId != null && alternateId != backendId) {
                    backendId = alternateId
                    backendEpisodeIds = resolveBackendEpisodeIdsForDiscovery(
                        animeId = animeId,
                        backendId = backendId,
                        isMovie = isMovie,
                        seasonNumber = effectiveSeason,
                        episodeNumber = effectiveEpisode,
                        catalogEpisodeId = catalogEpisodeId,
                        cumulativeOffset = identity.cumulativeEpisodeOffset,
                        allowCachedIds = false
                    )
                }
            }

            if (backendEpisodeIds.isEmpty()) {
                return@withContext staleRoomOrFailure(
                    scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode,
                    "Streaming episode record was not found on provider backend."
                )
            }

            val aggregatedCandidates = mutableListOf<Pair<String, ProphecySourceDto>>()
            var rawStatus: String? = null
            var anyPartial = false
            var lastErrorMsg: String? = null
            var hadHttpOrNetworkError = false

            suspend fun fetchCandidatesFromBackend(targetBackendId: String, epIds: List<String>) {
                for (backendEpId in epIds) {
                    val providerStarted = MonotonicMetrics.nowMs()
                    try {
                        val resp = prophecyApi.getEpisodeFull(targetBackendId, backendEpId)
                        MonotonicMetrics.recordDuration("provider_discovery_ms", providerStarted, "http=${resp.code()}")
                        if (resp.isSuccessful && resp.body() != null) {
                            val body = resp.body()!!
                            if (body.sourcesPartial == true) anyPartial = true
                            rawStatus = body.sourcesStatus ?: rawStatus
                            lastErrorMsg = body.sourceError ?: lastErrorMsg
                            body.sources.orEmpty().forEach { dto ->
                                aggregatedCandidates += backendEpId to dto
                            }
                        } else {
                            hadHttpOrNetworkError = true
                            if (EpisodeSourceRetryBackoff.isRetryableHttpStatus(resp.code())) {
                                recordSourceFailure(cacheKey)
                            }
                            lastErrorMsg = "Backend returned HTTP ${resp.code()}"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        hadHttpOrNetworkError = true
                        recordSourceFailure(cacheKey)
                        lastErrorMsg = e.message ?: "Network error during discovery"
                    }
                }
            }

            fetchCandidatesFromBackend(backendId, backendEpisodeIds)

            // If a duplicate/addon-only backend entry yielded zero usable candidates, try canonical re-match
            if (aggregatedCandidates.none { it.second.hasUsableSourceId } && !hadHttpOrNetworkError && canSelfHealBackend) {
                val alternateId = resolveBackendIdForDiscovery(animeId, anime, excludeBackendIds = setOf(backendId))
                if (alternateId != null && alternateId != backendId) {
                    val alternateEpIds = resolveBackendEpisodeIdsForDiscovery(
                        animeId = animeId,
                        backendId = alternateId,
                        isMovie = false,
                        seasonNumber = effectiveSeason,
                        episodeNumber = effectiveEpisode,
                        catalogEpisodeId = catalogEpisodeId,
                        cumulativeOffset = identity.cumulativeEpisodeOffset,
                        allowCachedIds = false
                    )
                    if (alternateEpIds.isNotEmpty()) {
                        backendId = alternateId
                        backendEpisodeIds = alternateEpIds
                        aggregatedCandidates.clear()
                        rawStatus = null
                        anyPartial = false
                        lastErrorMsg = null
                        fetchCandidatesFromBackend(backendId, backendEpisodeIds)
                    }
                }
            }

            val dedupedDtos = EpisodeSourceAggregator.aggregate(
                apiCandidates = aggregatedCandidates.map { it.second },
                rawStatus = rawStatus,
                partial = anyPartial,
                errorMessage = if (aggregatedCandidates.isEmpty()) lastErrorMsg else null,
                nowMs = System.currentTimeMillis()
            )

            if (aggregatedCandidates.isEmpty() && hadHttpOrNetworkError) {
                // Temporary failure MUST NOT delete cached records or mark permanently unavailable
                val syncedTemp = streamingRepository.publishDiscoveryAndSyncRoom(
                    contentKey = contentKey,
                    anilistId = identity.anilistAnimeId,
                    isMovie = isMovie,
                    backendId = backendId,
                    seasonNumber = effectiveSeason,
                    episodeNumber = effectiveEpisode,
                    catalogEpisodeId = catalogEpisodeId,
                    backendEpisodeId = backendEpisodeIds.firstOrNull(),
                    providers = emptyList(),
                    sources = emptyList(),
                    discoveryStatus = DiscoveryStatus.TEMPORARY_FAILURE,
                    canonicalTitle = identity.canonicalTitle
                )
                if (!syncedTemp) {
                    Log.w("AnimeRepository", "Temporary discovery state queued for retry: ${streamingRepository.lastWriteRejectionReason ?: "queued"}")
                }
                return@withContext staleRoomOrFailure(
                    scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode,
                    lastErrorMsg ?: "Temporary provider outage"
                )
            }

            clearSourceFailure(cacheKey)
            val backendEpMap = aggregatedCandidates.associate { it.second.id to it.first }
            val now = System.currentTimeMillis()

            val allExistingSources = streamCacheDao.getAllSources(scopeKey)
            val existingByComposite = allExistingSources.associateBy { "${it.providerId}|${it.sourceId}" }
            val existingBySourceIdGroups = allExistingSources.groupBy { it.sourceId }
            val sourceEntities = mutableListOf<StreamSourceEntity>()
            val providersMap = linkedMapOf<String, MutableList<StreamSourceEntity>>()

            for (dto in dedupedDtos.sources) {
                if (!dto.hasUsableSourceId) continue
                val providerId = ContentIdentityHelper.deterministicProviderId(dto.label)
                val prev = existingByComposite["$providerId|${dto.id}"]
                    ?: existingBySourceIdGroups[dto.id]?.singleOrNull()
                val effectiveExpiresAt = dto.expiresAt ?: prev?.expiresAt
                val validUrl = prev?.streamUrl?.takeIf { SafePlaybackHeaders.isValidStreamUrl(it) }
                val sEntity = StreamSourceEntity(
                    sourceDocKey = "$scopeKey|p|$providerId|s|${ContentIdentityHelper.sanitizeDocId(dto.id)}",
                    scopeKey = scopeKey,
                    providerId = providerId,
                    sourceId = dto.id,
                    sourceName = dto.displayProviderName,
                    backendEpisodeId = backendEpMap[dto.id] ?: prev?.backendEpisodeId,
                    streamUrl = validUrl,
                    urlType = prev?.urlType ?: "direct",
                    contentType = prev?.contentType,
                    quality = dto.quality ?: prev?.quality,
                    codec = dto.codec ?: prev?.codec,
                    audioLanguage = dto.language ?: prev?.audioLanguage,
                    downloadable = dto.downloadable ?: prev?.downloadable,
                    sizeBytes = dto.sizeBytes ?: prev?.sizeBytes,
                    safeHeadersJson = prev?.safeHeadersJson,
                    expiresAt = effectiveExpiresAt,
                    status = if (validUrl != null && !SourceExpiryTime.isExpiredOrInvalid(effectiveExpiresAt)) {
                        StreamRecordStatus.AVAILABLE.name
                    } else if (validUrl != null && SourceExpiryTime.evaluateExpiry(effectiveExpiresAt) == ExpiryState.EXPIRED) {
                        StreamRecordStatus.EXPIRED.name
                    } else {
                        StreamRecordStatus.MISSING_URL.name
                    },
                    version = (prev?.version ?: 0L) + 1L,
                    lastVerifiedAt = prev?.lastVerifiedAt,
                    lastPlaybackSuccessAt = prev?.lastPlaybackSuccessAt,
                    updatedAt = now
                )
                sourceEntities += sEntity
                providersMap.getOrPut(providerId) { mutableListOf() }.add(sEntity)
            }

            val providerEntities = providersMap.map { (providerId, pSources) ->
                StreamProviderEntity(
                    providerDocKey = "$scopeKey|p|$providerId",
                    scopeKey = scopeKey,
                    providerId = providerId,
                    providerName = pSources.first().sourceName,
                    status = "AVAILABLE",
                    sourceCount = pSources.size,
                    version = 1L,
                    updatedAt = now
                )
            }

            val discoveryStatus = when {
                dedupedDtos.state == EpisodeSourceResponseState.PARTIAL -> DiscoveryStatus.PARTIAL
                sourceEntities.isNotEmpty() -> DiscoveryStatus.COMPLETED
                dedupedDtos.state == EpisodeSourceResponseState.EMPTY -> DiscoveryStatus.COMPLETED
                else -> DiscoveryStatus.TEMPORARY_FAILURE
            }

            val seasonCount = if (isMovie) null else maxOf(effectiveSeason, episodeDao.getSeasonsForAnime(animeId).size.coerceAtLeast(1))
            val totalEps = if (isMovie) null else (anime?.episodeCount ?: episodeDao.getEpisodesForAnime(animeId).size)
            val seasonEpCount = if (isMovie) 0 else episodeDao.getEpisodesForAnime(animeId).count { it.seasonNumber == effectiveSeason }

            val discoveryPublished = streamingRepository.publishDiscoveryAndSyncRoom(
                contentKey = contentKey,
                anilistId = identity.anilistAnimeId,
                isMovie = isMovie,
                backendId = backendId,
                seasonNumber = effectiveSeason,
                episodeNumber = effectiveEpisode,
                catalogEpisodeId = catalogEpisodeId,
                backendEpisodeId = backendEpisodeIds.firstOrNull(),
                seasonCount = seasonCount,
                totalEpisodeCount = totalEps,
                seasonEpisodeCount = seasonEpCount,
                providers = providerEntities,
                sources = sourceEntities,
                discoveryStatus = discoveryStatus,
                canonicalTitle = identity.canonicalTitle
            )
            if (!discoveryPublished) {
                Log.w(
                    "AnimeRepository",
                    "Discovery persisted in Room; Firestore publish deferred/failed (${streamingRepository.lastWriteOutcome?.state}: ${streamingRepository.lastWriteRejectionReason ?: "queued"})"
                )
            }

            MonotonicMetrics.recordDuration("source_discovery_ms", discoveryStarted, "tier=BACKEND count=${dedupedDtos.sources.size}")
            Result.success(
                EpisodeSourceSnapshot(
                    sources = dedupedDtos.sources,
                    state = dedupedDtos.state,
                    backendStatus = dedupedDtos.backendStatus,
                    errorMessage = dedupedDtos.errorMessage,
                    fetchedAtMs = now,
                    seasonNumber = if (isMovie) null else effectiveSeason,
                    episodeNumber = if (isMovie) null else effectiveEpisode,
                    apiCandidateCount = dedupedDtos.apiCandidateCount,
                    parsedCandidateCount = dedupedDtos.parsedCandidateCount,
                    expiredCandidateCount = dedupedDtos.expiredCandidateCount,
                    exactDuplicateCount = dedupedDtos.exactDuplicateCount
                )
            )
        }
    }

    /**
     * Resolves an explicit backend catalog ID ONLY during streaming discovery/playback
     * when Firestore & Room don't already have one. Never called for Home or Search.
     */
    private suspend fun resolveBackendIdForDiscovery(
        animeId: String,
        anime: AnimeEntry?,
        excludeBackendIds: Set<String> = emptySet()
    ): String? {
        if (excludeBackendIds.isEmpty()) {
            anime?.backendAnimeId?.takeIf { it.isNotBlank() }?.let { return it }
            if (!animeId.startsWith("anilist_")) return animeId
            val canonicalId = anime?.canonicalAnimeId
            val aniListId = anime?.aniListId ?: animeId.removePrefix("anilist_").toIntOrNull()
            canonicalId?.let { id ->
                animeDao.getMappingForAniList(id)?.backendAnimeId?.takeIf { it.isNotBlank() }?.let { mapped ->
                    animeDao.updateBackendMappingForEntry(animeId, mapped)
                    return mapped
                }
            }
            aniListId?.let { id ->
                animeDao.getMappingForAniList(id)?.backendAnimeId?.takeIf { it.isNotBlank() }?.let { mapped ->
                    animeDao.updateBackendMappingForEntry(animeId, mapped)
                    return mapped
                }
            }
        } else if (!animeId.startsWith("anilist_")) {
            return animeId
        }

        val entry = anime ?: return null
        val aniListId = entry.aniListId ?: animeId.removePrefix("anilist_").toIntOrNull()
        val aliases = runCatching { animeDao.getAliasesForAnime(animeId) }.getOrDefault(emptyList())
            .map { it.originalAlias }
            .filter { it.isNotBlank() }
        val baseTitles = listOfNotNull(
            entry.canonicalTitle?.takeIf { it.isNotBlank() },
            entry.preferredTitle,
            entry.titleEnglish?.takeIf { it != entry.preferredTitle },
            entry.titleRomaji?.takeIf { it != entry.preferredTitle }
        ).distinct()
        val isMovie = ContentIdentityHelper.isMovie(entry)
        val queries = TitleMatcher.buildSearchQueryVariants(
            titles = baseTitles,
            aliases = aliases,
            isMovie = isMovie,
            canonicalTitle = entry.canonicalTitle
        )

        var bestItem: com.example.data.remote.ProphecySearchResultItem? = null
        var bestConfidence = 0f
        var bestSelectionScore = 0f
        val expectsMultiSeason = !isMovie && (
            entry.canonicalAnimeId != null ||
                (entry.canonicalSeasonNumber ?: 1) > 1 ||
                TitleMatcher.hasSequelMarker(baseTitles + aliases)
            )

        for (q in queries) {
            val res = runCatching { prophecyApi.searchAnime(query = q, limit = 15) }.getOrNull() ?: continue
            if (!res.isSuccessful) continue
            for (item in res.body()?.results.orEmpty()) {
                if (item.id.isBlank() || item.id in excludeBackendIds) continue
                val (confidence, score) = TitleMatcher.scoreBackendCandidateForEntry(entry, item, aliases)
                if (confidence >= 0.75f && score > bestSelectionScore) {
                    bestItem = item
                    bestConfidence = confidence
                    bestSelectionScore = score
                }
            }
            // Do not settle early on a seasonCount == null/1 addon-only duplicate when sequel/franchise matching
            if (bestItem != null && (!expectsMultiSeason || (bestItem.seasonCount ?: 0) >= 2) && bestConfidence >= 0.90f) {
                break
            }
        }

        val chosen = bestItem ?: return null
        val matchedId = chosen.id
        animeDao.updateBackendMappingForEntry(animeId, matchedId)
        aniListId?.let {
            animeDao.upsertMapping(CatalogMapping(aniListId = it, backendAnimeId = matchedId, confidence = bestConfidence))
        }
        entry.canonicalAnimeId?.takeIf { it > 0 && (chosen.seasonCount ?: 0) >= 2 }?.let { canonId ->
            animeDao.upsertMapping(CatalogMapping(aniListId = canonId, backendAnimeId = matchedId, confidence = bestConfidence))
        }
        return matchedId
    }

    /**
     * Resolves backend episode IDs internally:
     * - For Movies: handles Backend's `"S1 & E1"` / `"S1E1"` compatibility response strictly inside the
     *   Backend adapter and NEVER persists a Season/Episode row for the Movie.
     * - For Anime Series: maps AniList sequel entries to the matching Backend season and matches
     *   relative Season N numbers, disjoint absolute blocks in Season N, and strict continuous
     *   cumulative numbers (`offset + epNum > offset`) when a provider merges seasons under Season 1.
     */
    private suspend fun resolveBackendEpisodeIdsForDiscovery(
        animeId: String,
        backendId: String,
        isMovie: Boolean,
        seasonNumber: Int,
        episodeNumber: Int,
        catalogEpisodeId: String?,
        cumulativeOffset: Int = 0,
        allowCachedIds: Boolean = true
    ): List<String> {
        val anime = animeDao.getAnimeById(animeId)
        val contentKey = ContentIdentityHelper.canonicalContentKey(animeId, anime)
        if (!isMovie && allowCachedIds) {
            val cachedStreamEp = streamCacheDao.getEpisode("$contentKey:s$seasonNumber:e$episodeNumber")
            val existingIds: List<String> = cachedStreamEp?.backendSourceEpisodeIds?.let { encoded ->
                EpisodeSourceEpisodeIds.decode(encoded)
            }.orEmpty()
            if (existingIds.isNotEmpty()) return existingIds
            cachedStreamEp?.backendEpisodeId?.takeIf { it.isNotBlank() }?.let { return listOf(it) }

            catalogEpisodeId?.let { id ->
                episodeDao.getEpisodeById(id)?.let { ep ->
                    val parsed = EpisodeSourceEpisodeIds.read(ep)
                    if (parsed.any { !it.startsWith("anilist_ep_") }) {
                        return parsed.filterNot { it.startsWith("anilist_ep_") }
                    }
                }
            }
        } else if (isMovie && allowCachedIds) {
            val existingMovieSources = streamCacheDao.getActiveSources(ContentIdentityHelper.movieScopeKey(contentKey))
            val existingBackendEpIds = existingMovieSources.mapNotNull { it.backendEpisodeId?.takeIf(String::isNotBlank) }.distinct()
            if (existingBackendEpIds.isNotEmpty()) return existingBackendEpIds
        }

        val (detailResp, epResp) = coroutineScope {
            val d = async { runCatching { prophecyApi.getAnimeDetails(backendId) }.getOrNull() }
            val e = async { runCatching { prophecyApi.getAnimeEpisodes(backendId) }.getOrNull() }
            d.await() to e.await()
        }

        if (detailResp?.code() in listOf(404, 410)) {
            Log.w("AnimeRepository", "Cached backendId=$backendId returned HTTP ${detailResp?.code()}; clearing stale mapping.")
            invalidateStaleBackendMapping(animeId, anime, backendId)
            return emptyList()
        }

        val backendDetails = detailResp?.takeIf { it.isSuccessful }?.body()
        if (backendDetails != null) queueBackendCatalogMetadata(backendDetails)
        if (backendDetails != null && !isValidBackendDetailsForEntry(animeId, anime, backendDetails)) {
            Log.w("AnimeRepository", "Cached backendId=$backendId failed entry validation for $animeId; clearing stale mapping.")
            invalidateStaleBackendMapping(animeId, anime, backendId)
            return emptyList()
        }

        val backendEpisodes = mutableListOf<ProphecyEpisodeDto>()
        backendDetails?.seasons.orEmpty().forEach { s ->
            s.episodes.orEmpty().forEach { ep ->
                backendEpisodes += ep.copy(season = ep.season ?: s.number)
            }
        }
        if (epResp?.isSuccessful == true) {
            backendEpisodes += epResp.body().orEmpty()
        }

        if (isMovie) {
            // Translate Backend "S1 & E1" / "S1E1" movie compatibility response internally into Movie backendEpisodeId
            val movieEp = backendEpisodes.firstOrNull { ep ->
                (ep.season == 1 && ep.number == 1) ||
                    ep.title?.contains("S1 & E1", ignoreCase = true) == true ||
                    ep.id.contains("S1E1", ignoreCase = true)
            } ?: backendEpisodes.firstOrNull()
            return listOfNotNull(movieEp?.id?.takeIf { it.isNotBlank() })
        }

        val targetSeason = resolveEffectiveBackendSeason(
            anime = anime,
            requestedSeason = seasonNumber,
            backendDetails = backendDetails,
            backendEpisodes = backendEpisodes,
            catalogEpisodeId = catalogEpisodeId
        )

        var matched = matchEpisodesInSeason(
            backendEpisodes = backendEpisodes,
            targetSeason = targetSeason,
            requestedSeason = seasonNumber,
            episodeNumber = episodeNumber,
            cumulativeOffset = cumulativeOffset
        )
        if (matched.isEmpty() && targetSeason != seasonNumber) {
            matched = matchEpisodesInSeason(
                backendEpisodes = backendEpisodes,
                targetSeason = seasonNumber,
                requestedSeason = seasonNumber,
                episodeNumber = episodeNumber,
                cumulativeOffset = cumulativeOffset
            )
        }

        if (matched.isNotEmpty() && catalogEpisodeId != null) {
            episodeDao.getEpisodeById(catalogEpisodeId)?.let { existingEp ->
                val encoded = EpisodeSourceEpisodeIds.encode(matched)
                episodeDao.upsertEpisodes(listOf(existingEp.copy(sourceEpisodeIds = encoded)))
            }
        }
        return matched
    }

    private fun isDedicatedSequelBackendEntry(
        anime: AnimeEntry?,
        backendDetails: ProphecyAnimeDetailsResponse?,
        backendEpisodes: List<ProphecyEpisodeDto>,
        requestedSeason: Int
    ): Boolean {
        if (requestedSeason <= 1 || backendDetails == null) return false
        val availablePositiveSeasons = backendEpisodes
            .mapNotNull { it.season?.takeIf { s -> s > 0 } }
            .distinct()
        if (requestedSeason in availablePositiveSeasons) return false
        if (availablePositiveSeasons.any { it > 1 }) return false

        val backendTitles = listOfNotNull(
            backendDetails.title,
            backendDetails.titleEnglish,
            backendDetails.titleRomaji
        )
        if (TitleMatcher.hasSequelMarker(backendTitles)) return true

        val entryYear = anime?.year?.toIntOrNull()
        val backendYear = backendDetails.year?.toIntOrNull()
        val normCanonical = TitleMatcher.normalize(anime?.canonicalTitle)
        val normBackend = TitleMatcher.normalize(backendDetails.title)
        return entryYear != null &&
            backendYear != null &&
            kotlin.math.abs(entryYear - backendYear) <= 1 &&
            normCanonical.isNotBlank() &&
            normBackend != normCanonical
    }

    private suspend fun resolveEffectiveBackendSeason(
        anime: AnimeEntry?,
        requestedSeason: Int,
        backendDetails: ProphecyAnimeDetailsResponse?,
        backendEpisodes: List<ProphecyEpisodeDto>,
        catalogEpisodeId: String?
    ): Int {
        if (requestedSeason > 1) {
            return if (isDedicatedSequelBackendEntry(anime, backendDetails, backendEpisodes, requestedSeason)) {
                1
            } else {
                requestedSeason
            }
        }
        if (anime == null) return requestedSeason
        val backendYear = backendDetails?.year?.toIntOrNull()
        val availablePositiveSeasons = backendEpisodes
            .mapNotNull { it.season?.takeIf { s -> s > 0 } }
            .distinct()
            .sorted()
        if (availablePositiveSeasons.size <= 1) return requestedSeason

        val aliases = runCatching { animeDao.getAliasesForAnime(anime.id) }.getOrDefault(emptyList())
            .map { it.originalAlias }
        val titles = listOfNotNull(
            anime.preferredTitle,
            anime.titleEnglish,
            anime.titleRomaji
        ) + aliases

        // 1. Check if AniList Episode 1 has a real non-fallback title that uniquely matches a backend season
        val ep1Title = catalogEpisodeId?.let { episodeDao.getEpisodeById(it) }
            ?.takeIf { it.episodeNumber == 1 }
            ?.title
            ?.takeIf { it.isNotBlank() && !it.equals("Episode 1", ignoreCase = true) }
            ?: run {
                episodeDao.getEpisodesForAnime(anime.id)
                    .firstOrNull { it.seasonNumber == requestedSeason && it.episodeNumber == 1 }
                    ?.title
                    ?.takeIf { it.isNotBlank() && !it.equals("Episode 1", ignoreCase = true) }
            }
        if (!ep1Title.isNullOrBlank()) {
            val normEp1 = TitleMatcher.normalize(ep1Title)
            val ep1SeasonMatch = backendEpisodes
                .firstOrNull { ep ->
                    (ep.season ?: 0) > 0 && ep.title != null && TitleMatcher.normalize(ep.title) == normEp1
                }?.season
            if (ep1SeasonMatch != null && ep1SeasonMatch in availablePositiveSeasons) {
                return ep1SeasonMatch
            }
        }

        // 2. Explicit season marker in entry titles (e.g. "Season 2", "3rd Season", "Final Season")
        TitleMatcher.extractSeasonHint(titles)?.let { hint ->
            if (hint in availablePositiveSeasons) return hint
        }
        if (titles.any { it.contains("final season", ignoreCase = true) }) {
            val substantialLast = availablePositiveSeasons.lastOrNull { sNum ->
                backendEpisodes.count { it.season == sNum } >= 2
            } ?: availablePositiveSeasons.last()
            return substantialLast
        }

        // 3. Subtitled sequel entry whose premiere year is strictly later than the Season 1 backend entry
        val entryYear = anime.year?.toIntOrNull()
        if (entryYear != null && backendYear != null && entryYear > backendYear) {
            val laterSeasons = availablePositiveSeasons.filter { sNum ->
                sNum > 1 && backendEpisodes.count { it.season == sNum } >= 2
            }
            if (laterSeasons.size == 1) {
                return laterSeasons.first()
            }
            if (laterSeasons.isNotEmpty() && TitleMatcher.hasSequelMarker(titles)) {
                return laterSeasons.first()
            }
        }

        return requestedSeason
    }

    private fun matchEpisodesInSeason(
        backendEpisodes: List<ProphecyEpisodeDto>,
        targetSeason: Int,
        requestedSeason: Int,
        episodeNumber: Int,
        cumulativeOffset: Int = 0
    ): List<String> {
        val inSeason = backendEpisodes.filter { ep ->
            ep.id.isNotBlank() && (ep.season == targetSeason || (ep.season == null && targetSeason == 1))
        }

        val directMatches = inSeason
            .filter { it.number == episodeNumber }
            .map { it.id }
            .distinct()

        // Disjoint absolute episode blocks inside targetSeason (e.g. 26..37 in Season 2)
        val mappedAbsoluteMatches = mutableListOf<String>()
        if (targetSeason > 1 && inSeason.isNotEmpty()) {
            val sortedNumbers = inSeason.map { it.number }.filter { it > 0 }.distinct().sorted()
            val blocks = mutableListOf<Pair<Int, Int>>()
            var blockStart: Int? = null
            var prev = -1
            for (n in sortedNumbers) {
                if (blockStart == null) {
                    blockStart = n
                } else if (n > prev + 1) {
                    blocks += blockStart to prev
                    blockStart = n
                }
                prev = n
            }
            if (blockStart != null) blocks += blockStart to prev

            for ((start, end) in blocks) {
                if (start > 1) {
                    val targetNum = start + episodeNumber - 1
                    if (targetNum in start..end) {
                        inSeason.filter { it.number == targetNum }.forEach { mappedAbsoluteMatches += it.id }
                    }
                }
            }
        }

        // Strict continuous cumulative numbering under Season 1 (e.g. Dramachi putting S2 Ep1 at S1 E171):
        // ONLY match numbers strictly greater than cumulativeOffset; NEVER touch Season 1 (1..cumulativeOffset).
        val continuousMatches = mutableListOf<String>()
        if (requestedSeason > 1 && cumulativeOffset > 0 && targetSeason > 1) {
            val cumulativeNum = cumulativeOffset + episodeNumber
            if (cumulativeNum > cumulativeOffset) {
                backendEpisodes.asSequence()
                    .filter { ep ->
                        ep.id.isNotBlank() &&
                            (ep.season == 1 || ep.season == null) &&
                            ep.number == cumulativeNum &&
                            ep.number > cumulativeOffset
                    }
                    .forEach { continuousMatches += it.id }
            }
        }

        return (directMatches + mappedAbsoluteMatches + continuousMatches).distinct()
    }

    // ============================================================================================
    // 3. CACHE-FIRST PLAYBACK RESOLUTION (Room -> Firestore -> Other Sources -> Distributed-Locked Resolve)
    // ============================================================================================

    suspend fun resolvePlayableStream(
        animeId: String,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        catalogEpisodeId: String? = null,
        preferredSourceId: String? = null,
        forceResolveFresh: Boolean = false
    ): Result<ResolvedStreamPlayback> = withContext(Dispatchers.IO) {
        val playbackResolveStarted = MonotonicMetrics.nowMs()
        val anime = animeDao.getAnimeById(animeId)
        val identity = ContentIdentityHelper.resolveCanonicalIdentity(animeId, anime, seasonNumber, episodeNumber)
        val isMovie = identity.isMovie
        val contentKey = identity.contentKey
        val effectiveSeason = identity.seasonNumber
        val effectiveEpisode = identity.episodeNumber
        val scopeKey = ContentIdentityHelper.scopeKey(contentKey, isMovie, effectiveSeason, effectiveEpisode)

        // Ensure discovery is available cleanly (Room -> Firestore -> Backend discovery)
        var roomSources = streamCacheDao.getActiveSources(scopeKey)
        var hydratedFromFirestore = false
        var discoveryError: Throwable? = null
        if (roomSources.isEmpty()) {
            discoveryError = discoverStreamsForScope(
                animeId, effectiveSeason, effectiveEpisode, catalogEpisodeId, forceRefresh = false
            ).exceptionOrNull()
            roomSources = streamCacheDao.getActiveSources(scopeKey)
            hydratedFromFirestore = roomSources.any {
                SafePlaybackHeaders.isValidStreamUrl(it.streamUrl) &&
                    !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt) &&
                    it.status !in setOf("REMOVED", "EXPIRED", "FAILED")
            }
        }

        // 1. Check Room/synced Stream URL cache (unless explicit forceResolveFresh requested after failure)
        val orderedRoomSources = orderSourcesForPlayback(roomSources, preferredSourceId)
        val candidatesDto = orderedRoomSources.map { it.toProphecyDto() }
        if (!forceResolveFresh) {
            for (cachedSource in orderedRoomSources) {
                val url = cachedSource.streamUrl
                if (SafePlaybackHeaders.isValidStreamUrl(url) &&
                    !SourceExpiryTime.isExpiredOrInvalid(cachedSource.expiresAt) &&
                    cachedSource.status !in setOf("REMOVED", "EXPIRED", "FAILED")
                ) {
                    val data = cachedSource.toDirectPlayData()
                    val resolvedTier = if (hydratedFromFirestore) "FIRESTORE" else "ROOM"
                    val metricTag = if (hydratedFromFirestore) "tier=FIRESTORE" else "tier=ROOM"
                    val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, metricTag)
                    return@withContext Result.success(
                        ResolvedStreamPlayback(
                            source = cachedSource.toProphecyDto(),
                            directData = data,
                            candidates = candidatesDto,
                            fromCache = true,
                            cacheTier = resolvedTier,
                            resolveDurationMs = elapsed
                        )
                    )
                }
            }

            // 2. Check Firestore shared cache before calling Resolve
            val firestoreOutcome = streamingRepository.fetchAndSyncScopeWithOutcome(
                contentKey = contentKey,
                isMovie = isMovie,
                seasonNumber = effectiveSeason,
                episodeNumber = effectiveEpisode,
                catalogEpisodeId = catalogEpisodeId,
                preferServer = true,
                requirePlayableUrl = true
            )
            val firestoreData = firestoreOutcome.data
            if (firestoreData != null) {
                val orderedFs = orderSourcesForPlayback(
                    firestoreData.sources.filter { it.status != "REMOVED" && it.deletedAt == null },
                    preferredSourceId
                )
                for (fsSource in orderedFs) {
                    if (SafePlaybackHeaders.isValidStreamUrl(fsSource.streamUrl) &&
                        !SourceExpiryTime.isExpiredOrInvalid(fsSource.expiresAt)
                    ) {
                        val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, "tier=FIRESTORE")
                        return@withContext Result.success(
                            ResolvedStreamPlayback(
                                source = fsSource.toProphecyDto(),
                                directData = fsSource.toDirectPlayData(),
                                candidates = orderedFs.map { it.toProphecyDto() },
                                fromCache = true,
                                cacheTier = "FIRESTORE",
                                resolveDurationMs = elapsed
                            )
                        )
                    }
                }
            }
        }

        // Re-read after Firestore sync
        val refreshedRoomSources = orderSourcesForPlayback(streamCacheDao.getActiveSources(scopeKey), preferredSourceId)
        val allCandidates = refreshedRoomSources.map { it.toProphecyDto() }

        // 3. No valid cached Stream URL -> Distributed lease + Backend Resolve
        var lastError: Throwable? = null
        for (sourceEntity in refreshedRoomSources) {
            if (sourceEntity.sourceId.isBlank()) continue
            var lease = streamingRepository.acquireResolveLease(
                contentKey = contentKey,
                isMovie = isMovie,
                providerId = sourceEntity.providerId,
                sourceId = sourceEntity.sourceId,
                seasonNumber = if (isMovie) 0 else effectiveSeason,
                episodeNumber = if (isMovie) 0 else effectiveEpisode
            )

            // If another device/coroutine holds an active resolve lease for this exact source, wait cleanly and check Firestore
            if (lease == null) {
                delay(700L)
                val peerSync = streamingRepository.fetchAndSyncScopeWithOutcome(
                    contentKey, isMovie, effectiveSeason, effectiveEpisode, catalogEpisodeId,
                    preferServer = true, requirePlayableUrl = true
                ).data
                val peerHit = peerSync?.sources?.firstOrNull {
                    it.sourceId == sourceEntity.sourceId &&
                        it.status != "REMOVED" &&
                        SafePlaybackHeaders.isValidStreamUrl(it.streamUrl) &&
                        !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt)
                }
                if (peerHit != null) {
                    val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, "tier=FIRESTORE")
                    return@withContext Result.success(
                        ResolvedStreamPlayback(
                            source = peerHit.toProphecyDto(),
                            directData = peerHit.toDirectPlayData(),
                            candidates = allCandidates,
                            fromCache = true,
                            cacheTier = "FIRESTORE",
                            resolveDurationMs = elapsed
                        )
                    )
                }
                // Re-attempt lease acquisition if previous holder finished without a cached hit
                lease = streamingRepository.acquireResolveLease(
                    contentKey = contentKey,
                    isMovie = isMovie,
                    providerId = sourceEntity.providerId,
                    sourceId = sourceEntity.sourceId,
                    seasonNumber = if (isMovie) 0 else effectiveSeason,
                    episodeNumber = if (isMovie) 0 else effectiveEpisode
                )
                if (lease == null) {
                    lastError = IllegalStateException("Source resolution currently in progress on another worker.")
                    continue
                }
            }

            var finalLeaseStatus = "FAILED"
            try {
                val res = resolveDirectPlayback(sourceEntity.sourceId, forceRefresh = forceResolveFresh)
                val playData = res.getOrNull()
                if (playData != null) {
                    val validateStarted = MonotonicMetrics.nowMs()
                    val requestHeaders = SafePlaybackHeaders.sanitizeRequestHeaders(playData.headers)
                    val safeHeaders = SafePlaybackHeaders.sanitize(requestHeaders)
                    val isUrlValid = SafePlaybackHeaders.isValidStreamUrl(playData.playbackUrl) &&
                        !SourceExpiryTime.isExpiredOrInvalid(playData.expiresAt)
                    MonotonicMetrics.recordDuration("url_validation_ms", validateStarted, "valid=$isUrlValid")

                    if (isUrlValid) {
                        val sanitizedData = playData.copy(headers = requestHeaders)
                        val published = streamingRepository.publishResolvedStreamUrl(
                            sourceEntity = sourceEntity,
                            contentKey = contentKey,
                            isMovie = isMovie,
                            seasonNumber = effectiveSeason,
                            episodeNumber = effectiveEpisode,
                            streamUrl = sanitizedData.playbackUrl,
                            urlType = sanitizedData.urlType,
                            contentType = sanitizedData.contentType,
                            safeHeaders = safeHeaders,
                            expiresAt = sanitizedData.expiresAt,
                            anilistId = identity.anilistAnimeId,
                            backendId = identity.backendAnimeId ?: anime?.backendAnimeId,
                            canonicalTitle = identity.canonicalTitle
                        )
                        if (!published) {
                            Log.w("AnimeRepository", "Resolved stream URL persisted to Room; cloud sync deferred (${streamingRepository.lastWriteRejectionReason ?: "queued"})")
                        }
                        finalLeaseStatus = "COMPLETED"
                        val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, "tier=BACKEND_RESOLVE")
                        return@withContext Result.success(
                            ResolvedStreamPlayback(
                                source = sourceEntity.toProphecyDto(),
                                directData = sanitizedData,
                                candidates = allCandidates,
                                fromCache = false,
                                cacheTier = "BACKEND_RESOLVE",
                                resolveDurationMs = elapsed
                            )
                        )
                    } else {
                        lastError = IllegalStateException("Resolved playback URL was invalid or expired.")
                    }
                } else {
                    lastError = res.exceptionOrNull()
                }
            } catch (cancelled: CancellationException) {
                finalLeaseStatus = "CANCELLED"
                throw cancelled
            } catch (e: Exception) {
                lastError = e
            } finally {
                lease.let { heldLease ->
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            streamingRepository.releaseResolveLease(heldLease, finalLeaseStatus)
                        }
                    }
                }
            }
        }

        // Part 10 rule: failed resolve must NEVER erase a prior working URL; report error cleanly
        Result.failure(lastError ?: discoveryError ?: IllegalStateException("No playable source available right now."))
    }

    suspend fun reportPlaybackSuccess(
        animeId: String,
        sourceId: String,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1
    ) = withContext(Dispatchers.IO) {
        val anime = animeDao.getAnimeById(animeId)
        val identity = ContentIdentityHelper.resolveCanonicalIdentity(animeId, anime, seasonNumber, episodeNumber)
        val scopeKey = ContentIdentityHelper.scopeKey(identity.contentKey, identity.isMovie, identity.seasonNumber, identity.episodeNumber)
        val entity = streamCacheDao.getActiveSources(scopeKey).firstOrNull { it.sourceId == sourceId } ?: return@withContext
        val ok = streamingRepository.recordPlaybackSuccess(
            sourceEntity = entity,
            contentKey = identity.contentKey,
            isMovie = identity.isMovie,
            seasonNumber = identity.seasonNumber,
            episodeNumber = identity.episodeNumber,
            canonicalTitle = identity.canonicalTitle,
            backendId = identity.backendAnimeId ?: anime?.backendAnimeId
        )
        if (!ok) {
            Log.w("AnimeRepository", "Playback success queued for retry (${streamingRepository.lastWriteRejectionReason ?: "deferred"})")
        }
    }

    private suspend fun isValidBackendDetailsForEntry(
        animeId: String,
        entry: AnimeEntry?,
        details: ProphecyAnimeDetailsResponse
    ): Boolean {
        if (entry == null) return true
        val entryIsMovie = ContentIdentityHelper.isMovie(entry)
        val detailMedia = details.mediaType?.trim()?.lowercase()
        if (!entry.format.isNullOrBlank() && detailMedia in setOf("movie", "film", "anime", "tv", "series", "kdrama")) {
            val detailIsMovie = detailMedia == "movie" || detailMedia == "film"
            if (entryIsMovie != detailIsMovie) return false
        }
        val detailTitle = details.title.trim()
        if (detailTitle.isBlank()) return true
        val aliases = runCatching { animeDao.getAliasesForAnime(animeId) }.getOrDefault(emptyList())
            .map { it.originalAlias }
            .filter { it.isNotBlank() }
        val syntheticItem = com.example.data.remote.ProphecySearchResultItem(
            id = details.id,
            title = detailTitle,
            titleEnglish = details.titleEnglish,
            titleRomaji = details.titleRomaji,
            year = details.year,
            mediaType = details.mediaType,
            seasonCount = details.seasonCount
        )
        val confidence = TitleMatcher.computeMatchConfidenceForEntry(entry, syntheticItem, aliases)
        return confidence >= 0.45f
    }

    private suspend fun invalidateStaleBackendMapping(
        animeId: String,
        entry: AnimeEntry?,
        staleBackendId: String
    ) {
        runCatching { animeDao.clearBackendMappingForEntry(animeId) }
        val idsToCheck = listOfNotNull(
            entry?.aniListId,
            entry?.canonicalAnimeId,
            animeId.removePrefix("anilist_").toIntOrNull()
        ).distinct()
        for (id in idsToCheck) {
            runCatching {
                val mapping = animeDao.getMappingForAniList(id)
                if (mapping?.backendAnimeId == staleBackendId) {
                    animeDao.deleteMappingForAniList(id)
                }
            }
        }
    }

    private fun orderSourcesForPlayback(
        sources: List<StreamSourceEntity>,
        preferredSourceId: String?
    ): List<StreamSourceEntity> {
        val active = sources.filter { it.status != "REMOVED" && it.deletedAt == null }
        if (!preferredSourceId.isNullOrBlank()) {
            return active.sortedWith(
                compareBy<StreamSourceEntity> { if (it.sourceId == preferredSourceId) 0 else 1 }
                    .thenByDescending { SafePlaybackHeaders.isValidStreamUrl(it.streamUrl) && !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt) }
                    .thenByDescending { it.lastPlaybackSuccessAt ?: 0L }
            )
        }
        return active.sortedWith(
            compareByDescending<StreamSourceEntity> { SafePlaybackHeaders.isValidStreamUrl(it.streamUrl) && !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt) }
                .thenByDescending { it.lastPlaybackSuccessAt ?: 0L }
                .thenByDescending { it.downloadable == true }
        )
    }

    private suspend fun readRoomScopeSnapshot(
        scopeKey: String,
        animeId: String,
        isMovie: Boolean,
        seasonNumber: Int,
        episodeNumber: Int
    ): EpisodeSourceSnapshot? {
        val started = MonotonicMetrics.nowMs()
        val contentKey = ContentIdentityHelper.canonicalContentKey(animeId, animeDao.getAnimeById(animeId))
        val container = streamCacheDao.getContainer(ContentIdentityHelper.containerScopeKey(contentKey, isMovie))
        val episodeRecord = if (isMovie) null else streamCacheDao.getEpisode("$contentKey:s$seasonNumber:e$episodeNumber")
        val scopeDiscoveryStatus = if (isMovie) container?.discoveryStatus else episodeRecord?.discoveryStatus
        val scopeUpdatedAt = if (isMovie) container?.updatedAt else episodeRecord?.updatedAt
        val sources = streamCacheDao.getActiveSources(scopeKey)
        MonotonicMetrics.recordDuration("room_read_ms", started, "scope=$scopeKey count=${sources.size}")
        if (sources.isEmpty() && scopeDiscoveryStatus != DiscoveryStatus.COMPLETED.name) return null
        val dtos = sources.map { it.toProphecyDto() }
        val latestUpdated = sources.maxOfOrNull { it.updatedAt } ?: scopeUpdatedAt ?: container?.updatedAt ?: 0L
        val state = when {
            dtos.isNotEmpty() -> EpisodeSourceResponseState.AVAILABLE
            scopeDiscoveryStatus == DiscoveryStatus.COMPLETED.name -> EpisodeSourceResponseState.EMPTY
            scopeDiscoveryStatus == DiscoveryStatus.PARTIAL.name -> EpisodeSourceResponseState.PARTIAL
            else -> EpisodeSourceResponseState.UNKNOWN
        }
        return EpisodeSourceSnapshot(
            sources = dtos,
            state = state,
            fetchedAtMs = latestUpdated,
            seasonNumber = if (isMovie) null else seasonNumber,
            episodeNumber = if (isMovie) null else episodeNumber
        )
    }

    private suspend fun staleRoomOrFailure(
        scopeKey: String,
        animeId: String,
        isMovie: Boolean,
        seasonNumber: Int,
        episodeNumber: Int,
        message: String
    ): Result<EpisodeSourceSnapshot> {
        val existing = readRoomScopeSnapshot(scopeKey, animeId, isMovie, seasonNumber, episodeNumber)
        if (existing != null && existing.sources.isNotEmpty()) {
            return Result.success(existing.copy(state = EpisodeSourceResponseState.STALE, errorMessage = message))
        }
        return Result.failure(IllegalStateException(message))
    }

    private fun StreamSourceEntity.toProphecyDto(): ProphecySourceDto = ProphecySourceDto(
        id = sourceId,
        label = sourceName,
        quality = quality,
        codec = codec,
        language = audioLanguage,
        sizeBytes = sizeBytes,
        downloadable = downloadable,
        expiresAt = expiresAt
    )

    private fun StreamSourceEntity.toDirectPlayData(): DirectPlayData = DirectPlayData(
        mode = "direct",
        sourceId = sourceId,
        playbackUrl = streamUrl.orEmpty(),
        urlType = urlType,
        compatibility = "verified",
        requiresHeaders = !safeHeadersJson.isNullOrBlank(),
        headers = SafePlaybackHeaders.fromJson(safeHeadersJson),
        contentType = contentType,
        expiresAt = expiresAt
    )

    // ============================================================================================
    // Compatibility & Direct-Play Endpoint (POST /api/v1/play/direct ONLY — no /stream, /remux, /watch)
    // ============================================================================================

    suspend fun getCachedEpisodeSourceSnapshot(
        backendAnimeId: String,
        episodeId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): EpisodeSourceSnapshot? = withContext(Dispatchers.IO) {
        val anime = animeDao.getAnimeById(backendAnimeId) ?: animeDao.getAnimeByBackendId(backendAnimeId)
        if (anime != null) {
            val identity = ContentIdentityHelper.resolveCanonicalIdentity(
                animeId = anime.id,
                entry = anime,
                requestedSeasonNumber = seasonNumber ?: 1,
                requestedEpisodeNumber = episodeNumber ?: 1
            )
            val scopeKey = ContentIdentityHelper.scopeKey(
                contentKey = identity.contentKey,
                isMovie = identity.isMovie,
                seasonNumber = identity.seasonNumber,
                episodeNumber = identity.episodeNumber
            )
            readRoomScopeSnapshot(scopeKey, anime.id, identity.isMovie, identity.seasonNumber, identity.episodeNumber)?.let { return@withContext it }
        }
        val cacheKey = sourceCacheKey(backendAnimeId, seasonNumber, episodeId)
        readBestCachedSourceSnapshot(cacheKey, seasonNumber, episodeNumber)
    }

    suspend fun fetchEpisodeSources(
        backendAnimeId: String,
        episodeId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        forceRefresh: Boolean = false
    ): Result<List<ProphecySourceDto>> = fetchEpisodeSourceSnapshot(
        backendAnimeId, episodeId, forceRefresh, seasonNumber, episodeNumber
    ).map { it.sources }

    suspend fun fetchEpisodeSourceSnapshotForCanonical(
        backendAnimeId: String,
        episode: EpisodeEntry,
        forceRefresh: Boolean = false
    ): Result<EpisodeSourceSnapshot> {
        val anime = animeDao.getAnimeById(episode.animeId) ?: animeDao.getAnimeByBackendId(backendAnimeId)
        if (anime != null) {
            return discoverStreamsForScope(
                animeId = anime.id,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                catalogEpisodeId = episode.id,
                forceRefresh = forceRefresh
            )
        }
        val snapshots = mutableListOf<EpisodeSourceSnapshot>()
        var lastError: Throwable? = null
        for (lookupId in EpisodeSourceEpisodeIds.read(episode)) {
            val result = fetchEpisodeSourceSnapshot(backendAnimeId, lookupId, forceRefresh, episode.seasonNumber, episode.episodeNumber)
            result.getOrNull()?.let(snapshots::add) ?: run { lastError = result.exceptionOrNull() }
        }
        if (snapshots.isEmpty()) return Result.failure(lastError ?: IllegalStateException("No source response for canonical episode"))
        val candidates = snapshots.flatMap { it.sources }.distinctBy { it.stableIdentity }
        val preferred = snapshots.firstOrNull { it.sources.isNotEmpty() } ?: snapshots.first()
        return Result.success(
            preferred.copy(
                sources = candidates,
                state = if (candidates.isNotEmpty()) EpisodeSourceResponseState.AVAILABLE else preferred.state
            )
        )
    }

    suspend fun fetchEpisodeSourcesForCanonical(
        backendAnimeId: String,
        episode: EpisodeEntry,
        forceRefresh: Boolean = false
    ): Result<List<ProphecySourceDto>> =
        fetchEpisodeSourceSnapshotForCanonical(backendAnimeId, episode, forceRefresh).map { it.sources }

    suspend fun fetchEpisodeSourceSnapshot(
        backendAnimeId: String,
        episodeId: String,
        forceRefresh: Boolean = false,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<EpisodeSourceSnapshot> = withContext(Dispatchers.IO) {
        val cacheKey = sourceCacheKey(backendAnimeId, seasonNumber, episodeId)
        val cachedAtStart = readBestCachedSourceSnapshot(cacheKey, seasonNumber, episodeNumber)
        if (!forceRefresh && cachedAtStart != null && !cachedAtStart.isStale) {
            return@withContext Result.success(cachedAtStart)
        }
        sourceFetchMutexes.computeIfAbsent(cacheKey) { Mutex() }.withLock {
            val cached = readBestCachedSourceSnapshot(cacheKey, seasonNumber, episodeNumber)
            if (!forceRefresh && cached != null && !cached.isStale) {
                return@withContext Result.success(cached)
            }
            val retryRemainingMs = sourceRetryRemainingMs(cacheKey)
            if (EpisodeSourceRetryBackoff.shouldDelay(forceRefresh, retryRemainingMs)) {
                return@withContext staleSourceFallback(cached, seasonNumber, episodeNumber, "Source retry is backed off.")
            }
            val started = MonotonicMetrics.nowMs()
            try {
                val response = prophecyApi.getEpisodeFull(backendAnimeId, episodeId)
                MonotonicMetrics.recordDuration("source_discovery_ms", started, "http=${response.code()}")
                if (!response.isSuccessful || response.body() == null) {
                    if (EpisodeSourceRetryBackoff.isRetryableHttpStatus(response.code()) || response.body() == null) {
                        recordSourceFailure(cacheKey)
                    }
                    return@withContext staleSourceFallback(cached, seasonNumber, episodeNumber, "HTTP ${response.code()}")
                }
                val body = response.body()!!
                clearSourceFailure(cacheKey)
                val aggregated = EpisodeSourceAggregator.aggregate(
                    apiCandidates = body.sources,
                    rawStatus = body.sourcesStatus,
                    partial = body.sourcesPartial,
                    errorMessage = body.sourceError,
                    nowMs = System.currentTimeMillis()
                )
                val authoritativeComplete = body.sourcesPartial == false && body.sources != null && body.sourceError.isNullOrBlank()
                val mergedCandidates = EpisodeSourceCachePolicy.mergeRefreshCandidates(
                    existingCandidates = cached?.sources.orEmpty(),
                    incomingCandidates = aggregated.sources,
                    authoritativeComplete = authoritativeComplete,
                    nowMs = System.currentTimeMillis()
                )
                val snapshot = EpisodeSourceSnapshot(
                    sources = mergedCandidates,
                    state = aggregated.state,
                    backendStatus = aggregated.backendStatus,
                    errorMessage = aggregated.errorMessage,
                    fetchedAtMs = System.currentTimeMillis(),
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    apiCandidateCount = aggregated.apiCandidateCount,
                    parsedCandidateCount = aggregated.parsedCandidateCount,
                    expiredCandidateCount = aggregated.expiredCandidateCount,
                    exactDuplicateCount = aggregated.exactDuplicateCount
                )
                storeSourceSnapshot(cacheKey, backendAnimeId, episodeId, snapshot)
                Result.success(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                recordSourceFailure(cacheKey)
                staleSourceFallback(cached, seasonNumber, episodeNumber, e.message ?: "Source fetch error", e)
            }
        }
    }

    suspend fun resolveDirectPlayback(sourceId: String, forceRefresh: Boolean = false): Result<DirectPlayData> = withContext(Dispatchers.IO) {
        if (sourceId.isBlank()) return@withContext Result.failure(Exception("Source ID is blank"))
        resolveMutexes.computeIfAbsent(sourceId) { Mutex() }.withLock {
            val cached = resolvedPlaybackCache[sourceId]
            if (!forceRefresh && cached != null && !SourceExpiryTime.isExpiredOrInvalid(cached.data.expiresAt) &&
                System.currentTimeMillis() - cached.cachedAtMs <= 20 * 60 * 1000L
            ) {
                return@withContext Result.success(cached.data)
            }
            if (!forceRefresh) {
                streamCacheDao.getLatestActiveSourceBySourceId(sourceId)?.let { roomSource ->
                    if (SafePlaybackHeaders.isValidStreamUrl(roomSource.streamUrl) &&
                        !SourceExpiryTime.isExpiredOrInvalid(roomSource.expiresAt)
                    ) {
                        val playData = roomSource.toDirectPlayData()
                        resolvedPlaybackCache[sourceId] = ResolvedCacheEntry(playData, System.currentTimeMillis())
                        return@withContext Result.success(playData)
                    }
                }
            }
            val retryRemaining = directRetryMutex.withLock {
                ((directRetryStates[sourceId]?.retryAtMs ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
            }
            if (!forceRefresh && retryRemaining > 0L) {
                return@withContext Result.failure(
                    IllegalStateException("Direct-play retry is backed off for ${(retryRemaining + 999L) / 1000L} seconds.")
                )
            }
            try {
                val resolutionStartedAt = MonotonicMetrics.nowMs()
                val response = prophecyApi.resolveDirectPlay(DirectPlayRequest(sourceId = sourceId))
                MonotonicMetrics.recordDuration("resolve_ms", resolutionStartedAt, "http=${response.code()}")
                if (!response.isSuccessful || response.body() == null) {
                    val requestId = response.headers()["X-Request-ID"] ?: response.headers()["X-Correlation-ID"]
                    val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                    Log.w("AnimeRepository", httpFailureDiagnostic("direct_play", response.code(), requestId, errorBody))
                    if (EpisodeSourceRetryBackoff.isRetryableHttpStatus(response.code())) recordDirectPlayFailure(sourceId)
                    return@withContext Result.failure(Exception("Direct playback resolution failed: HTTP ${response.code()}"))
                }
                val body = response.body()!!
                if (!body.success || body.data == null) {
                    return@withContext Result.failure(Exception("Source not compatible or unverified"))
                }
                val raw = body.data
                // Keep provider-required auth/cookie headers for this in-memory request only.
                // Firestore publication applies the stricter shared-header sanitizer separately.
                val requestReady = raw.copy(headers = SafePlaybackHeaders.sanitizeRequestHeaders(raw.headers))
                if (requestReady.sourceId != sourceId || !isValidPlaybackUrl(requestReady.playbackUrl) ||
                    SourceExpiryTime.isExpiredOrInvalid(requestReady.expiresAt)
                ) {
                    return@withContext Result.failure(Exception("Resolved playback data is invalid or expired"))
                }
                resolvedPlaybackCache[sourceId] = ResolvedCacheEntry(requestReady, System.currentTimeMillis())
                directRetryMutex.withLock { directRetryStates.remove(sourceId) }
                Result.success(requestReady)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (generateSequence(e as Throwable?) { it.cause }.any {
                        it is java.net.SocketTimeoutException || it is java.io.IOException
                    }
                ) {
                    recordDirectPlayFailure(sourceId)
                }
                Result.failure(e)
            }
        }
    }

    fun invalidateDirectPlayback(sourceId: String) {
        if (sourceId.isNotBlank()) resolvedPlaybackCache.remove(sourceId)
    }

    // Watchlist & History (Preserved completely intact)
    suspend fun toggleWatchlist(animeId: String, status: String = "WATCHING"): WatchlistEntry? =
        withContext(Dispatchers.IO) {
            val identity = resolveCanonicalRoute(animeId).animeId
            val existing = watchlistDao.getWatchlistEntry(identity)
            if (existing != null && existing.status == status) {
                watchlistDao.deleteWatchlistEntry(identity)
                null
            } else {
                WatchlistEntry(
                    animeId = identity,
                    status = status,
                    isFavorite = existing?.isFavorite ?: false,
                    updatedAt = System.currentTimeMillis()
                ).also { entry ->
                    watchlistDao.upsertWatchlistEntry(entry)
                }
            }
        }

    suspend fun toggleFavorite(animeId: String): WatchlistEntry =
        withContext(Dispatchers.IO) {
            val identity = resolveCanonicalRoute(animeId).animeId
            val existing = watchlistDao.getWatchlistEntry(identity)
            val updated = if (existing != null) {
                existing.copy(
                    isFavorite = !existing.isFavorite,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                WatchlistEntry(
                    animeId = identity,
                    status = "PLANNING",
                    isFavorite = true,
                    updatedAt = System.currentTimeMillis()
                )
            }
            watchlistDao.upsertWatchlistEntry(updated)
            updated
        }

    suspend fun updatePlaybackProgress(
        animeId: String,
        episodeId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String?,
        positionMs: Long,
        durationMs: Long,
        animeTitle: String? = null,
        posterUrl: String? = null,
        mediaType: String? = null
    ) {
        withContext(Dispatchers.IO) {
            if (positionMs <= 0L && durationMs <= 0L) return@withContext
            val route = resolveCanonicalRoute(animeId, seasonNumber)
            val identity = route.animeId
            val effectiveSeason = route.seasonNumber ?: seasonNumber
            val isCompleted = durationMs > 0 && positionMs >= (durationMs * 0.9)
            val existing = historyDao.getHistoryForEpisode(identity, episodeId)
            val anime = animeDao.getAnimeById(identity)
            val resolvedTitle = animeTitle?.takeIf { it.isNotBlank() }
                ?: existing?.animeTitle?.takeIf { it.isNotBlank() }
                ?: anime?.preferredTitle
                ?: anime?.titleEnglish
                ?: "Anime"
            val resolvedPoster = posterUrl ?: existing?.posterUrl ?: anime?.posterUrl
            val resolvedType = mediaType ?: existing?.mediaType ?: anime?.format ?: "ANIME"
            val historyId = "history_${identity}_${episodeId}"

            historyDao.upsertHistory(
                PlaybackHistory(
                    id = historyId,
                    animeId = identity,
                    episodeId = episodeId,
                    seasonNumber = effectiveSeason,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle ?: existing?.episodeTitle,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    completed = isCompleted || (existing?.completed == true),
                    animeTitle = resolvedTitle,
                    posterUrl = resolvedPoster,
                    mediaType = resolvedType,
                    lastPlayedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) { historyDao.clearAllHistory() }

    suspend fun clearSearchAndMetadataCache() = withContext(Dispatchers.IO) {
        searchDao.clearAllSearchQueries()
        searchDao.clearAllSearchResultRefs()
        animeDao.clearUnsavedCache()
    }

    // Internal cache helpers
    private fun collectAliases(animeId: String, media: AniListMedia, out: MutableList<AnimeAlias>) {
        listOfNotNull(media.title?.romaji, media.title?.english, media.title?.native)
            .plus(media.synonyms.orEmpty())
            .filter(String::isNotBlank)
            .distinct()
            .forEach { alias ->
                out += AnimeAlias(
                    animeId = animeId,
                    alias = TitleMatcher.normalize(alias),
                    originalAlias = alias
                )
            }
    }

    private fun sourceCacheKey(animeId: String, seasonNumber: Int?, episodeId: String): String =
        EpisodeSourceCacheIdentity.key(animeId, seasonNumber, episodeId)

    private suspend fun readBestCachedSourceSnapshot(
        cacheKey: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): EpisodeSourceSnapshot? {
        val memory = sourceCacheMutex.withLock { memorySourcesCache[cacheKey]?.snapshot }
        if (memory != null) return refreshCachedExpiry(memory)
        return runCatching {
            val record = episodeDao.getSourceSnapshot(cacheKey) ?: return null
            val cachedSources = episodeDao.getSourceCandidates(cacheKey).map { row ->
                ProphecySourceDto(
                    id = row.sourceId,
                    label = row.providerLabel,
                    quality = row.quality,
                    codec = row.codec,
                    language = row.audioLanguage,
                    sizeBytes = row.sizeBytes,
                    downloadable = row.downloadable,
                    expiresAt = row.expiresAt
                )
            }
            val state = runCatching { EpisodeSourceResponseState.valueOf(record.state) }
                .getOrDefault(EpisodeSourceResponseState.UNKNOWN)
            refreshCachedExpiry(
                EpisodeSourceSnapshot(
                    sources = cachedSources,
                    state = state,
                    backendStatus = record.backendStatus,
                    fetchedAtMs = record.fetchedAtMs,
                    seasonNumber = record.seasonNumber ?: seasonNumber,
                    episodeNumber = record.episodeNumber ?: episodeNumber,
                    apiCandidateCount = record.apiCandidateCount,
                    parsedCandidateCount = record.apiCandidateCount,
                    expiredCandidateCount = record.expiredCandidateCount,
                    exactDuplicateCount = record.exactDuplicateCount
                )
            )
        }.getOrNull()
    }

    private fun refreshCachedExpiry(snapshot: EpisodeSourceSnapshot): EpisodeSourceSnapshot {
        val unexpired = snapshot.sources.filterNot { !it.expiresAt.isNullOrBlank() && isExpired(it.expiresAt) }
        val newlyExpired = snapshot.sources.size - unexpired.size
        val stale = System.currentTimeMillis() - snapshot.fetchedAtMs > sourceCacheTtlMs
        return snapshot.copy(
            sources = unexpired,
            state = if (stale) EpisodeSourceResponseState.STALE else snapshot.state,
            expiredCandidateCount = snapshot.expiredCandidateCount + newlyExpired
        )
    }

    private suspend fun staleSourceFallback(
        cached: EpisodeSourceSnapshot?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        message: String,
        cause: Throwable? = null
    ): Result<EpisodeSourceSnapshot> {
        if (cached != null) {
            return Result.success(
                EpisodeSourceCachePolicy.staleAfterRefreshFailure(refreshCachedExpiry(cached), message, System.currentTimeMillis())
                    .copy(seasonNumber = seasonNumber, episodeNumber = episodeNumber)
            )
        }
        return Result.failure(cause ?: IllegalStateException(message))
    }

    private suspend fun storeSourceSnapshot(
        cacheKey: String,
        animeId: String,
        episodeId: String,
        snapshot: EpisodeSourceSnapshot
    ) {
        sourceCacheMutex.withLock {
            memorySourcesCache[cacheKey] = SourceCacheEntry(snapshot)
            while (memorySourcesCache.size > maxMemorySourceSnapshots) {
                memorySourcesCache.remove(memorySourcesCache.keys.iterator().next())
            }
        }
        runCatching {
            episodeDao.replaceSourceSnapshot(
                EpisodeSourceSnapshotRecord(
                    cacheKey = cacheKey,
                    animeId = animeId,
                    seasonNumber = snapshot.seasonNumber,
                    episodeId = episodeId,
                    episodeNumber = snapshot.episodeNumber,
                    state = snapshot.state.name,
                    backendStatus = snapshot.backendStatus,
                    fetchedAtMs = snapshot.fetchedAtMs,
                    apiCandidateCount = snapshot.apiCandidateCount,
                    expiredCandidateCount = snapshot.expiredCandidateCount,
                    exactDuplicateCount = snapshot.exactDuplicateCount
                ),
                snapshot.sources.map { source ->
                    EpisodeSourceCandidateRecord(
                        candidateKey = candidateCacheKey(cacheKey, source),
                        cacheKey = cacheKey,
                        sourceId = source.id,
                        providerLabel = source.label,
                        quality = source.quality,
                        codec = source.codec,
                        audioLanguage = source.language,
                        sizeBytes = source.sizeBytes,
                        downloadable = source.downloadable,
                        expiresAt = source.expiresAt
                    )
                },
                maxMemorySourceSnapshots
            )
        }
    }

    private fun candidateCacheKey(cacheKey: String, source: ProphecySourceDto): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${cacheKey.length}:$cacheKey:${source.stableIdentity}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun sourceRetryRemainingMs(cacheKey: String): Long = sourceRetryMutex.withLock {
        ((sourceRetryStates[cacheKey]?.retryAtMs ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private suspend fun recordSourceFailure(cacheKey: String): Long = sourceRetryMutex.withLock {
        val now = System.currentTimeMillis()
        val failures = (sourceRetryStates[cacheKey]?.failures ?: 0) + 1
        val delayMs = EpisodeSourceRetryBackoff.delayMs(failures, java.util.concurrent.ThreadLocalRandom.current().nextLong(0L, 15_001L))
        sourceRetryStates[cacheKey] = SourceRetryState(failures, now + delayMs)
        delayMs
    }

    private suspend fun clearSourceFailure(cacheKey: String) {
        sourceRetryMutex.withLock { sourceRetryStates.remove(cacheKey) }
    }

    private suspend fun recordDirectPlayFailure(sourceId: String) {
        directRetryMutex.withLock {
            val failures = (directRetryStates[sourceId]?.failures ?: 0) + 1
            val delayMs = EpisodeSourceRetryBackoff.delayMs(failures, java.util.concurrent.ThreadLocalRandom.current().nextLong(0L, 15_001L))
            directRetryStates[sourceId] = SourceRetryState(failures, System.currentTimeMillis() + delayMs)
        }
    }

    private fun isExpired(expiresAt: String?): Boolean = SourceExpiryTime.isExpired(expiresAt)

    private fun httpFailureDiagnostic(endpoint: String, status: Int, requestId: String?, errorBody: String?): String {
        val redacted = errorBody.orEmpty()
            .replace(Regex("""(?i)([\"']?(?:authorization|access[_-]?token|refresh[_-]?token|token|signature|cookie|credential|password|secret|api[_-]?key)[\"']?\s*[:=]\s*[\"']?)[^,}\s\"']+""")) { match ->
                match.groupValues[1] + "[redacted]"
            }
            .replace(Regex("""https?://[^\s\"']+"""), "[redacted-url]")
            .replace(Regex("""[\r\n\t]+"""), " ")
            .take(500)
        return "backend_http_failure endpoint=$endpoint status=$status request_id=${requestId ?: "none"} error_body_excerpt=$redacted"
    }

    private companion object {
        val searchMutexes = ConcurrentHashMap<String, Mutex>()
        val detailRefreshMutexes = ConcurrentHashMap<String, Mutex>()
        val sourceFetchMutexes = ConcurrentHashMap<String, Mutex>()
    }
}
