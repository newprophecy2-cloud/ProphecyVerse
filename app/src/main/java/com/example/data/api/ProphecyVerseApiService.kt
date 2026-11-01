package com.example.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

interface ProphecyVerseApiService {

    @GET("health")
    suspend fun getHealth(): Response<Map<String, Any>>

    @GET("ready")
    suspend fun getReadiness(): Response<Map<String, Any>>

    @GET("api/v1/diagnostics")
    suspend fun getDiagnostics(): Response<Map<String, Any>>
}

data class BackendHealthReport(
    val endpoint: String,
    val isReachable: Boolean,
    val httpCode: Int,
    val latencyMs: Long,
    val payload: Map<String, Any> = emptyMap(),
    val errorMessage: String? = null
)

object ApiClient {
    private var customBaseUrl: String = "https://ais-dev-3vlscnped6if4eaesce5h3-992210285883.asia-east1.run.app/"

    val activeBaseUrl: String
        get() = customBaseUrl

    fun updateBaseUrl(newUrl: String) {
        var formatted = newUrl.trim()
        if (!formatted.endsWith("/")) {
            formatted += "/"
        }
        customBaseUrl = formatted
        cachedService = null
    }

    private var cachedService: ProphecyVerseApiService? = null

    fun getService(): ProphecyVerseApiService {
        val current = cachedService
        if (current != null) return current

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(customBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()

        val service = retrofit.create(ProphecyVerseApiService::class.java)
        cachedService = service
        return service
    }
}
