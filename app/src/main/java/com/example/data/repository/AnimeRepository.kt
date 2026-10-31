package com.example.data.repository

import com.example.data.local.AnimeAlias
import com.example.data.local.AnimeDao
import com.example.data.local.AnimeEntry
import com.example.data.local.AppDatabase
import com.example.data.local.CatalogMapping
import com.example.data.local.EpisodeDao
import com.example.data.local.EpisodeEntry
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
import com.example.data.remote.DirectPlayRequest
import com.example.data.remote.NetworkModule
import com.example.data.remote.ProphecyEpisodeDto
import com.example.data.remote.ProphecySeasonDto
import com.example.data.remote.ProphecySourceDto
import com.example.data.remote.ProphecyVerseApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar

class AnimeRepository(
    private val database: AppDatabase,
    private val prophecyApi: ProphecyVerseApiService = NetworkModule.prophecyVerseApi,
    private val aniListApi: AniListGraphQLService = NetworkModule.aniListApi
) {
    private val animeDao: AnimeDao = database.animeDao()
    private val episodeDao: EpisodeDao = database.episodeDao()
    private val searchDao: SearchDao = database.searchDao()
    private val watchlistDao: WatchlistDao = database.watchlistDao()
    private val historyDao: PlaybackHistoryDao = database.playbackHistoryDao()

    // Transient in-memory cache for source candidates during the active episode session
    // Strictly NOT persisted in Room or logs
    private val memorySourcesCache = mutableMapOf<String, List<ProphecySourceDto>>()

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
    fun observeAllEpisodes(animeId: String): Flow<List<EpisodeEntry>> = episodeDao.observeAllEpisodes(animeId)

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
     * Discovery sync: fetches trending, seasonal, and popular feeds from AniList,
     * verifies availability against ProphecyVerse provider catalog,
     * and discovers confirmed available Anime, K-Dramas, Movies, and Web Series.
     * The UI only observes Room.
     */
    suspend fun refreshHomeDiscovery(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cal = Calendar.getInstance()
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) // 0-11
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
            val response = aniListApi.postQuery(request)
            val data = if (response.isSuccessful) response.body()?.data else null

            val trendingMedia = data?.trending?.media ?: emptyList()
            val seasonalMedia = data?.seasonal?.media ?: emptyList()
            val popularMedia = data?.popular?.media ?: emptyList()

            val allMedia = (trendingMedia + seasonalMedia + popularMedia).distinctBy { it.id }
            val trendingIds = trendingMedia.map { it.id }.toSet()
            val seasonalIds = seasonalMedia.map { it.id }.toSet()
            val popularIds = popularMedia.map { it.id }.toSet()

            val entriesToUpsert = mutableListOf<AnimeEntry>()
            val aliasesToInsert = mutableListOf<AnimeAlias>()

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

                // Aliases
                val targetId = entry.id
                m.title?.romaji?.let { aliasesToInsert.add(AnimeAlias(animeId = targetId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                m.title?.english?.let { aliasesToInsert.add(AnimeAlias(animeId = targetId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                m.title?.native?.let { aliasesToInsert.add(AnimeAlias(animeId = targetId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                m.synonyms?.forEach { syn ->
                    aliasesToInsert.add(AnimeAlias(animeId = targetId, alias = TitleMatcher.normalize(syn), originalAlias = syn))
                }
            }

            // Verify top items for direct provider playability
            for (i in 0 until minOf(8, entriesToUpsert.size)) {
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

            // 2. Discover & verify K-Dramas from ProphecyVerse catalog
            try {
                val kdramaSearch = prophecyApi.searchAnime("korean", limit = 10)
                if (kdramaSearch.isSuccessful) {
                    kdramaSearch.body()?.results?.forEach { item ->
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
                }
            } catch (_: Exception) {}

            // 3. Discover & verify Movies from ProphecyVerse catalog
            try {
                val movieSearch = prophecyApi.searchAnime("movie", limit = 10)
                if (movieSearch.isSuccessful) {
                    movieSearch.body()?.results?.forEach { item ->
                        val entry = AnimeEntry(
                            id = item.id,
                            backendAnimeId = item.id,
                            preferredTitle = item.title,
                            titleEnglish = item.title,
                            format = "MOVIE",
                            year = item.year,
                            posterUrl = item.posterUrl,
                            isPlayable = true,
                            mediaCategory = "MOVIE",
                            genres = "Movie, Action, Animation",
                            updatedAt = System.currentTimeMillis()
                        )
                        entriesToUpsert.add(entry)
                        aliasesToInsert.add(AnimeAlias(animeId = item.id, alias = TitleMatcher.normalize(item.title), originalAlias = item.title))
                    }
                }
            } catch (_: Exception) {}

            // 4. Discover & verify Web Series from ProphecyVerse catalog
            try {
                val seriesSearch = prophecyApi.searchAnime("series", limit = 10)
                if (seriesSearch.isSuccessful) {
                    seriesSearch.body()?.results?.forEach { item ->
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
                }
            } catch (_: Exception) {}

            animeDao.upsertAnimeList(entriesToUpsert)
            animeDao.insertAliases(aliasesToInsert)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Database-first search:
     * 1. Query remote sources (AniList search + ProphecyVerse catalog search).
     * 2. Transactionally write validated entries into Room and create SearchResultRef links.
     * 3. The UI observes Room for the final ranking.
     */
    suspend fun searchAnime(query: String): Result<Int> = withContext(Dispatchers.IO) {
        val normQuery = TitleMatcher.normalize(query)
        if (normQuery.isBlank()) return@withContext Result.success(0)

        try {
            // A. Search ProphecyVerse playable catalog
            val prophecyResp = try {
                prophecyApi.searchAnime(query = query, limit = 20)
            } catch (e: Exception) {
                null
            }
            val prophecyItems = if (prophecyResp?.isSuccessful == true) {
                prophecyResp.body()?.results ?: emptyList()
            } else emptyList()

            // B. Search AniList metadata
            val anilistReq = AniListGraphQLRequest(
                query = AniListQueries.SEARCH_ANIME,
                variables = mapOf("search" to query, "page" to 1, "perPage" to 20)
            )
            val aniListResp = try {
                aniListApi.postQuery(anilistReq)
            } catch (e: Exception) {
                null
            }
            val aniListItems = if (aniListResp?.isSuccessful == true) {
                aniListResp.body()?.data?.page?.media ?: emptyList()
            } else emptyList()

            val entriesToUpsert = mutableListOf<AnimeEntry>()
            val aliasesToInsert = mutableListOf<AnimeAlias>()
            val resultRefs = mutableListOf<SearchResultRef>()
            var rank = 0

            // Match conservative pairings
            val matchedProphecyIds = mutableSetOf<String>()

            // 1. Process AniList items first and match against ProphecyVerse results
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
                    updatedAt = System.currentTimeMillis()
                )
                entriesToUpsert.add(entry)

                // Aliases
                media.title?.romaji?.let { aliasesToInsert.add(AnimeAlias(animeId = animeId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                media.title?.english?.let { aliasesToInsert.add(AnimeAlias(animeId = animeId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                media.title?.native?.let { aliasesToInsert.add(AnimeAlias(animeId = animeId, alias = TitleMatcher.normalize(it), originalAlias = it)) }
                media.synonyms?.forEach { syn ->
                    aliasesToInsert.add(AnimeAlias(animeId = animeId, alias = TitleMatcher.normalize(syn), originalAlias = syn))
                }

                resultRefs.add(
                    SearchResultRef(
                        normalizedQuery = normQuery,
                        animeId = animeId,
                        rankIndex = rank++
                    )
                )
            }

            // 2. Add remaining unmatched ProphecyVerse items as playable entries
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
                    isPlayable = true,
                    updatedAt = System.currentTimeMillis()
                )
                entriesToUpsert.add(entry)
                aliasesToInsert.add(
                    AnimeAlias(
                        animeId = pItem.id,
                        alias = TitleMatcher.normalize(pItem.title),
                        originalAlias = pItem.title
                    )
                )
                resultRefs.add(
                    SearchResultRef(
                        normalizedQuery = normQuery,
                        animeId = pItem.id,
                        rankIndex = rank++
                    )
                )
            }

            // Write transactionally into Room
            animeDao.upsertAnimeList(entriesToUpsert)
            animeDao.insertAliases(aliasesToInsert)

            searchDao.clearSearchResultsForQuery(normQuery)
            searchDao.insertSearchResultRefs(resultRefs)
            searchDao.upsertSearchQuery(
                SearchQuery(
                    query = normQuery,
                    totalResults = resultRefs.size
                )
            )

            Result.success(resultRefs.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Refreshes details and seasons/episodes for a given anime.
     * Persists seasons and episodes to Room so the UI observes Room.
     */
    suspend fun refreshAnimeDetails(animeId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var currentEntry = animeDao.getAnimeById(animeId)
            var backendId = currentEntry?.backendAnimeId ?: if (animeId.startsWith("anilist_")) null else animeId

            // Explicit stable mapping for One Piece (AniList ID 21) to 1999 Anime backend ID
            if (animeId == "anilist_${TitleMatcher.ONE_PIECE_ANILIST_ID}" ||
                currentEntry?.aniListId == TitleMatcher.ONE_PIECE_ANILIST_ID ||
                (currentEntry?.preferredTitle?.equals("One Piece", ignoreCase = true) == true &&
                 currentEntry?.format?.contains("live", ignoreCase = true) != true)
            ) {
                backendId = TitleMatcher.ONE_PIECE_ANIME_BACKEND_ID
                if (currentEntry != null) {
                    currentEntry = currentEntry.copy(backendAnimeId = backendId, isPlayable = true)
                    animeDao.upsertAnime(currentEntry)
                    animeDao.upsertMapping(
                        CatalogMapping(
                            aniListId = TitleMatcher.ONE_PIECE_ANILIST_ID,
                            backendAnimeId = backendId,
                            confidence = 1.0f
                        )
                    )
                }
            }

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
                    } catch (e: Exception) { null }

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

            // Fetch ProphecyVerse details and episodes if backendId is available
            if (backendId != null) {
                val detailResp = try {
                    prophecyApi.getAnimeDetails(backendId)
                } catch (e: Exception) { null }

                val seasonsList = mutableListOf<SeasonEntry>()
                val episodesList = mutableListOf<EpisodeEntry>()

                if (detailResp?.isSuccessful == true && detailResp.body() != null) {
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
                        isPlayable = true,
                        updatedAt = System.currentTimeMillis()
                    )
                    animeDao.upsertAnime(updatedEntry)

                    body.seasons?.forEach { s ->
                        val sNum = if (s.number > 0) s.number else 1
                        val seasonKey = "${animeId}_s$sNum"
                        seasonsList.add(
                            SeasonEntry(
                                id = seasonKey,
                                animeId = animeId,
                                seasonNumber = sNum,
                                title = "Season $sNum"
                            )
                        )
                        s.episodes?.forEach { ep ->
                            // Fallback: If ep.season is 0 or negative, associate with current season sNum
                            val epSeason = if (ep.season > 0) ep.season else sNum
                            episodesList.add(
                                EpisodeEntry(
                                    id = ep.id,
                                    animeId = animeId,
                                    seasonNumber = epSeason,
                                    episodeNumber = ep.number,
                                    title = ep.title ?: "Episode ${ep.number}",
                                    overview = ep.overview
                                )
                            )
                        }
                    }
                }

                // Dedicated episodes endpoint fallback: if details had no episodes, query /episodes
                if (episodesList.isEmpty()) {
                    val epResp = try {
                        prophecyApi.getAnimeEpisodes(backendId)
                    } catch (e: Exception) { null }

                    if (epResp?.isSuccessful == true && !epResp.body().isNullOrEmpty()) {
                        val dtoList = epResp.body()!!
                        val seasonsSet = mutableSetOf<Int>()
                        val defaultSeason = seasonsList.firstOrNull()?.seasonNumber ?: 1
                        dtoList.forEach { ep ->
                            val sNum = if (ep.season > 0) ep.season else defaultSeason
                            seasonsSet.add(sNum)
                            episodesList.add(
                                EpisodeEntry(
                                    id = ep.id,
                                    animeId = animeId,
                                    seasonNumber = sNum,
                                    episodeNumber = ep.number,
                                    title = ep.title ?: "Episode ${ep.number}",
                                    overview = ep.overview
                                )
                            )
                        }
                        seasonsSet.sorted().forEach { sNum ->
                            if (seasonsList.none { it.seasonNumber == sNum }) {
                                seasonsList.add(
                                    SeasonEntry(
                                        id = "${animeId}_s$sNum",
                                        animeId = animeId,
                                        seasonNumber = sNum,
                                        title = "Season $sNum"
                                    )
                                )
                            }
                        }
                    }
                }

                // Ensure every season present in episodesList has a corresponding SeasonEntry
                episodesList.map { it.seasonNumber }.distinct().sorted().forEach { sNum ->
                    if (seasonsList.none { it.seasonNumber == sNum }) {
                        seasonsList.add(
                            SeasonEntry(
                                id = "${animeId}_s$sNum",
                                animeId = animeId,
                                seasonNumber = sNum,
                                title = "Season $sNum"
                            )
                        )
                    }
                }

                // If seasons still empty but episodes exist, add default Season 1
                if (seasonsList.isEmpty() && episodesList.isNotEmpty()) {
                    seasonsList.add(
                        SeasonEntry(
                            id = "${animeId}_s1",
                            animeId = animeId,
                            seasonNumber = 1,
                            title = "Season 1"
                        )
                    )
                }

                if (seasonsList.isNotEmpty()) {
                    episodeDao.upsertSeasons(seasonsList)
                }
                if (episodesList.isNotEmpty()) {
                    episodeDao.upsertEpisodes(episodesList)
                }
            }

            // Also enrich metadata via AniList if available
            if (currentEntry?.aniListId != null) {
                val alResp = try {
                    aniListApi.postQuery(
                        AniListGraphQLRequest(
                            query = AniListQueries.MEDIA_DETAILS,
                            variables = mapOf("id" to currentEntry.aniListId)
                        )
                    )
                } catch (e: Exception) { null }

                if (alResp?.isSuccessful == true && alResp.body()?.data?.media != null) {
                    val m = alResp.body()!!.data!!.media!!
                    val enriched = (animeDao.getAnimeById(animeId) ?: currentEntry).copy(
                        titleRomaji = m.title?.romaji,
                        titleEnglish = m.title?.english,
                        titleNative = m.title?.native,
                        synopsis = m.description ?: currentEntry.synopsis,
                        bannerUrl = m.bannerImage ?: currentEntry.bannerUrl,
                        score = m.averageScore?.let { it / 10.0 } ?: currentEntry.score,
                        studios = m.studios?.nodes?.firstOrNull()?.name ?: currentEntry.studios,
                        episodeCount = m.episodes ?: currentEntry.episodeCount,
                        duration = m.duration ?: currentEntry.duration
                    )
                    animeDao.upsertAnime(enriched)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches source candidates for an episode.
     * Route D: GET /api/v1/anime/{anime_id}/episodes/{episode_id}/full
     * Kept in-memory only; never written to Room.
     */
    suspend fun fetchEpisodeSources(
        backendAnimeId: String,
        episodeId: String
    ): Result<List<ProphecySourceDto>> = withContext(Dispatchers.IO) {
        try {
            val response = prophecyApi.getEpisodeFull(backendAnimeId, episodeId)
            if (!response.isSuccessful || response.body() == null) {
                return@withContext Result.failure(Exception("Failed to get episode sources: ${response.code()}"))
            }

            val sources = response.body()?.sources ?: emptyList()
            memorySourcesCache[episodeId] = sources
            Result.success(sources)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resolves direct playback URL.
     * Route E: The ONE AND ONLY playback route:
     * POST /api/v1/play/direct
     * Body: { "source_id": "..." }
     * Kept in-memory only; never persisted or logged.
     */
    suspend fun resolveDirectPlayback(sourceId: String): Result<DirectPlayData> = withContext(Dispatchers.IO) {
        try {
            val response = prophecyApi.resolveDirectPlay(DirectPlayRequest(sourceId = sourceId))
            if (!response.isSuccessful || response.body() == null) {
                return@withContext Result.failure(Exception("Direct playback resolution failed: ${response.code()}"))
            }

            val body = response.body()!!
            if (!body.success || body.data == null) {
                return@withContext Result.failure(Exception("Source not compatible or unverified"))
            }

            Result.success(body.data)
        } catch (e: Exception) {
            Result.failure(e)
        }
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

            val history = PlaybackHistory(
                id = animeId,
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
}
