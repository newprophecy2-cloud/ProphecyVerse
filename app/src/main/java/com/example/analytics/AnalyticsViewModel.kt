package com.example.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.BackendHealthReport
import com.example.data.repository.BackendRepository
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
    val latencyHistory: List<Long> = emptyList(),
    val errorMessage: String? = null
)

class AnalyticsViewModel(
    private val repository: BackendRepository = BackendRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalyticsUiState(isLoading = true))
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()

    init {
        fetchAnalytics()
    }

    fun fetchAnalytics() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val hDeferred = async { repository.checkHealth() }
            val rDeferred = async { repository.checkReadiness() }
            val dDeferred = async { repository.fetchDiagnostics() }

            val h = hDeferred.await()
            val r = rDeferred.await()
            val d = dDeferred.await()

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
                latencyHistory = currentHistory,
                errorMessage = if (!h.isReachable && !r.isReachable) "ProphecyVerse REST backend is currently unreachable. Check Settings." else null
            )
        }
    }
}
