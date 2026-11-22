package com.example.core.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object BackendConfig {
    const val BASE_URL: String = "https://prophecy-verse-v3-backend.onrender.com/"
    const val HOST: String = "prophecy-verse-v3-backend.onrender.com"
}

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    data class Failure(
        val code: String?,
        val message: String,
        val retryable: Boolean,
        val httpStatus: Int?
    ) : ApiResult<Nothing>
}

@JsonClass(generateAdapter = true)
data class V3ErrorBody(
    @Json(name = "status") val status: String? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "code") val code: String? = null,
    @Json(name = "message") val message: String? = null
)

object V3ErrorMapper {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(V3ErrorBody::class.java)

    fun parseFailure(httpStatus: Int?, rawErrorBody: String?, fallbackMessage: String): ApiResult.Failure {
        val parsed = rawErrorBody?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { adapter.fromJson(raw) }.getOrNull()
        }
        val code = parsed?.code ?: parsed?.error
        val message = parsed?.message?.takeIf { it.isNotBlank() } ?: fallbackMessage
        val retryable = when (httpStatus) {
            408, 429, 500, 502, 503, 504 -> true
            400, 401, 403, 404 -> false
            null -> true
            else -> httpStatus >= 500
        }
        return ApiResult.Failure(
            code = code,
            message = message,
            retryable = retryable,
            httpStatus = httpStatus
        )
    }

    fun userFriendlyMessage(failure: ApiResult.Failure): String = when {
        failure.code == "SESSION_REQUIRED" || failure.code == "SESSION_INVALID" || failure.code == "SESSION_EXPIRED" ->
            "Your browsing session expired. Refreshing results..."
        failure.httpStatus == 403 || failure.code == "ACCESS_DENIED" ->
            "Source access was denied by the upstream provider (HTTP 403)."
        failure.httpStatus == 404 || failure.code == "NOT_FOUND" || failure.code == "EPISODE_NOT_FOUND" ->
            "This anime or episode is not available on the server."
        failure.httpStatus == 429 ->
            "Too many requests right now. Please try again in a moment."
        failure.httpStatus != null && failure.httpStatus >= 500 ->
            "Prophecy Verse server is temporarily unavailable (HTTP ${failure.httpStatus})."
        failure.httpStatus == null ->
            "Network unavailable. Check your connection and retry."
        else -> failure.message
    }
}
