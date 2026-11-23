package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.identity.CanonicalEpisodeCatalogBuilder
import com.example.data.identity.CanonicalProviderCatalogResult
import com.example.data.identity.EpisodeCanonicalizer
import com.example.data.identity.EpisodeSourceEpisodeIds
import com.example.data.identity.CanonicalAnimeIdentity
import com.example.data.identity.CanonicalAnimeRoute
import com.example.data.identity.ProviderCatalogPayload
import com.example.data.identity.ProviderPriorityPolicy
import com.example.data.local.AnimeAlias
import com.example.data.local.AnimeDao
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.CatalogMapping
import com.example.data.local.EpisodeDao
import com.example.data.local.EpisodeEntry
import com.example.data.local.PlaybackHistory
import com.example.data.metrics.PlaybackDiagnosticEvent
import com.example.data.metrics.PlaybackDiagnosticsLogger
import com.example.data.local.PlaybackHistoryDao
import com.example.data.local.SearchDao
import com.example.data.local.SearchQuery
import com.example.data.local.SearchResultRef
import com.example.data.local.SeasonEntry
import com.example.data.local.WatchlistDao
import com.example.data.local.WatchlistEntry
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLResponse
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.AniListMedia
import com.example.data.remote.AniListPage
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
import com.example.data.streaming.MonotonicMetrics
import com.example.data.streaming.ResolvedStreamPlayback
import com.example.data.streaming.SafePlaybackHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
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
    private val d1DataSource: com.example.data.d1.CloudflareD1DataSource = NetworkModule.d1DataSource,
    private val memoryCache: com.example.data.cache.MemoryCacheManager = com.example.data.cache.MemoryCacheManager(),
    private val metricsTracker: com.example.data.metrics.DatabaseMetricsTracker = com.example.data.metrics.DatabaseMetricsTracker.getInstance(),
    private val providerPolicy: ProviderPriorityPolicy = ProviderPriorityPolicy()
) {
    private val animeDao: AnimeDao = database.animeDao()
    private val v3Gateway = com.example.data.remote.V3BackendGateway(prophecyApi)
    private val episodeDao: EpisodeDao = database.episodeDao()
    private val searchDao: SearchDao = database.searchDao()
    private val watchlistDao: WatchlistDao = database.watchlistDao()
    private val historyDao: PlaybackHistoryDao = database.playbackHistoryDao()
    private val discoveredBackendEpisodeIds = ConcurrentHashMap<String, String>()
    private val providerBackendEpisodeIds = ConcurrentHashMap<String, String>()
    private data class PendingProviderTier(
        val tierRank: Int,
        val backendId: String,
        val episodeIds: List<String>,
        val effectiveBackendSeason: Int,
        val allowedEpisodeNumbers: Set<Int>
    )
    private val unqueriedFallbackTiers = ConcurrentHashMap<String, MutableList<PendingProviderTier>>()
    private val canonical4khdConfirmedAnimeIds = ConcurrentHashMap.newKeySet<String>()
    private val homeDiscoveryInFlight = ConcurrentHashMap<Boolean, CompletableDeferred<Result<Unit>>>()

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
        while (true) {
            val existingFlight = homeDiscoveryInFlight[force]
            if (existingFlight != null) {
                metricsTracker.recordDuplicateRequestPrevented()
                return@withContext existingFlight.await()
            }
            val createdFlight = CompletableDeferred<Result<Unit>>()
            if (homeDiscoveryInFlight.putIfAbsent(force, createdFlight) == null) {
                try {
                    val res = executeRefreshHomeDiscovery(force)
                    createdFlight.complete(res)
                    return@withContext res
                } catch (cancelled: CancellationException) {
                    createdFlight.cancel(cancelled)
                    throw cancelled
                } catch (t: Throwable) {
                    val failure = Result.failure<Unit>(t)
                    createdFlight.complete(failure)
                    return@withContext failure
                } finally {
                    homeDiscoveryInFlight.remove(force, createdFlight)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(IllegalStateException("Unreachable home discovery state"))
    }

    private suspend fun executeRefreshHomeDiscovery(force: Boolean): Result<Unit> =
        homeRefreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (force) {
                memoryCache.invalidateHome()
            } else {
                if (now - lastHomeRefreshTimestamp < 5 * 60 * 1000L && memoryCache.isHomeFresh("home_bundle")) {
                    memoryCache.getHomeSection("home_bundle")
                    return@withLock Result.success(Unit)
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
                        return@withLock Result.success(Unit)
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

                val request = AniListGraphQLRequest(
                    query = AniListQueries.DISCOVERY_FEEDS,
                    variables = mapOf("season" to season, "seasonYear" to year)
                )
                val response = postAniListDiscoveryWithRetry(request)
                MonotonicMetrics.recordDuration("anilist_request_ms", reqStarted, "op=home_feeds status=${response.code()}")

                if (!response.isSuccessful) {
                    val errBody = runCatching { response.errorBody()?.string() }.getOrNull()
                    Log.w("AnimeRepository", httpFailureDiagnostic("anilist_home", response.code(), response.headers()["X-Request-ID"], errBody))
                    return@withLock Result.failure(IllegalStateException("AniList home feeds failed: HTTP ${response.code()}"))
                }

                val body = response.body()
                if (body == null) {
                    Log.w("AnimeRepository", "AniList home feeds returned null HTTP body")
                    return@withLock Result.failure(IllegalStateException("AniList home feeds returned empty body"))
                }

                val gqlErrors = body.errors.orEmpty().filter { !it.message.isNullOrBlank() || it.status != null }
                val gqlErrorSummary = gqlErrors.joinToString("; ") { err ->
                    val statusTag = err.status?.let { "($it) " }.orEmpty()
                    val msg = err.message.orEmpty().replace(Regex("""[\r\n\t]+"""), " ").take(120)
                    "$statusTag$msg"
                }.take(300)

                val data = body.data
                if (data == null) {
                    Log.w("AnimeRepository", "AniList home feeds returned null data; errors=[$gqlErrorSummary]")
                    return@withLock Result.failure(
                        IllegalStateException(
                            if (gqlErrorSummary.isNotBlank()) "AniList GraphQL errors: $gqlErrorSummary"
                            else "AniList home feeds returned empty data"
                        )
                    )
                }

                fun safeSectionMedia(page: AniListPage?): List<AniListMedia> =
                    page?.media.orEmpty()
                        .filterNotNull()
                        .filter { it.id > 0 }
                        .distinctBy { it.id }

                val trendingMedia = safeSectionMedia(data.trending)
                val seasonalMedia = safeSectionMedia(data.seasonal)
                val popularMedia = safeSectionMedia(data.popular)
                val movieMedia = safeSectionMedia(data.movies)

                val allMedia = uniqueAniListMedia(trendingMedia + seasonalMedia + popularMedia + movieMedia)
                if (allMedia.isEmpty()) {
                    Log.w(
                        "AnimeRepository",
                        "AniList home feeds contained zero valid media items; preserving cached catalog. errors=[$gqlErrorSummary]"
                    )
                    return@withLock Result.failure(
                        IllegalStateException(
                            if (gqlErrorSummary.isNotBlank()) "AniList home feeds unavailable: $gqlErrorSummary"
                            else "AniList home feeds returned zero media items"
                        )
                    )
                }

                val trendingAuthoritative = trendingMedia.isNotEmpty() || (data.trending?.media != null && gqlErrors.isEmpty())
                val seasonalAuthoritative = seasonalMedia.isNotEmpty() || (data.seasonal?.media != null && gqlErrors.isEmpty())
                val popularAuthoritative = popularMedia.isNotEmpty() || (data.popular?.media != null && gqlErrors.isEmpty())

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
                    val preferredTitle = m.title?.english?.takeIf { it.isNotBlank() }
                        ?: m.title?.romaji?.takeIf { it.isNotBlank() }
                        ?: m.title?.native?.takeIf { it.isNotBlank() }
                        ?: existing?.preferredTitle
                        ?: "Anime #${m.id}"
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
                        titleRomaji = m.title?.romaji ?: existing?.titleRomaji,
                        titleEnglish = m.title?.english ?: existing?.titleEnglish,
                        titleNative = m.title?.native ?: existing?.titleNative,
                        preferredTitle = preferredTitle,
                        synopsis = m.description ?: existing?.synopsis,
                        year = (m.startDate?.year ?: m.seasonYear)?.toString() ?: existing?.year,
                        season = m.season ?: existing?.season,
                        format = m.format ?: existing?.format,
                        status = m.status ?: existing?.status,
                        genres = m.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: existing?.genres.orEmpty(),
                        score = m.averageScore?.let { it / 10.0 } ?: existing?.score,
                        episodeCount = resolveKnownEpisodeCount(m, existing?.episodeCount),
                        duration = m.duration ?: existing?.duration,
                        studios = m.studios?.nodes?.firstOrNull()?.name ?: existing?.studios,
                        posterUrl = m.coverImage?.large ?: m.coverImage?.medium ?: existing?.posterUrl,
                        bannerUrl = m.bannerImage ?: existing?.bannerUrl,
                        isPlayable = true,
                        isTrending = if (trendingAuthoritative) (m.id in trendingIds) else (existing?.isTrending ?: false),
                        isSeasonal = if (seasonalAuthoritative) (m.id in seasonalIds) else (existing?.isSeasonal ?: false),
                        isPopular = if (popularAuthoritative) (m.id in popularIds) else (existing?.isPopular ?: false),
                        mediaCategory = if (isMovieFormat) "MOVIE" else (existing?.mediaCategory ?: "ANIME"),
                        metadataCompleteness = existing?.metadataCompleteness ?: "UNKNOWN",
                        updatedAt = System.currentTimeMillis()
                    )
                    entriesToUpsert += entry
                    collectAliases(entry.id, m, aliasesToInsert)
                }

                val distinctAliases = aliasesToInsert.distinctBy { it.animeId to it.alias }
                val roomStarted = MonotonicMetrics.nowMs()
                database.withTransaction {
                    if (trendingAuthoritative) animeDao.clearTrendingFlags()
                    if (seasonalAuthoritative) animeDao.clearSeasonalFlags()
                    if (popularAuthoritative) animeDao.clearPopularFlags()
                    if (entriesToUpsert.isNotEmpty()) animeDao.upsertAnimeList(entriesToUpsert)
                    if (distinctAliases.isNotEmpty()) animeDao.insertAliases(distinctAliases)
                }
                MonotonicMetrics.recordDuration("room_transaction_ms", roomStarted, "op=home_feeds items=${entriesToUpsert.size}")

                memoryCache.putHomeSection("home_bundle", entriesToUpsert)
                entriesToUpsert.forEach { memoryCache.putAnime(it) }
                lastHomeRefreshTimestamp = System.currentTimeMillis()
                if (gqlErrors.isNotEmpty()) {
                    Log.w("AnimeRepository", "AniList home feeds succeeded partially with GraphQL errors=[$gqlErrorSummary]")
                    Result.failure(IllegalStateException("AniList home feeds partially failed: $gqlErrorSummary"))
                } else {
                    Result.success(Unit)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                metricsTracker.recordApiRequest(false)
                MonotonicMetrics.recordDuration("anilist_request_ms", reqStarted, "op=home_feeds error=${e::class.simpleName}")
                Log.w("AnimeRepository", "AniList home feeds exception (${e::class.simpleName}); preserving cached catalog.")
                Result.failure(e)
            }
        }

    private suspend fun postAniListDiscoveryWithRetry(
        request: AniListGraphQLRequest,
        maxRetries: Int = 2
    ): Response<AniListGraphQLResponse> {
        val backoffsMs = longArrayOf(100L, 250L)
        var lastError: Throwable? = null
        for (attempt in 0..maxRetries) {
            try {
                val response = withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) {
                    aniListApi.postQuery(request)
                }
                metricsTracker.recordApiRequest(response.isSuccessful)
                val code = response.code()
                val retryableHttp = code == 429 || code in 500..599
                if (!retryableHttp || attempt >= maxRetries) {
                    return response
                }
                val retryAfterSec = response.headers()["Retry-After"]?.trim()?.toLongOrNull()
                val retryDelayMs = retryAfterSec
                    ?.let { (it * 1000L).coerceIn(50L, 1_500L) }
                    ?: backoffsMs.getOrElse(attempt) { 250L }
                Log.w("AnimeRepository", "AniList home feeds HTTP $code on attempt=${attempt + 1}; retrying in ${retryDelayMs}ms")
                delay(retryDelayMs)
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) {
                    throw cancelled
                }
                metricsTracker.recordApiRequest(false)
                lastError = cancelled
                if (attempt >= maxRetries) throw cancelled
                delay(backoffsMs.getOrElse(attempt) { 250L })
            } catch (e: IOException) {
                metricsTracker.recordApiRequest(false)
                lastError = e
                if (attempt >= maxRetries) throw e
                delay(backoffsMs.getOrElse(attempt) { 250L })
            }
        }
        throw lastError ?: SocketTimeoutException("AniList home feeds failed after retries")
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
                        v3Gateway.searchCompat(query = trimmedQuery)
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
        val mediaFormat = media.format?.trim()?.uppercase()
        val mediaYear = media.seasonYear ?: media.startDate?.year
        val mediaEps = media.episodes?.takeIf { it > 0 }
            ?: media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }
        return media.relations?.edges.orEmpty()
            .firstOrNull { edge ->
                val node = edge.node ?: return@firstOrNull false
                if (!edge.relationType.equals("PREQUEL", ignoreCase = true)) return@firstOrNull false
                if (!isSeriesAnimeFormat(node.format, node.type)) return@firstOrNull false
                val nodeFormat = node.format?.trim()?.uppercase()
                // An ONA/short spinoff prequel (e.g. MONSTERS 2024 for One Piece 1999) must not hijack a TV series.
                if (nodeFormat == "ONA" && (mediaFormat == "TV" || mediaFormat == "TV_SHORT")) return@firstOrNull false
                val nodeEps = nodeEpisodeCount(node)
                if (nodeEps == 1 && (mediaEps == null || mediaEps > 1)) return@firstOrNull false
                val nodeYear = node.seasonYear ?: node.startDate?.year
                if (mediaYear != null && nodeYear != null && nodeYear > mediaYear) return@firstOrNull false
                true
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
                    val localEps = episodeDao.getEpisodesForAnime(animeId)
                    if (isMovieLocal && localEps.isNotEmpty()) {
                        metricsTracker.recordRoomHit()
                        memoryCache.putAnime(entry)
                        return@withContext Result.success(
                            EpisodeCatalogRefreshStatus(
                                state = EpisodeCatalogState.NOT_APPLICABLE,
                                detailsEndpointSucceeded = true,
                                episodeListEndpointSucceeded = true,
                                currentRefreshEpisodeIds = localEps.map { it.id }.toSet()
                            )
                        )
                    }
                    val localSeasons = memoryCache.getSeasons(animeId) ?: episodeDao.getSeasonsForAnime(animeId)
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
                        v3Gateway.getDetailsCompat(backendId)
                    }
                }.getOrNull()
                if (response?.code() in listOf(404, 410) && animeId.startsWith("anilist_")) {
                    invalidateStaleBackendMapping(animeId, entry, backendId)
                    val retryBackendId = resolveBackendIdForDiscovery(animeId, entry, excludeBackendIds = setOf(backendId))
                    if (retryBackendId != null && retryBackendId != backendId) {
                        backendId = retryBackendId
                        response = runCatching {
                            withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) {
                                v3Gateway.getDetailsCompat(backendId)
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

                if (response != null && response.isSuccessful && response.body() != null &&
                    !ContentIdentityHelper.isMovie(entry) && animeId.startsWith("anilist_")
                ) {
                    val candidateBody = response.body()!!
                    val hasAnyEpisodes = (candidateBody.episodeCount ?: 0) > 0 ||
                        candidateBody.seasons.orEmpty().any { !it.episodes.isNullOrEmpty() }
                    if (!hasAnyEpisodes) {
                        val altBackendId = resolveBackendIdForDiscovery(
                            animeId,
                            entry,
                            excludeBackendIds = setOf(backendId)
                        )
                        if (altBackendId != null && altBackendId != backendId) {
                            val altResp = runCatching {
                                withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) {
                                    v3Gateway.getDetailsCompat(altBackendId)
                                }
                            }.getOrNull()
                            val altBody = altResp?.takeIf { it.isSuccessful }?.body()
                            val altHasEpisodes = altBody != null && (
                                (altBody.episodeCount ?: 0) > 0 ||
                                    altBody.seasons.orEmpty().any { !it.episodes.isNullOrEmpty() }
                                )
                            if (altHasEpisodes) {
                                backendId = altBackendId
                                response = altResp
                            }
                        }
                    }
                }

                val details = response.body()!!

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

                val episodeProviders = rawDtoEpisodes.map { ep ->
                    ep to providerPolicy.identifyProviderFromEpisode(ep)
                }
                val hasExplicit4khdEpisodes = episodeProviders.any {
                    it.second == ProviderPriorityPolicy.PROVIDER_4KHDHUB
                }
                val hasExplicitNon4khdEpisodes = episodeProviders.any {
                    it.second != ProviderPriorityPolicy.PROVIDER_UNKNOWN &&
                        it.second != ProviderPriorityPolicy.PROVIDER_4KHDHUB
                }
                val existingSeasonsBeforeSync = if (!isMovie) episodeDao.getSeasonsForAnime(animeId) else emptyList()
                val existingEpisodesBeforeSync = if (!isMovie) episodeDao.getEpisodesForAnime(animeId) else emptyList()
                val existingIs4khdConfirmed = !isMovie && (
                    animeId in canonical4khdConfirmedAnimeIds ||
                        existingEpisodesBeforeSync.any { ep ->
                            ProviderPriorityPolicy.identifyProviderFromPrefixOrMarker(ep.id) == ProviderPriorityPolicy.PROVIDER_4KHDHUB ||
                                ProviderPriorityPolicy.identifyProviderFromToken(ep.id) == ProviderPriorityPolicy.PROVIDER_4KHDHUB
                        }
                    )
                val shouldReconcileMultiProvider = !isMovie && rawDtoEpisodes.isNotEmpty() && (
                    (hasExplicit4khdEpisodes && hasExplicitNon4khdEpisodes) ||
                        (!hasExplicit4khdEpisodes && existingIs4khdConfirmed && hasExplicitNon4khdEpisodes)
                    )
                if (hasExplicit4khdEpisodes && !isMovie) {
                    canonical4khdConfirmedAnimeIds += animeId
                }

                var canonicalReconcileResult: CanonicalProviderCatalogResult? = null
                val mappedSeasons: List<SeasonEntry>
                val mappedEpisodes: List<EpisodeEntry>
                val finalSeasons: List<SeasonEntry>

                if (isMovie) {
                    mappedSeasons = emptyList()
                    val movieEp = rawDtoEpisodes.firstOrNull { ep ->
                        ep.id.isNotBlank() && ((ep.season ?: 1) == 1 && ep.number == 1)
                    } ?: rawDtoEpisodes.firstOrNull { ep -> ep.id.isNotBlank() }
                    mappedEpisodes = if (movieEp != null) {
                        val scopeKey = ContentIdentityHelper.scopeKey(
                            contentKey = animeId,
                            isMovie = true,
                            seasonNumber = 0,
                            episodeNumber = 1
                        )
                        discoveredBackendEpisodeIds[scopeKey] = movieEp.id
                        listOf(
                            EpisodeEntry(
                                id = movieEp.id,
                                animeId = animeId,
                                seasonNumber = 0,
                                episodeNumber = 1,
                                title = movieEp.title?.takeIf { it.isNotBlank() } ?: preferredTitle,
                                overview = movieEp.overview?.takeIf { it.isNotBlank() },
                                isAvailable = true,
                                sourceEpisodeIds = buildEpisodeSourceIdsForDto(movieEp)
                            )
                        )
                    } else {
                        emptyList()
                    }
                    finalSeasons = emptyList()
                } else if (shouldReconcileMultiProvider) {
                    val providerPayloads = buildList {
                        if (hasExplicit4khdEpisodes) {
                            val primaryEps = episodeProviders
                                .filter { it.second == ProviderPriorityPolicy.PROVIDER_4KHDHUB }
                                .map { it.first }
                            val primarySeasonNums = primaryEps.mapNotNull { it.season?.takeIf { s -> s > 0 } }.toSet()
                            val primarySeasons = seasonDtos.filter {
                                primarySeasonNums.isEmpty() || it.number in primarySeasonNums
                            }
                            add(
                                ProviderCatalogPayload(
                                    providerId = ProviderPriorityPolicy.PROVIDER_4KHDHUB,
                                    animeIdentity = details.canonicalId ?: entry?.canonicalAnimeId?.toString() ?: preferredTitle,
                                    animeTitle = preferredTitle,
                                    seasons = primarySeasons,
                                    episodes = primaryEps,
                                    isAvailable = primaryEps.isNotEmpty(),
                                    cumulativeEpisodeOffset = 0
                                )
                            )
                        }
                        val fallbackGroups = episodeProviders
                            .filter { it.second != ProviderPriorityPolicy.PROVIDER_4KHDHUB }
                            .groupBy { (_, prov) ->
                                if (prov == ProviderPriorityPolicy.PROVIDER_UNKNOWN) ProviderPriorityPolicy.PROVIDER_DRAMACHI else prov
                            }
                        fallbackGroups.forEach { (prov, pairs) ->
                            val eps = pairs.map { it.first }
                            val sNums = eps.mapNotNull { it.season?.takeIf { s -> s > 0 } }.toSet()
                            add(
                                ProviderCatalogPayload(
                                    providerId = prov,
                                    animeIdentity = details.canonicalId ?: entry?.canonicalAnimeId?.toString() ?: preferredTitle,
                                    animeTitle = preferredTitle,
                                    seasons = seasonDtos.filter { sNums.isEmpty() || it.number in sNums },
                                    episodes = eps,
                                    isAvailable = eps.isNotEmpty(),
                                    cumulativeEpisodeOffset = entry?.cumulativeEpisodeOffset ?: 0
                                )
                            )
                        }
                    }
                    val reconciled = CanonicalEpisodeCatalogBuilder.build(
                        animeId = animeId,
                        canonicalAnimeIdentity = details.canonicalId ?: entry?.canonicalAnimeId?.toString() ?: preferredTitle,
                        payloads = providerPayloads,
                        existingCanonicalSeasons = existingSeasonsBeforeSync,
                        existingCanonicalEpisodes = existingEpisodesBeforeSync,
                        targetCatalogSeasonOverride = targetCatalogSeasonOverride,
                        policy = providerPolicy
                    )
                    canonicalReconcileResult = reconciled
                    if (reconciled.primaryAuthorityAvailable) {
                        canonical4khdConfirmedAnimeIds += animeId
                    }
                    mappedSeasons = reconciled.seasons
                    finalSeasons = reconciled.seasons
                    mappedEpisodes = reconciled.episodes
                } else {
                    mappedSeasons = seasonDtos.map { seasonDto ->
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
                                v3Gateway.getEpisodesFlatCompat(resolvedBackendId)
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
                    mappedEpisodes = rawMapped.distinctBy { it.id }

                    val missingSeasonNums = mappedEpisodes.map { it.seasonNumber }.distinct() -
                        mappedSeasons.map { it.seasonNumber }.toSet()
                    finalSeasons = (mappedSeasons + missingSeasonNums.map { sNum ->
                        SeasonEntry(
                            id = "${animeId}_s$sNum",
                            animeId = animeId,
                            seasonNumber = sNum,
                            backendSeasonId = null,
                            title = "Season $sNum"
                        )
                    }).sortedBy { it.seasonNumber }
                }

                val preservedPriorCatalog = canonicalReconcileResult?.preservedPriorCatalog == true
                val declaredTotalCount = if (preservedPriorCatalog) {
                    existingEpisodesBeforeSync.size.takeIf { it > 0 } ?: entry?.episodeCount
                } else {
                    details.episodeCount?.takeIf { it > 0 }
                        ?: mappedEpisodes.size.takeIf { it > 0 }
                        ?: entry?.episodeCount
                }
                val authoritativeComplete = !preservedPriorCatalog && (
                    isAuthoritativeEpisodeSnapshot(details.partial, details.seasons) ||
                        (details.partial == false && mappedEpisodes.isNotEmpty())
                    )
                val metadataCompleteness = when {
                    isMovie -> "NOT_APPLICABLE"
                    preservedPriorCatalog && existingEpisodesBeforeSync.isNotEmpty() -> entry?.metadataCompleteness ?: "COMPLETE"
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
                if (preservedPriorCatalog) {
                    if (mappedEpisodes.isNotEmpty()) {
                        episodeDao.mergeEpisodeSnapshot(animeId, finalSeasons, mappedEpisodes)
                    }
                } else if (authoritativeComplete || (canonicalReconcileResult?.primaryAuthorityAvailable == true && mappedEpisodes.isNotEmpty())) {
                    episodeDao.replaceEpisodeSnapshot(animeId, finalSeasons, mappedEpisodes)
                } else if (finalSeasons.isNotEmpty() || mappedEpisodes.isNotEmpty()) {
                    episodeDao.mergeEpisodeSnapshot(animeId, finalSeasons, mappedEpisodes)
                }

                // Record verified per-provider backendEpisodeId bindings for sequential tier resolution
                val contentKey = ContentIdentityHelper.canonicalContentKey(animeId, updatedEntry)
                val reconcileSnapshot = canonicalReconcileResult
                if (reconcileSnapshot != null) {
                    reconcileSnapshot.bindingsByCanonicalId.values.forEach { perProv ->
                        val sample = perProv.values.firstOrNull() ?: return@forEach
                        val epScopeKey = ContentIdentityHelper.scopeKey(
                            contentKey = contentKey,
                            isMovie = false,
                            seasonNumber = sample.seasonNumber,
                            episodeNumber = sample.episodeNumber
                        )
                        perProv.forEach { (prov, binding) ->
                            providerBackendEpisodeIds["$epScopeKey|prov:$prov"] = binding.backendEpisodeId
                        }
                    }
                } else {
                    episodeProviders.forEach { (dto, prov) ->
                        val dtoId = dto.id.trim()
                        if (dtoId.isNotEmpty() && prov != ProviderPriorityPolicy.PROVIDER_UNKNOWN) {
                            val rawEpSeason = dto.season?.takeIf { it > 0 } ?: 1
                            val epSeason = if (targetCatalogSeasonOverride != null && rawEpSeason == 1) {
                                targetCatalogSeasonOverride
                            } else {
                                rawEpSeason
                            }
                            val epScopeKey = ContentIdentityHelper.scopeKey(
                                contentKey = contentKey,
                                isMovie = false,
                                seasonNumber = epSeason,
                                episodeNumber = dto.number
                            )
                            providerBackendEpisodeIds["$epScopeKey|prov:$prov"] = dtoId
                        }
                    }
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
                    snapshotComplete = authoritativeComplete || preservedPriorCatalog,
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

    /**
     * Reconciles multi-provider episode payloads into Room with 4KHDHUB as sole canonical mapping authority.
     * Fallback providers attach verified sourceEpisodeIds without altering canonical season/episode counts.
     */
    suspend fun syncMultiProviderCatalog(
        animeId: String,
        payloads: List<ProviderCatalogPayload>,
        targetCatalogSeasonOverride: Int? = null
    ): CanonicalProviderCatalogResult = withContext(Dispatchers.IO) {
        detailRefreshMutexes.computeIfAbsent(animeId) { Mutex() }.withLock {
            val entry = animeDao.getAnimeById(animeId)
            val existingSeasons = episodeDao.getSeasonsForAnime(animeId)
            val existingEpisodes = episodeDao.getEpisodesForAnime(animeId)
            val canonicalAnimeIdentity = entry?.canonicalAnimeId?.toString()
                ?: entry?.preferredTitle
                ?: animeId

            val result = CanonicalEpisodeCatalogBuilder.build(
                animeId = animeId,
                canonicalAnimeIdentity = canonicalAnimeIdentity,
                payloads = payloads,
                existingCanonicalSeasons = existingSeasons,
                existingCanonicalEpisodes = existingEpisodes,
                targetCatalogSeasonOverride = targetCatalogSeasonOverride,
                policy = providerPolicy
            )

            if (result.primaryAuthorityAvailable) {
                canonical4khdConfirmedAnimeIds += animeId
                episodeDao.replaceEpisodeSnapshot(animeId, result.seasons, result.episodes)
                if (entry != null && result.episodes.isNotEmpty() && entry.episodeCount != result.episodes.size) {
                    animeDao.upsertAnime(
                        entry.copy(
                            episodeCount = result.episodes.size,
                            metadataCompleteness = "COMPLETE",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    memoryCache.invalidateAnime(animeId)
                }
            } else if (result.preservedPriorCatalog && result.episodes.isNotEmpty()) {
                episodeDao.mergeEpisodeSnapshot(animeId, result.seasons, result.episodes)
            }
            memoryCache.invalidateEpisodes(animeId)

            val contentKey = ContentIdentityHelper.canonicalContentKey(animeId, entry)
            result.bindingsByCanonicalId.values.forEach { perProv ->
                val sample = perProv.values.firstOrNull() ?: return@forEach
                val epScopeKey = ContentIdentityHelper.scopeKey(
                    contentKey = contentKey,
                    isMovie = false,
                    seasonNumber = sample.seasonNumber,
                    episodeNumber = sample.episodeNumber
                )
                perProv.forEach { (prov, binding) ->
                    providerBackendEpisodeIds["$epScopeKey|prov:$prov"] = binding.backendEpisodeId
                }
            }
            result
        }
    }

    private fun identifyProviderForEpisodeId(scopeKey: String, epId: String): String {
        val clean = epId.trim()
        if (clean.isEmpty()) return ProviderPriorityPolicy.PROVIDER_UNKNOWN
        val prefix = "$scopeKey|prov:"
        providerBackendEpisodeIds.entries
            .firstOrNull { it.key.startsWith(prefix) && it.value == clean }
            ?.let { return it.key.removePrefix(prefix) }
        ProviderPriorityPolicy.identifyProviderFromToken(clean)?.let { return it }
        ProviderPriorityPolicy.identifyProviderFromPrefixOrMarker(clean)?.let { return it }
        ProviderPriorityPolicy.identifyProviderFromText(clean)?.let { return it }
        return ProviderPriorityPolicy.PROVIDER_UNKNOWN
    }

    private fun resolveProviderBackendEpisodeId(
        scopeKey: String,
        candidateProvider: String,
        preferredBackendEpisodeId: String?,
        knownBackendEpisodeIds: Set<String>
    ): String? {
        val cleanPreferred = preferredBackendEpisodeId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanPreferred != null) return cleanPreferred
        if (candidateProvider != ProviderPriorityPolicy.PROVIDER_UNKNOWN) {
            providerBackendEpisodeIds["$scopeKey|prov:$candidateProvider"]
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            knownBackendEpisodeIds.firstOrNull { epId ->
                identifyProviderForEpisodeId(scopeKey, epId) == candidateProvider
            }?.let { return it }
            // Never reuse a different known provider's backendEpisodeId as this provider's ID
            return knownBackendEpisodeIds.firstOrNull { epId ->
                identifyProviderForEpisodeId(scopeKey, epId) == ProviderPriorityPolicy.PROVIDER_UNKNOWN
            }
        }
        return knownBackendEpisodeIds.firstOrNull { it.isNotBlank() }
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
    // 2. ROOM METADATA + BACKEND STREAMING DISCOVERY (Stream URLs strictly in Cloudflare D1)
    // ============================================================================================

    /**
     * Ensures provider + source metadata discovery exists in Memory for the given Anime Episode
     * or Movie. Reuses bounded LRU Memory cache first and calls Backend discovery when needed.
     */
    suspend fun discoverStreamsForScope(
        animeId: String,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        catalogEpisodeId: String? = null,
        forceRefresh: Boolean = false,
        allowBypassBackoff: Boolean = false
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
        val flightKey = "$scopeKey|fresh=$forceRefresh|fg=$allowBypassBackoff"
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
                        forceRefresh = forceRefresh,
                        allowBypassBackoff = allowBypassBackoff
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
        forceRefresh: Boolean,
        allowBypassBackoff: Boolean
    ): Result<EpisodeSourceSnapshot> {
        PlaybackDiagnosticsLogger.log(
            event = PlaybackDiagnosticEvent.SOURCE_DISCOVERY_STARTED,
            animeId = animeId,
            episodeId = catalogEpisodeId ?: scopeKey,
            detail = "forceRefresh=$forceRefresh foreground=$allowBypassBackoff"
        )
        val staleBackupOnForceRefresh = if (forceRefresh) {
            memoryCache.getStreamSnapshot(scopeKey)
                ?: readBestCachedSourceSnapshot(scopeKey, effectiveSeason, effectiveEpisode)
        } else {
            null
        }
        // Tier 0: Bounded Memory LRU cache for stream metadata snapshots
        if (forceRefresh) {
            memoryCache.invalidateStreamScope(scopeKey)
            sourceCacheMutex.withLock { memorySourcesCache.remove(scopeKey) }
        } else {
            val memSnap = memoryCache.getStreamSnapshot(scopeKey)
                ?: readBestCachedSourceSnapshot(scopeKey, effectiveSeason, effectiveEpisode)
            if (memSnap != null && !memSnap.isStale && memSnap.sources.isNotEmpty()) {
                MonotonicMetrics.recordDuration("source_discovery_ms", discoveryStarted, "tier=MEMORY count=${memSnap.sources.size}")
                PlaybackDiagnosticsLogger.log(
                    event = PlaybackDiagnosticEvent.SOURCE_DISCOVERY_COMPLETED,
                    animeId = animeId,
                    episodeId = catalogEpisodeId ?: scopeKey,
                    detail = "tier=MEMORY count=${memSnap.sources.size}"
                )
                return Result.success(memSnap)
            }
        }

        // Tier 1: Backend Provider/Source discovery
        val cacheKey = scopeKey
        if (forceRefresh || allowBypassBackoff) {
            clearSourceFailure(cacheKey)
        }
        val retryRemaining = sourceRetryRemainingMs(cacheKey)
        if (EpisodeSourceRetryBackoff.shouldDelay(forceRefresh || allowBypassBackoff, retryRemaining)) {
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
                "No streaming provider mapping was found for this title.",
                staleBackupOnForceRefresh
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
                "Streaming episode record was not found on provider backend.",
                staleBackupOnForceRefresh
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
                                    v3Gateway.getEpisodeSourcesCompat(backendEpId)
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

        suspend fun fetchTierIdsSequential(
            targetBackendId: String,
            epIds: List<String>,
            expectedSeason: Int,
            allowedNumbers: Set<Int>
        ) {
            val groupedByRank = epIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .groupBy { epId ->
                    providerPolicy.tierRank(identifyProviderForEpisodeId(scopeKey, epId))
                }
                .toSortedMap()

            if (groupedByRank.isEmpty()) {
                unqueriedFallbackTiers.remove(scopeKey)
                return
            }

            val rankEntries = groupedByRank.entries.toList()
            unqueriedFallbackTiers.remove(scopeKey)
            for ((index, entryRank) in rankEntries.withIndex()) {
                val tierEpIds = entryRank.value
                fetchCandidatesFromBackend(targetBackendId, tierEpIds, expectedSeason, allowedNumbers)
                if (aggregatedCandidates.any { it.second.hasUsableSourceId }) {
                    val remaining = rankEntries.drop(index + 1).map { (remRank, remIds) ->
                        PendingProviderTier(
                            tierRank = remRank,
                            backendId = targetBackendId,
                            episodeIds = remIds,
                            effectiveBackendSeason = expectedSeason,
                            allowedEpisodeNumbers = allowedNumbers
                        )
                    }.toMutableList()
                    if (remaining.isNotEmpty()) {
                        unqueriedFallbackTiers[scopeKey] = remaining
                    } else {
                        unqueriedFallbackTiers.remove(scopeKey)
                    }
                    // Higher-priority provider returned usable sources: stop without querying lower tiers
                    break
                }
            }
        }

        fetchTierIdsSequential(backendId, backendEpisodeIds, effectiveBackendSeason, allowedEpisodeNumbers)

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
                    fetchTierIdsSequential(backendId, backendEpisodeIds, effectiveBackendSeason, allowedEpisodeNumbers)
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
            // Temporary failure MUST NOT lose prior cached candidates during temporary outage
            return staleRoomOrFailure(
                scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode,
                lastErrorMsg ?: "Temporary provider outage",
                staleBackupOnForceRefresh
            )
        }

        clearSourceFailure(cacheKey)
        val now = System.currentTimeMillis()
        aggregatedCandidates.forEach { (backendEpId, dto) ->
            val providerId = ContentIdentityHelper.deterministicProviderId(dto.label)
            val normSourceId = SourceIdentityValidator.normalizeId(dto.id)
            if (backendEpId.isNotBlank() && normSourceId.isNotEmpty()) {
                discoveredBackendEpisodeIds["$scopeKey|$providerId|$normSourceId"] = backendEpId
            }
            val canonicalProv = providerPolicy.identifyProviderFromSource(dto)
            if (backendEpId.isNotBlank() && canonicalProv != ProviderPriorityPolicy.PROVIDER_UNKNOWN) {
                providerBackendEpisodeIds.putIfAbsent("$scopeKey|prov:$canonicalProv", backendEpId)
            }
        }
        val winningBackendEpId = aggregatedCandidates.firstOrNull { it.second.hasUsableSourceId }?.first
            ?: backendEpisodeIds.firstOrNull { it.isNotBlank() }
        winningBackendEpId?.let { firstBackendEpId ->
            discoveredBackendEpisodeIds[scopeKey] = firstBackendEpId
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

        val normalizedSources = dedupedDtos.sources
            .map { SourceIdentityValidator.normalizeDto(it) }
            .filter { dto ->
                val usable = dto.hasUsableSourceId
                if (!usable) {
                    PlaybackDiagnosticsLogger.log(
                        event = PlaybackDiagnosticEvent.SOURCE_CANDIDATE_REJECTED,
                        animeId = animeId,
                        episodeId = catalogEpisodeId ?: scopeKey,
                        reason = "blank_source_id",
                        detail = "provider=${dto.displayProviderName}"
                    )
                }
                usable
            }
        val effectiveErrorMessage = when {
            normalizedSources.isNotEmpty() -> dedupedDtos.errorMessage
            !dedupedDtos.errorMessage.isNullOrBlank() -> dedupedDtos.errorMessage
            !lastErrorMsg.isNullOrBlank() -> lastErrorMsg
            dedupedDtos.expiredCandidateCount > 0 ->
                "All ${dedupedDtos.expiredCandidateCount} discovered sources had expired links."
            dedupedDtos.apiCandidateCount > 0 ->
                "Backend returned ${dedupedDtos.apiCandidateCount} candidates without usable source IDs."
            else -> null
        }
        val effectiveState = when {
            normalizedSources.isEmpty() && dedupedDtos.apiCandidateCount > 0 && dedupedDtos.state == EpisodeSourceResponseState.AVAILABLE ->
                EpisodeSourceResponseState.FAILED
            else -> dedupedDtos.state
        }
        if (normalizedSources.isEmpty()) {
            PlaybackDiagnosticsLogger.log(
                event = PlaybackDiagnosticEvent.SOURCE_DISCOVERY_EMPTY,
                animeId = animeId,
                episodeId = catalogEpisodeId ?: scopeKey,
                reason = effectiveState.name,
                detail = "raw=${dedupedDtos.apiCandidateCount} expired=${dedupedDtos.expiredCandidateCount} err=${effectiveErrorMessage ?: "none"}"
            )
        } else {
            PlaybackDiagnosticsLogger.log(
                event = PlaybackDiagnosticEvent.SOURCE_DISCOVERY_COMPLETED,
                animeId = animeId,
                episodeId = catalogEpisodeId ?: scopeKey,
                detail = "tier=BACKEND count=${normalizedSources.size} state=${effectiveState.name}"
            )
        }
        val resultSnapshot = EpisodeSourceSnapshot(
            sources = normalizedSources,
            state = effectiveState,
            backendStatus = dedupedDtos.backendStatus,
            errorMessage = effectiveErrorMessage,
            fetchedAtMs = now,
            seasonNumber = if (isMovie) null else effectiveSeason,
            episodeNumber = if (isMovie) null else effectiveEpisode,
            apiCandidateCount = dedupedDtos.apiCandidateCount,
            parsedCandidateCount = dedupedDtos.parsedCandidateCount,
            expiredCandidateCount = dedupedDtos.expiredCandidateCount,
            exactDuplicateCount = dedupedDtos.exactDuplicateCount,
            selectedEpisode = updatedSelectedEpisode
        )
        storeSourceSnapshot(
            cacheKey = scopeKey,
            animeId = animeId,
            episodeId = catalogEpisodeId ?: scopeKey,
            snapshot = resultSnapshot
        )
        if (resultSnapshot.sources.isNotEmpty()) {
            memoryCache.putStreamSnapshot(scopeKey, resultSnapshot)
        }
        MonotonicMetrics.recordDuration("source_discovery_ms", discoveryStarted, "tier=BACKEND count=${normalizedSources.size}")
        return Result.success(resultSnapshot)
    }

    /**
     * Resolves an explicit backend catalog ID ONLY during streaming discovery/playback
     * when Room doesn't already have one. Never called for Home or Discovery.
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
                                v3Gateway.searchCompat(query = q)
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
        val scopeKey = ContentIdentityHelper.scopeKey(
            contentKey = ContentIdentityHelper.canonicalContentKey(animeId, anime),
            isMovie = isMovie,
            seasonNumber = seasonNumber,
            episodeNumber = if (isMovie) 1 else episodeNumber
        )
        val knownProviderEpIds = providerBackendEpisodeIds.entries
            .filter { it.key.startsWith("$scopeKey|prov:") && it.value.isNotBlank() }
            .sortedBy { providerPolicy.tierRank(it.key.removePrefix("$scopeKey|prov:")) }
            .map { it.value }
        fun prioritizeBackendIds(ids: List<String>): List<String> =
            ids.filter { it.isNotBlank() }
                .distinct()
                .sortedBy { id -> providerPolicy.tierRank(identifyProviderForEpisodeId(scopeKey, id)) }

        if (!isMovie && allowCachedIds) {
            discoveredBackendEpisodeIds[scopeKey]?.takeIf { it.isNotBlank() }?.let { cachedId ->
                val ordered = prioritizeBackendIds(listOf(cachedId) + knownProviderEpIds)
                return BackendEpisodeResolution(ordered, defaultAllowedNumbers, seasonNumber)
            }
        }
        if (!isMovie && !bypassCatalogEpisodeIds) {
            catalogEpisodeId?.let { id ->
                episodeDao.getEpisodeById(id)?.let { ep ->
                    val decoded = EpisodeSourceEpisodeIds.decode(ep.sourceEpisodeIds)
                        .filter { it.isNotBlank() && !it.startsWith("anilist_ep_") && !it.startsWith("movie_") }
                    val combined = prioritizeBackendIds(decoded + knownProviderEpIds)
                    if (combined.isNotEmpty()) {
                        return BackendEpisodeResolution(
                            combined,
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
            if (knownProviderEpIds.isNotEmpty()) {
                return BackendEpisodeResolution(
                    prioritizeBackendIds(knownProviderEpIds),
                    defaultAllowedNumbers,
                    seasonNumber
                )
            }
        } else if (isMovie && allowCachedIds) {
            discoveredBackendEpisodeIds[scopeKey]?.takeIf { it.isNotBlank() }?.let { movieBackendEpId ->
                return BackendEpisodeResolution(prioritizeBackendIds(listOf(movieBackendEpId) + knownProviderEpIds), setOf(1), 1)
            }
        }

        val detailResp = runCatching {
            withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) { v3Gateway.getDetailsCompat(backendId) }
        }.getOrNull()

        if (detailResp?.code() in listOf(404, 410)) {
            Log.w("AnimeRepository", "Cached backendId=$backendId returned HTTP ${detailResp?.code()}; clearing stale mapping.")
            invalidateStaleBackendMapping(animeId, anime, backendId)
            return BackendEpisodeResolution(emptyList(), defaultAllowedNumbers, seasonNumber)
        }

        val backendDetails = detailResp?.takeIf { it.isSuccessful }?.body()
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
                withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) { v3Gateway.getEpisodesFlatCompat(backendId) }
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
        preferredBackendEpisodeId: String? = null,
        knownBackendEpisodeIds: Set<String> = emptySet(),
        candidateProvider: String = ProviderPriorityPolicy.PROVIDER_UNKNOWN
    ): D1StreamKeys {
        val isMovie = identity.isMovie
        val resolvedAnimeId = anime?.backendAnimeId?.trim()?.takeIf { it.isNotEmpty() }
            ?: identity.backendAnimeId?.trim()?.takeIf { it.isNotEmpty() }
            ?: identity.anilistAnimeId?.let { aniId ->
                animeDao.getMappingForAniList(aniId)?.backendAnimeId?.trim()?.takeIf { it.isNotEmpty() }
            }
            ?: animeId.trim()

        val syntheticLocalMovieId = "movie_${identity.contentKey}"
        val syntheticLocalMovieFallback = "movie_${identity.contentKey}_e1"
        fun isSyntheticCatalogId(id: String): Boolean =
            id.startsWith("anilist_ep_") ||
                id == syntheticLocalMovieId ||
                id == syntheticLocalMovieFallback ||
                id == "movie_$animeId"

        val scopeKey = ContentIdentityHelper.scopeKey(
            contentKey = identity.contentKey,
            isMovie = isMovie,
            seasonNumber = seasonNumber,
            episodeNumber = if (isMovie) 1 else episodeNumber
        )
        val filteredKnown = knownBackendEpisodeIds.filter {
            it.isNotBlank() && !isSyntheticCatalogId(it)
        }.toSet()
        val providerMatchedId = resolveProviderBackendEpisodeId(
            scopeKey = scopeKey,
            candidateProvider = candidateProvider,
            preferredBackendEpisodeId = preferredBackendEpisodeId?.trim()?.takeIf {
                it.isNotEmpty() && !isSyntheticCatalogId(it)
            },
            knownBackendEpisodeIds = filteredKnown
        )
        val catalogFallbackId = catalogEpisodeId?.trim()?.takeIf { cleanCat ->
            cleanCat.isNotEmpty() &&
                !isSyntheticCatalogId(cleanCat) &&
                (candidateProvider == ProviderPriorityPolicy.PROVIDER_UNKNOWN ||
                    identifyProviderForEpisodeId(scopeKey, cleanCat) == candidateProvider ||
                    identifyProviderForEpisodeId(scopeKey, cleanCat) == ProviderPriorityPolicy.PROVIDER_UNKNOWN)
        }

        val resolvedEpisodeId = providerMatchedId
            ?: catalogFallbackId
            ?: if (isMovie) {
                syntheticLocalMovieFallback
            } else {
                "${identity.contentKey}_s${seasonNumber}_e${episodeNumber}"
            }

        return D1StreamKeys(animeId = resolvedAnimeId, episodeId = resolvedEpisodeId)
    }

    private suspend fun resolveD1StreamKeyCandidates(
        animeId: String,
        anime: AnimeEntry?,
        identity: com.example.data.streaming.CanonicalStreamIdentity,
        seasonNumber: Int,
        episodeNumber: Int,
        catalogEpisodeId: String?,
        preferredBackendEpisodeId: String? = null,
        knownBackendEpisodeIds: Set<String> = emptySet(),
        candidateProvider: String = ProviderPriorityPolicy.PROVIDER_UNKNOWN
    ): List<D1StreamKeys> {
        val primary = resolveD1StreamKeys(
            animeId = animeId,
            anime = anime,
            identity = identity,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            catalogEpisodeId = catalogEpisodeId,
            preferredBackendEpisodeId = preferredBackendEpisodeId,
            knownBackendEpisodeIds = knownBackendEpisodeIds,
            candidateProvider = candidateProvider
        )
        val syntheticLocalMovieId = "movie_${identity.contentKey}"
        val syntheticLocalMovieFallback = "movie_${identity.contentKey}_e1"
        fun isSyntheticCatalogId(id: String): Boolean =
            id.startsWith("anilist_ep_") ||
                id == syntheticLocalMovieId ||
                id == syntheticLocalMovieFallback ||
                id == "movie_$animeId"

        val scopeKey = ContentIdentityHelper.scopeKey(
            contentKey = identity.contentKey,
            isMovie = identity.isMovie,
            seasonNumber = seasonNumber,
            episodeNumber = if (identity.isMovie) 1 else episodeNumber
        )

        val hasResolvedBackendAnime = primary.animeId != animeId.trim() ||
            !anime?.backendAnimeId.isNullOrBlank() ||
            !identity.backendAnimeId.isNullOrBlank()
        val candidateAnimeIds = buildList {
            add(primary.animeId)
            anime?.backendAnimeId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            identity.backendAnimeId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            if (!hasResolvedBackendAnime) {
                animeId.trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }.distinct()

        val cleanPreferredEp = preferredBackendEpisodeId?.trim()?.takeIf {
            it.isNotEmpty() && !isSyntheticCatalogId(it)
        }
        val matchingKnown = knownBackendEpisodeIds.mapNotNull { epId ->
            val clean = epId.trim()
            if (clean.isEmpty() || isSyntheticCatalogId(clean)) {
                null
            } else if (candidateProvider != ProviderPriorityPolicy.PROVIDER_UNKNOWN) {
                val epProv = identifyProviderForEpisodeId(scopeKey, clean)
                clean.takeIf { epProv == candidateProvider || epProv == ProviderPriorityPolicy.PROVIDER_UNKNOWN }
            } else {
                clean
            }
        }
        val hasRealBackendEpisode = cleanPreferredEp != null || matchingKnown.isNotEmpty()
        val candidateEpisodeIds = buildList {
            add(primary.episodeId)
            if (cleanPreferredEp != null) {
                add(cleanPreferredEp)
            } else {
                addAll(matchingKnown)
                if (!hasRealBackendEpisode) {
                    catalogEpisodeId?.trim()?.takeIf { it.isNotEmpty() && !isSyntheticCatalogId(it) }?.let(::add)
                }
            }
        }.distinct().take(3)

        return buildList {
            add(primary)
            for (aId in candidateAnimeIds) {
                for (eId in candidateEpisodeIds) {
                    val item = D1StreamKeys(aId, eId)
                    if (item !in this) add(item)
                    if (size >= 3) return@buildList
                }
            }
        }
    }

    private fun inferUrlTypeFromUrl(
        url: String,
        contentType: String? = null,
        fallback: String = "direct"
    ): String {
        val inferred = com.example.data.d1.CloudflareD1DataSource.resolveEffectiveUrlType(
            explicitUrlType = fallback.takeIf { !it.equals("direct", ignoreCase = true) },
            url = url,
            contentType = contentType
        )
        return when {
            inferred != "direct" -> inferred
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
        preferredProviderId: String? = null,
        allowBypassBackoff: Boolean = false
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

        // Ensure source metadata discovery exists (Memory -> Backend)
        var snapshot = memoryCache.getStreamSnapshot(scopeKey)?.takeIf { !it.isStale && it.sources.isNotEmpty() }
            ?: readRoomScopeSnapshot(scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode)
        var discoveryError: Throwable? = null
        if (snapshot == null || snapshot.sources.isEmpty()) {
            val discoveryRes = discoverStreamsForScope(
                animeId, effectiveSeason, effectiveEpisode, catalogEpisodeId,
                forceRefresh = false,
                allowBypassBackoff = allowBypassBackoff
            )
            discoveryError = discoveryRes.exceptionOrNull()
            snapshot = discoveryRes.getOrNull()
                ?: readRoomScopeSnapshot(scopeKey, animeId, isMovie, effectiveSeason, effectiveEpisode)
        }

        val catalogEpisode = catalogEpisodeId?.let { episodeDao.getEpisodeById(it) }
            ?: if (!isMovie) {
                episodeDao.getEpisodesForAnime(animeId).firstOrNull {
                    it.seasonNumber == effectiveSeason && it.episodeNumber == effectiveEpisode
                }
            } else {
                null
            }
        val knownBackendEpisodeIds = buildSet {
            discoveredBackendEpisodeIds[scopeKey]?.takeIf { it.isNotBlank() }?.let(::add)
            catalogEpisode?.let { ep ->
                EpisodeSourceEpisodeIds.decode(ep.sourceEpisodeIds).forEach { id ->
                    val norm = SourceIdentityValidator.normalizeId(id)
                    if (norm.isNotEmpty() && !norm.startsWith("anilist_ep_") && !norm.startsWith("movie_")) {
                        add(norm)
                    }
                }
            }
        }

        fun providerIdOf(dto: ProphecySourceDto): String = ContentIdentityHelper.deterministicProviderId(dto.label)

        fun matchesPreferred(dto: ProphecySourceDto): Boolean {
            if (normPreferred != null && !SourceIdentityValidator.normalizedIdsEqual(dto.id, normPreferred)) {
                return false
            }
            if (normPreferredProvider != null && !SourceIdentityValidator.normalizedIdsEqual(providerIdOf(dto), normPreferredProvider)) {
                return false
            }
            return true
        }

        fun filterRemainingCandidates(all: List<ProphecySourceDto>, winner: ProphecySourceDto, winnerProviderId: String): List<ProphecySourceDto> {
            return listOf(winner) + all.filterNot {
                SourceIdentityValidator.normalizedIdsEqual(it.id, winner.id) &&
                    SourceIdentityValidator.normalizedIdsEqual(providerIdOf(it), winnerProviderId)
            }
        }

        val orderedSources = orderDtosForPlayback(
            snapshot?.sources.orEmpty().map { SourceIdentityValidator.normalizeDto(it) },
            normPreferred,
            normPreferredProvider
        )
        val cacheCheckPool = when {
            normPreferred != null -> {
                val matching = orderedSources.filter(::matchesPreferred)
                if (normPreferredProvider == null && matching.map(::providerIdOf).distinct().size > 1) {
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
            else -> orderedSources
        }

        val targetSources = if (normPreferred != null && !allowFallbackToOtherSources) {
            orderedSources.filter(::matchesPreferred)
        } else {
            orderedSources.filter { SourceIdentityValidator.normalizeId(it.id).isNotEmpty() }
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

        val checkedCacheKeys = HashSet<String>()

        suspend fun tryCachePoolForCandidates(pool: List<ProphecySourceDto>): ResolvedStreamPlayback? {
            if (forceResolveFresh || pool.isEmpty()) return null
            val resolvedKeysByCandidate = ArrayList<Pair<ProphecySourceDto, List<D1StreamKeys>>>(pool.size)
            for (candidate in pool) {
                val normSourceId = SourceIdentityValidator.normalizeId(candidate.id)
                if (normSourceId.isEmpty()) continue
                val candidateProviderId = providerIdOf(candidate)
                val canonicalProv = providerPolicy.identifyProviderFromSource(candidate)
                val preferredEpId = discoveredBackendEpisodeIds["$scopeKey|$candidateProviderId|$normSourceId"]
                    ?: canonicalProv.takeIf { it != ProviderPriorityPolicy.PROVIDER_UNKNOWN }
                        ?.let { providerBackendEpisodeIds["$scopeKey|prov:$it"] }
                val d1KeyCandidates = resolveD1StreamKeyCandidates(
                    animeId = animeId,
                    anime = anime,
                    identity = identity,
                    seasonNumber = effectiveSeason,
                    episodeNumber = effectiveEpisode,
                    catalogEpisodeId = catalogEpisode?.id ?: catalogEpisodeId,
                    preferredBackendEpisodeId = preferredEpId,
                    knownBackendEpisodeIds = knownBackendEpisodeIds,
                    candidateProvider = canonicalProv
                )
                val primaryEpKey = d1KeyCandidates.firstOrNull()?.episodeId
                if (SourceExpiryTime.isExpiredOrInvalid(candidate.expiresAt)) {
                    memoryCache.invalidatePlayback(normSourceId, scopeKey, candidateProviderId, primaryEpKey)
                }

                // In-memory validated playback cache
                val memHit = memoryCache.getResolvedPlayback(normSourceId, scopeKey, candidateProviderId, primaryEpKey)
                if (memHit != null && SourceIdentityValidator.validateDirectPlayData(memHit, normSourceId).isValid) {
                    val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, "tier=MEMORY")
                    return ResolvedStreamPlayback(
                        source = candidate,
                        directData = memHit,
                        candidates = filterRemainingCandidates(orderedSources, candidate, candidateProviderId),
                        fromCache = true,
                        cacheTier = "MEMORY",
                        resolveDurationMs = elapsed
                    )
                }
                resolvedKeysByCandidate += candidate to d1KeyCandidates
            }

            // Cloudflare D1 Stream URL lookup: batch single episode query when multiple candidates share primary keys
            val primaryBatchKey = resolvedKeysByCandidate.firstOrNull()?.second?.firstOrNull()
            val batchStreamsBySource: Map<String, List<com.example.data.d1.CloudflareD1DataSource.CachedStreamUrl>>? =
                if (resolvedKeysByCandidate.size > 1 && primaryBatchKey != null) {
                    val batchRes = d1DataSource.getStreamUrlsForEpisodeResult(
                        animeId = primaryBatchKey.animeId,
                        episodeId = primaryBatchKey.episodeId
                    )
                    if (batchRes.querySucceeded) {
                        batchRes.streams.groupBy { SourceIdentityValidator.normalizeId(it.sourceId) }
                    } else {
                        null
                    }
                } else {
                    null
                }

            for ((candidate, d1KeyCandidates) in resolvedKeysByCandidate) {
                val normSourceId = SourceIdentityValidator.normalizeId(candidate.id)
                val candidateProviderId = providerIdOf(candidate)
                checkedCacheKeys += "$candidateProviderId|$normSourceId"

                var d1Hit: com.example.data.d1.CloudflareD1DataSource.CachedStreamUrl? = null
                if (batchStreamsBySource != null && primaryBatchKey != null &&
                    d1KeyCandidates.firstOrNull() == primaryBatchKey
                ) {
                    val batchMatches = batchStreamsBySource[normSourceId].orEmpty()
                    d1Hit = batchMatches.firstOrNull { row ->
                        row.providerId.isNullOrBlank() ||
                            row.providerId.equals("direct", ignoreCase = true) ||
                            SourceIdentityValidator.normalizedIdsEqual(row.providerId, candidateProviderId)
                    }
                    if (d1Hit == null && d1KeyCandidates.size > 1) {
                        for (extraKeys in d1KeyCandidates.drop(1)) {
                            val found = d1DataSource.getValidStreamUrl(
                                animeId = extraKeys.animeId,
                                episodeId = extraKeys.episodeId,
                                sourceId = normSourceId,
                                allowCompositeFallback = false
                            )
                            if (found != null) {
                                d1Hit = found
                                break
                            }
                        }
                    }
                } else {
                    for (d1Keys in d1KeyCandidates) {
                        val found = d1DataSource.getValidStreamUrl(
                            animeId = d1Keys.animeId,
                            episodeId = d1Keys.episodeId,
                            sourceId = normSourceId,
                            allowCompositeFallback = false
                        )
                        if (found != null) {
                            d1Hit = found
                            break
                        }
                    }
                }

                if (d1Hit != null) {
                    val restoredHeaders = d1Hit.safeHeaders
                    val data = DirectPlayData(
                        mode = "direct",
                        sourceId = normSourceId,
                        playbackUrl = d1Hit.streamUrl,
                        urlType = inferUrlTypeFromUrl(d1Hit.streamUrl, d1Hit.contentType, d1Hit.urlType),
                        compatibility = "verified",
                        requiresHeaders = restoredHeaders.isNotEmpty(),
                        headers = restoredHeaders,
                        contentType = d1Hit.contentType,
                        expiresAt = d1Hit.expiresAt ?: candidate.expiresAt
                    )
                    if (SourceIdentityValidator.validateDirectPlayData(data, normSourceId).isValid) {
                        memoryCache.putResolvedPlayback(
                            sourceId = normSourceId,
                            data = data,
                            scopeKey = scopeKey,
                            providerId = candidateProviderId,
                            backendEpisodeId = d1Hit.episodeId
                        )
                        val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, "tier=D1")
                        return ResolvedStreamPlayback(
                            source = candidate,
                            directData = data,
                            candidates = filterRemainingCandidates(orderedSources, candidate, candidateProviderId),
                            fromCache = true,
                            cacheTier = "D1",
                            resolveDurationMs = elapsed
                        )
                    }
                }
            }
            return null
        }

        // Helper for single-source direct playback resolution with D1 check -> Backend API -> D1 save
        suspend fun resolveSingleCandidate(
            candidate: ProphecySourceDto,
            explicitEpisodeId: String? = null,
            explicitAllCandidates: List<ProphecySourceDto> = orderedSources
        ): Result<ResolvedStreamPlayback> {
            val normSourceId = SourceIdentityValidator.normalizeId(candidate.id)
            if (normSourceId.isEmpty()) {
                return Result.failure(
                    SourceResolutionDiagnosticException(
                        SourceDiagnosticCode.SOURCE_NOT_PLAYABLE,
                        "Source candidate has blank sourceId",
                        candidate.displayProviderName
                    )
                )
            }
            val candidateProviderId = providerIdOf(candidate)
            val canonicalProv = providerPolicy.identifyProviderFromSource(candidate)
            val preferredEpId = explicitEpisodeId?.takeIf { it.isNotBlank() }
                ?: discoveredBackendEpisodeIds["$scopeKey|$candidateProviderId|$normSourceId"]
                ?: canonicalProv.takeIf { it != ProviderPriorityPolicy.PROVIDER_UNKNOWN }
                    ?.let { providerBackendEpisodeIds["$scopeKey|prov:$it"] }
            val d1Keys = resolveD1StreamKeys(
                animeId = animeId,
                anime = anime,
                identity = identity,
                seasonNumber = effectiveSeason,
                episodeNumber = effectiveEpisode,
                catalogEpisodeId = catalogEpisode?.id ?: catalogEpisodeId,
                preferredBackendEpisodeId = preferredEpId,
                knownBackendEpisodeIds = knownBackendEpisodeIds,
                candidateProvider = canonicalProv
            )
            val alreadyCheckedCache = "$candidateProviderId|$normSourceId" in checkedCacheKeys
            val res = resolveDirectPlayback(
                sourceId = normSourceId,
                forceRefresh = forceResolveFresh,
                scopeKey = scopeKey,
                providerId = candidateProviderId,
                animeId = d1Keys.animeId,
                episodeId = d1Keys.episodeId,
                skipCacheCheck = !forceResolveFresh && alreadyCheckedCache,
                allowBypassBackoff = allowBypassBackoff,
                sourceMetadata = candidate
            )
            val playData = res.getOrNull()
                ?: return Result.failure(res.exceptionOrNull() ?: IllegalStateException("Direct playback failed"))

            val validateStarted = MonotonicMetrics.nowMs()
            val validation = SourceIdentityValidator.validateDirectPlayData(playData, normSourceId)
            val requestHeaders = SafePlaybackHeaders.sanitizeRequestHeaders(playData.headers)
            MonotonicMetrics.recordDuration("url_validation_ms", validateStarted, "valid=${validation.isValid}")

            return if (validation is SourceValidationResult.Valid) {
                val sanitizedData = playData.copy(
                    sourceId = SourceIdentityValidator.normalizeId(playData.sourceId),
                    headers = requestHeaders
                )
                val elapsed = MonotonicMetrics.recordDuration("playback_startup_ms", playbackResolveStarted, "tier=BACKEND_RESOLVE")
                Result.success(
                    ResolvedStreamPlayback(
                        source = candidate,
                        directData = sanitizedData,
                        candidates = filterRemainingCandidates(explicitAllCandidates, candidate, candidateProviderId),
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
                    providerLabel = candidate.displayProviderName,
                    detail = invalid.reason
                )
                Result.failure(
                    SourceResolutionDiagnosticException(invalid.code, invalid.reason, candidate.displayProviderName)
                )
            }
        }

        suspend fun resolveBatchHedged(
            candidates: List<ProphecySourceDto>,
            explicitEpisodeId: String? = null,
            explicitAllCandidates: List<ProphecySourceDto> = orderedSources
        ): Pair<ResolvedStreamPlayback?, Throwable?> {
            var tierError: Throwable? = null
            if (candidates.size <= 1) {
                candidates.firstOrNull()?.let { c ->
                    val attempt = resolveSingleCandidate(c, explicitEpisodeId, explicitAllCandidates)
                    if (attempt.isSuccess) return attempt.getOrNull() to null
                    tierError = attempt.exceptionOrNull()
                }
                return null to tierError
            }
            for ((batchIndex, batch) in candidates.chunked(3).withIndex()) {
                var batchWinner: ResolvedStreamPlayback? = null
                coroutineScope {
                    val channel = Channel<Result<ResolvedStreamPlayback>>(batch.size)
                    val jobs = mutableListOf<kotlinx.coroutines.Job>()
                    fun launchCandidate(candidate: ProphecySourceDto) {
                        jobs += launch {
                            val result = try {
                                resolveSingleCandidate(candidate, explicitEpisodeId, explicitAllCandidates)
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
                                tierError = firstResult.exceptionOrNull() ?: tierError
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
                            } else if (tierError == null || result.exceptionOrNull() !is CancellationException) {
                                tierError = result.exceptionOrNull() ?: tierError
                            }
                        }
                    }
                    jobs.forEach { it.cancel() }
                    channel.close()
                }
                if (batchWinner != null) {
                    return batchWinner to null
                }
            }
            return null to tierError
        }

        var lastError: Throwable? = null
        if (normPreferred != null) {
            tryCachePoolForCandidates(cacheCheckPool)?.let { return@withContext Result.success(it) }
            for (candidate in targetSources) {
                val attempt = resolveSingleCandidate(candidate)
                if (attempt.isSuccess) return@withContext attempt
                lastError = attempt.exceptionOrNull()
                if (!allowFallbackToOtherSources) break
            }
        } else {
            // Try each provider priority tier sequentially: 4KHDHUB -> Dramachi -> future providers.
            // Only fall back to lower-priority tiers after the higher-priority tier fails.
            val tierRanks = (cacheCheckPool + targetSources)
                .map { providerPolicy.tierRankForSource(it) }
                .distinct()
                .sorted()
            for (tierRank in tierRanks) {
                val tierCachePool = cacheCheckPool.filter { providerPolicy.tierRankForSource(it) == tierRank }
                tryCachePoolForCandidates(tierCachePool)?.let { return@withContext Result.success(it) }
                val tierSources = targetSources.filter { providerPolicy.tierRankForSource(it) == tierRank }
                if (tierSources.isNotEmpty()) {
                    val (winner, tierErr) = resolveBatchHedged(tierSources)
                    if (winner != null) return@withContext Result.success(winner)
                    if (tierErr != null) lastError = tierErr
                }
            }
        }

        // On-demand fallback to lower-priority provider tiers that were intentionally skipped
        // during initial 4KHDHUB-first discovery. Resolve using each tier's own backendEpisodeId.
        if (normPreferred == null || allowFallbackToOtherSources) {
            while (true) {
                val pendingQueue = unqueriedFallbackTiers[scopeKey] ?: break
                val nextTier = synchronized(pendingQueue) {
                    if (pendingQueue.isEmpty()) null else pendingQueue.removeAt(0)
                } ?: break
                if (pendingQueue.isEmpty()) {
                    unqueriedFallbackTiers.remove(scopeKey, pendingQueue)
                }

                for (tierEpId in nextTier.episodeIds) {
                    val epResp = runCatching {
                        withTimeout(DISCOVERY_PER_CALL_TIMEOUT_MS) {
                            v3Gateway.getEpisodeSourcesCompat(tierEpId)
                        }
                    }.getOrNull()
                    val body = epResp?.takeIf { it.isSuccessful }?.body() ?: continue
                    val validation = SourceIdentityValidator.validateEpisodeFullResponse(
                        body = body,
                        animeEntry = anime,
                        requestedBackendId = nextTier.backendId,
                        requestedBackendEpisodeId = tierEpId,
                        isMovie = isMovie,
                        requestedSeason = effectiveSeason,
                        effectiveBackendSeason = nextTier.effectiveBackendSeason,
                        allowedEpisodeNumbers = nextTier.allowedEpisodeNumbers,
                        cumulativeOffset = identity.cumulativeEpisodeOffset
                    )
                    if (validation is SourceValidationResult.Invalid) continue

                    val tierCandidates = orderDtosForPlayback(
                        body.sources.orEmpty()
                            .map { SourceIdentityValidator.normalizeDto(it) }
                            .filter { it.hasUsableSourceId && !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt) },
                        preferredSourceId = null,
                        preferredProviderId = null
                    )
                    if (tierCandidates.isEmpty()) continue

                    tierCandidates.forEach { dto ->
                        val pId = providerIdOf(dto)
                        val sId = SourceIdentityValidator.normalizeId(dto.id)
                        if (sId.isNotEmpty()) {
                            discoveredBackendEpisodeIds["$scopeKey|$pId|$sId"] = tierEpId
                        }
                    }

                    val combinedOrder = (orderedSources + tierCandidates).distinctBy { it.stableIdentity }
                    val cacheWinner = tryCachePoolForCandidates(tierCandidates)
                    if (cacheWinner != null) {
                        return@withContext Result.success(cacheWinner)
                    }
                    val (winner, tierErr) = resolveBatchHedged(
                        candidates = tierCandidates,
                        explicitEpisodeId = tierEpId,
                        explicitAllCandidates = combinedOrder
                    )
                    if (winner != null) {
                        return@withContext Result.success(winner)
                    }
                    if (tierErr != null) lastError = tierErr
                }
            }
        }

        // Failed resolve must NEVER erase a prior working URL; report error cleanly
        Result.failure(lastError ?: discoveryError ?: IllegalStateException("No playable source available right now."))
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

    private fun orderDtosForPlayback(
        sources: List<ProphecySourceDto>,
        preferredSourceId: String?,
        preferredProviderId: String? = null
    ): List<ProphecySourceDto> {
        val active = sources.filter { SourceIdentityValidator.normalizeId(it.id).isNotEmpty() }
        val normPref = preferredSourceId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        val normProv = preferredProviderId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        if (normPref != null) {
            return active.sortedWith(
                compareBy<ProphecySourceDto> {
                    val matchesSource = SourceIdentityValidator.normalizedIdsEqual(it.id, normPref)
                    val matchesProvider = normProv == null || SourceIdentityValidator.normalizedIdsEqual(
                        ContentIdentityHelper.deterministicProviderId(it.label),
                        normProv
                    )
                    if (matchesSource && matchesProvider) 0 else 1
                }
                    .thenByDescending { !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt) }
                    .thenBy { providerPolicy.tierRankForSource(it) }
                    .thenByDescending { qualityRank(it.quality) }
            )
        }
        return active.sortedWith(
            compareByDescending<ProphecySourceDto> { !SourceExpiryTime.isExpiredOrInvalid(it.expiresAt) }
                .thenBy { providerPolicy.tierRankForSource(it) }
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
        val cachedSnapshot = readBestCachedSourceSnapshot(
            cacheKey = scopeKey,
            seasonNumber = if (isMovie) null else seasonNumber,
            episodeNumber = if (isMovie) null else episodeNumber
        )
        MonotonicMetrics.recordDuration("room_read_ms", started, "scope=$scopeKey count=${cachedSnapshot?.sources?.size ?: 0}")
        if (cachedSnapshot == null) return null
        val cachedEpisode = if (isMovie) {
            null
        } else {
            episodeDao.getEpisodesForAnime(animeId).firstOrNull {
                it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber
            }
        }
        return cachedSnapshot.copy(selectedEpisode = cachedEpisode ?: cachedSnapshot.selectedEpisode)
    }

    private suspend fun staleRoomOrFailure(
        scopeKey: String,
        animeId: String,
        isMovie: Boolean,
        seasonNumber: Int,
        episodeNumber: Int,
        message: String,
        backupSnapshot: EpisodeSourceSnapshot? = null
    ): Result<EpisodeSourceSnapshot> {
        val existing = readRoomScopeSnapshot(scopeKey, animeId, isMovie, seasonNumber, episodeNumber)
            ?: backupSnapshot
        if (existing != null && existing.sources.isNotEmpty()) {
            val stale = existing.copy(state = EpisodeSourceResponseState.STALE, errorMessage = message)
            sourceCacheMutex.withLock { memorySourcesCache[scopeKey] = SourceCacheEntry(stale) }
            return Result.success(stale)
        }
        return Result.failure(IllegalStateException(message))
    }

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
                val response = v3Gateway.getEpisodeSourcesCompat(episodeId)
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
        skipCacheCheck: Boolean = false,
        allowBypassBackoff: Boolean = false,
        sourceMetadata: ProphecySourceDto? = null
    ): Result<DirectPlayData> = withContext(Dispatchers.IO) {
        val normSourceId = SourceIdentityValidator.normalizeId(sourceId)
        val normScope = scopeKey?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        val normProvider = providerId?.let { SourceIdentityValidator.normalizeId(it) }?.takeIf { it.isNotEmpty() }
        val normAnime = animeId.trim()
        val normEpisode = episodeId.trim()
        if (normSourceId.isEmpty()) return@withContext Result.failure(
            SourceResolutionDiagnosticException(SourceDiagnosticCode.SOURCE_NOT_PLAYABLE, "Source ID is blank")
        )

        val flightKey = "${normScope.orEmpty()}::${normProvider.orEmpty()}::${normAnime}::${normEpisode}::$normSourceId::fresh=$forceRefresh::fg=$allowBypassBackoff"
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
                        skipCacheCheck = skipCacheCheck,
                        allowBypassBackoff = allowBypassBackoff,
                        sourceMetadata = sourceMetadata
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
        skipCacheCheck: Boolean = false,
        allowBypassBackoff: Boolean = false,
        sourceMetadata: ProphecySourceDto? = null
    ): Result<DirectPlayData> {
        val scopeContentKey = normScope?.substringAfter(':', "")?.substringBefore(":s")
        val resolvedAnimeId = explicitAnimeId.takeIf { it.isNotBlank() }
            ?: scopeContentKey?.toIntOrNull()?.let { animeDao.getMappingForAniList(it)?.backendAnimeId }
            ?: scopeContentKey.orEmpty()
        val scopeBackendEpId = normScope?.let { sc ->
            (normProvider?.let { prov -> discoveredBackendEpisodeIds["$sc|$prov|$normSourceId"] })
                ?: discoveredBackendEpisodeIds[sc]
        }
        val resolvedEpisodeId = explicitEpisodeId.takeIf { it.isNotBlank() }
            ?: scopeBackendEpId?.takeIf { it.isNotBlank() }
            ?: normScope?.let { sc ->
                if (sc.startsWith("movie:") || sc.startsWith("movie_")) "movie_${scopeContentKey.orEmpty()}_e1"
                else if (sc.contains(":s") && sc.contains(":e")) "${scopeContentKey.orEmpty()}_s${sc.substringAfter(":s").substringBefore(":e")}_e${sc.substringAfter(":e")}"
                else null
            }.orEmpty()

        PlaybackDiagnosticsLogger.log(
            event = PlaybackDiagnosticEvent.PLAYBACK_RESOLUTION_STARTED,
            animeId = resolvedAnimeId.takeIf { it.isNotBlank() },
            episodeId = resolvedEpisodeId.takeIf { it.isNotBlank() },
            sourceId = normSourceId,
            detail = "forceRefresh=$forceRefresh foreground=$allowBypassBackoff"
        )

        val memEpisodeKey = resolvedEpisodeId.takeIf { it.isNotBlank() }
        if (forceRefresh) {
            memoryCache.invalidatePlayback(normSourceId, normScope, normProvider, memEpisodeKey)
        } else if (!skipCacheCheck) {
            memoryCache.getResolvedPlayback(normSourceId, normScope, normProvider, memEpisodeKey)?.let { cachedMem ->
                if (SourceIdentityValidator.validateDirectPlayData(cachedMem, normSourceId).isValid) {
                    PlaybackDiagnosticsLogger.log(
                        event = PlaybackDiagnosticEvent.PLAYBACK_RESOLUTION_SUCCEEDED,
                        animeId = resolvedAnimeId.takeIf { it.isNotBlank() },
                        episodeId = resolvedEpisodeId.takeIf { it.isNotBlank() },
                        sourceId = normSourceId,
                        detail = "tier=MEMORY"
                    )
                    return Result.success(cachedMem)
                }
                memoryCache.invalidatePlayback(normSourceId, normScope, normProvider, memEpisodeKey)
            }

            // Always check Cloudflare D1 before calling Backend POST /api/v1/play/direct
            if (resolvedAnimeId.isNotBlank() && resolvedEpisodeId.isNotBlank()) {
                val d1Hit = d1DataSource.getValidStreamUrl(
                    animeId = resolvedAnimeId,
                    episodeId = resolvedEpisodeId,
                    sourceId = normSourceId,
                    allowCompositeFallback = false
                )
                if (d1Hit != null) {
                    val restoredHeaders = d1Hit.safeHeaders
                    val d1PlayData = DirectPlayData(
                        mode = "direct",
                        sourceId = normSourceId,
                        playbackUrl = d1Hit.streamUrl,
                        urlType = inferUrlTypeFromUrl(d1Hit.streamUrl, d1Hit.contentType, d1Hit.urlType),
                        compatibility = "verified",
                        requiresHeaders = restoredHeaders.isNotEmpty(),
                        headers = restoredHeaders,
                        contentType = d1Hit.contentType,
                        expiresAt = d1Hit.expiresAt
                    )
                    if (SourceIdentityValidator.validateDirectPlayData(d1PlayData, normSourceId).isValid) {
                        memoryCache.putResolvedPlayback(normSourceId, d1PlayData, normScope, normProvider, memEpisodeKey)
                        PlaybackDiagnosticsLogger.log(
                            event = PlaybackDiagnosticEvent.PLAYBACK_RESOLUTION_SUCCEEDED,
                            animeId = resolvedAnimeId.takeIf { it.isNotBlank() },
                            episodeId = resolvedEpisodeId.takeIf { it.isNotBlank() },
                            sourceId = normSourceId,
                            detail = "tier=D1"
                        )
                        return Result.success(d1PlayData)
                    }
                }
            }
        }

        if (forceRefresh || allowBypassBackoff) {
            directRetryMutex.withLock { directRetryStates.remove(normSourceId) }
        }
        val retryRemaining = directRetryMutex.withLock {
            ((directRetryStates[normSourceId]?.retryAtMs ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        if (!forceRefresh && !allowBypassBackoff && retryRemaining > 0L) {
            PlaybackDiagnosticsLogger.log(
                event = PlaybackDiagnosticEvent.PLAYBACK_RESOLUTION_FAILED,
                animeId = resolvedAnimeId.takeIf { it.isNotBlank() },
                episodeId = resolvedEpisodeId.takeIf { it.isNotBlank() },
                sourceId = normSourceId,
                reason = "backed_off",
                detail = "remaining_ms=$retryRemaining"
            )
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
                v3Gateway.resolveEpisodeCompat(
                    episodeId = resolvedEpisodeId,
                    sourceId = normSourceId
                )
            }
            val resolveElapsed = (MonotonicMetrics.nowMs() - resolutionStartedAt).coerceAtLeast(0L)
            metricsTracker.recordApiRequest(response.isSuccessful)
            metricsTracker.recordResolverInvocation(resolveElapsed)
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
                PlaybackDiagnosticsLogger.log(
                    event = PlaybackDiagnosticEvent.PLAYBACK_RESOLUTION_FAILED,
                    animeId = resolvedAnimeId.takeIf { it.isNotBlank() },
                    episodeId = resolvedEpisodeId.takeIf { it.isNotBlank() },
                    sourceId = normSourceId,
                    reason = "http_${response.code()}"
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
                PlaybackDiagnosticsLogger.log(
                    event = PlaybackDiagnosticEvent.PLAYBACK_RESOLUTION_FAILED,
                    animeId = resolvedAnimeId.takeIf { it.isNotBlank() },
                    episodeId = resolvedEpisodeId.takeIf { it.isNotBlank() },
                    sourceId = normSourceId,
                    reason = "unverified_or_empty"
                )
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
                    PlaybackDiagnosticsLogger.log(
                        event = PlaybackDiagnosticEvent.PLAYBACK_RESOLUTION_FAILED,
                        animeId = resolvedAnimeId.takeIf { it.isNotBlank() },
                        episodeId = resolvedEpisodeId.takeIf { it.isNotBlank() },
                        sourceId = normSourceId,
                        reason = validation.code.name
                    )
                    return Result.failure(
                        SourceResolutionDiagnosticException(validation.code, validation.reason)
                    )
                }
                SourceValidationResult.Valid -> Unit
            }

            // Persist validated resolved Stream URL to Cloudflare D1 before returning to player (safe headers + content metadata + explicit/derived expiry)
            if (resolvedAnimeId.isNotBlank() && resolvedEpisodeId.isNotBlank()) {
                val validKnownExpiry = requestReady.expiresAt?.trim()?.takeIf {
                    SourceExpiryTime.evaluateExpiry(it) == ExpiryState.VALID
                } ?: SourceExpiryTime.deriveIsoExpiryFromUrl(requestReady.playbackUrl)
                val saved = try {
                    kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                        d1DataSource.saveStreamUrl(
                            animeId = resolvedAnimeId,
                            episodeId = resolvedEpisodeId,
                            sourceId = normSourceId,
                            streamUrl = requestReady.playbackUrl,
                            expiresAt = validKnownExpiry,
                            providerId = normProvider,
                            sourceName = sourceMetadata?.label?.trim()?.takeIf { it.isNotEmpty() },
                            urlType = requestReady.urlType,
                            contentType = requestReady.contentType,
                            headers = requestReady.headers,
                            quality = sourceMetadata?.quality,
                            codec = sourceMetadata?.codec,
                            audioLanguage = sourceMetadata?.language,
                            downloadable = sourceMetadata?.downloadable,
                            sizeBytes = sourceMetadata?.sizeBytes
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Throwable) {
                    Log.e(
                        "AnimeRepository",
                        "D1 persistence error: anime=$resolvedAnimeId, " +
                            "episode=$resolvedEpisodeId, source=$normSourceId (${err::class.simpleName})"
                    )
                    false
                }
                if (saved != true) {
                    Log.e(
                        "AnimeRepository",
                        "D1 persistence failed: anime=$resolvedAnimeId, " +
                            "episode=$resolvedEpisodeId, source=$normSourceId"
                    )
                }
            }

            memoryCache.putResolvedPlayback(normSourceId, requestReady, normScope, normProvider, memEpisodeKey)
            directRetryMutex.withLock { directRetryStates.remove(normSourceId) }
            PlaybackDiagnosticsLogger.log(
                event = PlaybackDiagnosticEvent.PLAYBACK_RESOLUTION_SUCCEEDED,
                animeId = resolvedAnimeId.takeIf { it.isNotBlank() },
                episodeId = resolvedEpisodeId.takeIf { it.isNotBlank() },
                sourceId = normSourceId,
                detail = "tier=BACKEND"
            )
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
            PlaybackDiagnosticsLogger.log(
                event = PlaybackDiagnosticEvent.PLAYBACK_RESOLUTION_FAILED,
                animeId = resolvedAnimeId.takeIf { it.isNotBlank() },
                episodeId = resolvedEpisodeId.takeIf { it.isNotBlank() },
                sourceId = normSourceId,
                reason = "timeout"
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
            PlaybackDiagnosticsLogger.log(
                event = PlaybackDiagnosticEvent.PLAYBACK_RESOLUTION_FAILED,
                animeId = resolvedAnimeId.takeIf { it.isNotBlank() },
                episodeId = resolvedEpisodeId.takeIf { it.isNotBlank() },
                sourceId = normSourceId,
                reason = e::class.simpleName
            )
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
        val catalogEpisode = if (!identity.isMovie) {
            episodeDao.getEpisodesForAnime(animeId).firstOrNull {
                it.seasonNumber == identity.seasonNumber && it.episodeNumber == identity.episodeNumber
            }
        } else {
            null
        }
        val canonicalProv = providerId?.let {
            ProviderPriorityPolicy.normalizeProviderName(it).takeIf { p -> p != ProviderPriorityPolicy.PROVIDER_UNKNOWN }
        } ?: ProviderPriorityPolicy.identifyProviderFromText(providerId)
            ?: ProviderPriorityPolicy.PROVIDER_UNKNOWN
        val preferredEpId = (normProvider?.let { discoveredBackendEpisodeIds["$scopeKey|$it|$normSourceId"] })
            ?: canonicalProv.takeIf { it != ProviderPriorityPolicy.PROVIDER_UNKNOWN }
                ?.let { providerBackendEpisodeIds["$scopeKey|prov:$it"] }
            ?: if (canonicalProv == ProviderPriorityPolicy.PROVIDER_UNKNOWN) discoveredBackendEpisodeIds[scopeKey] else null
        val knownBackendIds = buildSet {
            discoveredBackendEpisodeIds[scopeKey]?.takeIf { it.isNotBlank() }?.let(::add)
            providerBackendEpisodeIds.entries
                .filter { it.key.startsWith("$scopeKey|prov:") && it.value.isNotBlank() }
                .forEach { add(it.value) }
            catalogEpisode?.let { ep ->
                EpisodeSourceEpisodeIds.decode(ep.sourceEpisodeIds).forEach { id ->
                    val norm = SourceIdentityValidator.normalizeId(id)
                    if (norm.isNotEmpty() && !norm.startsWith("anilist_ep_") && !norm.startsWith("movie_")) {
                        add(norm)
                    }
                }
            }
        }
        val d1KeyCandidates = resolveD1StreamKeyCandidates(
            animeId = animeId,
            anime = anime,
            identity = identity,
            seasonNumber = identity.seasonNumber,
            episodeNumber = identity.episodeNumber,
            catalogEpisodeId = catalogEpisode?.id,
            preferredBackendEpisodeId = preferredEpId,
            knownBackendEpisodeIds = knownBackendIds,
            candidateProvider = canonicalProv
        )
        runCatching {
            d1KeyCandidates.forEach { d1Keys ->
                d1DataSource.invalidateStreamUrl(d1Keys.animeId, d1Keys.episodeId, normSourceId)
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
        d1DataSource.clearDigestCache()
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
            ?: memoryCache.getStreamSnapshot(cacheKey)
            ?: return null
        return refreshCachedExpiry(memory).let { refreshed ->
            if (refreshed.seasonNumber == null && seasonNumber != null) {
                refreshed.copy(seasonNumber = seasonNumber, episodeNumber = episodeNumber ?: refreshed.episodeNumber)
            } else {
                refreshed
            }
        }
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
        @Suppress("UNUSED_PARAMETER") animeId: String,
        @Suppress("UNUSED_PARAMETER") episodeId: String,
        snapshot: EpisodeSourceSnapshot
    ) {
        sourceCacheMutex.withLock {
            memorySourcesCache[cacheKey] = SourceCacheEntry(snapshot)
            while (memorySourcesCache.size > maxMemorySourceSnapshots) {
                memorySourcesCache.remove(memorySourcesCache.keys.iterator().next())
            }
        }
        if (snapshot.sources.isNotEmpty()) {
            memoryCache.putStreamSnapshot(cacheKey, snapshot)
        }
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
        private const val DISCOVERY_PER_CALL_TIMEOUT_MS = 60_000L
        private const val SEARCH_PER_QUERY_TIMEOUT_MS = 20_000L
        private const val DIRECT_PLAY_RESOLVE_TIMEOUT_MS = 45_000L
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
                    AnimeRepository(
                        database = db,
                        applicationContext = appCtx,
                        d1DataSource = NetworkModule.d1DataSource,
                        memoryCache = com.example.data.cache.MemoryCacheManager.getInstance(),
                        metricsTracker = metrics
                    ).also { INSTANCE = it }
                }
            }
        }
    }
}
