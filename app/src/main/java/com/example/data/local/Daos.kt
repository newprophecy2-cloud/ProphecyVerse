package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AnimeDao {
    @Query("SELECT * FROM anime_entries WHERE isTrending = 1 ORDER BY score DESC, updatedAt DESC LIMIT 20")
    fun observeTrending(): Flow<List<AnimeEntry>>

    @Query("SELECT * FROM anime_entries WHERE isSeasonal = 1 ORDER BY score DESC, updatedAt DESC LIMIT 20")
    fun observeSeasonal(): Flow<List<AnimeEntry>>

    @Query("SELECT * FROM anime_entries WHERE isPopular = 1 ORDER BY score DESC, updatedAt DESC LIMIT 20")
    fun observePopular(): Flow<List<AnimeEntry>>

    @Query("SELECT * FROM anime_entries WHERE id = :id LIMIT 1")
    fun observeAnimeById(id: String): Flow<AnimeEntry?>

    @Query("SELECT * FROM anime_entries WHERE id = :id LIMIT 1")
    suspend fun getAnimeById(id: String): AnimeEntry?

    @Query("SELECT * FROM anime_entries WHERE backendAnimeId = :backendId LIMIT 1")
    suspend fun getAnimeByBackendId(backendId: String): AnimeEntry?

    @Query("SELECT * FROM anime_entries WHERE aniListId = :aniListId LIMIT 1")
    suspend fun getAnimeByAniListId(aniListId: Int): AnimeEntry?

    @Query("""
        SELECT DISTINCT a.* FROM anime_entries a
        LEFT JOIN anime_aliases al ON a.id = al.animeId
        WHERE a.preferredTitle LIKE '%' || :query || '%'
           OR a.titleEnglish LIKE '%' || :query || '%'
           OR a.titleRomaji LIKE '%' || :query || '%'
           OR al.alias LIKE '%' || :query || '%'
        ORDER BY a.score DESC, a.updatedAt DESC
        LIMIT 50
    """)
    fun searchLocal(query: String): Flow<List<AnimeEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnime(anime: AnimeEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnimeList(list: List<AnimeEntry>)

    @Query("UPDATE anime_entries SET isTrending = 0 WHERE isTrending = 1")
    suspend fun clearTrendingFlags()

    @Query("UPDATE anime_entries SET isSeasonal = 0 WHERE isSeasonal = 1")
    suspend fun clearSeasonalFlags()

    @Query("UPDATE anime_entries SET isPopular = 0 WHERE isPopular = 1")
    suspend fun clearPopularFlags()

    @Query("""
        DELETE FROM anime_entries 
        WHERE id NOT IN (SELECT animeId FROM watchlist_entries)
          AND id NOT IN (SELECT animeId FROM playback_history)
          AND isTrending = 0 AND isSeasonal = 0 AND isPopular = 0
    """)
    suspend fun clearUnsavedCache()

    // Aliases
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAliases(aliases: List<AnimeAlias>)

    @Query("SELECT * FROM anime_aliases WHERE animeId = :animeId")
    suspend fun getAliasesForAnime(animeId: String): List<AnimeAlias>

    // Catalog Mapping
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMapping(mapping: CatalogMapping)

    @Query("SELECT * FROM catalog_mappings WHERE aniListId = :aniListId LIMIT 1")
    suspend fun getMappingForAniList(aniListId: Int): CatalogMapping?
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM season_entries WHERE animeId = :animeId ORDER BY seasonNumber ASC")
    fun observeSeasons(animeId: String): Flow<List<SeasonEntry>>

    @Query("SELECT * FROM episode_entries WHERE animeId = :animeId AND seasonNumber = :seasonNumber ORDER BY episodeNumber ASC")
    fun observeEpisodes(animeId: String, seasonNumber: Int): Flow<List<EpisodeEntry>>

    @Query("SELECT * FROM episode_entries WHERE animeId = :animeId ORDER BY seasonNumber ASC, episodeNumber ASC")
    fun observeAllEpisodes(animeId: String): Flow<List<EpisodeEntry>>

    @Query("SELECT * FROM episode_entries WHERE id = :episodeId LIMIT 1")
    suspend fun getEpisodeById(episodeId: String): EpisodeEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeasons(seasons: List<SeasonEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(episodes: List<EpisodeEntry>)

    @Query("DELETE FROM episode_entries WHERE animeId = :animeId")
    suspend fun deleteEpisodesForAnime(animeId: String)

    @Query("DELETE FROM season_entries WHERE animeId = :animeId")
    suspend fun deleteSeasonsForAnime(animeId: String)
}

@Dao
interface SearchDao {
    @Query("""
        SELECT a.* FROM anime_entries a
        INNER JOIN search_result_refs r ON a.id = r.animeId
        WHERE r.normalizedQuery = :query
        ORDER BY r.rankIndex ASC
    """)
    fun observeSearchResults(query: String): Flow<List<AnimeEntry>>

    @Query("SELECT * FROM search_queries WHERE query = :query LIMIT 1")
    suspend fun getSearchQuery(query: String): SearchQuery?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchQuery(query: SearchQuery)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchResultRefs(refs: List<SearchResultRef>)

    @Query("DELETE FROM search_result_refs WHERE normalizedQuery = :query")
    suspend fun clearSearchResultsForQuery(query: String)

    @Query("DELETE FROM search_queries")
    suspend fun clearAllSearchQueries()

    @Query("DELETE FROM search_result_refs")
    suspend fun clearAllSearchResultRefs()
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist_entries ORDER BY updatedAt DESC")
    fun observeAllWatchlist(): Flow<List<WatchlistEntry>>

    @Query("SELECT * FROM watchlist_entries WHERE status = :status ORDER BY updatedAt DESC")
    fun observeWatchlistByStatus(status: String): Flow<List<WatchlistEntry>>

    @Query("SELECT * FROM watchlist_entries WHERE isFavorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<WatchlistEntry>>

    @Query("SELECT * FROM watchlist_entries WHERE animeId = :animeId LIMIT 1")
    fun observeWatchlistEntry(animeId: String): Flow<WatchlistEntry?>

    @Query("SELECT * FROM watchlist_entries WHERE animeId = :animeId LIMIT 1")
    suspend fun getWatchlistEntry(animeId: String): WatchlistEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchlistEntry(entry: WatchlistEntry)

    @Query("DELETE FROM watchlist_entries WHERE animeId = :animeId")
    suspend fun deleteWatchlistEntry(animeId: String)
}

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY lastPlayedAt DESC LIMIT 100")
    fun observeHistory(): Flow<List<PlaybackHistory>>

    @Query("""
        SELECT * FROM playback_history 
        WHERE completed = 0 AND positionMs > 5000 
        ORDER BY lastPlayedAt DESC 
        LIMIT 20
    """)
    fun observeContinueWatching(): Flow<List<PlaybackHistory>>

    @Query("SELECT * FROM playback_history WHERE animeId = :animeId AND episodeId = :episodeId LIMIT 1")
    suspend fun getHistoryForEpisode(animeId: String, episodeId: String): PlaybackHistory?

    @Query("SELECT * FROM playback_history WHERE animeId = :animeId ORDER BY lastPlayedAt DESC LIMIT 1")
    fun observeLastPlayedForAnime(animeId: String): Flow<PlaybackHistory?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(history: PlaybackHistory)

    @Query("DELETE FROM playback_history WHERE id = :id")
    suspend fun deleteHistory(id: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearAllHistory()
}

@Dao
interface UserPreferenceDao {
    @Query("SELECT prefValue FROM user_preferences WHERE prefKey = :key LIMIT 1")
    fun observePref(key: String): Flow<String?>

    @Query("SELECT prefValue FROM user_preferences WHERE prefKey = :key LIMIT 1")
    suspend fun getPref(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPref(pref: UserPreference)
}
