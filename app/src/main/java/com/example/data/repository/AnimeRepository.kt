package com.example.data.repository

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
import com.example.data.local.WatchlistDao
import com.example.data.local.WatchlistEntry
import com.example.data.remote.AniListGraphQLRequest
import com.example.data.remote.AniListGraphQLService
import com.example.data.remote.AniListMedia
import com.example.data.remote.AniListQueries
import com.example.data.remote.DirectPlayData
import com.example.data.firebase.SharedCatalogMetadata
import com.example.data.firebase.SharedCatalogRepository
import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.NetworkModule
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecySeasonDto
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import android.util.Log
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

internal fun isValidPlaybackUrl(value: String): Boolean {
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return false
    return when (uri.scheme?.lowercase()) {
        "http", "https" -> !uri.host.isNullOrBlank()
        "file" -> !uri.path.isNullOrBlank()
        else -> false
    }
}

internal fun uniqueAniListMedia(items: List<AniListMedia>): List<AniListMedia> = items.distinctBy { it.id }

/** Map only explicit backend media types; unknown values remain unclassified. */
internal fun backendMediaCategory(mediaType: String?): String? = when (mediaType?.trim()?.lowercase()) {
    "anime" -> "ANIME"
    "movie" -> "MOVIE"
    "kdrama", "k-drama", "korean_drama" -> "KDRAMA"
    "series", "web_series", "tv_series" -> "SERIES"
    else -> null
}

internal fun preserveMovieMediaCategory(
    existingCategory: String?,
    existingFormat: String?,
    detailMediaType: String?
): String? {
    if (existingCategory.equals("MOVIE", ignoreCase = true) || existingFormat.equals("MOVIE", ignoreCase = true)) {
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
    private val applicationContext: Context? = null
) {
    private val animeDao: AnimeDao = database.animeDao()
    private val episodeDao: EpisodeDao = database.episodeDao()
    private val searchDao: SearchDao = database.searchDao()
    private val watchlistDao: WatchlistDao = database.watchlistDao()
    private val historyDao: PlaybackHistoryDao = database.playbackHistoryDao()
    private val sharedCatalog = SharedCatalogRepository(database.sharedCatalogDao(), networkAvailable = {
        val manager = applicationContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val capabilities = if (manager != null && network != null) manager.getNetworkCapabilities(network) else null
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    })

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

    // Short-lived memory tier; Room is the bounded persistent metadata tier.
    private val memorySourcesCache = LinkedHashMap<String, SourceCacheEntry>(16, 0.75f, true)
    private val sourceCacheMutex = Mutex()
    private data class ResolvedCacheEntry(val data: DirectPlayData, val cachedAtMs: Long)
    private val resolvedPlaybackCache = ConcurrentHashMap<String, ResolvedCacheEntry>()
    private val resolveMutexes = ConcurrentHashMap<String, Mutex>()
    private val sourceCacheTtlMs = 2 * 60 * 1000L
    private val maxMemorySourceSnapshots = 120

    private val homeRefreshMutex = Mutex()
    private var lastHomeRefreshTimestamp: Long = 0L

    private val searchCatalogResults = ConcurrentHashMap<String, List<com.example.data.remote.ProphecySearchResultItem>>()

    // Database observation flows (Database-First)
    fun observeTrending(): Flow<List<AnimeEntry>> = animeDao.observeTrending()
    fun observeSeasonal(): Flow<List<AnimeEntry>> = animeDao.observeSeasonal()
    fun observePopular(): Flow<List<AnimeEntry>> = animeDao.observePopular()
    fun observeKDramas(): Flow<List<AnimeEntry>> = animeDao.observeKDramas()
    fun observeMovies(): Flow<List<AnimeEntry>> = animeDao.observeMovies()
    fun observeWebSeries(): Flow<List<AnimeEntry>> = animeDao.observeWebSeries()
    fun observeAnimeById(id: String): Flow<AnimeEntry?> = animeDao.observeAnimeById(id)
    fun observeSeasons(animeId: String): Flow<List<SeasonEntry>> = episodeDao.observeSeasons(animeId)
    fun observeEpisodes(animeId: String, seasonNumber: Int): Flow<List<EpisodeEntry>> =
        episodeDao.observeEpisodes(animeId, seasonNumber)
    fun observeEpisodeWindow(
        animeId: String,
        seasonNumber: Int,
        firstEpisode: Int,
        episodeLimit: Int
    ): Flow<List<EpisodeEntry>> = episodeDao.observeEpisodeWindow(animeId, seasonNumber, firstEpisode, episodeLimit)
    fun observeAllEpisodes(animeId: String): Flow<List<EpisodeEntry>> = episodeDao.observeAllEpisodes(animeId)
    fun observeFirstEpisode(animeId: String): Flow<EpisodeEntry?> = episodeDao.observeFirstEpisode(animeId)

    /** Pending-only recovery hook; it never scans or republishes the catalog. */
    suspend fun processPendingSharedCatalogWrites(): Int = sharedCatalog.processPendingQueue()

    /** Called from SearchViewModel after local results are visible; capped to 20 DTOs per event. */
    suspend fun syncSearchResults(query: String) = withContext(Dispatchers.IO) {
        val key = TitleMatcher.normalize(query)
        searchCatalogResults[key].orEmpty().asSequence().take(20)
            .mapNotNull { SharedCatalogMetadata.fromSearch(it) }
            .forEach { sharedCatalog.acceptBackendMetadata(it) }
        sharedCatalog.processPendingQueue()
    }

    /** Called only for an explicit season selection; uses real persisted backend season/episode IDs. */
    suspend fun syncOpenedSeason(animeId: String, seasonNumber: Int) = withContext(Dispatchers.IO) {
        if (animeId.isBlank() || seasonNumber <= 0) return@withContext
        val parent = animeDao.getAnimeById(animeId) ?: return@withContext
        val backendId = parent.backendAnimeId?.takeIf { it.isNotBlank() } ?:
            animeId.takeUnless { it.startsWith("anilist_") } ?: return@withContext
        val season = episodeDao.getSeasonsForAnime(animeId).firstOrNull { it.seasonNumber == seasonNumber }
            ?: return@withContext
        SharedCatalogMetadata.fromSeason(
            backendId, null, ProphecySeasonDto(canonicalId = season.backendSeasonId, number = season.seasonNumber)
        )?.let { sharedCatalog.acceptBackendMetadata(it) }
        episodeDao.getEpisodesForAnime(animeId).asSequence()
            .filter { it.seasonNumber == seasonNumber }.take(19)
            .map { ep -> ProphecyEpisodeDto(id = ep.id, season = ep.seasonNumber, number = ep.episodeNumber,
                title = ep.title, overview = ep.overview) }
            .mapNotNull { SharedCatalogMetadata.fromEpisode(backendId, null, seasonNumber, it) }
            .forEach { sharedCatalog.acceptBackendMetadata(it) }
        sharedCatalog.processPendingQueue()
    }

    /** Called from the explicit episode-open UI event, separately from playback source resolution. */
    suspend fun syncOpenedEpisode(animeId: String, episode: EpisodeEntry) = withContext(Dispatchers.IO) {
        if (animeId.isBlank() || episode.id.isBlank() || episode.seasonNumber <= 0 || episode.episodeNumber <= 0) return@withContext
        val parent = animeDao.getAnimeById(animeId) ?: return@withContext
        val backendId = parent.backendAnimeId?.takeIf { it.isNotBlank() } ?: animeId.takeUnless { it.startsWith("anilist_") }
            ?: return@withContext
        val episodeDto = ProphecyEpisodeDto(id = episode.id, season = episode.seasonNumber,
            number = episode.episodeNumber, title = episode.title, overview = episode.overview)
        SharedCatalogMetadata.fromEpisode(backendId, null, episode.seasonNumber, episodeDto)
            ?.let { sharedCatalog.acceptBackendMetadata(it) }
        sharedCatalog.processPendingQueue()
    }

    fun observeSearchResults(query: String): Flow<List<AnimeEntry>> {
        val norm = TitleMatcher.normalize(query)
        return searchDao.observeSearchResults(norm)
    }

    fun observeLocalSearchFallback(query: String): Flow<List<AnimeEntry>> {
        val norm = TitleMatcher.normalize(query)
        return animeDao.searchLocal(norm)
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

    /**
     * Discovery sync: fetches trending, seasonal, and popular feeds in parallel,
     * verifies availability against ProphecyVerse provider catalog,
     * and discovers confirmed available Anime, K-Dramas, Movies, and Web Series.
     * Uses 5-minute cache throttle to prevent duplicate requests.
     */
    suspend fun refreshHomeDiscovery(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        homeRefreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && (now - lastHomeRefreshTimestamp < 5 * 60 * 1000L)) {
                return@withContext Result.success(Unit)
            }

            try {
                coroutineScope {
                    val cal = Calendar.getInstance()
                    val year = cal.get(Calendar.YEAR)
                    val month = cal.get(Calendar.MONTH) // 0-11
                    val season = when (month) {
                        11, 0, 1 -> "WINTER"
                        2, 3, 4 -> "SPRING"
                        5, 6, 7 -> "SUMMER"
                        else -> "FALL"
                    }

                    // 1. Fetch AniList Feeds in background
                    val aniListDeferred = async {
                        try {
                            val request = AniListGraphQLRequest(
                                query = AniListQueries.DISCOVERY_FEEDS,
                                variables = mapOf("season" to season, "seasonYear" to year)
                            )
                            val response = aniListApi.postQuery(request)
                            if (response.isSuccessful) response.body()?.data else null
                        } catch (_: Exception) {
                            null
                        }
                    }

                    // 2. Discover K-Dramas from ProphecyVerse catalog in parallel
                    val kdramaDeferred = async {
                        try {
                            val resp = prophecyApi.searchAnime("korean", limit = 10)
                            if (resp.isSuccessful) resp.body()?.results ?: emptyList() else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                    // 3. Discover Movies from ProphecyVerse catalog in parallel
                    val movieDeferred = async {
                        try {
                            val resp = prophecyApi.searchAnime("movie", limit = 10)
                            if (resp.isSuccessful) resp.body()?.results ?: emptyList() else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                    // 4. Discover Web Series from ProphecyVerse catalog in parallel
                    val seriesDeferred = async {
                        try {
                            val resp = prophecyApi.searchAnime("series", limit = 10)
                            if (resp.isSuccessful) resp.body()?.results ?: emptyList() else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                    val data = aniListDeferred.await()
                    val kdramaItems = kdramaDeferred.await()
                    val movieItems = movieDeferred.await()
                    val seriesItems = seriesDeferred.await()

                    val entriesToUpsert = mutableListOf<AnimeEntry>()
                    val aliasesToInsert = mutableListOf<AnimeAlias>()

                    // Process AniList items
                    val trendingMedia = data?.trending?.media ?: emptyList()
                    val seasonalMedia = data?.seasonal?.media ?: emptyList()
                    val popularMedia = data?.popular?.media ?: emptyList()

                    val allMedia = (trendingMedia + seasonalMedia + popularMedia).distinctBy { it.id }
                    val trendingIds = trendingMedia.map { it.id }.toSet()
                    val seasonalIds = seasonalMedia.map { it.id }.toSet()
                    val popularIds = popularMedia.map { it.id }.toSet()

                    for (m in allMedia) {
                        val animeId = "anilist_${m.id}"
                        val existing = animeDao.getAnimeById(animeId) ?: animeDao.getAnimeByAniListId(m.id)
                        val preferredTitle = m.title?.english ?: m.title?.romaji ?: m.title?.native ?: "Anime #${m.id}"

                        val entry = AnimeEntry(
                            id = existing?.id ?: animeId,
                            aniListId = m.id,
                            backendAnimeId = existing?.backendAnimeId,
                            titleRomaji = m.title?.romaji,
                            titleEnglish = m.title?.english,
                            titleNative = m.title?.native,
                            preferredTitle = preferredTitle,
                            synopsis = m.description,
                            year = (m.startDate?.year ?: m.seasonYear)?.toString(),
                            season = m.season,
                            format = m.format,
                            status = m.status,
                            genres = m.genres?.joinToString(", ") ?: "",
                            score = m.averageScore?.let { it / 10.0 },
                            episodeCount = m.episodes,
                            duration = m.duration,
                            studios = m.studios?.nodes?.firstOrNull()?.name,
                            posterUrl = m.coverImage?.large ?: m.coverImage?.medium,
                            bannerUrl = m.bannerImage,
                            isPlayable = existing?.isPlayable ?: false,
                            isTrending = trendingIds.contains(m.id),
                            isSeasonal = seasonalIds.contains(m.id),
                            isPopular = popularIds.contains(m.id),
                            mediaCategory = "ANIME",
                            updatedAt = System.currentTimeMillis()
                        )
                        entriesToUpsert.add(entry)

                        val targetId = entry.id
                        m.title?.romaji?.let { aliasesToInsert.add(AnimeAlias(animeId = targetId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                        m.title?.english?.let { aliasesToInsert.add(AnimeAlias(animeId = targetId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                        m.title?.native?.let { aliasesToInsert.add(AnimeAlias(animeId = targetId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                        m.synonyms?.forEach { syn ->
                            aliasesToInsert.add(AnimeAlias(animeId = targetId, alias = TitleMatcher.normalize(syn), originalAlias = syn))
                        }
                    }

                    // Verify top 6 items for direct provider playability if unverified
                    for (i in 0 until minOf(6, entriesToUpsert.size)) {
                        val item = entriesToUpsert[i]
                        if (item.backendAnimeId == null) {
                            try {
                                val searchRes = prophecyApi.searchAnime(query = item.preferredTitle, limit = 5)
                                if (searchRes.isSuccessful) {
                                    val results = searchRes.body()?.results ?: emptyList()
                                    val matched = results
                                        .map { p -> p to TitleMatcher.computeMatchConfidenceForEntry(item, p) }
                                        .filter { it.second >= 0.80f }
                                        .maxByOrNull { it.second }
                                    if (matched != null) {
                                        entriesToUpsert[i] = item.copy(
                                            backendAnimeId = matched.first.id,
                                            isPlayable = true
                                        )
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    // Add K-Dramas
                    kdramaItems.forEach { item ->
                        val entry = AnimeEntry(
                            id = item.id,
                            backendAnimeId = item.id,
                            preferredTitle = item.title,
                            titleEnglish = item.title,
                            format = item.mediaType ?: "series",
                            year = item.year,
                            posterUrl = item.posterUrl,
                            isPlayable = true,
                            mediaCategory = "KDRAMA",
                            genres = "K-Drama, Romance, Drama",
                            updatedAt = System.currentTimeMillis()
                        )
                        entriesToUpsert.add(entry)
                        aliasesToInsert.add(AnimeAlias(animeId = item.id, alias = TitleMatcher.normalize(item.title), originalAlias = item.title))
                    }

                    // Add only items explicitly classified as movies by the authoritative backend.
                    // The search query is free text and must not be treated as a media-type filter.
                    movieItems.filter { backendMediaCategory(it.mediaType) == "MOVIE" }.forEach { item ->
                        val existing = animeDao.getAnimeById(item.id)
                        val entry = AnimeEntry(
                            id = item.id,
                            backendAnimeId = item.id,
                            preferredTitle = item.title,
                            titleEnglish = item.title,
                            format = item.mediaType ?: "MOVIE",
                            year = item.year,
                            posterUrl = item.posterUrl ?: existing?.posterUrl,
                            isPlayable = true,
                            mediaCategory = "MOVIE",
                            genres = existing?.genres ?: "",
                            updatedAt = System.currentTimeMillis()
                        )
                        entriesToUpsert.add(entry)
                        aliasesToInsert.add(AnimeAlias(animeId = item.id, alias = TitleMatcher.normalize(item.title), originalAlias = item.title))
                    }

                    // Add Web Series
                    seriesItems.forEach { item ->
                        if (!item.title.contains("Bonus", ignoreCase = true)) {
                            val entry = AnimeEntry(
                                id = item.id,
                                backendAnimeId = item.id,
                                preferredTitle = item.title,
                                titleEnglish = item.title,
                                format = "series",
                                year = item.year,
                                posterUrl = item.posterUrl,
                                isPlayable = true,
                                mediaCategory = "SERIES",
                                genres = "Web Series, Drama",
                                updatedAt = System.currentTimeMillis()
                            )
                            entriesToUpsert.add(entry)
                            aliasesToInsert.add(AnimeAlias(animeId = item.id, alias = TitleMatcher.normalize(item.title), originalAlias = item.title))
                        }
                    }

                    animeDao.upsertAnimeList(entriesToUpsert)
                    animeDao.insertAliases(aliasesToInsert)
                    lastHomeRefreshTimestamp = System.currentTimeMillis()
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Progressive Search:
     * 1. Query ProphecyVerse catalog first (Tier 1). As soon as results return,
     *    instantly write confirmed playable entries to Room so the user sees results immediately!
     * 2. Query AniList in parallel (Tier 2). When AniList returns, enhance metadata, match titles,
     *    and update Room.
     * Never marks an item as playable unless a valid playable source has actually been confirmed.
     */
    private suspend fun persistProphecySearchPage(
        normalizedQuery: String,
        pageItems: List<com.example.data.remote.ProphecySearchResultItem>,
        rankOffset: Int
    ) {
        if (pageItems.isEmpty()) return
        val writeStartedAt = System.currentTimeMillis()
        val entries = mutableListOf<AnimeEntry>()
        val aliases = mutableListOf<AnimeAlias>()
        val refs = mutableListOf<SearchResultRef>()
        pageItems.forEachIndexed { index, item ->
            val existing = animeDao.getAnimeById(item.id)
            entries += AnimeEntry(
                id = item.id,
                aniListId = existing?.aniListId,
                backendAnimeId = item.id,
                preferredTitle = item.title,
                titleEnglish = item.title,
                format = item.mediaType,
                year = item.year,
                posterUrl = item.posterUrl ?: existing?.posterUrl,
                mediaCategory = backendMediaCategory(item.mediaType) ?: existing?.mediaCategory ?: "ANIME",
                genres = existing?.genres ?: "",
                isPlayable = true,
                updatedAt = System.currentTimeMillis()
            )
            aliases += AnimeAlias(
                animeId = item.id,
                alias = TitleMatcher.normalize(item.title),
                originalAlias = item.title
            )
            refs += SearchResultRef(
                normalizedQuery = normalizedQuery,
                animeId = item.id,
                rankIndex = rankOffset + index
            )
        }
        animeDao.upsertAnimeList(entries.distinctBy { it.id })
        animeDao.insertAliases(aliases)
        if (rankOffset == 0) searchDao.clearSearchResultsForQuery(normalizedQuery)
        searchDao.insertSearchResultRefs(refs)
        searchDao.upsertSearchQuery(
            SearchQuery(query = normalizedQuery, totalResults = rankOffset + refs.size)
        )
        Log.i("AnimeRepository", "search_room_update duration_ms=${System.currentTimeMillis() - writeStartedAt} results=${rankOffset + refs.size}")
    }

    private suspend fun fetchAllProphecySearchResults(
        query: String,
        onPage: suspend (items: List<com.example.data.remote.ProphecySearchResultItem>, rankOffset: Int) -> Unit
    ): List<com.example.data.remote.ProphecySearchResultItem> {
        val results = LinkedHashMap<String, com.example.data.remote.ProphecySearchResultItem>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String?>()
        var page = 0
        val searchStartedAt = System.currentTimeMillis()
        do {
            if (!seenCursors.add(cursor) || page >= 10 || results.size >= 500) break
            val requestStartedAt = System.currentTimeMillis()
            val response = try {
                prophecyApi.searchAnime(query = query, limit = 50, cursor = cursor)
            } finally {
                Log.i("AnimeRepository", "search_page duration_ms=${System.currentTimeMillis() - requestStartedAt}")
            }
            if (!response.isSuccessful) break
            val body = response.body() ?: break
            val pageItems = mutableListOf<com.example.data.remote.ProphecySearchResultItem>()
            body.results.forEach { item ->
                if (item.id.isNotBlank() && results.putIfAbsent(item.id, item) == null) pageItems += item
            }
            if (pageItems.isNotEmpty()) {
                onPage(pageItems, results.size - pageItems.size)
                if (page == 0) {
                    Log.i("AnimeRepository", "search_first_results_available_ms=${System.currentTimeMillis() - searchStartedAt}")
                }
            }
            val next = body.nextCursor?.takeIf { it.isNotBlank() }
            val shouldContinue = body.partial == true || next != null
            cursor = if (shouldContinue) next else null
            page++
        } while (cursor != null && results.size < 500)
        Log.i("AnimeRepository", "search_full_catalog_complete_ms=${System.currentTimeMillis() - searchStartedAt} pages=$page")
        return results.values.toList()
    }

    /** Immediately expose live AniList results; backend identity is attached only when already known. */
    private suspend fun publishAniListSearchResults(
        normalizedQuery: String,
        mediaItems: List<AniListMedia>
    ) {
        val uniqueMedia = uniqueAniListMedia(mediaItems)
        if (uniqueMedia.isEmpty()) return
        val entries = mutableListOf<AnimeEntry>()
        val aliases = mutableListOf<AnimeAlias>()
        val refs = mutableListOf<SearchResultRef>()
        uniqueMedia.forEachIndexed { index, media ->
            val existing = animeDao.getAnimeByAniListId(media.id)
            val backendId = existing?.backendAnimeId?.takeIf(String::isNotBlank)
            val animeId = backendId ?: existing?.id?.takeUnless { it.startsWith("anilist_") }
                ?: "anilist_${media.id}"
            val title = media.title?.english ?: media.title?.romaji ?: media.title?.native ?: "Anime #${media.id}"
            entries += AnimeEntry(
                id = animeId, aniListId = media.id, backendAnimeId = backendId,
                titleRomaji = media.title?.romaji, titleEnglish = media.title?.english,
                titleNative = media.title?.native, preferredTitle = title,
                synopsis = media.description ?: existing?.synopsis,
                year = (media.startDate?.year ?: media.seasonYear)?.toString() ?: existing?.year,
                season = media.season ?: existing?.season, format = media.format ?: existing?.format,
                status = media.status ?: existing?.status,
                genres = media.genres?.joinToString(", ") ?: existing?.genres.orEmpty(),
                score = media.averageScore?.div(10.0) ?: existing?.score,
                episodeCount = media.episodes ?: existing?.episodeCount,
                duration = media.duration ?: existing?.duration,
                studios = media.studios?.nodes?.firstOrNull()?.name ?: existing?.studios,
                posterUrl = media.coverImage?.large ?: media.coverImage?.medium ?: existing?.posterUrl,
                bannerUrl = media.bannerImage ?: existing?.bannerUrl,
                isPlayable = backendId != null || existing?.isPlayable == true,
                mediaCategory = existing?.mediaCategory ?: "ANIME", updatedAt = System.currentTimeMillis()
            )
            listOfNotNull(media.title?.romaji, media.title?.english, media.title?.native)
                .plus(media.synonyms.orEmpty()).filter(String::isNotBlank).distinct()
                .forEach { alias -> aliases += AnimeAlias(animeId = animeId,
                    alias = TitleMatcher.normalize(alias), originalAlias = alias) }
            refs += SearchResultRef(normalizedQuery = normalizedQuery, animeId = animeId, rankIndex = index)
        }
        animeDao.upsertAnimeList(entries.distinctBy { it.id })
        if (aliases.isNotEmpty()) animeDao.insertAliases(aliases.distinctBy { it.animeId to it.alias })
        searchDao.clearSearchResultsForQuery(normalizedQuery)
        searchDao.insertSearchResultRefs(refs.distinctBy { it.animeId })
        searchDao.upsertSearchQuery(SearchQuery(normalizedQuery, refs.distinctBy { it.animeId }.size))
    }

    suspend fun searchAnime(query: String): Result<Int> = withContext(Dispatchers.IO) {
        val normQuery = TitleMatcher.normalize(query)
        if (normQuery.isBlank()) return@withContext Result.success(0)
        val fullSearchStartedAt = System.currentTimeMillis()

        try {
            searchMutexes.computeIfAbsent(normQuery) { Mutex() }.withLock {
                // Firestore is the shared metadata authority. Hydrate Room/UI from it before
                // consulting the local cache or calling live metadata providers.
                // Warm Room from shared metadata, but cached results must never suppress live search.
                hydrateFirestoreSearchResults(normQuery)
                coroutineScope {
                // Launch both queries in parallel with bounded concurrency
                val prophecyDeferred = async {
                    try {
                        fetchAllProphecySearchResults(query) { pageItems, rankOffset ->
                            persistProphecySearchPage(normQuery, pageItems, rankOffset)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                val anilistDeferred = async {
                    try {
                        val anilistReq = AniListGraphQLRequest(
                            query = AniListQueries.SEARCH_ANIME,
                            variables = mapOf("search" to query, "page" to 1, "perPage" to 20)
                        )
                        val resp = aniListApi.postQuery(anilistReq)
                        val items = if (resp.isSuccessful) resp.body()?.data?.page?.media ?: emptyList() else emptyList()
                        publishAniListSearchResults(normQuery, items)
                        items
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                // First page was already written by the fetch callback; subsequent pages also
                // update Room before optional AniList matching completes.
                // AniList publishes its live results inside its task before waiting on backend I/O.
                val aniListItems = anilistDeferred.await()
                val prophecyItems = prophecyDeferred.await()
                searchCatalogResults[normQuery] = prophecyItems.take(20)
                if (searchCatalogResults.size > 20) searchCatalogResults.keys.filter { it != normQuery }.take(5).forEach { searchCatalogResults.remove(it) }
                // Enrich the immediate AniList result set with reliable backend mappings.
                val entriesToUpsert = mutableListOf<AnimeEntry>()
                val aliasesToInsert = mutableListOf<AnimeAlias>()
                val finalRefs = mutableListOf<SearchResultRef>()
                var rank = 0

                val matchedProphecyIds = mutableSetOf<String>()

                // Match AniList items with Prophecy items
                for (media in aniListItems) {
                    var matchedProphecy: com.example.data.remote.ProphecySearchResultItem? = null
                    var bestConfidence = 0f

                    for (pItem in prophecyItems) {
                        if (matchedProphecyIds.contains(pItem.id)) continue
                        val conf = TitleMatcher.computeMatchConfidence(media, pItem)
                        if (conf >= 0.80f && conf > bestConfidence) {
                            bestConfidence = conf
                            matchedProphecy = pItem
                        }
                    }

                    val animeId = matchedProphecy?.id ?: "anilist_${media.id}"
                    val isPlayable = matchedProphecy != null
                    if (matchedProphecy != null) {
                        matchedProphecyIds.add(matchedProphecy.id)
                        animeDao.upsertMapping(
                            CatalogMapping(
                                aniListId = media.id,
                                backendAnimeId = matchedProphecy.id,
                                confidence = bestConfidence
                            )
                        )
                    }

                    val existing = animeDao.getAnimeById(animeId)
                    val preferredTitle = media.title?.english ?: media.title?.romaji ?: media.title?.native ?: (matchedProphecy?.title ?: "Anime #${media.id}")

                    val entry = AnimeEntry(
                        id = animeId,
                        aniListId = media.id,
                        backendAnimeId = matchedProphecy?.id ?: existing?.backendAnimeId,
                        titleRomaji = media.title?.romaji,
                        titleEnglish = media.title?.english,
                        titleNative = media.title?.native,
                        preferredTitle = preferredTitle,
                        synopsis = media.description ?: existing?.synopsis,
                        year = (media.startDate?.year ?: media.seasonYear)?.toString() ?: matchedProphecy?.year,
                        season = media.season,
                        format = media.format ?: matchedProphecy?.mediaType,
                        status = media.status,
                        genres = media.genres?.joinToString(", ") ?: existing?.genres ?: "",
                        score = media.averageScore?.let { it / 10.0 } ?: existing?.score,
                        episodeCount = media.episodes,
                        duration = media.duration,
                        studios = media.studios?.nodes?.firstOrNull()?.name,
                        posterUrl = media.coverImage?.large ?: matchedProphecy?.posterUrl ?: existing?.posterUrl,
                        bannerUrl = media.bannerImage ?: existing?.bannerUrl,
                        isPlayable = isPlayable || (existing?.isPlayable == true),
                        mediaCategory = backendMediaCategory(matchedProphecy?.mediaType) ?: existing?.mediaCategory ?: "ANIME",
                        updatedAt = System.currentTimeMillis()
                    )
                    entriesToUpsert.add(entry)

                    media.title?.romaji?.let { aliasesToInsert.add(AnimeAlias(animeId = animeId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                    media.title?.english?.let { aliasesToInsert.add(AnimeAlias(animeId = animeId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                    media.title?.native?.let { aliasesToInsert.add(AnimeAlias(animeId = animeId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                    media.synonyms?.forEach { syn ->
                        aliasesToInsert.add(AnimeAlias(animeId = animeId, alias = TitleMatcher.normalize(syn), originalAlias = syn))
                    }

                    finalRefs.add(SearchResultRef(normalizedQuery = normQuery, animeId = animeId, rankIndex = rank++))
                }

                // Add remaining unmatched Prophecy items
                for (pItem in prophecyItems) {
                    if (matchedProphecyIds.contains(pItem.id)) continue
                    val existing = animeDao.getAnimeById(pItem.id)
                    val entry = AnimeEntry(
                        id = pItem.id,
                        aniListId = existing?.aniListId,
                        backendAnimeId = pItem.id,
                        preferredTitle = pItem.title,
                        titleEnglish = pItem.title,
                        format = pItem.mediaType,
                        year = pItem.year,
                        posterUrl = pItem.posterUrl ?: existing?.posterUrl,
                        mediaCategory = backendMediaCategory(pItem.mediaType) ?: existing?.mediaCategory ?: "ANIME",
                        genres = existing?.genres ?: "",
                        isPlayable = true,
                        updatedAt = System.currentTimeMillis()
                    )
                    entriesToUpsert.add(entry)
                    aliasesToInsert.add(AnimeAlias(animeId = pItem.id, alias = TitleMatcher.normalize(pItem.title), originalAlias = pItem.title))
                    finalRefs.add(SearchResultRef(normalizedQuery = normQuery, animeId = pItem.id, rankIndex = rank++))
                }

                if (entriesToUpsert.isNotEmpty()) {
                    val finalWriteStartedAt = System.currentTimeMillis()
                    val uniqueEntries = entriesToUpsert.distinctBy { it.id }
                    val uniqueRefs = finalRefs.distinctBy { it.animeId }
                    animeDao.upsertAnimeList(uniqueEntries)
                    animeDao.insertAliases(aliasesToInsert)
                    searchDao.clearSearchResultsForQuery(normQuery)
                    searchDao.insertSearchResultRefs(uniqueRefs.mapIndexed { index, ref -> ref.copy(rankIndex = index) })
                    searchDao.upsertSearchQuery(SearchQuery(query = normQuery, totalResults = uniqueRefs.size))
                    Log.i("AnimeRepository", "search_final_room_update_ms=${System.currentTimeMillis() - finalWriteStartedAt} results=${uniqueRefs.size}")
                }

                    Log.i("AnimeRepository", "search_full_completion_ms=${System.currentTimeMillis() - fullSearchStartedAt}")
                    Result.success(finalRefs.distinctBy { it.animeId }.size.coerceAtLeast(prophecyItems.size))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Silently prefetch details/episode metadata for the latest search's backend candidates. */
    suspend fun prefetchSearchResults(query: String) = withContext(Dispatchers.IO) {
        val normalized = TitleMatcher.normalize(query)
        val candidates = searchCatalogResults[normalized].orEmpty().take(6)
        coroutineScope {
            candidates.map { candidate -> async {
                searchPrefetchSemaphore.withPermit {
                    refreshAnimeDetails(candidate.id)
                }
            } }.awaitAll()
        }
    }

    private suspend fun hydrateFirestoreSearchResults(normalizedQuery: String): Int {
        val records = sharedCatalog.searchTitles(normalizedQuery)
        if (records.isEmpty()) return 0
        val entries = records.mapNotNull { record ->
            val json = JSONObject(record.payloadJson)
            val backendId = record.backendId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = json.optString("title").takeIf { it.isNotBlank() } ?: backendId
            val category = if (record.contentType == "MOVIE") "MOVIE" else "ANIME"
            val existing = animeDao.getAnimeByBackendId(backendId)
            AnimeEntry(
                id = existing?.id ?: backendId,
                aniListId = existing?.aniListId,
                backendAnimeId = backendId,
                titleEnglish = existing?.titleEnglish ?: title,
                titleRomaji = existing?.titleRomaji,
                titleNative = existing?.titleNative,
                preferredTitle = title,
                synopsis = json.optString("description").takeIf { it.isNotBlank() } ?: existing?.synopsis,
                year = json.optString("releaseYear").takeIf { it.isNotBlank() } ?: existing?.year,
                format = json.optString("mediaType").takeIf { it.isNotBlank() } ?: existing?.format,
                genres = (json.optJSONArray("genres")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.joinToString(", ")
                } ?: existing?.genres ?: ""),
                posterUrl = json.optString("posterUrl").takeIf { it.isNotBlank() } ?: existing?.posterUrl,
                bannerUrl = json.optString("bannerUrl").takeIf { it.isNotBlank() } ?: existing?.bannerUrl,
                isPlayable = true,
                mediaCategory = category,
                updatedAt = System.currentTimeMillis()
            )
        }.distinctBy { it.id }
        if (entries.isEmpty()) return 0
        animeDao.upsertAnimeList(entries)
        searchDao.clearSearchResultsForQuery(normalizedQuery)
        searchDao.insertSearchResultRefs(entries.mapIndexed { index, entry ->
            SearchResultRef(normalizedQuery = normalizedQuery, animeId = entry.id, rankIndex = index)
        })
        searchDao.upsertSearchQuery(SearchQuery(normalizedQuery, entries.size))
        return entries.size
    }

    /**
     * Refreshes details and seasons/episodes for a given anime.
     * Persists seasons and episodes to Room so the UI observes Room.
     */
    suspend fun refreshAnimeDetails(animeId: String): Result<EpisodeCatalogRefreshStatus> = withContext(Dispatchers.IO) {
      detailRefreshMutexes.computeIfAbsent(animeId) { Mutex() }.withLock {
        val refreshStartedAt = System.currentTimeMillis()
        try {
            var currentEntry = animeDao.getAnimeById(animeId)
            var backendId = currentEntry?.backendAnimeId ?: if (animeId.startsWith("anilist_")) null else animeId
            var detailsSucceeded = false
            var episodeListSucceeded = false
            var snapshotComplete = false
            var detailHttpCode: Int? = null
            var episodeHttpCode: Int? = null
            var currentRefreshEpisodeIds: Set<String> = emptySet()
            var firestoreDetailComplete = false

            // Check existing catalog mapping table first
            if (backendId == null && currentEntry?.aniListId != null) {
                val mapping = animeDao.getMappingForAniList(currentEntry.aniListId!!)
                if (mapping != null) {
                    backendId = mapping.backendAnimeId
                    if (currentEntry?.backendAnimeId == null) {
                        currentEntry = currentEntry!!.copy(backendAnimeId = backendId, isPlayable = true)
                        animeDao.upsertAnime(currentEntry!!)
                    }
                }
            }

            // If backendId is not yet known, search ProphecyVerse for matching title
            if (backendId == null && currentEntry != null) {
                val searchQueries = listOfNotNull(
                    currentEntry!!.preferredTitle,
                    currentEntry!!.titleEnglish?.takeIf { it != currentEntry!!.preferredTitle },
                    currentEntry!!.titleRomaji?.takeIf { it != currentEntry!!.preferredTitle }
                ).filter { it.isNotBlank() }

                for (query in searchQueries) {
                    val searchRes = try {
                        prophecyApi.searchAnime(query = query, limit = 20)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }

                    if (searchRes?.isSuccessful == true) {
                        val pItems = searchRes.body()?.results ?: emptyList()
                        val matchedCandidate = pItems
                            .map { pItem -> pItem to TitleMatcher.computeMatchConfidenceForEntry(currentEntry!!, pItem) }
                            .filter { it.second >= 0.75f }
                            .maxByOrNull { it.second }

                        if (matchedCandidate != null) {
                            val match = matchedCandidate.first
                            backendId = match.id
                            currentEntry = currentEntry!!.copy(
                                backendAnimeId = backendId,
                                isPlayable = true
                            )
                            animeDao.upsertAnime(currentEntry!!)
                            if (currentEntry!!.aniListId != null) {
                                animeDao.upsertMapping(
                                    CatalogMapping(
                                        aniListId = currentEntry!!.aniListId!!,
                                        backendAnimeId = backendId,
                                        confidence = matchedCandidate.second
                                    )
                                )
                            }
                            break
                        }
                    }
                }
            }

            // Firestore-first detail hydration. Live backend remains responsible for missing
            // fields and season/episode metadata; playback sources never come from Firestore.
            if (backendId != null) {
                val contentType = if (currentEntry?.mediaCategory.equals("MOVIE", ignoreCase = true)) "MOVIE" else "ANIME"
                val shared = runCatching {
                    if (contentType == "MOVIE") {
                        sharedCatalog.readMovie(SharedCatalogMetadata.documentIdFor(contentType, backendId))
                    } else {
                        sharedCatalog.readAnime(SharedCatalogMetadata.documentIdFor(contentType, backendId))
                    }
                }.getOrNull()
                if (shared != null) {
                    val json = JSONObject(shared.payloadJson)
                    firestoreDetailComplete = json.optBoolean("metadataComplete", false)
                    val sharedTitle = json.optString("title").takeIf { it.isNotBlank() }
                    val hydrated = (currentEntry ?: AnimeEntry(backendId, preferredTitle = sharedTitle ?: backendId)).copy(
                        backendAnimeId = backendId,
                        preferredTitle = sharedTitle ?: currentEntry?.preferredTitle ?: backendId,
                        synopsis = json.optString("description").takeIf { it.isNotBlank() } ?: currentEntry?.synopsis,
                        year = json.optString("releaseYear").takeIf { it.isNotBlank() } ?: currentEntry?.year,
                        posterUrl = json.optString("posterUrl").takeIf { it.isNotBlank() } ?: currentEntry?.posterUrl,
                        bannerUrl = json.optString("bannerUrl").takeIf { it.isNotBlank() } ?: currentEntry?.bannerUrl,
                        genres = json.optJSONArray("genres")?.let { array ->
                            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.joinToString(", ")
                        } ?: currentEntry?.genres.orEmpty(),
                        isPlayable = true,
                        updatedAt = System.currentTimeMillis()
                    )
                    animeDao.upsertAnime(hydrated)
                    currentEntry = hydrated
                }
            }

            // Fetch ProphecyVerse details and episodes if backendId is available
            if (backendId != null) {
                val (detailResp, epResp) = coroutineScope {
                    val detailDeferred = async {
                        if (firestoreDetailComplete) return@async null
                        val startedAt = System.currentTimeMillis()
                        try { prophecyApi.getAnimeDetails(backendId).also {
                            Log.i("AnimeRepository", "detail_endpoint_ms=${System.currentTimeMillis() - startedAt}")
                        } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            Log.i("AnimeRepository", "detail_endpoint_ms=${System.currentTimeMillis() - startedAt} failed=true")
                            null
                        }
                    }
                    val episodesDeferred = async {
                        val startedAt = System.currentTimeMillis()
                        try { prophecyApi.getAnimeEpisodes(backendId).also {
                            Log.i("AnimeRepository", "episode_endpoint_ms=${System.currentTimeMillis() - startedAt}")
                        } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            Log.i("AnimeRepository", "episode_endpoint_ms=${System.currentTimeMillis() - startedAt} failed=true")
                            null
                        }
                    }
                    detailDeferred.await() to episodesDeferred.await()
                }
                detailHttpCode = detailResp?.code()
                episodeHttpCode = epResp?.code()

                val seasonsList = mutableListOf<SeasonEntry>()
                val episodeCandidates = mutableListOf<EpisodeRecordCandidate>()
                var episodeSnapshotComplete = false
                val sharedRecords = mutableListOf<SharedCatalogMetadata>()

                if (detailResp?.isSuccessful == true && detailResp.body() != null) {
                    detailsSucceeded = true
                    val body = detailResp.body()!!

                    // Update AnimeEntry
                    val updatedEntry = (currentEntry ?: AnimeEntry(
                        id = backendId,
                        preferredTitle = body.title
                    )).copy(
                        backendAnimeId = backendId,
                        preferredTitle = body.title.ifBlank { currentEntry?.preferredTitle ?: "Anime" },
                        synopsis = body.description ?: currentEntry?.synopsis,
                        posterUrl = body.posterUrl ?: currentEntry?.posterUrl,
                        year = body.year ?: currentEntry?.year,
                        format = body.mediaType ?: currentEntry?.format,
                        genres = body.genres?.joinToString(", ") ?: currentEntry?.genres ?: "",
                        mediaCategory = preserveMovieMediaCategory(
                            existingCategory = currentEntry?.mediaCategory,
                            existingFormat = currentEntry?.format,
                            detailMediaType = body.mediaType
                        ) ?: "ANIME",
                        isPlayable = true,
                        updatedAt = System.currentTimeMillis()
                    )
                    animeDao.upsertAnime(updatedEntry)
                    // Cache/queue only actual backend fields. Movies never produce season/episode rows.
                    SharedCatalogMetadata.fromDetail(body)?.let(sharedRecords::add)
                    if (body.mediaType.equals("anime", ignoreCase = true)) {
                        var catalogCount = 1
                        for (season in body.seasons.orEmpty()) {
                            if (catalogCount >= 20) break
                            SharedCatalogMetadata.fromSeason(backendId, body.canonicalId, season)?.let {
                                sharedRecords.add(it); catalogCount++
                            }
                            for (ep in season.episodes.orEmpty()) {
                                if (catalogCount >= 20) break
                                val realSeason = ep.season?.takeIf { it > 0 } ?: season.number
                                SharedCatalogMetadata.fromEpisode(backendId, body.canonicalId, realSeason, ep)?.let {
                                    sharedRecords.add(it); catalogCount++
                                }
                            }
                        }
                        body.genres.orEmpty().asSequence().take((20 - catalogCount).coerceAtLeast(0))
                            .mapNotNull { SharedCatalogMetadata.fromGenre(it) }
                            .forEach { sharedRecords.add(it) }
                    }
                    // Current responses can explicitly be partial. Unknown completeness is treated
                    // conservatively so a partial/older response cannot erase cached Episodes.
                    episodeSnapshotComplete = isAuthoritativeEpisodeSnapshot(body.partial, body.seasons)
                    snapshotComplete = episodeSnapshotComplete
                    body.seasons?.forEach { s ->
                        if (s.number <= 0) return@forEach
                        val sNum = s.number
                        val seasonKey = "${animeId}_s$sNum"
                        seasonsList.add(
                            SeasonEntry(
                                id = seasonKey,
                                animeId = animeId,
                                seasonNumber = sNum,
                                backendSeasonId = s.canonicalId,
                                title = null
                            )
                        )
                        s.episodes?.forEach episodeLoop@{ ep ->
                            if (ep.id.isBlank() || ep.number <= 0) return@episodeLoop
                            // The containing backend season is authoritative when an episode omits its season.
                            val epSeason = ep.season?.takeIf { it > 0 } ?: sNum
                            episodeCandidates.add(
                                EpisodeRecordCandidate(
                                    episode = EpisodeEntry(
                                    id = ep.id,
                                    animeId = animeId,
                                    seasonNumber = epSeason,
                                    episodeNumber = ep.number,
                                    title = ep.title,
                                    overview = ep.overview
                                    ),
                                    canonicalId = ep.canonicalId,
                                    source = EpisodeRecordSource.DETAILS
                                )
                            )
                        }
                    }
                }

                if (epResp?.isSuccessful == true && epResp.body() != null) {
                    episodeListSucceeded = true
                    // This endpoint has no explicit partial/completeness marker, so merge its
                    // records but never use it alone as authority to delete cached Episodes.
                    val dtoList = epResp.body()!!
                    val seasonsSet = mutableSetOf<Int>()
                    dtoList.forEach episodeLoop@{ ep ->
                        if (ep.id.isBlank() || ep.season == null || ep.season <= 0 || ep.number <= 0) return@episodeLoop
                        val sNum = ep.season
                        seasonsSet.add(sNum)
                        val newEp = EpisodeRecordCandidate(
                            episode = EpisodeEntry(
                                id = ep.id,
                                animeId = animeId,
                                seasonNumber = sNum,
                                episodeNumber = ep.number,
                                title = ep.title,
                                overview = ep.overview
                            ),
                            canonicalId = ep.canonicalId,
                            source = EpisodeRecordSource.EPISODE_LIST
                        )
                        episodeCandidates.add(newEp)
                    }
                    seasonsSet.sorted().forEach { sNum ->
                        if (seasonsList.none { it.seasonNumber == sNum }) {
                            seasonsList.add(
                                SeasonEntry(
                                    id = "${animeId}_s$sNum",
                                    animeId = animeId,
                                    seasonNumber = sNum,
                                    title = null
                                )
                            )
                        }
                    }
                }

                val episodesList = EpisodeCatalogIdentity.merge(episodeCandidates)
                // Ensure every season present in episodesList has a corresponding SeasonEntry
                episodesList.map { it.seasonNumber }.distinct().sorted().forEach { sNum ->
                    if (seasonsList.none { it.seasonNumber == sNum }) {
                        seasonsList.add(
                            SeasonEntry(
                                id = "${animeId}_s$sNum",
                                animeId = animeId,
                                seasonNumber = sNum,
                                title = null
                            )
                        )
                    }
                }

                val incomingSeasons = seasonsList.distinctBy { it.id }
                val incomingEpisodes = episodesList.distinctBy { it.id }
                currentRefreshEpisodeIds = incomingEpisodes.map { it.id }.toSet()
                val roomWriteStartedAt = System.currentTimeMillis()
                if (episodeSnapshotComplete) {
                    episodeDao.replaceEpisodeSnapshot(animeId, incomingSeasons, incomingEpisodes)
                } else if (incomingSeasons.isNotEmpty() || incomingEpisodes.isNotEmpty()) {
                    episodeDao.mergeEpisodeSnapshot(animeId, incomingSeasons, incomingEpisodes)
                }
                Log.i("AnimeRepository", "catalog_room_update_ms=${System.currentTimeMillis() - roomWriteStartedAt} episodes=${incomingEpisodes.size}")
                // All backend-driven Room writes are committed before optional Firestore traffic.
                sharedRecords.forEach { sharedCatalog.acceptBackendMetadata(it) }
                sharedCatalog.processPendingQueue()
            }

            val cachedEpisodeCount = episodeDao.getEpisodesForAnime(animeId).size
            val state = resolveEpisodeCatalogState(
                snapshotComplete = snapshotComplete,
                cachedEpisodeCount = cachedEpisodeCount,
                backendIdKnown = backendId != null,
                detailsEndpointSucceeded = detailsSucceeded,
                episodeListEndpointSucceeded = episodeListSucceeded
            )
            val endpointErrors = buildList {
                if (backendId != null && !detailsSucceeded) {
                    add("Anime details endpoint failed${detailHttpCode?.let { " (HTTP $it)" }.orEmpty()}.")
                }
                if (backendId != null && !episodeListSucceeded) {
                    add("Episode list endpoint failed${episodeHttpCode?.let { " (HTTP $it)" }.orEmpty()}.")
                }
            }
            val refreshStatus = EpisodeCatalogRefreshStatus(
                state = state,
                detailsEndpointSucceeded = detailsSucceeded,
                episodeListEndpointSucceeded = episodeListSucceeded,
                errorMessage = endpointErrors.takeIf { it.isNotEmpty() }?.joinToString(" "),
                currentRefreshEpisodeIds = currentRefreshEpisodeIds
            )
            Log.i("AnimeRepository", "detail_catalog_full_completion_ms=${System.currentTimeMillis() - refreshStartedAt} state=${state.name}")
            Result.success(refreshStatus)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
      }
    }

    /** Optional metadata enrichment runs after core catalog rows are already available to the UI. */
    suspend fun refreshOptionalAniListMetadata(animeId: String) = withContext(Dispatchers.IO) {
        val existing = animeDao.getAnimeById(animeId) ?: return@withContext
        val aniListId = existing.aniListId ?: return@withContext
        val startedAt = System.currentTimeMillis()
        try {
            val response = aniListApi.postQuery(
                AniListGraphQLRequest(
                    query = AniListQueries.MEDIA_DETAILS,
                    variables = mapOf("id" to aniListId)
                )
            )
            Log.i("AnimeRepository", "optional_anilist_metadata_ms=${System.currentTimeMillis() - startedAt} success=${response.isSuccessful}")
            val media = response.body()?.data?.media.takeIf { response.isSuccessful } ?: return@withContext
            val current = animeDao.getAnimeById(animeId) ?: existing
            animeDao.upsertAnime(
                current.copy(
                    titleRomaji = media?.title?.romaji ?: current.titleRomaji,
                    titleEnglish = media?.title?.english ?: current.titleEnglish,
                    titleNative = media?.title?.native ?: current.titleNative,
                    synopsis = media?.description ?: current.synopsis,
                    bannerUrl = media?.bannerImage ?: current.bannerUrl,
                    score = media?.averageScore?.let { it / 10.0 } ?: current.score,
                    studios = media?.studios?.nodes?.firstOrNull()?.name ?: current.studios,
                    episodeCount = media?.episodes ?: current.episodeCount,
                    duration = media?.duration ?: current.duration
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.i("AnimeRepository", "optional_anilist_metadata_ms=${System.currentTimeMillis() - startedAt} failed=true")
        }
    }

    /** Read memory/Room metadata immediately; stale candidates are explicitly marked unverified. */
    suspend fun getCachedEpisodeSourceSnapshot(
        backendAnimeId: String,
        episodeId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): EpisodeSourceSnapshot? = withContext(Dispatchers.IO) {
        val cacheKey = sourceCacheKey(backendAnimeId, seasonNumber, episodeId)
        readBestCachedSourceSnapshot(cacheKey, seasonNumber, episodeNumber)
    }

    /**
     * Fetches source candidates using the existing Episode-level full endpoint. Only non-sensitive
     * source metadata is cached; resolved playback URLs and headers are never stored here.
     */
    suspend fun fetchEpisodeSources(
        backendAnimeId: String,
        episodeId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        forceRefresh: Boolean = false
    ): Result<List<ProphecySourceDto>> = fetchEpisodeSourceSnapshot(
        backendAnimeId,
        episodeId,
        forceRefresh = forceRefresh,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber
    )
        .map { it.sources }

    /**
     * Returns both candidates and the server's response state so an empty, partial, or failed
     * provider lookup is not accidentally presented as a confirmed empty result.
     */
    suspend fun fetchEpisodeSourceSnapshot(
        backendAnimeId: String,
        episodeId: String,
        forceRefresh: Boolean = false,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<EpisodeSourceSnapshot> = withContext(Dispatchers.IO) {
        val cacheKey = sourceCacheKey(backendAnimeId, seasonNumber, episodeId)
        val generationAtStart = sourceFetchGenerations.computeIfAbsent(cacheKey) { java.util.concurrent.atomic.AtomicLong() }.get()
        val cachedAtStart = readBestCachedSourceSnapshot(cacheKey, seasonNumber, episodeNumber)
        if (!forceRefresh && cachedAtStart != null && !cachedAtStart.isStale) {
            return@withContext Result.success(cachedAtStart)
        }
        sourceFetchMutexes.computeIfAbsent(cacheKey) { Mutex() }.withLock {
            val cached = readBestCachedSourceSnapshot(cacheKey, seasonNumber, episodeNumber)
            if (forceRefresh && sourceFetchGenerations[cacheKey]?.get()?.let { it > generationAtStart } == true && cached != null) {
                return@withContext Result.success(cached)
            }
            if (!forceRefresh && cached != null && !cached.isStale) {
                return@withContext Result.success(cached)
            }
            val retryRemainingMs = sourceRetryRemainingMs(cacheKey)
            if (EpisodeSourceRetryBackoff.shouldDelay(forceRefresh, retryRemainingMs)) {
                val message = "Source retry is delayed by backoff for ${(retryRemainingMs + 999L) / 1000L} seconds."
                return@withContext staleSourceFallback(cached, seasonNumber, episodeNumber, message)
            }
            val sourceDiscoveryStartedAt = System.currentTimeMillis()
            try {
                val requestStartedAt = System.currentTimeMillis()
                val response = prophecyApi.getEpisodeFull(backendAnimeId, episodeId)
                Log.i("AnimeRepository", "source_lookup_http_ms=${System.currentTimeMillis() - requestStartedAt}")
                if (!response.isSuccessful || response.body() == null) {
                    val retryable = response.code() == 408 || response.code() == 429 || response.code() >= 500 || response.body() == null
                    val retryRemaining = if (retryable) recordSourceFailure(cacheKey) else 0L
                    val message = buildString {
                        append("Episode source request failed with HTTP ${response.code()}.")
                        if (retryRemaining > 0L) append(" Retry after ${(retryRemaining + 999L) / 1000L} seconds.")
                    }
                    Log.i("AnimeRepository", "source_discovery_total_ms=${System.currentTimeMillis() - sourceDiscoveryStartedAt} outcome=failed")
                    return@withContext staleSourceFallback(cached, seasonNumber, episodeNumber, message)
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
                if (aggregated.sources.isEmpty() && cached != null && aggregated.state in setOf(
                        EpisodeSourceResponseState.UNKNOWN,
                        EpisodeSourceResponseState.PARTIAL,
                        EpisodeSourceResponseState.FAILED,
                        EpisodeSourceResponseState.TIMEOUT
                    )) {
                    val message = aggregated.errorMessage?.takeIf { it.isNotBlank() }
                        ?: "The response did not confirm an empty source list."
                    return@withContext staleSourceFallback(cached, seasonNumber, episodeNumber, message)
                }
                val authoritativeComplete = body.sourcesPartial == false && body.sources != null && body.sourceError.isNullOrBlank()
                val mergedCandidates = EpisodeSourceCachePolicy.mergeRefreshCandidates(
                    existingCandidates = cached?.sources.orEmpty(),
                    incomingCandidates = aggregated.sources,
                    authoritativeComplete = authoritativeComplete,
                    nowMs = System.currentTimeMillis()
                )
                val incomingIds = aggregated.sources.mapNotNull { it.id.trim().takeIf(String::isNotEmpty) }.toSet()
                val retainedCachedCandidates = !authoritativeComplete && cached?.sources.orEmpty().any { old ->
                    if (old.hasUsableSourceId) old.id.trim() !in incomingIds else old !in aggregated.sources
                }
                val snapshotState = if (retainedCachedCandidates && aggregated.state == EpisodeSourceResponseState.AVAILABLE) {
                    EpisodeSourceResponseState.UNKNOWN
                } else {
                    aggregated.state
                }
                val snapshotErrorMessage = if (retainedCachedCandidates) {
                    listOfNotNull(
                        aggregated.errorMessage?.takeIf { it.isNotBlank() },
                        "Older cached candidates were retained because this response did not confirm a complete source list."
                    ).joinToString(" ")
                } else {
                    aggregated.errorMessage
                }
                val snapshot = EpisodeSourceSnapshot(
                    sources = mergedCandidates,
                    state = snapshotState,
                    backendStatus = aggregated.backendStatus,
                    errorMessage = snapshotErrorMessage,
                    fetchedAtMs = System.currentTimeMillis(),
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    apiCandidateCount = aggregated.apiCandidateCount,
                    parsedCandidateCount = aggregated.parsedCandidateCount,
                    expiredCandidateCount = aggregated.expiredCandidateCount,
                    exactDuplicateCount = aggregated.exactDuplicateCount
                )
                storeSourceSnapshot(cacheKey, backendAnimeId, episodeId, snapshot)
                sourceFetchGenerations.computeIfAbsent(cacheKey) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
                Log.i("AnimeRepository", "source_discovery_total_ms=${System.currentTimeMillis() - sourceDiscoveryStartedAt} state=${snapshot.state.name} candidates=${snapshot.sources.size}")
                Result.success(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val retryRemaining = recordSourceFailure(cacheKey)
                val message = buildString {
                    append(if (isTimeoutFailure(e)) "Source refresh timed out." else "Source refresh failed.")
                    append(" Retry after ${(retryRemaining + 999L) / 1000L} seconds.")
                }
                Log.i("AnimeRepository", "source_discovery_total_ms=${System.currentTimeMillis() - sourceDiscoveryStartedAt} outcome=failed timeout=${isTimeoutFailure(e)}")
                staleSourceFallback(cached, seasonNumber, episodeNumber, message, IllegalStateException(message, e))
            }
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
        if (memory != null) {
            val normalized = refreshCachedExpiry(memory)
            sourceCacheMutex.withLock { putMemorySourceSnapshot(cacheKey, normalized) }
            return normalized
        }
        return try {
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
            val stored = EpisodeSourceSnapshot(
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
            val normalized = refreshCachedExpiry(stored)
            sourceCacheMutex.withLock { putMemorySourceSnapshot(cacheKey, normalized) }
            normalized
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w("AnimeRepository", "Could not read cached source metadata; continuing with the API", e)
            null
        }
    }

    private fun refreshCachedExpiry(snapshot: EpisodeSourceSnapshot): EpisodeSourceSnapshot {
        val unexpired = snapshot.sources.filterNot { !it.expiresAt.isNullOrBlank() && isExpired(it.expiresAt) }
        val newlyExpired = snapshot.sources.size - unexpired.size
        val stale = System.currentTimeMillis() - snapshot.fetchedAtMs > sourceCacheTtlMs
        return snapshot.copy(
            sources = unexpired,
            state = if (stale) EpisodeSourceResponseState.STALE else snapshot.state,
            errorMessage = if (stale && snapshot.errorMessage.isNullOrBlank())
                "Cached source candidates are stale and have not been verified current." else snapshot.errorMessage,
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
            val stale = EpisodeSourceCachePolicy.staleAfterRefreshFailure(
                snapshot = refreshCachedExpiry(cached),
                message = "Refresh could not verify the cached candidates. $message",
                nowMs = System.currentTimeMillis()
            )
            return Result.success(stale.copy(seasonNumber = seasonNumber, episodeNumber = episodeNumber))
        }
        val failure = cause ?: IllegalStateException(message)
        return Result.failure(failure)
    }

    private suspend fun storeSourceSnapshot(
        cacheKey: String,
        animeId: String,
        episodeId: String,
        snapshot: EpisodeSourceSnapshot
    ) {
        sourceCacheMutex.withLock { putMemorySourceSnapshot(cacheKey, snapshot) }
        try {
            val record = EpisodeSourceSnapshotRecord(
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
            )
            val candidates = snapshot.sources.map { source ->
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
            }
            episodeDao.replaceSourceSnapshot(record, candidates, maxMemorySourceSnapshots)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w("AnimeRepository", "Could not persist safe source metadata; current results remain available", e)
        }
    }

    private fun putMemorySourceSnapshot(cacheKey: String, snapshot: EpisodeSourceSnapshot) {
        memorySourcesCache[cacheKey] = SourceCacheEntry(snapshot)
        while (memorySourcesCache.size > maxMemorySourceSnapshots) {
            val eldestKey = memorySourcesCache.keys.iterator().next()
            memorySourcesCache.remove(eldestKey)
        }
    }

    private suspend fun sourceRetryRemainingMs(cacheKey: String): Long = sourceRetryMutex.withLock {
        ((sourceRetryStates[cacheKey]?.retryAtMs ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private suspend fun recordSourceFailure(cacheKey: String): Long = sourceRetryMutex.withLock {
            val now = System.currentTimeMillis()
            val failures = (sourceRetryStates[cacheKey]?.failures ?: 0) + 1
            val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0L, 30_001L)
            val delayMs = EpisodeSourceRetryBackoff.delayMs(failures, jitter)
            sourceRetryStates[cacheKey] = SourceRetryState(failures, now + delayMs)
            while (sourceRetryStates.size > maxMemorySourceSnapshots) {
                sourceRetryStates.remove(sourceRetryStates.keys.firstOrNull() ?: break)
            }
            delayMs
        }

    private suspend fun clearSourceFailure(cacheKey: String) {
        sourceRetryMutex.withLock { sourceRetryStates.remove(cacheKey) }
    }

    private fun candidateCacheKey(cacheKey: String, source: ProphecySourceDto): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${cacheKey.length}:$cacheKey:${source.stableIdentity}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isTimeoutFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is java.net.SocketTimeoutException }

    /**
     * Resolves direct playback URL.
     * Route E: The ONE AND ONLY playback route:
     * POST /api/v1/play/direct
     * Body: { "source_id": "..." }
     * Kept in-memory only; never persisted or logged.
     */
    suspend fun resolveDirectPlayback(sourceId: String): Result<DirectPlayData> = withContext(Dispatchers.IO) {
        if (sourceId.isBlank()) return@withContext Result.failure(Exception("Source ID is blank"))
        resolveMutexes.computeIfAbsent(sourceId) { Mutex() }.withLock {
            val cached = resolvedPlaybackCache[sourceId]
            if (cached != null && !isExpired(cached.data.expiresAt) &&
                System.currentTimeMillis() - cached.cachedAtMs <= 20 * 60 * 1000L) {
                return@withContext Result.success(cached.data)
            }
        try {
            val resolutionStartedAt = System.currentTimeMillis()
            val response = prophecyApi.resolveDirectPlay(DirectPlayRequest(sourceId = sourceId))
            Log.i("AnimeRepository", "direct_play_resolution_ms=${System.currentTimeMillis() - resolutionStartedAt} http_status=${response.code()}")
            if (!response.isSuccessful || response.body() == null) {
                return@withContext Result.failure(Exception("Direct playback resolution failed: ${response.code()}"))
            }

            val body = response.body()!!
            if (!body.success || body.data == null) {
                return@withContext Result.failure(Exception("Source not compatible or unverified"))
            }

            val data = body.data
            if (data.sourceId != sourceId || !isPlayableUrl(data.playbackUrl) || isExpired(data.expiresAt)) {
                return@withContext Result.failure(Exception("Resolved playback data is invalid or expired"))
            }
            resolvedPlaybackCache[sourceId] = ResolvedCacheEntry(data, System.currentTimeMillis())
            Result.success(data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
        }
    }

    /** Drop only the in-memory direct response for a source after a playback error. */
    fun invalidateDirectPlayback(sourceId: String) {
        if (sourceId.isNotBlank()) resolvedPlaybackCache.remove(sourceId)
    }

    private fun isPlayableUrl(url: String): Boolean {
        return isValidPlaybackUrl(url)
    }

    private fun isExpired(expiresAt: String?): Boolean {
        return SourceExpiryTime.isExpired(expiresAt)
    }

    // Watchlist methods
    suspend fun toggleWatchlist(animeId: String, status: String = "WATCHING") {
        withContext(Dispatchers.IO) {
            val existing = watchlistDao.getWatchlistEntry(animeId)
            if (existing != null && existing.status == status) {
                watchlistDao.deleteWatchlistEntry(animeId)
            } else {
                watchlistDao.upsertWatchlistEntry(
                    WatchlistEntry(
                        animeId = animeId,
                        status = status,
                        isFavorite = existing?.isFavorite ?: false
                    )
                )
            }
        }
    }

    suspend fun toggleFavorite(animeId: String) {
        withContext(Dispatchers.IO) {
            val existing = watchlistDao.getWatchlistEntry(animeId)
            if (existing != null) {
                watchlistDao.upsertWatchlistEntry(existing.copy(isFavorite = !existing.isFavorite))
            } else {
                watchlistDao.upsertWatchlistEntry(
                    WatchlistEntry(
                        animeId = animeId,
                        status = "PLANNING",
                        isFavorite = true
                    )
                )
            }
        }
    }

    // History methods
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
            val isCompleted = durationMs > 0 && positionMs >= (durationMs * 0.9)
            val anime = animeDao.getAnimeById(animeId)
            val resolvedTitle = animeTitle ?: anime?.preferredTitle ?: anime?.titleEnglish ?: "Anime"
            val resolvedPoster = posterUrl ?: anime?.posterUrl
            val resolvedType = mediaType ?: anime?.format ?: "ANIME"

            // One row represents one anime episode. This is idempotent across repeated
            // player callbacks while still retaining separate episodes in History.
            val historyId = "history_${animeId}_${episodeId}"

            val history = PlaybackHistory(
                id = historyId,
                animeId = animeId,
                episodeId = episodeId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                positionMs = positionMs,
                durationMs = durationMs,
                completed = isCompleted,
                animeTitle = resolvedTitle,
                posterUrl = resolvedPoster,
                mediaType = resolvedType,
                lastPlayedAt = System.currentTimeMillis()
            )
            historyDao.upsertHistory(history)
        }
    }

    suspend fun clearHistory() {
        withContext(Dispatchers.IO) {
            historyDao.clearAllHistory()
        }
    }

    suspend fun clearSearchAndMetadataCache() {
        withContext(Dispatchers.IO) {
            searchDao.clearAllSearchQueries()
            searchDao.clearAllSearchResultRefs()
            animeDao.clearUnsavedCache()
        }
    }

    private companion object {
        val searchMutexes = ConcurrentHashMap<String, Mutex>()
        val searchPrefetchSemaphore = Semaphore(2)
        val detailRefreshMutexes = ConcurrentHashMap<String, Mutex>()
        val sourceFetchMutexes = ConcurrentHashMap<String, Mutex>()
        val sourceFetchGenerations = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
        val sourceRetryMutex = Mutex()
        val sourceRetryStates = ConcurrentHashMap<String, SourceRetryState>()
    }
}
