package com.example.data.d1

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface strictly bound to the Prophecy-verse-DB Cloudflare Worker routes
 * for resolved Stream URL caching only (`/v1/tables`, `/v1/tables/stream_urls`,
 * `/v1/tables/stream_urls/:id`, `/v1/tables/stream_urls/upsert`).
 */
interface CloudflareD1ApiService {

    @GET("tables")
    suspend fun listTables(): Response<D1TablesResponse>

    @GET("tables/stream_urls")
    suspend fun listStreamUrls(
        @Query("where") where: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1StreamUrlRecordDto>>

    @GET("tables/stream_urls/{id}")
    suspend fun getStreamUrl(
        @Path("id") id: String
    ): Response<D1RecordResponse<D1StreamUrlRecordDto>>

    @POST("tables/stream_urls/upsert")
    suspend fun upsertStreamUrl(
        @Body record: D1StreamUrlRecordDto
    ): Response<D1RecordResponse<D1StreamUrlRecordDto>>

    @DELETE("tables/stream_urls/{id}")
    suspend fun deleteStreamUrl(
        @Path("id") id: String
    ): Response<D1RecordResponse<D1StreamUrlRecordDto>>
}
