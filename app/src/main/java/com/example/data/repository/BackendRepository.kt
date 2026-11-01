package com.example.data.repository

import com.example.data.api.ApiClient
import com.example.data.api.BackendHealthReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackendRepository {

    suspend fun checkHealth(): BackendHealthReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val response = ApiClient.getService().getHealth()
            val latency = System.currentTimeMillis() - start
            BackendHealthReport(
                endpoint = "/health",
                isReachable = response.isSuccessful,
                httpCode = response.code(),
                latencyMs = latency,
                payload = response.body() ?: emptyMap(),
                errorMessage = if (!response.isSuccessful) "HTTP ${response.code()}: ${response.message()}" else null
            )
        } catch (e: Exception) {
            BackendHealthReport(
                endpoint = "/health",
                isReachable = false,
                httpCode = 0,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = e.localizedMessage ?: "Connection failed"
            )
        }
    }

    suspend fun checkReadiness(): BackendHealthReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val response = ApiClient.getService().getReadiness()
            val latency = System.currentTimeMillis() - start
            BackendHealthReport(
                endpoint = "/ready",
                isReachable = response.isSuccessful,
                httpCode = response.code(),
                latencyMs = latency,
                payload = response.body() ?: emptyMap(),
                errorMessage = if (!response.isSuccessful) "HTTP ${response.code()}: ${response.message()}" else null
            )
        } catch (e: Exception) {
            BackendHealthReport(
                endpoint = "/ready",
                isReachable = false,
                httpCode = 0,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = e.localizedMessage ?: "Connection failed"
            )
        }
    }

    suspend fun fetchDiagnostics(): BackendHealthReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val response = ApiClient.getService().getDiagnostics()
            val latency = System.currentTimeMillis() - start
            BackendHealthReport(
                endpoint = "/api/v1/diagnostics",
                isReachable = response.isSuccessful,
                httpCode = response.code(),
                latencyMs = latency,
                payload = response.body() ?: emptyMap(),
                errorMessage = if (!response.isSuccessful) "HTTP ${response.code()}: ${response.message()}" else null
            )
        } catch (e: Exception) {
            BackendHealthReport(
                endpoint = "/api/v1/diagnostics",
                isReachable = false,
                httpCode = 0,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = e.localizedMessage ?: "Connection failed"
            )
        }
    }
}
