package com.example.data.d1

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface strictly bound to verified routes exposed by the
 * Prophecy-verse-DB Cloudflare Worker (`/v1/tables`, `/v1/tables/:table`,
 * `/v1/tables/:table/:id`, `/v1/tables/:table/upsert`).
 */
interface CloudflareD1ApiService {

    @GET("tables")
    suspend fun listTables(): Response<D1TablesResponse>

    // 1. anime_entries
    @GET("tables/anime_entries")
    suspend fun listAnimeEntries(
        @Query("where") where: String? = null,
        @Query("columns") columns: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1AnimeEntryDto>>

    @GET("tables/anime_entries/{id}")
    suspend fun getAnimeEntry(@Path("id") id: String): Response<D1RecordResponse<D1AnimeEntryDto>>

    @POST("tables/anime_entries/upsert")
    suspend fun upsertAnimeEntry(@Body entry: D1AnimeEntryDto): Response<D1RecordResponse<D1AnimeEntryDto>>

    // 2. anime_aliases
    @GET("tables/anime_aliases")
    suspend fun listAnimeAliases(
        @Query("where") where: String? = null,
        @Query("columns") columns: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1AnimeAliasDto>>

    @POST("tables/anime_aliases")
    suspend fun createAnimeAlias(@Body alias: D1AnimeAliasInsertDto): Response<D1RecordResponse<D1AnimeAliasDto>>

    // 3. catalog_mappings
    @GET("tables/catalog_mappings")
    suspend fun listCatalogMappings(
        @Query("where") where: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1CatalogMappingDto>>

    @GET("tables/catalog_mappings/{aniListId}")
    suspend fun getCatalogMapping(@Path("aniListId") aniListId: Int): Response<D1RecordResponse<D1CatalogMappingDto>>

    @POST("tables/catalog_mappings/upsert")
    suspend fun upsertCatalogMapping(@Body mapping: D1CatalogMappingDto): Response<D1RecordResponse<D1CatalogMappingDto>>

    // 4. season_entries
    @GET("tables/season_entries")
    suspend fun listSeasonEntries(
        @Query("where") where: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1SeasonEntryDto>>

    @POST("tables/season_entries/upsert")
    suspend fun upsertSeasonEntry(@Body season: D1SeasonEntryDto): Response<D1RecordResponse<D1SeasonEntryDto>>

    // 5. episode_entries
    @GET("tables/episode_entries")
    suspend fun listEpisodeEntries(
        @Query("where") where: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1EpisodeEntryDto>>

    @POST("tables/episode_entries/upsert")
    suspend fun upsertEpisodeEntry(@Body episode: D1EpisodeEntryDto): Response<D1RecordResponse<D1EpisodeEntryDto>>

    // 6. stream_containers
    @GET("tables/stream_containers/{scopeKey}")
    suspend fun getStreamContainer(@Path("scopeKey") scopeKey: String): Response<D1RecordResponse<D1StreamContainerDto>>

    @GET("tables/stream_containers")
    suspend fun listStreamContainers(
        @Query("where") where: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1StreamContainerDto>>

    @POST("tables/stream_containers/upsert")
    suspend fun upsertStreamContainer(@Body container: D1StreamContainerDto): Response<D1RecordResponse<D1StreamContainerDto>>

    // 7. stream_seasons
    @GET("tables/stream_seasons")
    suspend fun listStreamSeasons(
        @Query("where") where: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1StreamSeasonDto>>

    @POST("tables/stream_seasons/upsert")
    suspend fun upsertStreamSeason(@Body season: D1StreamSeasonDto): Response<D1RecordResponse<D1StreamSeasonDto>>

    // 8. stream_episodes
    @GET("tables/stream_episodes")
    suspend fun listStreamEpisodes(
        @Query("where") where: String? = null,
        @Query("sort") sort: String? = null,
        @Query("order") order: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1StreamEpisodeDto>>

    @POST("tables/stream_episodes/upsert")
    suspend fun upsertStreamEpisode(@Body episode: D1StreamEpisodeDto): Response<D1RecordResponse<D1StreamEpisodeDto>>

    // 9. stream_providers
    @GET("tables/stream_providers")
    suspend fun listStreamProviders(
        @Query("where") where: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1StreamProviderDto>>

    @POST("tables/stream_providers/upsert")
    suspend fun upsertStreamProvider(@Body provider: D1StreamProviderDto): Response<D1RecordResponse<D1StreamProviderDto>>

    // 10. stream_sources
    @GET("tables/stream_sources")
    suspend fun listStreamSources(
        @Query("where") where: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1StreamSourceDto>>

    @GET("tables/stream_sources/{sourceDocKey}")
    suspend fun getStreamSource(@Path("sourceDocKey") sourceDocKey: String): Response<D1RecordResponse<D1StreamSourceDto>>

    @POST("tables/stream_sources/upsert")
    suspend fun upsertStreamSource(@Body source: D1StreamSourceDto): Response<D1RecordResponse<D1StreamSourceDto>>
}
