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
        val exactDuplicateCount: Int = 0,
        val selectedEpisode: EpisodeEntry? = null
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
    private val searchNextCursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, String?>>()
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
    // 1. HOME DISCOVERY (AniList Exclusively) & BACKEND SEARCH / DETAILS PIPELINE
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

    private fun extractAniListIdFromBackend(
        canonicalId: String?,
        providerMappings: List<com.example.data.remote.ProphecyProviderMappingDto>?
    ): Int? {
        val fromCanonical = canonicalId?.trim()?.let { raw ->
            if (raw.startsWith("anilist_", ignoreCase = true)) {
                raw.substringAfter('_').toIntOrNull()
            } else {
                raw.toIntOrNull()
            }
        }?.takeIf { it > 0 }
        if (fromCanonical != null) return fromCanonical

        return providerMappings.orEmpty().firstNotNullOfOrNull { mapping ->
            if (mapping.provider.equals("anilist", ignoreCase = true)) {
                (mapping.value ?: mapping.id)?.trim()
                    ?.removePrefix("anilist_")
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            } else {
                null
            }
        }
    }

    private fun formatForBackendCategory(
        mediaCategory: String?,
        mediaType: String?,
        fallbackFormat: String?
    ): String = when {
        mediaCategory.equals("MOVIE", ignoreCase = true) ||
            mediaType.equals("movie", ignoreCase = true) ||
            mediaType.equals("film", ignoreCase = true) -> "MOVIE"
        mediaType.equals("series", ignoreCase = true) ||
            mediaType.equals("tv_series", ignoreCase = true) ||
            mediaType.equals("web_series", ignoreCase = true) ||
            mediaType.equals("kdrama", ignoreCase = true) ||
            mediaType.equals("k-drama", ignoreCase = true) ||
            mediaType.equals("korean_drama", ignoreCase = true) -> "SERIES"
        else -> fallbackFormat ?: "TV"
    }

    private fun collectBackendAliases(
        animeId: String,
        titles: List<String?>,
        alternativeTitles: List<String>?,
        out: MutableList<AnimeAlias>
    ) {
        (titles.filterNotNull() + alternativeTitles.orEmpty())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { alias ->
                val normAlias = TitleMatcher.normalize(alias)
                if (normAlias.isNotBlank()) {
                    out += AnimeAlias(
                        animeId = animeId,
                        alias = normAlias,
                        originalAlias = alias
                    )
                }
            }
    }

    suspend fun searchAnimePage(query: String, page: Int = 1): Result<SearchPageResult> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        val normQuery = TitleMatcher.normalize(trimmedQuery)
        if (normQuery.isBlank()) return@withContext Result.success(SearchPageResult(0, 1, false))
        val targetPage = page.coerceAtLeast(1)

        try {
            searchMutexes.computeIfAbsent(normQuery) { Mutex() }.withLock {
                val cachedMem = memoryCache.getSearchPage(normQuery, targetPage)
                if (cachedMem != null && searchDao.getSearchResultsOnce(normQuery).isNotEmpty()) {
                    cachedMem.nextCursor?.takeIf { it.isNotBlank() }?.let { cursor ->
                        searchNextCursors.computeIfAbsent(normQuery) { ConcurrentHashMap() }[targetPage + 1] = cursor
                    }
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
                        val knownNextCursor = searchNextCursors[normQuery]?.get(2)?.takeIf { it.isNotBlank() }
                        val effectiveHasNext = savedQuery.hasNextPage && knownNextCursor != null
                        memoryCache.putSearchPage(
                            normQuery,
                            targetPage,
                            com.example.data.cache.MemoryCacheManager.SearchPageCacheValue(
                                results = localRefs,
                                currentPage = savedQuery.currentPage,
                                hasNextPage = effectiveHasNext,
                                nextCursor = knownNextCursor
                            )
                        )
                        return@withContext Result.success(
                            SearchPageResult(localRefs.size, savedQuery.currentPage, effectiveHasNext)
                        )
                    }
                }
                metricsTracker.recordRoomMiss()

                val cursorMap = searchNextCursors.computeIfAbsent(normQuery) { ConcurrentHashMap() }
                val requestCursor = if (targetPage == 1) {
                    null
                } else {
                    cursorMap[targetPage]?.takeIf { it.isNotBlank() }
                        ?: return@withContext Result.success(SearchPageResult(0, targetPage, false))
                }

                val reqStarted = MonotonicMetrics.nowMs()
                val resp = runCatching {
                    withTimeout(SEARCH_PER_QUERY_TIMEOUT_MS) {
                        prophecyApi.searchAnime(query = trimmedQuery, limit = 25, cursor = requestCursor)
                    }
                }.getOrNull()
                metricsTracker.recordApiRequest(resp?.isSuccessful == true)
                MonotonicMetrics.recordDuration("backend_search_ms", reqStarted, "op=search page=$targetPage status=${resp?.code() ?: -1}")

                if (resp == null || !resp.isSuccessful || resp.body() == null) {
                    return@withContext Result.failure(
                        IllegalStateException("ProphecyVerse search failed: HTTP ${resp?.code() ?: 0}")
                    )
                }

                val body = resp.body()!!
                val uniqueItems = body.results
                    .mapNotNull { item -> item.id.trim().takeIf { it.isNotEmpty() }?.let { cleanId -> item.copy(id = cleanId) } }
                    .distinctBy { it.id }
                val nextCursor = body.nextCursor?.takeIf { it.isNotBlank() }
                val hasNext = nextCursor != null
                if (targetPage == 1) cursorMap.clear()
                if (nextCursor != null) {
                    cursorMap[targetPage + 1] = nextCursor
                } else {
                    cursorMap.remove(targetPage + 1)
                }

                val entries = mutableListOf<AnimeEntry>()
                val aliases = mutableListOf<AnimeAlias>()
                val mappings = mutableListOf<CatalogMapping>()
                val newRefs = mutableListOf<SearchResultRef>()

                val rankOffset = if (targetPage == 1) 0 else {
                    (searchDao.getSearchQuery(normQuery)?.totalResults ?: 0)
                }

                val now = System.currentTimeMillis()
                uniqueItems.forEachIndexed { index, item ->
                    val parsedAniListId = extractAniListIdFromBackend(item.canonicalId, item.providerMappings)
                    val existing = animeDao.getAnimeByBackendId(item.id)
                        ?: animeDao.getAnimeById(item.id)
                        ?: parsedAniListId?.let { id ->
                            animeDao.getAnimeByAniListId(id) ?: animeDao.getAnimeById("anilist_$id")
                        }
                    val animeId = existing?.id ?: item.id
                    val preferredTitle = item.title.takeIf { it.isNotBlank() }
                        ?: item.titleEnglish?.takeIf { it.isNotBlank() }
                        ?: item.titleRomaji?.takeIf { it.isNotBlank() }
                        ?: existing?.preferredTitle
                        ?: "Anime #${item.id}"
                    val resolvedCategory = preserveMovieMediaCategory(existing?.mediaCategory, existing?.format, item.mediaType)
                        ?: backendMediaCategory(item.mediaType)
                        ?: existing?.mediaCategory
                        ?: "ANIME"
                    val resolvedFormat = formatForBackendCategory(resolvedCategory, item.mediaType, existing?.format)
                    val isMovie = resolvedCategory.equals("MOVIE", ignoreCase = true)

                    entries += AnimeEntry(
                        id = animeId,
                        aniListId = parsedAniListId ?: existing?.aniListId,
                        backendAnimeId = item.id,
                        canonicalAnimeId = if (isMovie) null else existing?.canonicalAnimeId,
                        canonicalTitle = if (isMovie) null else existing?.canonicalTitle,
                        canonicalSeasonNumber = if (isMovie) null else existing?.canonicalSeasonNumber,
                        cumulativeEpisodeOffset = if (isMovie) null else existing?.cumulativeEpisodeOffset,
                        titleRomaji = item.titleRomaji?.takeIf { it.isNotBlank() } ?: existing?.titleRomaji,
                        titleEnglish = item.titleEnglish?.takeIf { it.isNotBlank() } ?: existing?.titleEnglish,
                        titleNative = item.titleNative?.takeIf { it.isNotBlank() } ?: existing?.titleNative,
                        preferredTitle = preferredTitle,
                        synopsis = existing?.synopsis,
                        year = item.year?.takeIf { it.isNotBlank() } ?: existing?.year,
                        season = existing?.season,
                        format = resolvedFormat,
                        status = item.status?.takeIf { it.isNotBlank() } ?: existing?.status,
                        genres = existing?.genres.orEmpty(),
                        score = existing?.score,
                        episodeCount = if (isMovie) 1 else (item.episodeCount?.takeIf { it > 0 } ?: existing?.episodeCount),
                        duration = existing?.duration,
                        studios = existing?.studios,
                        posterUrl = item.posterUrl?.takeIf { it.isNotBlank() } ?: existing?.posterUrl,
                        bannerUrl = item.bannerUrl?.takeIf { it.isNotBlank() } ?: existing?.bannerUrl,
                        isPlayable = true,
                        isTrending = existing?.isTrending ?: false,
                        isSeasonal = existing?.isSeasonal ?: false,
                        isPopular = existing?.isPopular ?: false,
                        mediaCategory = resolvedCategory,
                        metadataCompleteness = existing?.metadataCompleteness ?: "UNKNOWN",
                        updatedAt = now
                    )
                    collectBackendAliases(
                        animeId = animeId,
                        titles = listOf(preferredTitle, item.title, item.titleEnglish, item.titleRomaji, item.titleNative),
                        alternativeTitles = item.alternativeTitles,
                        out = aliases
                    )
                    parsedAniListId?.let { aniId ->
                        mappings += CatalogMapping(aniListId = aniId, backendAnimeId = item.id, confidence = 1.0f)
                    }
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
                    mappings.distinctBy { it.aniListId }.forEach { animeDao.upsertMapping(it) }
                    if (targetPage == 1) searchDao.clearSearchResultsForQuery(normQuery)
                    if (distinctRefs.isNotEmpty()) searchDao.insertSearchResultRefs(distinctRefs)
                    searchDao.upsertSearchQuery(
                        SearchQuery(
                            query = normQuery,
                            totalResults = totalKnown,
                            currentPage = targetPage,
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
                        currentPage = targetPage,
                        hasNextPage = hasNext,
                        nextCursor = nextCursor
                    )
                )
                distinctEntries.forEach { memoryCache.putAnime(it) }

                Result.success(SearchPageResult(uniqueItems.size, targetPage, hasNext))
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

    private fun resolveKnownEpisodeCount(media: AniListMedia, fallback: Int? = null): Int? {
        val explicitTotal = media.episodes?.takeIf { it > 0 }
        if (explicitTotal != null) return explicitTotal
        val airedSoFar = media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }
        if (airedSoFar != null) return airedSoFar
        val streamingCount = media.streamingEpisodes?.size?.takeIf { it > 0 }
        return streamingCount ?: fallback
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

    private suspend fun resolveBackendIdForDetails(animeId: String, entry: AnimeEntry?): String? {
        entry?.backendAnimeId?.takeIf { it.isNotBlank() }?.let { return it }
        if (!animeId.startsWith("anilist_")) return animeId
        return resolveBackendIdForDiscovery(animeId, entry)
    }

    private fun buildEpisodeSourceIdsForDto(ep: ProphecyEpisodeDto): String {
        val primaryId = ep.id.trim().takeIf { it.isNotEmpty() }
            ?: ep.canonicalId?.trim()?.takeIf { it.isNotEmpty() }
        return EpisodeSourceEpisodeIds.encode(listOfNotNull(primaryId))
    }

    private suspend fun mergeSelectedEpisodeIntoCatalog(
        animeId: String,
        isMovie: Boolean,
        requestedSeason: Int,
        requestedEpisode: Int,
        catalogEpisodeId: String?,
        selectedEp: ProphecyEpisodeDto
    ): EpisodeEntry? {
        val epSeason = selectedEp.season?.takeIf { it > 0 } ?: requestedSeason
        val epNumber = selectedEp.number.takeIf { it > 0 } ?: requestedEpisode
        val selectedBackendId = selectedEp.id.trim()
        val incomingIds = buildEpisodeSourceIdsForDto(selectedEp)
        val incomingTitle = selectedEp.title?.takeIf { it.isNotBlank() }
        val incomingOverview = selectedEp.overview?.takeIf { it.isNotBlank() }

        if (isMovie) {
            val movieId = catalogEpisodeId ?: "movie_$animeId"
            return EpisodeEntry(
                id = movieId,
                animeId = animeId,
                seasonNumber = 0,
                episodeNumber = 1,
                title = incomingTitle,
                overview = incomingOverview,
                isAvailable = true,
                sourceEpisodeIds = incomingIds
            )
        }

        val existing = catalogEpisodeId?.let { episodeDao.getEpisodeById(it) }
            ?: selectedBackendId.takeIf { it.isNotEmpty() }?.let { episodeDao.getEpisodeById(it) }
            ?: episodeDao.getEpisodesForAnime(animeId).firstOrNull {
                it.seasonNumber == epSeason && it.episodeNumber == epNumber
            }
        val targetId = existing?.id ?: selectedBackendId.ifEmpty {
            catalogEpisodeId ?: "${animeId}_s${epSeason}_e${epNumber}"
        }
        val mergedSourceIds = EpisodeSourceEpisodeIds.encode(
            EpisodeSourceEpisodeIds.decode(existing?.sourceEpisodeIds) +
                EpisodeSourceEpisodeIds.decode(incomingIds)
        )
        val merged = EpisodeEntry(
            id = targetId,
            animeId = animeId,
            seasonNumber = existing?.seasonNumber ?: epSeason,
            episodeNumber = existing?.episodeNumber ?: epNumber,
            title = incomingTitle ?: existing?.title,
            overview = incomingOverview ?: existing?.overview,
            isAvailable = true,
            sourceEpisodeIds = mergedSourceIds
        )
        if (existing != merged) {
            episodeDao.upsertEpisodes(listOf(merged))
            metricsTracker.recordRoomWrite()
            memoryCache.invalidateEpisodes(animeId)
        }
        return merged
    }

    /**
     * Refreshes Anime or Movie details using ProphecyVerse Backend (`GET /api/v1/anime/{id}`).
     * - Movies: marks `NOT_APPLICABLE` for episode catalog and stores zero season/episode rows.
     * - Series: parses seasons and nested episodes with real backend IDs cleanly into Room.
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

                var backendId = resolveBackendIdForDetails(animeId, entry)
                if (backendId == null) {
                    if (ContentIdentityHelper.isMovie(entry)) {
                        return@withContext Result.success(
                            EpisodeCatalogRefreshStatus(
                                state = EpisodeCatalogState.NOT_APPLICABLE,
                                detailsEndpointSucceeded = false,
                                episodeListEndpointSucceeded = false
                            )
                        )
                    }
                    val cachedEps = episodeDao.getEpisodesForAnime(animeId)
                    return@withContext Result.success(
                        EpisodeCatalogRefreshStatus(
                            state = if (cachedEps.isNotEmpty()) EpisodeCatalogState.PARTIAL else EpisodeCatalogState.FAILED,
                            detailsEndpointSucceeded = false,
                            episodeListEndpointSucceeded = false,
                            currentRefreshEpisodeIds = cachedEps.map { it.id }.toSet(),
                            errorMessage = "Could not locate this title on ProphecyVerse backend."
                        )
                    )
                }

                val reqStarted = MonotonicMetrics.nowMs()
                var response = runCatching {
                    withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) {
                        prophecyApi.getAnimeDetails(backendId)
                    }
                }.getOrNull()
                if (response?.code() in listOf(404, 410) && animeId.startsWith("anilist_")) {
                    invalidateStaleBackendMapping(animeId, entry, backendId)
                    val retryBackendId = resolveBackendIdForDiscovery(animeId, entry, excludeBackendIds = setOf(backendId))
                    if (retryBackendId != null && retryBackendId != backendId) {
                        backendId = retryBackendId
                        response = runCatching {
                            withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) {
                                prophecyApi.getAnimeDetails(backendId)
                            }
                        }.getOrNull()
                    }
                }
                metricsTracker.recordApiRequest(response?.isSuccessful == true)
                MonotonicMetrics.recordDuration("backend_details_ms", reqStarted, "op=anime_details id=$backendId status=${response?.code() ?: -1}")

                if (response == null || !response.isSuccessful || response.body() == null) {
                    val isMovie = ContentIdentityHelper.isMovie(entry)
                    if (isMovie) {
                        return@withContext Result.success(
                            EpisodeCatalogRefreshStatus(EpisodeCatalogState.NOT_APPLICABLE, false, false)
                        )
                    }
                    val cachedEps = episodeDao.getEpisodesForAnime(animeId)
                    val state = if (cachedEps.isNotEmpty()) EpisodeCatalogState.PARTIAL else EpisodeCatalogState.FAILED
                    return@withContext Result.success(
                        EpisodeCatalogRefreshStatus(
                            state = state,
                            detailsEndpointSucceeded = false,
                            episodeListEndpointSucceeded = false,
                            currentRefreshEpisodeIds = cachedEps.map { it.id }.toSet(),
                            errorMessage = "Could not refresh details from ProphecyVerse (HTTP ${response?.code() ?: 0})."
                        )
                    )
                }

                val details = response.body()!!
                if (d1DataSource == null) queueBackendCatalogMetadata(details)

                val resolvedCategory = preserveMovieMediaCategory(entry?.mediaCategory, entry?.format, details.mediaType)
                    ?: backendMediaCategory(details.mediaType)
                    ?: entry?.mediaCategory
                    ?: "ANIME"
                val isMovie = resolvedCategory.equals("MOVIE", ignoreCase = true) || ContentIdentityHelper.isMovie(entry)
                val resolvedFormat = formatForBackendCategory(resolvedCategory, details.mediaType, entry?.format)

                val parsedAniListId = extractAniListIdFromBackend(details.canonicalId, details.providerMappings)
                    ?: entry?.aniListId
                    ?: animeId.removePrefix("anilist_").toIntOrNull()
                val resolvedBackendId = details.id.trim().ifEmpty { backendId }
                val preferredTitle = details.title.takeIf { it.isNotBlank() }
                    ?: details.titleEnglish?.takeIf { it.isNotBlank() }
                    ?: details.titleRomaji?.takeIf { it.isNotBlank() }
                    ?: entry?.preferredTitle
                    ?: "Anime #$resolvedBackendId"

                val seasonDtos = details.seasons.orEmpty().filter { it.number > 0 }
                val rawDtoEpisodes = seasonDtos.flatMap { s ->
                    s.episodes.orEmpty().map { ep -> ep.copy(season = ep.season ?: s.number) }
                }
                val remapDedicatedSequelSeason = !isMovie &&
                    entry?.canonicalSeasonNumber != null &&
                    entry.canonicalSeasonNumber > 1 &&
                    isDedicatedSequelBackendEntry(entry, details, rawDtoEpisodes, entry.canonicalSeasonNumber)
                val targetCatalogSeasonOverride = if (remapDedicatedSequelSeason) entry!!.canonicalSeasonNumber else null

                val mappedSeasons = if (isMovie) {
                    emptyList()
                } else {
                    seasonDtos.map { seasonDto ->
                        val effectiveSeasonNum = if (targetCatalogSeasonOverride != null && seasonDto.number == 1) {
                            targetCatalogSeasonOverride
                        } else {
                            seasonDto.number
                        }
                        SeasonEntry(
                            id = "${animeId}_s$effectiveSeasonNum",
                            animeId = animeId,
                            seasonNumber = effectiveSeasonNum,
                            backendSeasonId = seasonDto.canonicalId?.takeIf { it.isNotBlank() }
                                ?: seasonDto.id?.takeIf { it.isNotBlank() },
                            title = seasonDto.title?.takeIf { it.isNotBlank() } ?: "Season $effectiveSeasonNum"
                        )
                    }.distinctBy { it.seasonNumber }
                }

                val mappedEpisodes = if (isMovie) {
                    emptyList()
                } else {
                    val rawMapped = mutableListOf<EpisodeEntry>()
                    for (seasonDto in seasonDtos) {
                        for (epDto in seasonDto.episodes.orEmpty()) {
                            val epId = epDto.id.trim()
                            if (epId.isEmpty() || epDto.number <= 0) continue
                            val rawEpSeason = epDto.season?.takeIf { it > 0 } ?: seasonDto.number
                            val epSeason = if (targetCatalogSeasonOverride != null && rawEpSeason == 1) {
                                targetCatalogSeasonOverride
                            } else {
                                rawEpSeason
                            }
                            rawMapped += EpisodeEntry(
                                id = epId,
                                animeId = animeId,
                                seasonNumber = epSeason,
                                episodeNumber = epDto.number,
                                title = epDto.title?.takeIf { it.isNotBlank() },
                                overview = epDto.overview?.takeIf { it.isNotBlank() },
                                isAvailable = true,
                                sourceEpisodeIds = buildEpisodeSourceIdsForDto(epDto)
                            )
                        }
                    }
                    if (rawMapped.isEmpty()) {
                        val epResp = runCatching {
                            withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) {
                                prophecyApi.getAnimeEpisodes(resolvedBackendId)
                            }
                        }.getOrNull()
                        if (epResp?.isSuccessful == true) {
                            for (epDto in epResp.body().orEmpty()) {
                                val epId = epDto.id.trim()
                                if (epId.isEmpty() || epDto.number <= 0) continue
                                val rawEpSeason = epDto.season?.takeIf { it > 0 } ?: 1
                                val epSeason = if (targetCatalogSeasonOverride != null && rawEpSeason == 1) {
                                    targetCatalogSeasonOverride
                                } else {
                                    rawEpSeason
                                }
                                rawMapped += EpisodeEntry(
                                    id = epId,
                                    animeId = animeId,
                                    seasonNumber = epSeason,
                                    episodeNumber = epDto.number,
                                    title = epDto.title?.takeIf { it.isNotBlank() },
                                    overview = epDto.overview?.takeIf { it.isNotBlank() },
                                    isAvailable = true,
                                    sourceEpisodeIds = buildEpisodeSourceIdsForDto(epDto)
                                )
                            }
                        }
                    }
                    rawMapped.distinctBy { it.id }
                }

                val finalSeasons = if (isMovie) {
                    emptyList()
                } else {
                    val missingSeasonNums = mappedEpisodes.map { it.seasonNumber }.distinct() -
                        mappedSeasons.map { it.seasonNumber }.toSet()
                    (mappedSeasons + missingSeasonNums.map { sNum ->
                        SeasonEntry(
                            id = "${animeId}_s$sNum",
                            animeId = animeId,
                            seasonNumber = sNum,
                            backendSeasonId = null,
                            title = "Season $sNum"
                        )
                    }).sortedBy { it.seasonNumber }
                }

                val declaredTotalCount = details.episodeCount?.takeIf { it > 0 }
                    ?: mappedEpisodes.size.takeIf { it > 0 }
                    ?: entry?.episodeCount
                val authoritativeComplete = isAuthoritativeEpisodeSnapshot(details.partial, details.seasons) ||
                    (details.partial == false && mappedEpisodes.isNotEmpty())
                val metadataCompleteness = when {
                    isMovie -> "NOT_APPLICABLE"
                    authoritativeComplete && mappedEpisodes.isNotEmpty() -> "COMPLETE"
                    details.partial == false && details.status.equals("FINISHED", ignoreCase = true) && mappedEpisodes.isNotEmpty() -> "COMPLETE"
                    mappedEpisodes.isNotEmpty() -> "PARTIAL"
                    else -> "MISSING_EPISODES"
                }

                val updatedEntry = (entry ?: AnimeEntry(id = animeId, preferredTitle = preferredTitle)).copy(
                    aniListId = parsedAniListId,
                    backendAnimeId = resolvedBackendId,
                    canonicalAnimeId = if (isMovie) null else entry?.canonicalAnimeId,
                    canonicalTitle = if (isMovie) null else entry?.canonicalTitle,
                    canonicalSeasonNumber = if (isMovie) null else entry?.canonicalSeasonNumber,
                    cumulativeEpisodeOffset = if (isMovie) null else entry?.cumulativeEpisodeOffset,
                    titleRomaji = details.titleRomaji?.takeIf { it.isNotBlank() } ?: entry?.titleRomaji,
                    titleEnglish = details.titleEnglish?.takeIf { it.isNotBlank() } ?: entry?.titleEnglish,
                    titleNative = details.titleNative?.takeIf { it.isNotBlank() } ?: entry?.titleNative,
                    preferredTitle = preferredTitle,
                    synopsis = details.description?.takeIf { it.isNotBlank() } ?: entry?.synopsis,
                    year = details.year?.takeIf { it.isNotBlank() } ?: entry?.year,
                    season = entry?.season,
                    format = resolvedFormat,
                    status = details.status?.takeIf { it.isNotBlank() } ?: entry?.status,
                    genres = details.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: entry?.genres.orEmpty(),
                    score = entry?.score,
                    episodeCount = if (isMovie) 1 else declaredTotalCount,
                    duration = details.durationMinutes?.takeIf { it > 0 } ?: entry?.duration,
                    studios = entry?.studios,
                    posterUrl = details.posterUrl?.takeIf { it.isNotBlank() } ?: entry?.posterUrl,
                    bannerUrl = details.bannerUrl?.takeIf { it.isNotBlank() } ?: entry?.bannerUrl,
                    isPlayable = true,
                    mediaCategory = resolvedCategory,
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

                parsedAniListId?.let { aniId ->
                    animeDao.upsertMapping(CatalogMapping(aniListId = aniId, backendAnimeId = resolvedBackendId, confidence = 1.0f))
                }

                val aliases = mutableListOf<AnimeAlias>()
                collectBackendAliases(
                    animeId = animeId,
                    titles = listOf(preferredTitle, details.title, details.titleEnglish, details.titleRomaji, details.titleNative),
                    alternativeTitles = details.alternativeTitles,
                    out = aliases
                )
                val existingAliasSet = runCatching { animeDao.getAliasesForAnime(animeId) }
                    .getOrDefault(emptyList())
                    .map { it.alias }
                    .toSet()
                val distinctAliases = aliases.distinctBy { it.animeId to it.alias }
                val newAliases = distinctAliases.filter { it.alias !in existingAliasSet }
                if (newAliases.isNotEmpty()) animeDao.insertAliases(newAliases)

                if (isMovie) {
                    // Movies MUST NEVER store SeasonEntry or EpisodeEntry rows in Room
                    episodeDao.deleteEpisodesForAnime(animeId)
                    episodeDao.deleteSeasonsForAnime(animeId)
                    return@withContext Result.success(
                        EpisodeCatalogRefreshStatus(
                            state = EpisodeCatalogState.NOT_APPLICABLE,
                            detailsEndpointSucceeded = true,
                            episodeListEndpointSucceeded = false
                        )
                    )
                }

                if (targetCatalogSeasonOverride != null && targetCatalogSeasonOverride > 1) {
                    episodeDao.deleteOtherSeasonEpisodes(animeId, targetCatalogSeasonOverride)
                    episodeDao.deleteOtherSeasons(animeId, targetCatalogSeasonOverride)
                }
                if (authoritativeComplete) {
                    episodeDao.replaceEpisodeSnapshot(animeId, finalSeasons, mappedEpisodes)
                } else if (finalSeasons.isNotEmpty() || mappedEpisodes.isNotEmpty()) {
                    episodeDao.mergeEpisodeSnapshot(animeId, finalSeasons, mappedEpisodes)
                }

                val persistedSeasons = episodeDao.getSeasonsForAnime(animeId)
                val persistedEpisodes = episodeDao.getEpisodesForAnime(animeId)
                if (persistedSeasons.isNotEmpty()) memoryCache.putSeasons(animeId, persistedSeasons)
                val firstSeasonNum = updatedEntry.canonicalSeasonNumber?.takeIf { it > 1 }
                    ?: persistedSeasons.firstOrNull()?.seasonNumber
                    ?: 1
                val firstPageEps = persistedEpisodes.filter { it.seasonNumber == firstSeasonNum }.take(30)
                if (firstPageEps.isNotEmpty()) memoryCache.putEpisodesPage(animeId, firstSeasonNum, 0, firstPageEps)

                val catalogState = resolveEpisodeCatalogState(
                    snapshotComplete = authoritativeComplete,
                    cachedEpisodeCount = persistedEpisodes.size,
                    backendIdKnown = true,
                    detailsEndpointSucceeded = true,
                    episodeListEndpointSucceeded = mappedEpisodes.isNotEmpty(),
                    isMovieCatalog = false
                )
                Result.success(
                    EpisodeCatalogRefreshStatus(
                        state = catalogState,
                        detailsEndpointSucceeded = true,
                        episodeListEndpointSucceeded = mappedEpisodes.isNotEmpty(),
                        currentRefreshEpisodeIds = persistedEpisodes.map { it.id }.toSet()
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun loadEpisodePage(
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
        emptyList()
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

        var latestSelectedEpisodeDto: ProphecyEpisodeDto? = null
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
                    if (body.selectedEpisode != null) {
                        val incomingSel = body.selectedEpisode
                        val prevSel = latestSelectedEpisodeDto
                        latestSelectedEpisodeDto = if (prevSel == null) {
                            incomingSel
                        } else {
                            prevSel.copy(
                                title = prevSel.title?.takeIf { it.isNotBlank() } ?: incomingSel.title,
                                overview = prevSel.overview?.takeIf { it.isNotBlank() } ?: incomingSel.overview
                            )
                        }
                    }
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
                    allowCachedIds = false,
                    bypassCatalogEpisodeIds = true
                )
                if (altState.episodeIds.isNotEmpty()) {
                    backendId = alternateId
                    backendEpisodeIds = altState.episodeIds
                    allowedEpisodeNumbers = altState.allowedEpisodeNumbers
                    effectiveBackendSeason = altState.effectiveBackendSeason
                    aggregatedCandidates.clear()
                    latestSelectedEpisodeDto = null
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
            val resolvedStatus = when {
                prev?.status == StreamRecordStatus.REMOVED.name || prev?.deletedAt != null -> StreamRecordStatus.REMOVED.name
                SourceExpiryTime.evaluateExpiry(effectiveExpiresAt) == ExpiryState.EXPIRED -> StreamRecordStatus.EXPIRED.name
                else -> prev?.status ?: StreamRecordStatus.MISSING_URL.name
            }
            val resolvedBackendEpId = backendEpMap[compositeKey] ?: prev?.backendEpisodeId
            val resolvedQuality = dto.quality ?: prev?.quality
            val resolvedCodec = dto.codec ?: prev?.codec
            val resolvedLang = dto.language ?: prev?.audioLanguage
            val resolvedDownloadable = dto.downloadable ?: prev?.downloadable
            val resolvedSizeBytes = dto.sizeBytes ?: prev?.sizeBytes
            val sourceChanged = prev == null ||
                prev.sourceName != dto.displayProviderName ||
                prev.backendEpisodeId != resolvedBackendEpId ||
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
                urlType = prev?.urlType ?: "direct",
                contentType = prev?.contentType,
                quality = resolvedQuality,
                codec = resolvedCodec,
                audioLanguage = resolvedLang,
                downloadable = resolvedDownloadable,
                sizeBytes = resolvedSizeBytes,
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

        val updatedSelectedEpisode = if (latestSelectedEpisodeDto != null) {
            mergeSelectedEpisodeIntoCatalog(
                animeId = animeId,
                isMovie = isMovie,
                requestedSeason = effectiveSeason,
                requestedEpisode = effectiveEpisode,
                catalogEpisodeId = catalogEpisodeId,
                selectedEp = latestSelectedEpisodeDto!!
            )
        } else if (!isMovie) {
            catalogEpisodeId?.let { episodeDao.getEpisodeById(it) }
                ?: episodeDao.getEpisodesForAnime(animeId).firstOrNull {
                    it.seasonNumber == effectiveSeason && it.episodeNumber == effectiveEpisode
                }
        } else {
            null
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
            exactDuplicateCount = dedupedDtos.exactDuplicateCount,
            selectedEpisode = updatedSelectedEpisode
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
            }
            aniListId?.let { id ->
                animeDao.getMappingForAniList(id)?.backendAnimeId?.takeIf { it.isNotBlank() }?.let { mapped ->
                    if (anime?.backendAnimeId != mapped || !anime.isPlayable) {
                        animeDao.updateBackendMappingForEntry(animeId, mapped)
                    }
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
        animeDao.getAnimeById(animeId)?.let { updated ->
            memoryCache.putAnime(updated)
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
        allowCachedIds: Boolean = true,
        bypassCatalogEpisodeIds: Boolean = false
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
        }
        if (!isMovie && !bypassCatalogEpisodeIds) {
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
                    if (allowCachedIds && !ep.id.startsWith("anilist_ep_") && !ep.id.startsWith("movie_") && !ep.id.startsWith("ep_") && ep.id.isNotBlank()) {
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

        val detailResp = runCatching {
            withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) { prophecyApi.getAnimeDetails(backendId) }
        }.getOrNull()

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
        if (backendEpisodes.isEmpty()) {
            val epResp = runCatching {
                withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) { prophecyApi.getAnimeEpisodes(backendId) }
            }.getOrNull()
            if (epResp?.isSuccessful == true) {
                backendEpisodes += epResp.body().orEmpty()
            }
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
    // 3. D1-FIRST STREAM URL CACHE & DIRECT-PLAY RESOLUTION
    // ============================================================================================

    private data class D1StreamKeys(
        val animeId: String,
        val episodeId: String
    )

    private suspend fun resolveD1StreamKeys(
        animeId: String,
        anime: AnimeEntry?,
        identity: com.example.data.streaming.CanonicalStreamIdentity,
        seasonNumber: Int,
        episodeNumber: Int,
        catalogEpisodeId: String?,
        sourceEntity: StreamSourceEntity? = null,
        knownBackendEpisodeIds: Set<String> = emptySet()
    ): D1StreamKeys {
        val isMovie = identity.isMovie
        val resolvedAnimeId = anime?.backendAnimeId?.trim()?.takeIf { it.isNotEmpty() }
            ?: identity.backendAnimeId?.trim()?.takeIf { it.isNotEmpty() }
            ?: identity.anilistAnimeId?.let { aniId ->
                animeDao.getMappingForAniList(aniId)?.backendAnimeId?.trim()?.takeIf { it.isNotEmpty() }
            }
            ?: animeId.trim()

        val resolvedEpisodeId = sourceEntity?.backendEpisodeId?.trim()?.takeIf {
                it.isNotEmpty() && !it.startsWith("anilist_ep_") && !it.startsWith("movie_")
            }
            ?: knownBackendEpisodeIds.firstOrNull {
                it.isNotBlank() && !it.startsWith("anilist_ep_") && !it.startsWith("movie_")
            }
            ?: catalogEpisodeId?.trim()?.takeIf {
                it.isNotEmpty() && !it.startsWith("anilist_ep_") && !it.startsWith("movie_")
            }
            ?: if (isMovie) {
                "movie_${identity.contentKey}_e1"
            } else {
                "${identity.contentKey}_s${seasonNumber}_e${episodeNumber}"
            }

        return D1StreamKeys(animeId = resolvedAnimeId, episodeId = resolvedEpisodeId)
    }

    private fun inferUrlTypeFromUrl(url: String, fallback: String = "direct"): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") -> "hls"
            clean.endsWith(".mpd") -> "dash"
            fallback.isNotBlank() -> fallback
            else -> "direct"
        }
    }

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

        // Ensure source metadata discovery exists in Room (Memory -> Room -> Backend)
        var roomSources = streamCacheDao.getActiveSources(scopeKey)
        var discoveryError: Throwable? = null
        if (roomSources.isEmpty()) {
            discoveryError = discoverStreamsForScope(
                animeId, effectiveSeason, effectiveEpisode, catalogEpisodeId, forceRefresh = false
            ).exceptionOrNull()
            roomSources = streamCacheDao.getActiveSources(scopeKey)
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

        // 1. Check Memory + Cloudflare D1 Stream URL cache BEFORE calling Backend resolution
        if (!forceResolveFresh) {
            for (cachedSource in cacheCheckPool) {
                val normSourceId = SourceIdentityValidator.normalizeId(cachedSource.sourceId)
                if (normSourceId.isEmpty()) continue
                val metaValidation = SourceIdentityValidator.validateCachedSourceEntity(
                    entity = cachedSource,
                    expectedScopeKey = scopeKey,
                    expectedSourceId = normPreferred ?: cachedSource.sourceId,
                    expectedProviderId = normPreferredProvider ?: cachedSource.providerId,
                    allowedBackendEpisodeIds = knownBackendEpisodeIds
                )
                if (metaValidation is SourceValidationResult.Invalid) {
                    SourceIdentityValidator.recordDiagnostic(
                        code = metaValidation.code,
                        scopeKey = scopeKey,
                        sourceId = cachedSource.sourceId,
                        providerLabel = cachedSource.sourceName,
                        detail = metaValidation.reason
                    )
                    if (metaValidation.code == SourceDiagnosticCode.SOURCE_EXPIRED) {
                        memoryCache.invalidatePlayback(normSourceId, scopeKey, cachedSource.providerId)
                    }
                    continue
                }

                // 1a. In-memory validated playback cache
                val memHit = memoryCache.getResolvedPlayback(normSourceId, scopeKey, cachedSource.providerId)
                if (memHit != null && SourceIdentityValidator.validateDirectPlayData(memHit, normSourceId).isValid) {
                    val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, "tier=MEMORY")
                    val normDto = SourceIdentityValidator.normalizeDto(cachedSource.toProphecyDto())
                    return@withContext Result.success(
                        ResolvedStreamPlayback(
                            source = normDto,
                            directData = memHit,
                            candidates = filterRemainingCandidates(candidatesDto, normDto, cachedSource.providerId),
                            fromCache = true,
                            cacheTier = "MEMORY",
                            resolveDurationMs = elapsed
                        )
                    )
                }

                // 1b. Cloudflare D1 Stream URL Cache lookup (anime_id, episode_id, source_id)
                val d1Keys = resolveD1StreamKeys(
                    animeId = animeId,
                    anime = anime,
                    identity = identity,
                    seasonNumber = effectiveSeason,
                    episodeNumber = effectiveEpisode,
                    catalogEpisodeId = catalogEpisodeId,
                    sourceEntity = cachedSource,
                    knownBackendEpisodeIds = knownBackendEpisodeIds
                )
                val d1Hit = d1DataSource?.getValidStreamUrl(
                    animeId = d1Keys.animeId,
                    episodeId = d1Keys.episodeId,
                    sourceId = normSourceId
                )
                if (d1Hit != null) {
                    val data = DirectPlayData(
                        mode = "direct",
                        sourceId = normSourceId,
                        playbackUrl = d1Hit.streamUrl,
                        urlType = inferUrlTypeFromUrl(d1Hit.streamUrl, cachedSource.urlType),
                        compatibility = "verified",
                        requiresHeaders = false,
                        headers = emptyList(),
                        contentType = cachedSource.contentType,
                        expiresAt = d1Hit.expiresAt ?: cachedSource.expiresAt
                    )
                    if (SourceIdentityValidator.validateDirectPlayData(data, normSourceId).isValid) {
                        memoryCache.putResolvedPlayback(normSourceId, data, scopeKey, cachedSource.providerId)
                        val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, "tier=D1")
                        val normDto = SourceIdentityValidator.normalizeDto(cachedSource.toProphecyDto())
                        return@withContext Result.success(
                            ResolvedStreamPlayback(
                                source = normDto,
                                directData = data,
                                candidates = filterRemainingCandidates(candidatesDto, normDto, cachedSource.providerId),
                                fromCache = true,
                                cacheTier = "D1",
                                resolveDurationMs = elapsed
                            )
                        )
                    }
                }
            }
        }

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

        // Helper for single-source direct playback resolution with D1 check -> Backend API -> D1 save
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
            val d1Keys = resolveD1StreamKeys(
                animeId = animeId,
                anime = anime,
                identity = identity,
                seasonNumber = effectiveSeason,
                episodeNumber = effectiveEpisode,
                catalogEpisodeId = catalogEpisodeId,
                sourceEntity = sourceEntity,
                knownBackendEpisodeIds = knownBackendEpisodeIds
            )
            val lease = streamingRepository.acquireResolveLease(
                contentKey = contentKey,
                isMovie = isMovie,
                providerId = sourceEntity.providerId,
                sourceId = normSourceId,
                seasonNumber = if (isMovie) 0 else effectiveSeason,
                episodeNumber = if (isMovie) 0 else effectiveEpisode
            )

            var finalLeaseStatus = "FAILED"
            try {
                val res = resolveDirectPlayback(
                    sourceId = normSourceId,
                    forceRefresh = forceResolveFresh,
                    scopeKey = scopeKey,
                    providerId = sourceEntity.providerId,
                    animeId = d1Keys.animeId,
                    episodeId = d1Keys.episodeId,
                    skipCacheCheck = !forceResolveFresh
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
                        val validExpiry = sanitizedData.expiresAt?.trim()?.takeIf {
                            SourceExpiryTime.evaluateExpiry(it) == ExpiryState.VALID
                        }
                        streamingRepository.publishResolvedStreamUrl(
                            sourceEntity = sourceEntity.copy(sourceId = normSourceId),
                            contentKey = contentKey,
                            isMovie = isMovie,
                            seasonNumber = effectiveSeason,
                            episodeNumber = effectiveEpisode,
                            streamUrl = sanitizedData.playbackUrl,
                            urlType = sanitizedData.urlType,
                            contentType = sanitizedData.contentType,
                            safeHeaders = safeHeaders,
                            expiresAt = validExpiry,
                            anilistId = identity.anilistAnimeId,
                            backendId = identity.backendAnimeId ?: anime?.backendAnimeId,
                            canonicalTitle = identity.canonicalTitle
                        )
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
                if (lease != null) {
                    runCatching {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            streamingRepository.releaseResolveLease(lease = lease, resolveStatus = finalLeaseStatus)
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
                    .thenByDescending { it.status == StreamRecordStatus.AVAILABLE.name && !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt) }
                    .thenByDescending { it.lastPlaybackSuccessAt ?: 0L }
                    .thenByDescending { qualityRank(it.quality) }
            )
        }
        return active.sortedWith(
            compareByDescending<StreamSourceEntity> { it.status == StreamRecordStatus.AVAILABLE.name && !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt) }
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
        val cachedEpisode = if (isMovie) {
            null
        } else {
            episodeDao.getEpisodesForAnime(animeId).firstOrNull {
                it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber
            }
        }
        return EpisodeSourceSnapshot(
            sources = dtos,
            state = state,
            fetchedAtMs = latestUpdated,
            seasonNumber = if (isMovie) null else seasonNumber,
            episodeNumber = if (isMovie) null else episodeNumber,
            selectedEpisode = cachedEpisode
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
                val targetAnimeEntry = animeDao.getAnimeById(backendAnimeId)
                    ?: animeDao.getAnimeByBackendId(backendAnimeId)
                val resolvedAnimeId = targetAnimeEntry?.id ?: backendAnimeId
                val isMovieTarget = ContentIdentityHelper.isMovie(targetAnimeEntry) ||
                    (seasonNumber == 0 && episodeId.startsWith("movie_"))
                val updatedSelectedEpisode = body.selectedEpisode?.let { selEp ->
                    mergeSelectedEpisodeIntoCatalog(
                        animeId = resolvedAnimeId,
                        isMovie = isMovieTarget,
                        requestedSeason = seasonNumber ?: selEp.season ?: 1,
                        requestedEpisode = episodeNumber ?: selEp.number.takeIf { it > 0 } ?: 1,
                        catalogEpisodeId = episodeId,
                        selectedEp = selEp
                    )
                }
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
                    sources = mergedCandidates.map { SourceIdentityValidator.normalizeDto(it) },
                    state = aggregated.state,
                    backendStatus = aggregated.backendStatus,
                    errorMessage = aggregated.errorMessage,
                    fetchedAtMs = System.currentTimeMillis(),
                    seasonNumber = seasonNumber ?: updatedSelectedEpisode?.seasonNumber,
                    episodeNumber = episodeNumber ?: updatedSelectedEpisode?.episodeNumber,
                    apiCandidateCount = aggregated.apiCandidateCount,
                    parsedCandidateCount = aggregated.parsedCandidateCount,
                    expiredCandidateCount = aggregated.expiredCandidateCount,
                    exactDuplicateCount = aggregated.exactDuplicateCount,
                    selectedEpisode = updatedSelectedEpisode ?: cached?.selectedEpisode
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
        providerId: String? = null,
        animeId: String = "",
        episodeId: String = "",
        skipCacheCheck: Boolean = false
    ): Result<DirectPlayData> = withContext(Dispatchers.IO) {
        val normSourceId = SourceIdentityValidator.normalizeId(sourceId)
        val normScope = scopeKey?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        val normProvider = providerId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        val normAnime = animeId.trim()
        val normEpisode = episodeId.trim()
        if (normSourceId.isEmpty()) return@withContext Result.failure(
            SourceResolutionDiagnosticException(SourceDiagnosticCode.SOURCE_NOT_PLAYABLE, "Source ID is blank")
        )

        val flightKey = "${normScope.orEmpty()}::${normProvider.orEmpty()}::${normAnime}::${normEpisode}::$normSourceId::fresh=$forceRefresh"
        while (true) {
            val existing = inFlightDirectResolve[flightKey]
            if (existing != null) {
                metricsTracker.recordDuplicateRequestPrevented()
                return@withContext existing.await()
            }
            val created = CompletableDeferred<Result<DirectPlayData>>()
            if (inFlightDirectResolve.putIfAbsent(flightKey, created) == null) {
                try {
                    val computed = executeResolveDirectPlayback(
                        normSourceId = normSourceId,
                        forceRefresh = forceRefresh,
                        normScope = normScope,
                        normProvider = normProvider,
                        explicitAnimeId = normAnime,
                        explicitEpisodeId = normEpisode,
                        skipCacheCheck = skipCacheCheck
                    )
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
        normProvider: String?,
        explicitAnimeId: String = "",
        explicitEpisodeId: String = "",
        skipCacheCheck: Boolean = false
    ): Result<DirectPlayData> {
        val roomSource = when {
            normScope != null && normProvider != null ->
                streamCacheDao.getLatestActiveSourceForProviderInScope(normScope, normProvider, normSourceId)
            normScope != null ->
                streamCacheDao.getActiveSourcesForSourceIdInScope(normScope, normSourceId).firstOrNull()
            else ->
                streamCacheDao.getLatestActiveSourceBySourceId(normSourceId)
        }
        val effectiveScope = normScope ?: roomSource?.scopeKey
        val scopeContentKey = effectiveScope?.substringAfter(':', "")?.substringBefore(":s")
        val resolvedAnimeId = explicitAnimeId.takeIf { it.isNotBlank() }
            ?: scopeContentKey?.toIntOrNull()?.let { animeDao.getMappingForAniList(it)?.backendAnimeId }
            ?: scopeContentKey.orEmpty()
        val resolvedEpisodeId = explicitEpisodeId.takeIf { it.isNotBlank() }
            ?: roomSource?.backendEpisodeId?.takeIf { it.isNotBlank() }
            ?: effectiveScope?.let { sc ->
                if (sc.startsWith("movie:") || sc.startsWith("movie_")) "movie_${scopeContentKey.orEmpty()}_e1"
                else if (sc.contains(":s") && sc.contains(":e")) "${scopeContentKey.orEmpty()}_s${sc.substringAfter(":s").substringBefore(":e")}_e${sc.substringAfter(":e")}"
                else null
            }.orEmpty()

        if (forceRefresh) {
            memoryCache.invalidatePlayback(normSourceId, normScope, normProvider)
        } else if (!skipCacheCheck) {
            memoryCache.getResolvedPlayback(normSourceId, normScope, normProvider)?.let { cachedMem ->
                if (SourceIdentityValidator.validateDirectPlayData(cachedMem, normSourceId).isValid) {
                    return Result.success(cachedMem)
                }
                memoryCache.invalidatePlayback(normSourceId, normScope, normProvider)
            }

            // Always check Cloudflare D1 before calling Backend POST /api/v1/play/direct
            if (resolvedAnimeId.isNotBlank() && resolvedEpisodeId.isNotBlank()) {
                val d1Hit = d1DataSource?.getValidStreamUrl(resolvedAnimeId, resolvedEpisodeId, normSourceId)
                if (d1Hit != null) {
                    val d1PlayData = DirectPlayData(
                        mode = "direct",
                        sourceId = normSourceId,
                        playbackUrl = d1Hit.streamUrl,
                        urlType = inferUrlTypeFromUrl(d1Hit.streamUrl, roomSource?.urlType ?: "direct"),
                        compatibility = "verified",
                        requiresHeaders = false,
                        headers = emptyList(),
                        contentType = roomSource?.contentType,
                        expiresAt = d1Hit.expiresAt ?: roomSource?.expiresAt
                    )
                    if (SourceIdentityValidator.validateDirectPlayData(d1PlayData, normSourceId).isValid) {
                        memoryCache.putResolvedPlayback(normSourceId, d1PlayData, normScope, normProvider ?: roomSource?.providerId)
                        return Result.success(d1PlayData)
                    }
                }
            }
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
                prophecyApi.resolveDirectPlay(
                    DirectPlayRequest(
                        animeId = resolvedAnimeId,
                        episodeId = resolvedEpisodeId,
                        sourceId = normSourceId
                    )
                )
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

            // Persist validated resolved Stream URL to Cloudflare D1 asynchronously (expires_at saved only when actual expiry is known)
            if (resolvedAnimeId.isNotBlank() && resolvedEpisodeId.isNotBlank()) {
                val validKnownExpiry = requestReady.expiresAt?.trim()?.takeIf {
                    SourceExpiryTime.evaluateExpiry(it) == ExpiryState.VALID
                }
                d1DataSource?.let { d1 ->
                    sharedCatalogScope.launch {
                        runCatching {
                            kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                                d1.saveStreamUrl(
                                    animeId = resolvedAnimeId,
                                    episodeId = resolvedEpisodeId,
                                    sourceId = normSourceId,
                                    streamUrl = requestReady.playbackUrl,
                                    expiresAt = validKnownExpiry
                                )
                            }
                        }.onFailure { err ->
                            Log.w("AnimeRepository", "D1 saveStreamUrl failed cleanly: ${err.message}")
                        }
                    }
                }
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
        val identity = ContentIdentityHelper.resolveCanonicalIdentity(animeId, anime, seasonNumber, episodeNumber)
        val scopeKey = ContentIdentityHelper.scopeKey(
            contentKey = identity.contentKey,
            isMovie = identity.isMovie,
            seasonNumber = identity.seasonNumber,
            episodeNumber = identity.episodeNumber
        )
        memoryCache.invalidatePlayback(normSourceId, scopeKey, normProvider)
        if (normProvider == null) {
            memoryCache.invalidatePlayback(normSourceId, null, null)
        }
        val matchingSource = if (normProvider != null) {
            streamCacheDao.getLatestActiveSourceForProviderInScope(scopeKey, normProvider, normSourceId)
        } else {
            streamCacheDao.getActiveSourcesForSourceIdInScope(scopeKey, normSourceId).firstOrNull()
        }
        val d1Keys = resolveD1StreamKeys(
            animeId = animeId,
            anime = anime,
            identity = identity,
            seasonNumber = identity.seasonNumber,
            episodeNumber = identity.episodeNumber,
            catalogEpisodeId = null,
            sourceEntity = matchingSource
        )
        runCatching {
            d1DataSource?.invalidateStreamUrl(d1Keys.animeId, d1Keys.episodeId, normSourceId)
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
