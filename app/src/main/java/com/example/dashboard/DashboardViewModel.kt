package com.example.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.BackendHealthReport
import com.example.data.model.InstallationRecord
import com.example.data.model.NoticeItem
import com.example.data.model.VersionConfig
import com.example.data.repository.ActivityLogRepository
import com.example.data.repository.BackendRepository
import com.example.data.repository.InstallationRepository
import com.example.data.repository.InstallationStats
import com.example.data.repository.NoticeRepository
import com.example.data.repository.NotificationRepository
import com.example.data.repository.UserRepository
import com.example.data.repository.VersionControlRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isLoading: Boolean = false,
    val installationStats: InstallationStats = InstallationStats(),
    val recentInstallations: List<InstallationRecord> = emptyList(),
    val totalUsersCount: Int = 0,
    val activeUsersCount: Int = 0,
    val noticesCount: Int = 0,
    val activeNotices: List<NoticeItem> = emptyList(),
    val queuedNotificationsCount: Int = 0,
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
    private val installationRepo: InstallationRepository = InstallationRepository(),
    private val userRepo: UserRepository = UserRepository(),
    private val notificationRepo: NotificationRepository = NotificationRepository(),
    private val logRepo: ActivityLogRepository = ActivityLogRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState(isLoading = true))
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    private var installationsListener: ListenerRegistration? = null
    private var usersListener: ListenerRegistration? = null
    private var hasInitialInstallationSnapshot = false
    private var hasInitialUserSnapshot = false
    private var liveReloadJob: Job? = null

    init {
        refreshDashboard()
        startRealtimeRefreshListeners()
    }

    private fun startRealtimeRefreshListeners() {
        val firestore = runCatching { FirebaseFirestore.getInstance() }.getOrNull() ?: return
        installationsListener = firestore.collection("installations").addSnapshotListener { snapshot, error ->
            if (error != null) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Live installation updates unavailable: ${error.localizedMessage ?: "Firestore read failed"}"
                )
            } else if (snapshot != null) {
                if (hasInitialInstallationSnapshot) scheduleLiveRefresh() else hasInitialInstallationSnapshot = true
            }
        }
        usersListener = firestore.collection("users").addSnapshotListener { snapshot, error ->
            if (error != null) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Live user updates unavailable: ${error.localizedMessage ?: "Firestore read failed"}"
                )
            } else if (snapshot != null) {
                if (hasInitialUserSnapshot) scheduleLiveRefresh() else hasInitialUserSnapshot = true
            }
        }
    }

    private fun scheduleLiveRefresh() {
        liveReloadJob?.cancel()
        liveReloadJob = viewModelScope.launch {
            delay(500)
            refreshDashboard()
        }
    }

    fun refreshDashboard() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val healthDeferred = async { backendRepo.checkHealth() }
            val readyDeferred = async { backendRepo.checkReadiness() }
            val noticesDeferred = async { noticeRepo.getNotices() }
            val versionDeferred = async { versionRepo.getVersionConfig() }
            val instStatsDeferred = async { installationRepo.getInstallationStats() }
            val recentInstDeferred = async { installationRepo.getInstallationsPage(pageSize = 5) }
            val usersDeferred = async { userRepo.getUsers(pageSize = 100) }
            val userCountsDeferred = async { userRepo.getUserCounts() }
            val notifsDeferred = async { notificationRepo.getNotifications(limit = 20) }

            val health = healthDeferred.await()
            val ready = readyDeferred.await()
            val noticesResult = noticesDeferred.await()
            val versionResult = versionDeferred.await()
            val instStatsResult = instStatsDeferred.await()
            val recentInstResult = recentInstDeferred.await()
            val usersResult = usersDeferred.await()
            val userCountsResult = userCountsDeferred.await()
            val notifsResult = notifsDeferred.await()

            val notices = noticesResult.getOrNull() ?: emptyList()
            val version = versionResult.getOrNull() ?: VersionConfig()
            val instStats = instStatsResult.getOrNull() ?: InstallationStats()
            val recentInst = recentInstResult.getOrNull()?.items ?: emptyList()
            val users = usersResult.getOrNull() ?: emptyList()
            val userCounts = userCountsResult.getOrNull()
            val notifs = notifsResult.getOrNull() ?: emptyList()

            val isFirebaseOk = instStatsResult.isSuccess && recentInstResult.isSuccess && userCountsResult.isSuccess
            val coreReadErrors = listOfNotNull(
                instStatsResult.exceptionOrNull()?.localizedMessage?.let { "installations: $it" },
                recentInstResult.exceptionOrNull()?.localizedMessage?.let { "recent installations: $it" },
                userCountsResult.exceptionOrNull()?.localizedMessage?.let { "users: $it" }
            )

            _uiState.value = DashboardUiState(
                isLoading = false,
                installationStats = instStats,
                recentInstallations = recentInst,
                totalUsersCount = userCounts?.total ?: users.size,
                activeUsersCount = userCounts?.active ?: users.count { it.isActive },
                noticesCount = notices.size,
                activeNotices = notices.filter { it.isEffectivelyActive },
                queuedNotificationsCount = notifs.count { it.status == "queued" },
                versionConfig = version,
                healthReport = health,
                readinessReport = ready,
                isFirebaseConnected = isFirebaseOk,
                lastRefreshedAt = System.currentTimeMillis(),
                errorMessage = when {
                    coreReadErrors.isNotEmpty() -> "Firestore data read failed — ${coreReadErrors.joinToString("; ")}. Check admin authorization and Firestore rules."
                    !isFirebaseOk && !health.isReachable -> "Backend and Firebase offline. Check network and authorized admin access."
                    else -> null
                }
            )
        }
    }

    override fun onCleared() {
        installationsListener?.remove()
        usersListener?.remove()
        liveReloadJob?.cancel()
        super.onCleared()
    }
}
