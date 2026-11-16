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
 * for resolved Stream URL caching using the canonical `stream_sources` table:
 * `/v1/tables`, `/v1/tables/stream_sources`, `/v1/tables/stream_sources/{id}`,
 * `/v1/tables/stream_sources/upsert`.
 */
interface CloudflareD1ApiService {

    @GET("tables")
    suspend fun listTables(): Response<D1TablesResponse>

    @GET("tables/stream_sources")
    suspend fun listStreamSources(
        @Query("where") where: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<D1ListResponse<D1StreamSourceRecordDto>>

    @GET("tables/stream_sources/{id}")
    suspend fun getStreamSource(
        @Path("id") id: String
    ): Response<D1RecordResponse<D1StreamSourceRecordDto>>

    @POST("tables/stream_sources/upsert")
    suspend fun upsertStreamSource(
        @Body record: D1StreamSourceRecordDto
    ): Response<D1RecordResponse<D1StreamSourceRecordDto>>

    @DELETE("tables/stream_sources/{id}")
    suspend fun deleteStreamSource(
        @Path("id") id: String
    ): Response<D1RecordResponse<D1StreamSourceRecordDto>>
}
