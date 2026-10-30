package com.example.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ProphecyVerseApiService {
    @GET("/api/v1/anime/search")
    suspend fun searchAnime(
        @Query("q") query: String,
        @Query("limit") limit: Int? = 20,
        @Query("cursor") cursor: String? = null
    ): Response<ProphecySearchResponse>

    @GET("/api/v1/anime/{anime_id}")
    suspend fun getAnimeDetails(
        @Path("anime_id") animeId: String
    ): Response<ProphecyAnimeDetailsResponse>

    @GET("/api/v1/anime/{anime_id}/episodes")
    suspend fun getAnimeEpisodes(
        @Path("anime_id") animeId: String
    ): Response<List<ProphecyEpisodeDto>>

    @GET("/api/v1/anime/{anime_id}/episodes/{episode_id}/full")
    suspend fun getEpisodeFull(
        @Path("anime_id") animeId: String,
        @Path("episode_id") episodeId: String
    ): Response<ProphecyEpisodeFullResponse>

    // The ONE AND ONLY playback route allowed
    @POST("/api/v1/play/direct")
    suspend fun resolveDirectPlay(
        @Body request: DirectPlayRequest
    ): Response<DirectPlayResponse>
}
