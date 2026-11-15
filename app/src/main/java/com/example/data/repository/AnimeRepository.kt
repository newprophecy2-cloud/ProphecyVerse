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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    val streamingRepository: FirestoreStreamingRepository = FirestoreStreamingRepository(database.streamCacheDao()),
    private val d1DataSource: com.example.data.d1.CloudflareD1DataSource? = null,
    private val memoryCache: com.example.data.cache.MemoryCacheManager = com.example.data.cache.MemoryCacheManager(),
    private val metricsTracker: com.example.data.metrics.DatabaseMetricsTracker = com.example.data.metrics.DatabaseMetricsTracker.getInstance()
) {
    val usesFirestoreStreamingRealtime: Boolean
        get() = !streamingRepository.usesD1Primary
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
    suspend fun processPendingSharedCatalogQueue(): Int {
        if (d1DataSource != null) return 0
        return sharedCatalogRepository?.processPendingQueue() ?: 0
    }
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
    private val directRetryMutex = Mutex()
    private val directRetryStates = ConcurrentHashMap<String, SourceRetryState>()
    private val sourceRetryMutex = Mutex()
    private val sourceRetryStates = ConcurrentHashMap<String, SourceRetryState>()
    private val resolveMutexes = ConcurrentHashMap<String, Mutex>()
    private val inFlightDiscovery = ConcurrentHashMap<String, CompletableDeferred<Result<EpisodeSourceSnapshot>>>()
    private val inFlightDirectResolve = ConcurrentHashMap<String, CompletableDeferred<Result<DirectPlayData>>>()
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
            if (force) {
                memoryCache.invalidateHome()
            } else {
                if (now - lastHomeRefreshTimestamp < 5 * 60 * 1000L && memoryCache.isHomeFresh("home_bundle")) {
                    memoryCache.getHomeSection("home_bundle")
                    return@withContext Result.success(Unit)
                }

                val latestHomeAt = animeDao.getLatestHomeUpdatedAt() ?: 0L
                if (latestHomeAt > 0L && (now - latestHomeAt < memoryCache.homeTtlMs)) {
                    val localTrending = animeDao.getTrendingOnce()
                    val localSeasonal = animeDao.getSeasonalOnce()
                    if (localTrending.isNotEmpty() || localSeasonal.isNotEmpty()) {
                        val localHome = (localTrending + localSeasonal + animeDao.getPopularOnce() + animeDao.getMoviesOnce()).distinctBy { it.id }
                        metricsTracker.recordRoomHit()
                        memoryCache.putHomeSection("home_bundle", localHome)
                        localHome.forEach { memoryCache.putAnime(it) }
                        lastHomeRefreshTimestamp = now
                        return@withContext Result.success(Unit)
                    }
                }
                metricsTracker.recordRoomMiss()

                if (d1DataSource != null) {
                    val d1Home = runCatching {
                        coroutineScope {
                            val trendingDeferred = async { d1DataSource.fetchHomeSection("isTrending", limit = 25, offset = 0) }
                            val seasonalDeferred = async { d1DataSource.fetchHomeSection("isSeasonal", limit = 25, offset = 0) }
                            val popularDeferred = async { d1DataSource.fetchHomeSection("isPopular", limit = 25, offset = 0) }
                            val movieDeferred = async { d1DataSource.fetchHomeSection("MOVIE", limit = 25, offset = 0) }
                            (trendingDeferred.await() + seasonalDeferred.await() + popularDeferred.await() + movieDeferred.await()).distinctBy { it.id }
                        }
                    }.getOrDefault(emptyList())
                    val newestD1At = d1Home.maxOfOrNull { it.updatedAt } ?: 0L
                    if (d1Home.isNotEmpty() &&
                        d1Home.any { it.isTrending || it.isSeasonal || it.isPopular } &&
                        (now - newestD1At < D1_HOME_FRESHNESS_MS)
                    ) {
                        val mergedEntries = d1Home.map { dto ->
                            dto.toRoomEntity(animeDao.getAnimeById(dto.id))
                        }
                        database.withTransaction {
                            animeDao.clearTrendingFlags()
                            animeDao.clearSeasonalFlags()
                            animeDao.clearPopularFlags()
                            animeDao.upsertAnimeList(mergedEntries)
                        }
                        memoryCache.putHomeSection("home_bundle", mergedEntries)
                        mergedEntries.forEach { memoryCache.putAnime(it) }
                        lastHomeRefreshTimestamp = now
                        return@withContext Result.success(Unit)
                    }
                }
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
                metricsTracker.recordApiRequest(response.isSuccessful)
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

                val distinctAliases = aliasesToInsert.distinctBy { it.animeId to it.alias }
                val roomStarted = MonotonicMetrics.nowMs()
                database.withTransaction {
                    animeDao.clearTrendingFlags()
                    animeDao.clearSeasonalFlags()
                    animeDao.clearPopularFlags()
                    if (entriesToUpsert.isNotEmpty()) animeDao.upsertAnimeList(entriesToUpsert)
                    if (distinctAliases.isNotEmpty()) animeDao.insertAliases(distinctAliases)
                }
                MonotonicMetrics.recordDuration("room_transaction_ms", roomStarted, "op=home_feeds items=${entriesToUpsert.size}")

                memoryCache.putHomeSection("home_bundle", entriesToUpsert)
                entriesToUpsert.forEach { memoryCache.putAnime(it) }
                lastHomeRefreshTimestamp = System.currentTimeMillis()

                d1DataSource?.let { d1 ->
                    sharedCatalogScope.launch {
                        runCatching {
                            d1.upsertAnimeBatchIfChanged(entriesToUpsert)
                            distinctAliases.groupBy { it.animeId }.forEach { (id, list) ->
                                d1.insertMissingAliases(id, list)
                            }
                        }
                    }
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                metricsTracker.recordApiRequest(false)
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
                val cachedMem = memoryCache.getSearchPage(normQuery, targetPage)
                if (cachedMem != null && searchDao.getSearchResultsOnce(normQuery).isNotEmpty()) {
                    return@withContext Result.success(
                        SearchPageResult(
                            totalReturnedInPage = cachedMem.results.size,
                            currentPage = cachedMem.currentPage,
                            hasNextPage = cachedMem.hasNextPage
                        )
                    )
                }

                val savedQuery = searchDao.getSearchQuery(normQuery)
                if (targetPage == 1 &&
                    savedQuery != null &&
                    savedQuery.currentPage == 1 &&
                    (System.currentTimeMillis() - savedQuery.timestamp < memoryCache.searchTtlMs)
                ) {
                    val localRefs = searchDao.getSearchResultsOnce(normQuery)
                    if (localRefs.isNotEmpty()) {
                        metricsTracker.recordRoomHit()
                        memoryCache.putSearchPage(
                            normQuery,
                            targetPage,
                            com.example.data.cache.MemoryCacheManager.SearchPageCacheValue(
                                results = localRefs,
                                currentPage = savedQuery.currentPage,
                                hasNextPage = savedQuery.hasNextPage
                            )
                        )
                        return@withContext Result.success(
                            SearchPageResult(localRefs.size, savedQuery.currentPage, savedQuery.hasNextPage)
                        )
                    }
                }
                metricsTracker.recordRoomMiss()

                val reqStarted = MonotonicMetrics.nowMs()
                val resp = runCatching {
                    aniListApi.postQuery(
                        AniListGraphQLRequest(
                            query = AniListQueries.SEARCH_ANIME,
                            variables = mapOf("search" to query.trim(), "page" to targetPage, "perPage" to 25)
                        )
                    )
                }.getOrNull()
                metricsTracker.recordApiRequest(resp?.isSuccessful == true)
                MonotonicMetrics.recordDuration("anilist_request_ms", reqStarted, "op=search page=$targetPage status=${resp?.code() ?: -1}")

                if (resp == null || !resp.isSuccessful) {
                    if (d1DataSource != null) {
                        val d1Rows = d1DataSource.searchAnimeByAliasPage(
                            normalizedQuery = normQuery,
                            limit = 25,
                            offset = (targetPage - 1) * 25
                        )
                        if (d1Rows.isNotEmpty()) {
                            val d1Entries = d1Rows.map { dto -> dto.toRoomEntity(animeDao.getAnimeById(dto.id)) }
                            val rankOffset = if (targetPage == 1) 0 else (searchDao.getSearchQuery(normQuery)?.totalResults ?: 0)
                            val d1Refs = d1Entries.mapIndexed { index, entry ->
                                SearchResultRef(normalizedQuery = normQuery, animeId = entry.id, rankIndex = rankOffset + index)
                            }
                            database.withTransaction {
                                animeDao.upsertAnimeList(d1Entries)
                                if (targetPage == 1) searchDao.clearSearchResultsForQuery(normQuery)
                                searchDao.insertSearchResultRefs(d1Refs)
                                searchDao.upsertSearchQuery(
                                    SearchQuery(
                                        query = normQuery,
                                        totalResults = if (targetPage == 1) d1Refs.size else rankOffset + d1Refs.size,
                                        currentPage = targetPage,
                                        hasNextPage = d1Entries.size >= 25
                                    )
                                )
                            }
                            memoryCache.putSearchPage(
                                normQuery,
                                targetPage,
                                com.example.data.cache.MemoryCacheManager.SearchPageCacheValue(
                                    results = d1Entries,
                                    currentPage = targetPage,
                                    hasNextPage = d1Entries.size >= 25
                                )
                            )
                            return@withContext Result.success(SearchPageResult(d1Entries.size, targetPage, d1Entries.size >= 25))
                        }
                    }
                    return@withContext Result.failure(IllegalStateException("AniList search failed: HTTP ${resp?.code() ?: 0}"))
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

                val distinctEntries = entries.distinctBy { it.id }
                val distinctAliases = aliases.distinctBy { it.animeId to it.alias }
                val distinctRefs = newRefs.distinctBy { it.animeId }
                val totalKnown = if (targetPage == 1) distinctRefs.size else rankOffset + distinctRefs.size

                val roomStarted = MonotonicMetrics.nowMs()
                database.withTransaction {
                    if (distinctEntries.isNotEmpty()) animeDao.upsertAnimeList(distinctEntries)
                    if (distinctAliases.isNotEmpty()) animeDao.insertAliases(distinctAliases)
                    if (targetPage == 1) searchDao.clearSearchResultsForQuery(normQuery)
                    if (distinctRefs.isNotEmpty()) searchDao.insertSearchResultRefs(distinctRefs)
                    searchDao.upsertSearchQuery(
                        SearchQuery(
                            query = normQuery,
                            totalResults = totalKnown,
                            currentPage = currentPage,
                            hasNextPage = hasNext
                        )
                    )
                }
                MonotonicMetrics.recordDuration("room_transaction_ms", roomStarted, "op=search_save results=${distinctRefs.size}")

                memoryCache.putSearchPage(
                    normQuery,
                    targetPage,
                    com.example.data.cache.MemoryCacheManager.SearchPageCacheValue(
                        results = distinctEntries,
                        currentPage = currentPage,
                        hasNextPage = hasNext
                    )
                )
                distinctEntries.forEach { memoryCache.putAnime(it) }

                d1DataSource?.let { d1 ->
                    sharedCatalogScope.launch {
                        runCatching {
                            d1.upsertAnimeBatchIfChanged(distinctEntries)
                            distinctAliases.groupBy { it.animeId }.forEach { (id, list) ->
                                d1.insertMissingAliases(id, list)
                            }
                        }
                    }
                }

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
    suspend fun refreshAnimeDetails(animeId: String, forceRefresh: Boolean = false): Result<EpisodeCatalogRefreshStatus> = withContext(Dispatchers.IO) {
        detailRefreshMutexes.computeIfAbsent(animeId) { Mutex() }.withLock {
            try {
                val now = System.currentTimeMillis()
                if (forceRefresh) {
                    memoryCache.invalidateAnime(animeId)
                    memoryCache.invalidateEpisodes(animeId)
                }

                val cachedMem = if (!forceRefresh) memoryCache.getAnime(animeId) else null
                var entry = cachedMem ?: animeDao.getAnimeById(animeId)

                if (!forceRefresh && entry != null &&
                    entry.metadataCompleteness != "UNKNOWN" &&
                    (now - entry.updatedAt < memoryCache.animeTtlMs)
                ) {
                    val isMovieLocal = ContentIdentityHelper.isMovie(entry)
                    if (isMovieLocal) {
                        metricsTracker.recordRoomHit()
                        memoryCache.putAnime(entry)
                        return@withContext Result.success(
                            EpisodeCatalogRefreshStatus(
                                state = EpisodeCatalogState.NOT_APPLICABLE,
                                detailsEndpointSucceeded = true,
                                episodeListEndpointSucceeded = false
                            )
                        )
                    }
                    val localSeasons = memoryCache.getSeasons(animeId) ?: episodeDao.getSeasonsForAnime(animeId)
                    val localEps = episodeDao.getEpisodesForAnime(animeId)
                    if (entry.metadataCompleteness == "MISSING_EPISODES" || (localSeasons.isNotEmpty() && localEps.isNotEmpty())) {
                        metricsTracker.recordRoomHit()
                        memoryCache.putAnime(entry)
                        if (localSeasons.isNotEmpty()) memoryCache.putSeasons(animeId, localSeasons)
                        val state = when {
                            entry.metadataCompleteness == "COMPLETE" && localEps.isNotEmpty() -> EpisodeCatalogState.COMPLETE
                            localEps.isNotEmpty() -> EpisodeCatalogState.PARTIAL
                            else -> EpisodeCatalogState.UNKNOWN
                        }
                        return@withContext Result.success(
                            EpisodeCatalogRefreshStatus(
                                state = state,
                                detailsEndpointSucceeded = true,
                                episodeListEndpointSucceeded = localEps.isNotEmpty(),
                                currentRefreshEpisodeIds = localEps.map { it.id }.toSet()
                            )
                        )
                    }
                }
                if (!forceRefresh) metricsTracker.recordRoomMiss()

                // Tier 2.5: Cloudflare D1 shared catalog lookup before hitting AniList
                if (!forceRefresh && d1DataSource != null) {
                    val lookupAniListId = entry?.aniListId
                        ?: animeId.removePrefix("anilist_").toIntOrNull()
                    val d1Dto = d1DataSource.fetchAnimeById(animeId)
                        ?: lookupAniListId?.let { d1DataSource.fetchAnimeByAniListId(it) }
                    if (d1Dto != null &&
                        d1Dto.metadataCompleteness != "UNKNOWN" &&
                        (now - d1Dto.updatedAt < memoryCache.animeTtlMs)
                    ) {
                        val mergedEntry = d1Dto.toRoomEntity(entry)
                        if (entry == null || mergedEntry.copy(updatedAt = entry.updatedAt) != entry) {
                            animeDao.upsertAnime(mergedEntry)
                            metricsTracker.recordRoomWrite()
                        } else {
                            metricsTracker.recordRoomWriteSkipped()
                        }
                        memoryCache.putAnime(mergedEntry)
                        val isMovieD1 = ContentIdentityHelper.isMovie(mergedEntry)
                        if (isMovieD1) {
                            return@withContext Result.success(
                                EpisodeCatalogRefreshStatus(
                                    state = EpisodeCatalogState.NOT_APPLICABLE,
                                    detailsEndpointSucceeded = true,
                                    episodeListEndpointSucceeded = false
                                )
                            )
                        }
                        val d1Seasons = d1DataSource.fetchSeasonsForAnime(mergedEntry.id).map { it.toRoomEntity() }
                        val activeSeason = mergedEntry.canonicalSeasonNumber?.takeIf { it > 1 }
                            ?: d1Seasons.firstOrNull()?.seasonNumber
                            ?: 1
                        val d1EpisodesPage = d1DataSource.fetchEpisodesPage(
                            animeId = mergedEntry.id,
                            seasonNumber = activeSeason,
                            limit = 30,
                            offset = 0
                        ).map { it.toRoomEntity() }
                        if (d1Seasons.isNotEmpty() || d1EpisodesPage.isNotEmpty() || mergedEntry.metadataCompleteness == "MISSING_EPISODES") {
                            if (d1Seasons.isNotEmpty() || d1EpisodesPage.isNotEmpty()) {
                                episodeDao.mergeEpisodeSnapshot(mergedEntry.id, d1Seasons, d1EpisodesPage)
                            }
                            if (d1Seasons.isNotEmpty()) memoryCache.putSeasons(mergedEntry.id, d1Seasons)
                            if (d1EpisodesPage.isNotEmpty()) memoryCache.putEpisodesPage(mergedEntry.id, activeSeason, 0, d1EpisodesPage)
                            val cachedEps = episodeDao.getEpisodesForAnime(mergedEntry.id)
                            val state = when {
                                mergedEntry.metadataCompleteness == "COMPLETE" && cachedEps.isNotEmpty() -> EpisodeCatalogState.COMPLETE
                                cachedEps.isNotEmpty() -> EpisodeCatalogState.PARTIAL
                                else -> EpisodeCatalogState.UNKNOWN
                            }
                            return@withContext Result.success(
                                EpisodeCatalogRefreshStatus(
                                    state = state,
                                    detailsEndpointSucceeded = true,
                                    episodeListEndpointSucceeded = cachedEps.isNotEmpty(),
                                    currentRefreshEpisodeIds = cachedEps.map { it.id }.toSet()
                                )
                            )
                        }
                    }
                }

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
                metricsTracker.recordApiRequest(response.isSuccessful)
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
                val animeChanged = entry == null ||
                    updatedEntry.copy(updatedAt = entry.updatedAt) != entry ||
                    forceRefresh ||
                    (now - entry.updatedAt >= memoryCache.animeTtlMs / 2)
                if (animeChanged) {
                    animeDao.upsertAnime(updatedEntry)
                    metricsTracker.recordRoomWrite()
                } else {
                    metricsTracker.recordRoomWriteSkipped()
                }
                memoryCache.putAnime(updatedEntry)
                entry = updatedEntry

                val aliases = mutableListOf<AnimeAlias>()
                collectAliases(animeId, media, aliases)
                val existingAliasSet = runCatching { animeDao.getAliasesForAnime(animeId) }
                    .getOrDefault(emptyList())
                    .map { it.alias }
                    .toSet()
                val distinctAliases = aliases.distinctBy { it.animeId to it.alias }
                val newAliases = distinctAliases.filter { it.alias !in existingAliasSet }
                if (newAliases.isNotEmpty()) animeDao.insertAliases(newAliases)

                if (isMovie) {
                    d1DataSource?.let { d1 ->
                        sharedCatalogScope.launch {
                            runCatching {
                                d1.upsertAnimeIfChanged(updatedEntry)
                                if (distinctAliases.isNotEmpty()) d1.insertMissingAliases(animeId, distinctAliases)
                            }
                        }
                    }
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

                val finalSeasons = episodeDao.getSeasonsForAnime(animeId)
                val finalEpisodes = episodeDao.getEpisodesForAnime(animeId)
                if (finalSeasons.isNotEmpty()) memoryCache.putSeasons(animeId, finalSeasons)
                val firstPageEps = finalEpisodes.filter { it.seasonNumber == targetSeasonNumber }.take(30)
                if (firstPageEps.isNotEmpty()) memoryCache.putEpisodesPage(animeId, targetSeasonNumber, 0, firstPageEps)

                d1DataSource?.let { d1 ->
                    sharedCatalogScope.launch {
                        runCatching {
                            d1.upsertAnimeIfChanged(updatedEntry)
                            if (distinctAliases.isNotEmpty()) d1.insertMissingAliases(animeId, distinctAliases)
                            if (finalSeasons.isNotEmpty()) d1.upsertSeasonsIfChanged(finalSeasons)
                            if (finalEpisodes.isNotEmpty()) d1.upsertEpisodesIfChanged(finalEpisodes.take(60))
                        }
                    }
                }

                val finalCount = finalEpisodes.size
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
                        currentRefreshEpisodeIds = finalEpisodes.map { it.id }.toSet()
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun loadEpisodePageFromD1(
        animeId: String,
        seasonNumber: Int,
        offset: Int,
        limit: Int = 30
    ): List<EpisodeEntry> = withContext(Dispatchers.IO) {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 100)
        val page = safeOffset / safeLimit
        memoryCache.getEpisodesPage(animeId, seasonNumber, page)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return@withContext it }

        val local = episodeDao.getEpisodesPageOnce(animeId, seasonNumber, safeLimit, safeOffset)
        if (local.isNotEmpty()) {
            metricsTracker.recordRoomHit()
            memoryCache.putEpisodesPage(animeId, seasonNumber, page, local)
            return@withContext local
        }
        metricsTracker.recordRoomMiss()

        val d1 = d1DataSource ?: return@withContext emptyList()
        val remote = runCatching {
            d1.fetchEpisodesPage(animeId, seasonNumber, limit = safeLimit, offset = safeOffset)
                .map { it.toRoomEntity() }
        }.getOrDefault(emptyList())
        if (remote.isNotEmpty()) {
            episodeDao.mergeEpisodeSnapshot(animeId, emptyList(), remote)
            memoryCache.putEpisodesPage(animeId, seasonNumber, page, remote)
        }
        remote
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

        // Single-flight coalescing across concurrent callers (UI, PreResolve, DownloadManager)
        val flightKey = "$scopeKey|fresh=$forceRefresh"
        while (true) {
            val existingFlight = inFlightDiscovery[flightKey]
            if (existingFlight != null) {
                metricsTracker.recordDuplicateRequestPrevented()
                return@withContext existingFlight.await()
            }
            val createdFlight = CompletableDeferred<Result<EpisodeSourceSnapshot>>()
            if (inFlightDiscovery.putIfAbsent(flightKey, createdFlight) == null) {
                try {
                    val computed = executeDiscoverStreamsForScope(
                        discoveryStarted = discoveryStarted,
                        animeId = animeId,
                        anime = anime,
                        identity = identity,
                        isMovie = isMovie,
                        contentKey = contentKey,
                        effectiveSeason = effectiveSeason,
                        effectiveEpisode = effectiveEpisode,
                        scopeKey = scopeKey,
                        catalogEpisodeId = catalogEpisodeId,
                        forceRefresh = forceRefresh
                    )
                    createdFlight.complete(computed)
                    return@withContext computed
                } catch (cancelled: CancellationException) {
                    createdFlight.cancel(cancelled)
                    throw cancelled
                } catch (t: Throwable) {
                    val failure = Result.failure<EpisodeSourceSnapshot>(t)
                    createdFlight.complete(failure)
                    return@withContext failure
                } finally {
                    inFlightDiscovery.remove(flightKey, createdFlight)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(IllegalStateException("Unreachable discovery state"))
    }

    private suspend fun executeDiscoverStreamsForScope(
        discoveryStarted: Long,
        animeId: String,
        anime: AnimeEntry?,
        identity: com.example.data.streaming.CanonicalStreamIdentity,
        isMovie: Boolean,
        contentKey: String,
        effectiveSeason: Int,
        effectiveEpisode: Int,
        scopeKey: String,
        catalogEpisodeId: String?,
        forceRefresh: Boolean
    ): Result<EpisodeSourceSnapshot> {
        // Tier 0: Memory LRU cache for stream metadata snapshots
        if (forceRefresh) {
            memoryCache.invalidateStreamScope(scopeKey)
        } else {
            memoryCache.getStreamSnapshot(scopeKey)?.let { memSnap ->
                if (!memSnap.isStale && memSnap.sources.isNotEmpty()) {
                    MonotonicMetrics.recordDuration("source_discovery_ms", discoveryStarted, "tier=MEMORY count=${memSnap.sources.size}")
                    return Result.success(memSnap)
                }
            }
        }

        // Tier 1: Room cached providers/sources
        if (!forceRefresh) {
            val roomSnapshot = readRoomScopeSnapshot(scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode)
            if (roomSnapshot != null && roomSnapshot.sources.isNotEmpty() && !roomSnapshot.isStale) {
                metricsTracker.recordRoomHit()
                memoryCache.putStreamSnapshot(scopeKey, roomSnapshot)
                MonotonicMetrics.recordDuration("source_discovery_ms", discoveryStarted, "tier=ROOM count=${roomSnapshot.sources.size}")
                return Result.success(roomSnapshot)
            }
            metricsTracker.recordRoomMiss()
        }

        // Tier 2: Shared cache (D1 primary when enabled, Firestore/test CloudStore otherwise)
        val readOutcome = streamingRepository.fetchAndSyncScopeWithOutcome(
            contentKey = contentKey,
            isMovie = isMovie,
            seasonNumber = effectiveSeason,
            episodeNumber = effectiveEpisode,
            catalogEpisodeId = catalogEpisodeId,
            preferServer = forceRefresh
        )
        val sharedData = readOutcome.data
        if (!forceRefresh && sharedData != null &&
            (sharedData.discoveryStatus == DiscoveryStatus.COMPLETED.name ||
                sharedData.sources.any { it.status != "REMOVED" && it.deletedAt == null })
        ) {
            val synced = readRoomScopeSnapshot(scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode)
            if (synced != null && synced.sources.isNotEmpty()) {
                memoryCache.putStreamSnapshot(scopeKey, synced)
                val tierLabel = if (streamingRepository.usesD1Primary) "D1" else "FIRESTORE"
                MonotonicMetrics.recordDuration("source_discovery_ms", discoveryStarted, "tier=$tierLabel count=${synced.sources.size}")
                return Result.success(synced)
            }
        }
        if (!forceRefresh && readOutcome.isHardError) {
            val fallbackRoom = readRoomScopeSnapshot(scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode)
            if (fallbackRoom != null && fallbackRoom.sources.isNotEmpty()) {
                Log.w("AnimeRepository", "Firestore read failed (${readOutcome.status}); returning cached Room scope cleanly.")
                return Result.success(
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
                return Result.success(fallback.copy(state = EpisodeSourceResponseState.STALE))
            }
            return Result.failure(
                IllegalStateException("Source discovery retry is backed off for ${(retryRemaining + 999L) / 1000L} seconds.")
            )
        }

        var backendId = resolveBackendIdForDiscovery(animeId, anime)
            ?: return staleRoomOrFailure(
                scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode,
                "No streaming provider mapping was found for this title."
            )

        val resolutionState = resolveBackendEpisodeIdsWithMetadata(
            animeId = animeId,
            backendId = backendId,
            isMovie = isMovie,
            seasonNumber = effectiveSeason,
            episodeNumber = effectiveEpisode,
            catalogEpisodeId = catalogEpisodeId,
            cumulativeOffset = identity.cumulativeEpisodeOffset,
            allowCachedIds = !forceRefresh
        )
        var backendEpisodeIds = resolutionState.episodeIds
        var allowedEpisodeNumbers = resolutionState.allowedEpisodeNumbers
        var effectiveBackendSeason = resolutionState.effectiveBackendSeason

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
                val altState = resolveBackendEpisodeIdsWithMetadata(
                    animeId = animeId,
                    backendId = backendId,
                    isMovie = isMovie,
                    seasonNumber = effectiveSeason,
                    episodeNumber = effectiveEpisode,
                    catalogEpisodeId = catalogEpisodeId,
                    cumulativeOffset = identity.cumulativeEpisodeOffset,
                    allowCachedIds = false
                )
                backendEpisodeIds = altState.episodeIds
                allowedEpisodeNumbers = altState.allowedEpisodeNumbers
                effectiveBackendSeason = altState.effectiveBackendSeason
            }
        }

        if (backendEpisodeIds.isEmpty()) {
            return staleRoomOrFailure(
                scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode,
                "Streaming episode record was not found on provider backend."
            )
        }

        val aggregatedCandidates = mutableListOf<Pair<String, ProphecySourceDto>>()
        var rawStatus: String? = null
        var anyPartial = false
        var lastErrorMsg: String? = null
        var hadHttpOrNetworkError = false

        suspend fun fetchCandidatesFromBackend(
            targetBackendId: String,
            epIds: List<String>,
            expectedSeason: Int,
            allowedNumbers: Set<Int>
        ) {
            if (epIds.isEmpty()) return
            val gate = Semaphore(3)
            val perCallResults = coroutineScope {
                epIds.map { backendEpId ->
                    async {
                        gate.withPermit {
                            val providerStarted = MonotonicMetrics.nowMs()
                            try {
                                val resp = withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) {
                                    prophecyApi.getEpisodeFull(targetBackendId, backendEpId)
                                }
                                MonotonicMetrics.recordDuration("provider_discovery_ms", providerStarted, "http=${resp.code()}")
                                if (resp.isSuccessful && resp.body() != null) {
                                    val body = resp.body()!!
                                    val validation = SourceIdentityValidator.validateEpisodeFullResponse(
                                        body = body,
                                        animeEntry = anime,
                                        requestedBackendId = targetBackendId,
                                        requestedBackendEpisodeId = backendEpId,
                                        isMovie = isMovie,
                                        requestedSeason = effectiveSeason,
                                        effectiveBackendSeason = expectedSeason,
                                        allowedEpisodeNumbers = allowedNumbers,
                                        cumulativeOffset = identity.cumulativeEpisodeOffset
                                    )
                                    if (validation is SourceValidationResult.Invalid) {
                                        SourceIdentityValidator.recordDiagnostic(
                                            code = validation.code,
                                            scopeKey = scopeKey,
                                            sourceId = backendEpId,
                                            detail = validation.reason
                                        )
                                        Triple(backendEpId, null as com.example.data.remote.ProphecyEpisodeFullResponse?, validation.reason to false)
                                    } else {
                                        Triple(backendEpId, body, null to false)
                                    }
                                } else {
                                    if (EpisodeSourceRetryBackoff.isRetryableHttpStatus(resp.code())) {
                                        recordSourceFailure(cacheKey)
                                    }
                                    Triple(backendEpId, null, ("Backend returned HTTP ${resp.code()}") to true)
                                }
                            } catch (cancelled: CancellationException) {
                                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) {
                                    throw cancelled
                                }
                                // TimeoutCancellationException from withTimeout converts to timeout diagnostic
                                recordSourceFailure(cacheKey)
                                SourceIdentityValidator.recordDiagnostic(
                                    code = SourceDiagnosticCode.RESOLUTION_TIMEOUT,
                                    scopeKey = scopeKey,
                                    sourceId = backendEpId,
                                    detail = "Episode discovery timed out after ${DISCOVERY_PER_CALL_TIMEOUT_MS}ms"
                                )
                                Triple(backendEpId, null, "Source discovery timed out" to true)
                            } catch (e: Exception) {
                                recordSourceFailure(cacheKey)
                                Triple(backendEpId, null, (e.message ?: "Network error during discovery") to true)
                            }
                        }
                    }
                }.awaitAll()
            }

            for ((backendEpId, body, errorPair) in perCallResults) {
                if (body != null) {
                    if (body.sourcesPartial == true) anyPartial = true
                    rawStatus = body.sourcesStatus ?: rawStatus
                    lastErrorMsg = body.sourceError ?: lastErrorMsg
                    body.sources.orEmpty().forEach { dto ->
                        aggregatedCandidates += backendEpId to SourceIdentityValidator.normalizeDto(dto)
                    }
                } else {
                    val (errMsg, isNetworkErr) = errorPair
                    if (isNetworkErr) hadHttpOrNetworkError = true
                    if (!errMsg.isNullOrBlank()) lastErrorMsg = errMsg
                }
            }
        }

        fetchCandidatesFromBackend(backendId, backendEpisodeIds, effectiveBackendSeason, allowedEpisodeNumbers)

        // If a duplicate/addon-only backend entry yielded zero usable candidates, try canonical re-match
        if (aggregatedCandidates.none { it.second.hasUsableSourceId } && !hadHttpOrNetworkError && canSelfHealBackend) {
            val alternateId = resolveBackendIdForDiscovery(animeId, anime, excludeBackendIds = setOf(backendId))
            if (alternateId != null && alternateId != backendId) {
                val altState = resolveBackendEpisodeIdsWithMetadata(
                    animeId = animeId,
                    backendId = alternateId,
                    isMovie = isMovie,
                    seasonNumber = effectiveSeason,
                    episodeNumber = effectiveEpisode,
                    catalogEpisodeId = catalogEpisodeId,
                    cumulativeOffset = identity.cumulativeEpisodeOffset,
                    allowCachedIds = false
                )
                if (altState.episodeIds.isNotEmpty()) {
                    backendId = alternateId
                    backendEpisodeIds = altState.episodeIds
                    allowedEpisodeNumbers = altState.allowedEpisodeNumbers
                    effectiveBackendSeason = altState.effectiveBackendSeason
                    aggregatedCandidates.clear()
                    rawStatus = null
                    anyPartial = false
                    lastErrorMsg = null
                    fetchCandidatesFromBackend(backendId, backendEpisodeIds, effectiveBackendSeason, allowedEpisodeNumbers)
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
            return staleRoomOrFailure(
                scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode,
                lastErrorMsg ?: "Temporary provider outage"
            )
        }

        clearSourceFailure(cacheKey)
        val backendEpMap = aggregatedCandidates.associate {
            "${ContentIdentityHelper.deterministicProviderId(it.second.label)}|${SourceIdentityValidator.normalizeId(it.second.id)}" to it.first
        }
        val now = System.currentTimeMillis()

        val allExistingSources = streamCacheDao.getAllSources(scopeKey)
        val existingProvidersByKey = streamCacheDao.getAllProviders(scopeKey).associateBy { it.providerDocKey }
        val existingByComposite = allExistingSources.associateBy { "${it.providerId}|${SourceIdentityValidator.normalizeId(it.sourceId)}" }
        val sourceEntities = mutableListOf<StreamSourceEntity>()
        val providersMap = linkedMapOf<String, MutableList<StreamSourceEntity>>()
        val seenProviderSourcePairs = HashSet<String>()

        for (rawDto in dedupedDtos.sources) {
            val dto = SourceIdentityValidator.normalizeDto(rawDto)
            if (!dto.hasUsableSourceId) continue
            val providerId = ContentIdentityHelper.deterministicProviderId(dto.label)
            val compositeKey = "$providerId|${dto.id}"
            if (!seenProviderSourcePairs.add(compositeKey)) continue
            val prev = existingByComposite[compositeKey]
            val effectiveExpiresAt = dto.expiresAt ?: prev?.expiresAt
            val validUrl = prev?.streamUrl?.takeIf { SafePlaybackHeaders.isValidStreamUrl(it) }
            val resolvedStatus = if (validUrl != null && !SourceExpiryTime.isExpiredOrInvalid(effectiveExpiresAt)) {
                StreamRecordStatus.AVAILABLE.name
            } else if (validUrl != null && SourceExpiryTime.evaluateExpiry(effectiveExpiresAt) == ExpiryState.EXPIRED) {
                StreamRecordStatus.EXPIRED.name
            } else {
                StreamRecordStatus.MISSING_URL.name
            }
            val persistedUrl = if (resolvedStatus == StreamRecordStatus.AVAILABLE.name) validUrl else null
            val persistedSafeHeaders = if (resolvedStatus == StreamRecordStatus.AVAILABLE.name) prev?.safeHeadersJson else null
            val resolvedBackendEpId = backendEpMap[compositeKey] ?: prev?.backendEpisodeId
            val resolvedQuality = dto.quality ?: prev?.quality
            val resolvedCodec = dto.codec ?: prev?.codec
            val resolvedLang = dto.language ?: prev?.audioLanguage
            val resolvedDownloadable = dto.downloadable ?: prev?.downloadable
            val resolvedSizeBytes = dto.sizeBytes ?: prev?.sizeBytes
            val sourceChanged = prev == null ||
                prev.sourceName != dto.displayProviderName ||
                prev.backendEpisodeId != resolvedBackendEpId ||
                prev.streamUrl != persistedUrl ||
                prev.safeHeadersJson != persistedSafeHeaders ||
                prev.quality != resolvedQuality ||
                prev.codec != resolvedCodec ||
                prev.audioLanguage != resolvedLang ||
                prev.downloadable != resolvedDownloadable ||
                prev.sizeBytes != resolvedSizeBytes ||
                prev.expiresAt != effectiveExpiresAt ||
                prev.status != resolvedStatus ||
                prev.deletedAt != null
            val sEntity = StreamSourceEntity(
                sourceDocKey = "$scopeKey|p|$providerId|s|${ContentIdentityHelper.sanitizeDocId(dto.id)}",
                scopeKey = scopeKey,
                providerId = providerId,
                sourceId = dto.id,
                sourceName = dto.displayProviderName,
                backendEpisodeId = resolvedBackendEpId,
                streamUrl = persistedUrl,
                urlType = prev?.urlType ?: "direct",
                contentType = prev?.contentType,
                quality = resolvedQuality,
                codec = resolvedCodec,
                audioLanguage = resolvedLang,
                downloadable = resolvedDownloadable,
                sizeBytes = resolvedSizeBytes,
                safeHeadersJson = persistedSafeHeaders,
                expiresAt = effectiveExpiresAt,
                status = resolvedStatus,
                version = if (sourceChanged) (prev?.version ?: 0L) + 1L else (prev?.version ?: 1L),
                lastVerifiedAt = prev?.lastVerifiedAt,
                lastPlaybackSuccessAt = prev?.lastPlaybackSuccessAt,
                updatedAt = if (sourceChanged) now else (prev?.updatedAt ?: now)
            )
            sourceEntities += sEntity
            providersMap.getOrPut(providerId) { mutableListOf() }.add(sEntity)
        }

        val providerEntities = providersMap.map { (providerId, pSources) ->
            val pDocKey = "$scopeKey|p|$providerId"
            val prevProv = existingProvidersByKey[pDocKey]
            val provName = pSources.first().sourceName
            val provChanged = prevProv == null ||
                prevProv.providerName != provName ||
                prevProv.status != "AVAILABLE" ||
                prevProv.sourceCount != pSources.size ||
                prevProv.deletedAt != null
            StreamProviderEntity(
                providerDocKey = pDocKey,
                scopeKey = scopeKey,
                providerId = providerId,
                providerName = provName,
                status = "AVAILABLE",
                sourceCount = pSources.size,
                version = if (provChanged) (prevProv?.version ?: 0L) + 1L else (prevProv?.version ?: 1L),
                updatedAt = if (provChanged) now else (prevProv?.updatedAt ?: now)
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

        val resultSnapshot = EpisodeSourceSnapshot(
            sources = dedupedDtos.sources.map { SourceIdentityValidator.normalizeDto(it) },
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
        if (resultSnapshot.sources.isNotEmpty()) {
            memoryCache.putStreamSnapshot(scopeKey, resultSnapshot)
        }
        MonotonicMetrics.recordDuration("source_discovery_ms", discoveryStarted, "tier=BACKEND count=${dedupedDtos.sources.size}")
        return Result.success(resultSnapshot)
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
                    if (anime?.backendAnimeId != mapped || !anime.isPlayable) {
                        animeDao.updateBackendMappingForEntry(animeId, mapped)
                    }
                    return mapped
                }
                d1DataSource?.fetchCatalogMapping(id)?.let { d1Map ->
                    val roomMap = d1Map.toRoomEntity()
                    animeDao.upsertMapping(roomMap)
                    if (anime?.backendAnimeId != roomMap.backendAnimeId || !anime.isPlayable) {
                        animeDao.updateBackendMappingForEntry(animeId, roomMap.backendAnimeId)
                    }
                    return roomMap.backendAnimeId
                }
            }
            aniListId?.let { id ->
                animeDao.getMappingForAniList(id)?.backendAnimeId?.takeIf { it.isNotBlank() }?.let { mapped ->
                    if (anime?.backendAnimeId != mapped || !anime.isPlayable) {
                        animeDao.updateBackendMappingForEntry(animeId, mapped)
                    }
                    return mapped
                }
                d1DataSource?.fetchCatalogMapping(id)?.let { d1Map ->
                    val roomMap = d1Map.toRoomEntity()
                    animeDao.upsertMapping(roomMap)
                    if (anime?.backendAnimeId != roomMap.backendAnimeId || !anime.isPlayable) {
                        animeDao.updateBackendMappingForEntry(animeId, roomMap.backendAnimeId)
                    }
                    return roomMap.backendAnimeId
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

        for (queryBatch in queries.chunked(2)) {
            val batchResponses = coroutineScope {
                queryBatch.map { q ->
                    async {
                        runCatching {
                            withTimeout(SEARCH_PER_QUERY_TIMEOUT_MS) {
                                prophecyApi.searchAnime(query = q, limit = 15)
                            }
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            for (res in batchResponses) {
                if (res == null || !res.isSuccessful) continue
                for (item in res.body()?.results.orEmpty()) {
                    if (item.id.isBlank() || item.id in excludeBackendIds) continue
                    val (confidence, score) = TitleMatcher.scoreBackendCandidateForEntry(entry, item, aliases)
                    if (confidence >= 0.75f && score > bestSelectionScore) {
                        bestItem = item
                        bestConfidence = confidence
                        bestSelectionScore = score
                    }
                }
            }
            // Do not settle early on a seasonCount == null/1 addon-only duplicate when sequel/franchise matching
            if (bestItem != null && (!expectsMultiSeason || (bestItem!!.seasonCount ?: 0) >= 2) && bestConfidence >= 0.90f) {
                break
            }
        }

        val chosen = bestItem ?: return null
        val matchedId = chosen.id
        animeDao.updateBackendMappingForEntry(animeId, matchedId)
        val newMappings = mutableListOf<CatalogMapping>()
        aniListId?.let {
            val mapping = CatalogMapping(aniListId = it, backendAnimeId = matchedId, confidence = bestConfidence)
            animeDao.upsertMapping(mapping)
            newMappings += mapping
        }
        entry.canonicalAnimeId?.takeIf { it > 0 && (chosen.seasonCount ?: 0) >= 2 }?.let { canonId ->
            val mapping = CatalogMapping(aniListId = canonId, backendAnimeId = matchedId, confidence = bestConfidence)
            animeDao.upsertMapping(mapping)
            newMappings += mapping
        }
        d1DataSource?.let { d1 ->
            sharedCatalogScope.launch {
                runCatching {
                    newMappings.forEach { d1.upsertCatalogMappingIfChanged(it) }
                    animeDao.getAnimeById(animeId)?.let { updated ->
                        d1.upsertAnimeIfChanged(updated)
                        memoryCache.putAnime(updated)
                    }
                }
            }
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
    private data class BackendEpisodeResolution(
        val episodeIds: List<String>,
        val allowedEpisodeNumbers: Set<Int>,
        val effectiveBackendSeason: Int
    )

    private suspend fun resolveBackendEpisodeIdsForDiscovery(
        animeId: String,
        backendId: String,
        isMovie: Boolean,
        seasonNumber: Int,
        episodeNumber: Int,
        catalogEpisodeId: String?,
        cumulativeOffset: Int = 0,
        allowCachedIds: Boolean = true
    ): List<String> = resolveBackendEpisodeIdsWithMetadata(
        animeId, backendId, isMovie, seasonNumber, episodeNumber, catalogEpisodeId, cumulativeOffset, allowCachedIds
    ).episodeIds

    private suspend fun resolveBackendEpisodeIdsWithMetadata(
        animeId: String,
        backendId: String,
        isMovie: Boolean,
        seasonNumber: Int,
        episodeNumber: Int,
        catalogEpisodeId: String?,
        cumulativeOffset: Int = 0,
        allowCachedIds: Boolean = true
    ): BackendEpisodeResolution {
        val defaultAllowedNumbers = buildSet {
            add(if (isMovie) 1 else episodeNumber)
            if (!isMovie && cumulativeOffset > 0) add(cumulativeOffset + episodeNumber)
        }
        val anime = animeDao.getAnimeById(animeId)
        val contentKey = ContentIdentityHelper.canonicalContentKey(animeId, anime)
        if (!isMovie && allowCachedIds) {
            val cachedStreamEp = streamCacheDao.getEpisode("$contentKey:s$seasonNumber:e$episodeNumber")
            val existingIds: List<String> = cachedStreamEp?.backendSourceEpisodeIds?.let { encoded ->
                EpisodeSourceEpisodeIds.decode(encoded)
            }.orEmpty()
            if (existingIds.isNotEmpty()) {
                return BackendEpisodeResolution(existingIds, defaultAllowedNumbers, seasonNumber)
            }
            cachedStreamEp?.backendEpisodeId?.takeIf { it.isNotBlank() }?.let {
                return BackendEpisodeResolution(listOf(it), defaultAllowedNumbers, seasonNumber)
            }

            catalogEpisodeId?.let { id ->
                episodeDao.getEpisodeById(id)?.let { ep ->
                    val decoded = EpisodeSourceEpisodeIds.decode(ep.sourceEpisodeIds)
                        .filter { it.isNotBlank() && !it.startsWith("anilist_ep_") && !it.startsWith("movie_") }
                    if (decoded.isNotEmpty()) {
                        return BackendEpisodeResolution(
                            decoded,
                            defaultAllowedNumbers,
                            seasonNumber
                        )
                    }
                    if (!animeId.startsWith("anilist_") && ep.id.isNotBlank() && !ep.id.startsWith("anilist_ep_")) {
                        return BackendEpisodeResolution(
                            listOf(ep.id),
                            defaultAllowedNumbers,
                            seasonNumber
                        )
                    }
                    if (!ep.id.startsWith("anilist_ep_") && !ep.id.startsWith("movie_") && !ep.id.startsWith("ep_") && ep.id.isNotBlank()) {
                        return BackendEpisodeResolution(
                            listOf(ep.id),
                            defaultAllowedNumbers,
                            seasonNumber
                        )
                    }
                }
            }
        } else if (isMovie && allowCachedIds) {
            val existingMovieSources = streamCacheDao.getActiveSources(ContentIdentityHelper.movieScopeKey(contentKey))
            val existingBackendEpIds = existingMovieSources.mapNotNull { it.backendEpisodeId?.takeIf(String::isNotBlank) }.distinct()
            if (existingBackendEpIds.isNotEmpty()) {
                return BackendEpisodeResolution(existingBackendEpIds, setOf(1), 1)
            }
        }

        val (detailResp, epResp) = coroutineScope {
            val d = async { runCatching { withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) { prophecyApi.getAnimeDetails(backendId) } }.getOrNull() }
            val e = async { runCatching { withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) { prophecyApi.getAnimeEpisodes(backendId) } }.getOrNull() }
            d.await() to e.await()
        }

        if (detailResp?.code() in listOf(404, 410)) {
            Log.w("AnimeRepository", "Cached backendId=$backendId returned HTTP ${detailResp?.code()}; clearing stale mapping.")
            invalidateStaleBackendMapping(animeId, anime, backendId)
            return BackendEpisodeResolution(emptyList(), defaultAllowedNumbers, seasonNumber)
        }

        val backendDetails = detailResp?.takeIf { it.isSuccessful }?.body()
        if (backendDetails != null && d1DataSource == null) queueBackendCatalogMetadata(backendDetails)
        if (backendDetails != null && !isValidBackendDetailsForEntry(animeId, anime, backendDetails)) {
            Log.w("AnimeRepository", "Cached backendId=$backendId failed entry validation for $animeId; clearing stale mapping.")
            invalidateStaleBackendMapping(animeId, anime, backendId)
            return BackendEpisodeResolution(emptyList(), defaultAllowedNumbers, seasonNumber)
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
            return BackendEpisodeResolution(
                episodeIds = listOfNotNull(movieEp?.id?.takeIf { it.isNotBlank() }),
                allowedEpisodeNumbers = setOf(1),
                effectiveBackendSeason = movieEp?.season ?: 1
            )
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
                if (existingEp.sourceEpisodeIds != encoded) {
                    episodeDao.upsertEpisodes(listOf(existingEp.copy(sourceEpisodeIds = encoded)))
                    metricsTracker.recordRoomWrite()
                } else {
                    metricsTracker.recordRoomWriteSkipped()
                }
            }
        }
        val matchedSet = matched.toSet()
        val matchedNumbers = backendEpisodes.asSequence()
            .filter { it.id in matchedSet && it.number > 0 }
            .map { it.number }
            .toSet()
        return BackendEpisodeResolution(
            episodeIds = matched,
            allowedEpisodeNumbers = (defaultAllowedNumbers + matchedNumbers).toSet(),
            effectiveBackendSeason = targetSeason
        )
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
        forceResolveFresh: Boolean = false,
        allowFallbackToOtherSources: Boolean = preferredSourceId.isNullOrBlank(),
        preferredProviderId: String? = null
    ): Result<ResolvedStreamPlayback> = withContext(Dispatchers.IO) {
        val playbackResolveStarted = MonotonicMetrics.nowMs()
        val normPreferred = preferredSourceId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        val normPreferredProvider = preferredProviderId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        val anime = animeDao.getAnimeById(animeId)
        val identity = ContentIdentityHelper.resolveCanonicalIdentity(animeId, anime, seasonNumber, episodeNumber)
        val isMovie = identity.isMovie
        val contentKey = identity.contentKey
        val effectiveSeason = identity.seasonNumber
        val effectiveEpisode = identity.episodeNumber
        val scopeKey = ContentIdentityHelper.scopeKey(contentKey, isMovie, effectiveSeason, effectiveEpisode)

        // Ensure discovery is available cleanly (Memory -> Room -> D1/Firestore -> Backend discovery)
        var roomSources = streamCacheDao.getActiveSources(scopeKey)
        var hydratedFromFirestore = false
        var discoveryError: Throwable? = null
        if (roomSources.isEmpty()) {
            discoveryError = discoverStreamsForScope(
                animeId, effectiveSeason, effectiveEpisode, catalogEpisodeId, forceRefresh = false
            ).exceptionOrNull()
            roomSources = streamCacheDao.getActiveSources(scopeKey)
            hydratedFromFirestore = roomSources.any {
                SourceIdentityValidator.validateCachedSourceEntity(it, scopeKey).isValid
            }
        }

        val epRecord = if (isMovie) null else streamCacheDao.getEpisode("$contentKey:s$effectiveSeason:e$effectiveEpisode")
        val catalogEpisode = catalogEpisodeId?.let { episodeDao.getEpisodeById(it) }
        val knownBackendEpisodeIds = buildSet {
            catalogEpisode?.let { ep ->
                EpisodeSourceEpisodeIds.decode(ep.sourceEpisodeIds).forEach { id ->
                    val norm = SourceIdentityValidator.normalizeId(id)
                    if (norm.isNotEmpty() && !norm.startsWith("anilist_ep_") && !norm.startsWith("movie_")) {
                        add(norm)
                    }
                }
            }
            epRecord?.let { rec ->
                SourceIdentityValidator.normalizeId(rec.backendEpisodeId)
                    .takeIf { it.isNotEmpty() && !it.startsWith("anilist_ep_") && !it.startsWith("movie_") }
                    ?.let(::add)
                EpisodeSourceEpisodeIds.decode(rec.backendSourceEpisodeIds).forEach { id ->
                    val norm = SourceIdentityValidator.normalizeId(id)
                    if (norm.isNotEmpty() && !norm.startsWith("anilist_ep_") && !norm.startsWith("movie_")) {
                        add(norm)
                    }
                }
            }
        }

        fun matchesPreferred(entity: StreamSourceEntity): Boolean {
            if (normPreferred != null && !SourceIdentityValidator.normalizedIdsEqual(entity.sourceId, normPreferred)) {
                return false
            }
            if (normPreferredProvider != null && !SourceIdentityValidator.normalizedIdsEqual(entity.providerId, normPreferredProvider)) {
                return false
            }
            return true
        }

        fun filterRemainingCandidates(all: List<ProphecySourceDto>, winner: ProphecySourceDto, winnerProviderId: String): List<ProphecySourceDto> {
            return listOf(winner) + all.filterNot {
                SourceIdentityValidator.normalizedIdsEqual(it.id, winner.id) &&
                    SourceIdentityValidator.normalizedIdsEqual(
                        ContentIdentityHelper.deterministicProviderId(it.label),
                        winnerProviderId
                    )
            }
        }

        // Order sources consistently; when preferredSourceId is requested strictly, never return a different cached source
        val orderedRoomSources = orderSourcesForPlayback(roomSources, normPreferred, normPreferredProvider)
        val candidatesDto = orderedRoomSources.map { SourceIdentityValidator.normalizeDto(it.toProphecyDto()) }
        val cacheCheckPool = when {
            normPreferred != null -> {
                val matching = orderedRoomSources.filter(::matchesPreferred)
                if (normPreferredProvider == null && matching.map { it.providerId }.distinct().size > 1) {
                    SourceIdentityValidator.recordDiagnostic(
                        code = SourceDiagnosticCode.PROVIDER_ID_MISMATCH,
                        scopeKey = scopeKey,
                        sourceId = normPreferred,
                        detail = "Ambiguous cached sources share sourceId without providerId"
                    )
                    emptyList()
                } else {
                    matching
                }
            }
            else -> orderedRoomSources
        }

        // 1. Check Room/synced Stream URL cache (unless explicit forceResolveFresh requested after failure)
        if (!forceResolveFresh) {
            for (cachedSource in cacheCheckPool) {
                val cachedValidation = SourceIdentityValidator.validateCachedSourceEntity(
                    entity = cachedSource,
                    expectedScopeKey = scopeKey,
                    expectedSourceId = normPreferred ?: cachedSource.sourceId,
                    expectedProviderId = normPreferredProvider ?: cachedSource.providerId,
                    allowedBackendEpisodeIds = knownBackendEpisodeIds
                )
                if (cachedValidation is SourceValidationResult.Invalid) {
                    if (SafePlaybackHeaders.isValidStreamUrl(cachedSource.streamUrl)) {
                        SourceIdentityValidator.recordDiagnostic(
                            code = cachedValidation.code,
                            scopeKey = scopeKey,
                            sourceId = cachedSource.sourceId,
                            providerLabel = cachedSource.sourceName,
                            detail = cachedValidation.reason
                        )
                    }
                    if (cachedSource.streamUrl != null && (cachedValidation.code == SourceDiagnosticCode.SOURCE_EXPIRED || !SafePlaybackHeaders.isValidStreamUrl(cachedSource.streamUrl))) {
                        memoryCache.invalidatePlayback(cachedSource.sourceId, scopeKey, cachedSource.providerId)
                        runCatching { streamCacheDao.evictExpiredStreamUrl(cachedSource.sourceDocKey, System.currentTimeMillis()) }
                    }
                    continue
                }
                val data = cachedSource.toDirectPlayData()
                val resolvedTier = if (hydratedFromFirestore) "FIRESTORE" else "ROOM"
                val metricTag = if (hydratedFromFirestore) "tier=FIRESTORE" else "tier=ROOM"
                val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, metricTag)
                val normDto = SourceIdentityValidator.normalizeDto(cachedSource.toProphecyDto())
                return@withContext Result.success(
                    ResolvedStreamPlayback(
                        source = normDto,
                        directData = data,
                        candidates = filterRemainingCandidates(candidatesDto, normDto, cachedSource.providerId),
                        fromCache = true,
                        cacheTier = resolvedTier,
                        resolveDurationMs = elapsed
                    )
                )
            }

            // 2. Check Firestore shared cache before calling Resolve (skipped in D1-primary mode since D1 holds discovery metadata only)
            if (!streamingRepository.usesD1Primary) {
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
                        normPreferred,
                        normPreferredProvider
                    )
                    val fsPool = if (normPreferred != null) {
                        val matching = orderedFs.filter(::matchesPreferred)
                        if (normPreferredProvider == null && matching.map { it.providerId }.distinct().size > 1) {
                            emptyList()
                        } else {
                            matching
                        }
                    } else {
                        orderedFs
                    }
                    for (fsSource in fsPool) {
                        val fsValidation = SourceIdentityValidator.validateCachedSourceEntity(
                            entity = fsSource,
                            expectedScopeKey = scopeKey,
                            expectedSourceId = normPreferred ?: fsSource.sourceId,
                            expectedProviderId = normPreferredProvider ?: fsSource.providerId,
                            allowedBackendEpisodeIds = knownBackendEpisodeIds
                        )
                        if (fsValidation !is SourceValidationResult.Valid) continue
                        val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, "tier=FIRESTORE")
                        val normDto = SourceIdentityValidator.normalizeDto(fsSource.toProphecyDto())
                        val fsCandidates = orderedFs.map { SourceIdentityValidator.normalizeDto(it.toProphecyDto()) }
                        return@withContext Result.success(
                            ResolvedStreamPlayback(
                                source = normDto,
                                directData = fsSource.toDirectPlayData(),
                                candidates = filterRemainingCandidates(fsCandidates, normDto, fsSource.providerId),
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
        val refreshedRoomSources = orderSourcesForPlayback(streamCacheDao.getActiveSources(scopeKey), normPreferred, normPreferredProvider)
        val allCandidates = refreshedRoomSources.map { SourceIdentityValidator.normalizeDto(it.toProphecyDto()) }

        val targetSources = if (normPreferred != null && !allowFallbackToOtherSources) {
            refreshedRoomSources.filter(::matchesPreferred)
        } else {
            refreshedRoomSources.filter { SourceIdentityValidator.normalizeId(it.sourceId).isNotEmpty() }
        }

        if (normPreferred != null && targetSources.isEmpty()) {
            val notFoundErr = SourceResolutionDiagnosticException(
                diagnosticCode = SourceDiagnosticCode.PROVIDER_UNAVAILABLE,
                message = "Requested source '$normPreferred' is not available for this episode."
            )
            SourceIdentityValidator.recordDiagnostic(
                code = SourceDiagnosticCode.PROVIDER_UNAVAILABLE,
                scopeKey = scopeKey,
                sourceId = normPreferred,
                detail = notFoundErr.message.orEmpty()
            )
            return@withContext Result.failure(notFoundErr)
        }

        // Helper for single-source direct playback resolution with lease, strict validation, and publish
        suspend fun resolveSingleCandidate(sourceEntity: StreamSourceEntity): Result<ResolvedStreamPlayback> {
            val normSourceId = SourceIdentityValidator.normalizeId(sourceEntity.sourceId)
            if (normSourceId.isEmpty()) {
                return Result.failure(
                    SourceResolutionDiagnosticException(
                        SourceDiagnosticCode.SOURCE_NOT_PLAYABLE,
                        "Source candidate has blank sourceId",
                        sourceEntity.sourceName
                    )
                )
            }
            var lease = streamingRepository.acquireResolveLease(
                contentKey = contentKey,
                isMovie = isMovie,
                providerId = sourceEntity.providerId,
                sourceId = normSourceId,
                seasonNumber = if (isMovie) 0 else effectiveSeason,
                episodeNumber = if (isMovie) 0 else effectiveEpisode
            )

            // If another device holds a Firestore resolve lease, wait cleanly and check Firestore (skipped in D1 primary)
            if (lease == null) {
                if (!streamingRepository.usesD1Primary) {
                    delay(700L)
                    val peerSync = streamingRepository.fetchAndSyncScopeWithOutcome(
                        contentKey, isMovie, effectiveSeason, effectiveEpisode, catalogEpisodeId,
                        preferServer = true, requirePlayableUrl = true
                    ).data
                    val peerHit = peerSync?.sources?.firstOrNull {
                        SourceIdentityValidator.normalizedIdsEqual(it.sourceId, normSourceId) &&
                            SourceIdentityValidator.normalizedIdsEqual(it.providerId, sourceEntity.providerId) &&
                            SourceIdentityValidator.validateCachedSourceEntity(
                                entity = it,
                                expectedScopeKey = scopeKey,
                                expectedSourceId = normSourceId,
                                expectedProviderId = sourceEntity.providerId,
                                allowedBackendEpisodeIds = knownBackendEpisodeIds
                            ).isValid
                    }
                    if (peerHit != null) {
                        val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, "tier=FIRESTORE")
                        val normDto = SourceIdentityValidator.normalizeDto(peerHit.toProphecyDto())
                        return Result.success(
                            ResolvedStreamPlayback(
                                source = normDto,
                                directData = peerHit.toDirectPlayData(),
                                candidates = filterRemainingCandidates(allCandidates, normDto, peerHit.providerId),
                                fromCache = true,
                                cacheTier = "FIRESTORE",
                                resolveDurationMs = elapsed
                            )
                        )
                    }
                }
                lease = streamingRepository.acquireResolveLease(
                    contentKey = contentKey,
                    isMovie = isMovie,
                    providerId = sourceEntity.providerId,
                    sourceId = normSourceId,
                    seasonNumber = if (isMovie) 0 else effectiveSeason,
                    episodeNumber = if (isMovie) 0 else effectiveEpisode
                )
                if (lease == null) {
                    return Result.failure(
                        SourceResolutionDiagnosticException(
                            SourceDiagnosticCode.PROVIDER_UNAVAILABLE,
                            "Source resolution currently in progress on another worker.",
                            sourceEntity.sourceName
                        )
                    )
                }
            }

            var finalLeaseStatus = "FAILED"
            try {
                val res = resolveDirectPlayback(
                    sourceId = normSourceId,
                    forceRefresh = forceResolveFresh,
                    scopeKey = scopeKey,
                    providerId = sourceEntity.providerId
                )
                val playData = res.getOrNull()
                if (playData != null) {
                    val validateStarted = MonotonicMetrics.nowMs()
                    val validation = SourceIdentityValidator.validateDirectPlayData(playData, normSourceId)
                    val requestHeaders = SafePlaybackHeaders.sanitizeRequestHeaders(playData.headers)
                    val safeHeaders = SafePlaybackHeaders.sanitize(requestHeaders)
                    MonotonicMetrics.recordDuration("url_validation_ms", validateStarted, "valid=${validation.isValid}")

                    if (validation is SourceValidationResult.Valid) {
                        val sanitizedData = playData.copy(
                            sourceId = SourceIdentityValidator.normalizeId(playData.sourceId),
                            headers = requestHeaders
                        )
                        val published = streamingRepository.publishResolvedStreamUrl(
                            sourceEntity = sourceEntity.copy(sourceId = normSourceId),
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
                        val normDto = SourceIdentityValidator.normalizeDto(sourceEntity.toProphecyDto())
                        return Result.success(
                            ResolvedStreamPlayback(
                                source = normDto,
                                directData = sanitizedData,
                                candidates = filterRemainingCandidates(allCandidates, normDto, sourceEntity.providerId),
                                fromCache = false,
                                cacheTier = "BACKEND_RESOLVE",
                                resolveDurationMs = elapsed
                            )
                        )
                    } else {
                        val invalid = validation as SourceValidationResult.Invalid
                        SourceIdentityValidator.recordDiagnostic(
                            code = invalid.code,
                            scopeKey = scopeKey,
                            sourceId = normSourceId,
                            providerLabel = sourceEntity.sourceName,
                            detail = invalid.reason
                        )
                        return Result.failure(
                            SourceResolutionDiagnosticException(invalid.code, invalid.reason, sourceEntity.sourceName)
                        )
                    }
                } else {
                    return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Direct playback failed"))
                }
            } catch (cancelled: CancellationException) {
                finalLeaseStatus = "CANCELLED"
                throw cancelled
            } catch (e: Exception) {
                return Result.failure(e)
            } finally {
                val heldLease = lease
                if (heldLease != null) {
                    runCatching {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            streamingRepository.releaseResolveLease(heldLease, finalLeaseStatus)
                        }
                    }
                }
            }
        }

        // 3. No valid cached Stream URL -> Primary-first with hedged bounded concurrent fallback
        var lastError: Throwable? = null
        if (targetSources.size <= 1 || normPreferred != null) {
            for (sourceEntity in targetSources) {
                val attempt = resolveSingleCandidate(sourceEntity)
                if (attempt.isSuccess) return@withContext attempt
                lastError = attempt.exceptionOrNull()
                // If user asked strictly for normPreferred and fallback is disallowed, break immediately
                if (normPreferred != null && !allowFallbackToOtherSources) break
            }
        } else {
            // Hedged bounded concurrency (max 3 providers per batch): try top-ranked source first;
            // if it resolves within 250ms, return with 1 request. If it fails immediately or exceeds
            // 250ms, race remaining batch siblings concurrently and cancel slower siblings on first win.
            for ((batchIndex, batch) in targetSources.chunked(3).withIndex()) {
                var batchWinner: ResolvedStreamPlayback? = null
                coroutineScope {
                    val channel = Channel<Result<ResolvedStreamPlayback>>(batch.size)
                    val jobs = mutableListOf<kotlinx.coroutines.Job>()
                    fun launchCandidate(candidate: StreamSourceEntity) {
                        jobs += launch {
                            val result = try {
                                resolveSingleCandidate(candidate)
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                Result.failure(t)
                            }
                            channel.trySend(result)
                        }
                    }

                    launchCandidate(batch.first())
                    var remaining = batch.size
                    if (batchIndex == 0 && batch.size > 1) {
                        val firstResult = kotlinx.coroutines.withTimeoutOrNull(250L) {
                            channel.receive()
                        }
                        if (firstResult != null) {
                            remaining--
                            if (firstResult.isSuccess && firstResult.getOrNull() != null) {
                                batchWinner = firstResult.getOrNull()
                            } else {
                                lastError = firstResult.exceptionOrNull() ?: lastError
                            }
                        }
                        if (batchWinner == null) {
                            for (i in 1 until batch.size) {
                                launchCandidate(batch[i])
                            }
                        }
                    } else {
                        for (i in 1 until batch.size) {
                            launchCandidate(batch[i])
                        }
                    }

                    if (batchWinner == null) {
                        while (remaining > 0) {
                            val result = channel.receive()
                            remaining--
                            if (result.isSuccess && result.getOrNull() != null) {
                                batchWinner = result.getOrNull()
                                break
                            } else if (lastError == null || result.exceptionOrNull() !is CancellationException) {
                                lastError = result.exceptionOrNull() ?: lastError
                            }
                        }
                    }
                    jobs.forEach { it.cancel() }
                    channel.close()
                }
                if (batchWinner != null) {
                    return@withContext Result.success(batchWinner!!)
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
        episodeNumber: Int = 1,
        providerId: String? = null
    ) = withContext(Dispatchers.IO) {
        val normSource = SourceIdentityValidator.normalizeId(sourceId)
        val normProvider = providerId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        val anime = animeDao.getAnimeById(animeId)
        val identity = ContentIdentityHelper.resolveCanonicalIdentity(animeId, anime, seasonNumber, episodeNumber)
        val scopeKey = ContentIdentityHelper.scopeKey(identity.contentKey, identity.isMovie, identity.seasonNumber, identity.episodeNumber)
        val matching = streamCacheDao.getActiveSources(scopeKey).filter {
            SourceIdentityValidator.normalizedIdsEqual(it.sourceId, normSource) &&
                (normProvider == null || SourceIdentityValidator.normalizedIdsEqual(it.providerId, normProvider))
        }
        val entity = if (matching.size == 1 || normProvider != null) matching.firstOrNull() else null
        entity ?: return@withContext
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

    private fun qualityRank(quality: String?): Int {
        val norm = quality?.trim()?.lowercase().orEmpty()
        return when {
            norm.contains("2160") || norm.contains("4k") -> 4
            norm.contains("1080") -> 3
            norm.contains("720") -> 2
            norm.contains("480") || norm.contains("360") -> 1
            else -> 0
        }
    }

    private fun orderSourcesForPlayback(
        sources: List<StreamSourceEntity>,
        preferredSourceId: String?,
        preferredProviderId: String? = null
    ): List<StreamSourceEntity> {
        val active = sources.filter { it.status != "REMOVED" && it.deletedAt == null }
        val normPref = preferredSourceId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        val normProv = preferredProviderId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        if (normPref != null) {
            return active.sortedWith(
                compareBy<StreamSourceEntity> {
                    val matchesSource = SourceIdentityValidator.normalizedIdsEqual(it.sourceId, normPref)
                    val matchesProvider = normProv == null || SourceIdentityValidator.normalizedIdsEqual(it.providerId, normProv)
                    if (matchesSource && matchesProvider) 0 else 1
                }
                    .thenByDescending { SafePlaybackHeaders.isValidStreamUrl(it.streamUrl) && !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt) }
                    .thenByDescending { it.lastPlaybackSuccessAt ?: 0L }
                    .thenByDescending { qualityRank(it.quality) }
            )
        }
        return active.sortedWith(
            compareByDescending<StreamSourceEntity> { SafePlaybackHeaders.isValidStreamUrl(it.streamUrl) && !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt) }
                .thenByDescending { it.lastPlaybackSuccessAt ?: 0L }
                .thenByDescending { qualityRank(it.quality) }
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

    suspend fun resolveDirectPlayback(
        sourceId: String,
        forceRefresh: Boolean = false,
        scopeKey: String? = null,
        providerId: String? = null
    ): Result<DirectPlayData> = withContext(Dispatchers.IO) {
        val normSourceId = SourceIdentityValidator.normalizeId(sourceId)
        val normScope = scopeKey?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        val normProvider = providerId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        if (normSourceId.isEmpty()) return@withContext Result.failure(
            SourceResolutionDiagnosticException(SourceDiagnosticCode.SOURCE_NOT_PLAYABLE, "Source ID is blank")
        )

        val flightKey = "${normScope.orEmpty()}::${normProvider.orEmpty()}::$normSourceId::fresh=$forceRefresh"
        while (true) {
            val existing = inFlightDirectResolve[flightKey]
            if (existing != null) {
                metricsTracker.recordDuplicateRequestPrevented()
                return@withContext existing.await()
            }
            val created = CompletableDeferred<Result<DirectPlayData>>()
            if (inFlightDirectResolve.putIfAbsent(flightKey, created) == null) {
                try {
                    val computed = executeResolveDirectPlayback(normSourceId, forceRefresh, normScope, normProvider)
                    created.complete(computed)
                    return@withContext computed
                } catch (cancelled: CancellationException) {
                    created.cancel(cancelled)
                    throw cancelled
                } catch (t: Throwable) {
                    val failure = Result.failure<DirectPlayData>(t)
                    created.complete(failure)
                    return@withContext failure
                } finally {
                    inFlightDirectResolve.remove(flightKey, created)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(IllegalStateException("Unreachable direct-play state"))
    }

    private suspend fun executeResolveDirectPlayback(
        normSourceId: String,
        forceRefresh: Boolean,
        normScope: String?,
        normProvider: String?
    ): Result<DirectPlayData> {
        if (forceRefresh) {
            memoryCache.invalidatePlayback(normSourceId, normScope, normProvider)
        } else {
            memoryCache.getResolvedPlayback(normSourceId, normScope, normProvider)?.let { cachedMem ->
                if (SourceIdentityValidator.validateDirectPlayData(cachedMem, normSourceId).isValid) {
                    return Result.success(cachedMem)
                }
                memoryCache.invalidatePlayback(normSourceId, normScope, normProvider)
            }
            val roomSource = when {
                normScope != null && normProvider != null -> {
                    streamCacheDao.getLatestActiveSourceForProviderInScope(normScope, normProvider, normSourceId)
                }
                normScope != null -> {
                    val matches = streamCacheDao.getActiveSourcesForSourceIdInScope(normScope, normSourceId)
                    if (matches.map { it.providerId }.distinct().size > 1) {
                        SourceIdentityValidator.recordDiagnostic(
                            code = SourceDiagnosticCode.PROVIDER_ID_MISMATCH,
                            scopeKey = normScope,
                            sourceId = normSourceId,
                            detail = "Skipped ambiguous Room source without providerId"
                        )
                        null
                    } else {
                        matches.firstOrNull()
                    }
                }
                else -> streamCacheDao.getLatestActiveSourceBySourceId(normSourceId)
            }
            if (roomSource != null) {
                val roomValid = if (normScope != null) {
                    SourceIdentityValidator.validateCachedSourceEntity(
                        entity = roomSource,
                        expectedScopeKey = normScope,
                        expectedSourceId = normSourceId,
                        expectedProviderId = normProvider
                    ).isValid
                } else {
                    SafePlaybackHeaders.isValidStreamUrl(roomSource.streamUrl) &&
                        !SourceExpiryTime.isExpiredOrInvalid(roomSource.expiresAt)
                }
                if (roomValid) {
                    metricsTracker.recordRoomHit()
                    val playData = roomSource.toDirectPlayData().copy(sourceId = normSourceId)
                    memoryCache.putResolvedPlayback(normSourceId, playData, normScope, normProvider ?: roomSource.providerId)
                    return Result.success(playData)
                }
                if (roomSource.streamUrl != null && (roomSource.status == StreamRecordStatus.EXPIRED.name || SourceExpiryTime.isExpiredOrInvalid(roomSource.expiresAt) || !SafePlaybackHeaders.isValidStreamUrl(roomSource.streamUrl))) {
                    memoryCache.invalidatePlayback(normSourceId, normScope, normProvider ?: roomSource.providerId)
                    runCatching { streamCacheDao.evictExpiredStreamUrl(roomSource.sourceDocKey, System.currentTimeMillis()) }
                }
            }
            metricsTracker.recordRoomMiss()
        }

        val retryRemaining = directRetryMutex.withLock {
            ((directRetryStates[normSourceId]?.retryAtMs ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        if (!forceRefresh && retryRemaining > 0L) {
            return Result.failure(
                SourceResolutionDiagnosticException(
                    SourceDiagnosticCode.PROVIDER_UNAVAILABLE,
                    "Direct-play retry is backed off for ${(retryRemaining + 999L) / 1000L} seconds."
                )
            )
        }
        return try {
            val resolutionStartedAt = MonotonicMetrics.nowMs()
            val response = withTimeout(DIRECT_PLAY_RESOLVE_TIMEOUT_MS) {
                prophecyApi.resolveDirectPlay(DirectPlayRequest(sourceId = normSourceId))
            }
            metricsTracker.recordApiRequest(response.isSuccessful)
            MonotonicMetrics.recordDuration("resolve_ms", resolutionStartedAt, "http=${response.code()}")
            if (!response.isSuccessful || response.body() == null) {
                val requestId = response.headers()["X-Request-ID"] ?: response.headers()["X-Correlation-ID"]
                val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                Log.w("AnimeRepository", httpFailureDiagnostic("direct_play", response.code(), requestId, errorBody))
                if (EpisodeSourceRetryBackoff.isRetryableHttpStatus(response.code())) recordDirectPlayFailure(normSourceId)
                SourceIdentityValidator.recordDiagnostic(
                    code = SourceDiagnosticCode.PROVIDER_UNAVAILABLE,
                    scopeKey = normScope ?: "direct:$normSourceId",
                    sourceId = normSourceId,
                    detail = "HTTP ${response.code()}"
                )
                return Result.failure(
                    SourceResolutionDiagnosticException(
                        SourceDiagnosticCode.PROVIDER_UNAVAILABLE,
                        "Direct playback resolution failed: HTTP ${response.code()}"
                    )
                )
            }
            val body = response.body()!!
            if (!body.success || body.data == null) {
                return Result.failure(
                    SourceResolutionDiagnosticException(
                        SourceDiagnosticCode.SOURCE_NOT_PLAYABLE,
                        "Source not compatible or unverified"
                    )
                )
            }
            val raw = body.data
            // Keep provider-required auth/cookie headers for this in-memory request only.
            // Shared storage never stores temporary auth/cookie headers.
            val requestReady = raw.copy(
                sourceId = SourceIdentityValidator.normalizeId(raw.sourceId),
                headers = SafePlaybackHeaders.sanitizeRequestHeaders(raw.headers)
            )
            when (val validation = SourceIdentityValidator.validateDirectPlayData(requestReady, normSourceId)) {
                is SourceValidationResult.Invalid -> {
                    SourceIdentityValidator.recordDiagnostic(
                        code = validation.code,
                        scopeKey = normScope ?: "direct:$normSourceId",
                        sourceId = normSourceId,
                        detail = validation.reason
                    )
                    return Result.failure(
                        SourceResolutionDiagnosticException(validation.code, validation.reason)
                    )
                }
                SourceValidationResult.Valid -> Unit
            }
            memoryCache.putResolvedPlayback(normSourceId, requestReady, normScope, normProvider)
            directRetryMutex.withLock { directRetryStates.remove(normSourceId) }
            Result.success(requestReady)
        } catch (cancelled: CancellationException) {
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) {
                throw cancelled
            }
            metricsTracker.recordApiRequest(false)
            recordDirectPlayFailure(normSourceId)
            SourceIdentityValidator.recordDiagnostic(
                code = SourceDiagnosticCode.RESOLUTION_TIMEOUT,
                scopeKey = normScope ?: "direct:$normSourceId",
                sourceId = normSourceId,
                detail = "Direct playback timed out after ${DIRECT_PLAY_RESOLVE_TIMEOUT_MS}ms"
            )
            Result.failure(
                SourceResolutionDiagnosticException(
                    SourceDiagnosticCode.RESOLUTION_TIMEOUT,
                    "Direct playback resolution timed out after ${DIRECT_PLAY_RESOLVE_TIMEOUT_MS / 1000}s"
                )
            )
        } catch (e: Exception) {
            metricsTracker.recordApiRequest(false)
            if (generateSequence(e as Throwable?) { it.cause }.any {
                    it is java.net.SocketTimeoutException || it is java.io.IOException
                }
            ) {
                recordDirectPlayFailure(normSourceId)
            }
            Result.failure(e)
        }
    }

    fun invalidateDirectPlayback(sourceId: String, scopeKey: String? = null, providerId: String? = null) {
        val normSourceId = SourceIdentityValidator.normalizeId(sourceId)
        if (normSourceId.isNotEmpty()) {
            memoryCache.invalidatePlayback(normSourceId, scopeKey, providerId)
        }
    }

    suspend fun invalidateFailedCachedPlayback(
        animeId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        sourceId: String,
        providerId: String? = null
    ) = withContext(Dispatchers.IO) {
        val normSourceId = SourceIdentityValidator.normalizeId(sourceId)
        val normProvider = providerId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        if (normSourceId.isEmpty()) return@withContext
        val anime = animeDao.getAnimeById(animeId)
        val scopeKey = SourceIdentityValidator.scopeForEpisode(animeId, anime, seasonNumber, episodeNumber)
        memoryCache.invalidatePlayback(normSourceId, scopeKey, normProvider)
        if (normProvider == null) {
            memoryCache.invalidatePlayback(normSourceId, null, null)
        }
        runCatching {
            val now = System.currentTimeMillis()
            if (normProvider != null) {
                streamCacheDao.invalidateCachedStreamUrlForProvider(scopeKey, normProvider, normSourceId, now)
            } else {
                streamCacheDao.invalidateCachedStreamUrl(scopeKey, normSourceId, now)
            }
        }
        SourceIdentityValidator.recordDiagnostic(
            code = SourceDiagnosticCode.SOURCE_NOT_PLAYABLE,
            scopeKey = scopeKey,
            sourceId = normSourceId,
            providerLabel = normProvider,
            detail = "Invalidated failed cached stream URL before fresh resolve"
        )
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
        memoryCache.clearCatalogAndSearchCaches()
        d1DataSource?.clearDigestCache()
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

    companion object {
        private const val D1_HOME_FRESHNESS_MS = 6 * 60 * 60 * 1000L
        private const val DISCOVERY_PER_CALL_TIMEOUT_MS = 8_500L
        private const val SEARCH_PER_QUERY_TIMEOUT_MS = 6_000L
        private const val DIRECT_PLAY_RESOLVE_TIMEOUT_MS = 10_000L
        val searchMutexes = ConcurrentHashMap<String, Mutex>()
        val detailRefreshMutexes = ConcurrentHashMap<String, Mutex>()
        val sourceFetchMutexes = ConcurrentHashMap<String, Mutex>()

        @Volatile
        private var INSTANCE: AnimeRepository? = null

        fun getInstance(context: Context): AnimeRepository {
            val appCtx = context.applicationContext
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    NetworkModule.initializeHttpCache(appCtx)
                    val db = AppDatabase.getInstance(appCtx)
                    val metrics = com.example.data.metrics.DatabaseMetricsTracker.getInstance()
                    val streamingRepo = FirestoreStreamingRepository(
                        streamCacheDao = db.streamCacheDao(),
                        d1DataSource = NetworkModule.d1DataSource,
                        metricsTracker = metrics
                    )
                    AnimeRepository(
                        database = db,
                        applicationContext = appCtx,
                        streamingRepository = streamingRepo,
                        d1DataSource = NetworkModule.d1DataSource,
                        memoryCache = com.example.data.cache.MemoryCacheManager.getInstance(),
                        metricsTracker = metrics
                    ).also { INSTANCE = it }
                }
            }
        }
    }
}
