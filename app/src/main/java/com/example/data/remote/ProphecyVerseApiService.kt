package com.example.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Prophecy Verse V3 Backend Retrofit Contract.
 * Base URL: https://prophecy-verse-v3-backend.onrender.com/
 */
interface ProphecyVerseApiService {
    @GET("/api/v1/anime/search")
    suspend fun searchAnime(
        @Query("q") query: String
    ): Response<V3SearchResponse>

    @GET("/api/v1/anime/{anime_id}")
    suspend fun getAnimeDetails(
        @Path("anime_id") animeId: String
    ): Response<V3AnimeDetailsResponse>

    @GET("/api/v1/anime/{anime_id}/seasons")
    suspend fun getAnimeSeasons(
        @Path("anime_id") animeId: String
    ): Response<V3SeasonsResponse>

    @GET("/api/v1/seasons/{season_id}/episodes")
    suspend fun getSeasonEpisodes(
        @Path("season_id") seasonId: String
    ): Response<V3EpisodesResponse>

    @GET("/api/v1/episodes/{episode_id}/sources")
    suspend fun getEpisodeSources(
        @Path("episode_id") episodeId: String
    ): Response<V3SourcesResponse>

    @POST("/api/v1/episodes/{episode_id}/resolve")
    suspend fun resolveEpisode(
        @Path("episode_id") episodeId: String,
        @Body request: V3ResolveRequest
    ): Response<V3ResolveResponse>
}
