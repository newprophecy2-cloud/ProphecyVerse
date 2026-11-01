package com.example.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.BackendHealthReport
import com.example.data.model.NoticeItem
import com.example.data.model.VersionConfig
import com.example.data.repository.ActivityLogRepository
import com.example.data.repository.BackendRepository
import com.example.data.repository.NoticeRepository
import com.example.data.repository.VersionControlRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isLoading: Boolean = false,
    val noticesCount: Int = 0,
    val activeNotices: List<NoticeItem> = emptyList(),
    val versionConfig: VersionConfig? = null,
    val healthReport: BackendHealthReport? = null,
    val readinessReport: BackendHealthReport? = null,
    val isFirebaseConnected: Boolean = false,
    val lastRefreshedAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)

class DashboardViewModel(
    private val backendRepo: BackendRepository = BackendRepository(),
    private val noticeRepo: NoticeRepository = NoticeRepository(),
    private val versionRepo: VersionControlRepository = VersionControlRepository(),
    private val logRepo: ActivityLogRepository = ActivityLogRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState(isLoading = true))
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        refreshDashboard()
    }

    fun refreshDashboard() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val healthDeferred = async { backendRepo.checkHealth() }
            val readyDeferred = async { backendRepo.checkReadiness() }
            val noticesDeferred = async { noticeRepo.getNotices() }
            val versionDeferred = async { versionRepo.getVersionConfig() }

            val health = healthDeferred.await()
            val ready = readyDeferred.await()
            val noticesResult = noticesDeferred.await()
            val versionResult = versionDeferred.await()

            val notices = noticesResult.getOrNull() ?: emptyList()
            val version = versionResult.getOrNull() ?: VersionConfig()
            val isFirebaseOk = noticesResult.isSuccess || versionResult.isSuccess

            _uiState.value = DashboardUiState(
                isLoading = false,
                noticesCount = notices.size,
                activeNotices = notices.filter { it.published },
                versionConfig = version,
                healthReport = health,
                readinessReport = ready,
                isFirebaseConnected = isFirebaseOk,
                lastRefreshedAt = System.currentTimeMillis(),
                errorMessage = if (!isFirebaseOk && !health.isReachable) {
                    "Backend and Firebase offline. Configure endpoints or check network."
                } else null
            )
        }
    }
}
