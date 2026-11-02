package com.example.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.BackendHealthReport
import com.example.data.repository.BackendRepository
import com.example.data.repository.InstallationRepository
import com.example.data.repository.InstallationStats
import com.example.data.repository.WatchAnalyticsData
import com.example.data.repository.WatchAnalyticsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AnalyticsUiState(
    val isLoading: Boolean = false,
    val healthReport: BackendHealthReport? = null,
    val readinessReport: BackendHealthReport? = null,
    val diagnosticsReport: BackendHealthReport? = null,
    val installationStats: InstallationStats = InstallationStats(),
    val watchAnalytics: WatchAnalyticsData = WatchAnalyticsData(),
    val latencyHistory: List<Long> = emptyList(),
    val errorMessage: String? = null
)

class AnalyticsViewModel(
    private val backendRepository: BackendRepository = BackendRepository(),
    private val installationRepository: InstallationRepository = InstallationRepository(),
    private val watchAnalyticsRepository: WatchAnalyticsRepository = WatchAnalyticsRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalyticsUiState(isLoading = true))
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()

    init {
        fetchAnalytics()
    }

    fun fetchAnalytics() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val healthDeferred = async { backendRepository.checkHealth() }
            val readyDeferred = async { backendRepository.checkReadiness() }
            val diagDeferred = async { backendRepository.fetchDiagnostics() }
            val instStatsDeferred = async { installationRepository.getInstallationStats() }
            val watchAnalyticsDeferred = async { watchAnalyticsRepository.getWatchAnalytics() }

            val h = healthDeferred.await()
            val r = readyDeferred.await()
            val d = diagDeferred.await()
            val instStatsResult = instStatsDeferred.await()
            val watchAnalyticsResult = watchAnalyticsDeferred.await()
            val instStats = instStatsResult.getOrNull() ?: InstallationStats()
            val watchData = watchAnalyticsResult.getOrNull() ?: WatchAnalyticsData()

            val currentHistory = _uiState.value.latencyHistory.toMutableList()
            if (h.isReachable) {
                currentHistory.add(h.latencyMs)
                if (currentHistory.size > 10) currentHistory.removeAt(0)
            }

            _uiState.value = AnalyticsUiState(
                isLoading = false,
                healthReport = h,
                readinessReport = r,
                diagnosticsReport = d,
                installationStats = instStats,
                watchAnalytics = watchData,
                latencyHistory = currentHistory,
                errorMessage = listOfNotNull(
                    instStatsResult.exceptionOrNull()?.localizedMessage?.let { "installations: $it" },
                    watchAnalyticsResult.exceptionOrNull()?.localizedMessage?.let { "watch history: $it" }
                ).takeIf { it.isNotEmpty() }?.joinToString(prefix = "Firestore analytics read failed — ", separator = "; ")
                    ?: if (!h.isReachable && !r.isReachable && instStats.totalCount == 0) {
                        "Backend probes unreachable and Firebase telemetry is empty."
                    } else null
            )
        }
    }
}
