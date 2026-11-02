package com.example.installations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.InstallationRecord
import com.example.data.repository.ActivityLogRepository
import com.example.data.repository.InstallationDetailBundle
import com.example.data.repository.InstallationRepository
import com.example.data.repository.InstallationStats
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InstallationUiState(
    val isLoading: Boolean = false,
    val isLoadingStats: Boolean = false,
    val isLoadingMore: Boolean = false,
    val installations: List<InstallationRecord> = emptyList(),
    val stats: InstallationStats = InstallationStats(),
    val selectedDetailBundle: InstallationDetailBundle? = null,
    val searchQuery: String = "",
    val statusFilter: String = "ALL", // "ALL", "ACTIVE_NOW", "RECENT", "INACTIVE"
    val versionFilter: String = "ALL",
    val hasMore: Boolean = false,
    val isDeleting: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class InstallationViewModel(
    private val repository: InstallationRepository = InstallationRepository(),
    private val logRepository: ActivityLogRepository = ActivityLogRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(InstallationUiState(isLoading = true))
    val uiState: StateFlow<InstallationUiState> = _uiState.asStateFlow()

    private var lastSnapshot: DocumentSnapshot? = null
    private var searchDebounceJob: Job? = null

    init {
        refreshAll()
    }

    fun refreshAll() {
        loadStats()
        loadInstallations(reset = true)
    }

    fun loadStats() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingStats = true)
            val res = repository.getInstallationStats()
            if (res.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoadingStats = false,
                    stats = res.getOrNull() ?: InstallationStats()
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoadingStats = false)
            }
        }
    }

    fun loadInstallations(reset: Boolean = true) {
        viewModelScope.launch {
            if (reset) {
                lastSnapshot = null
                _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            } else {
                _uiState.value = _uiState.value.copy(isLoadingMore = true)
            }

            val current = _uiState.value
            val res = repository.getInstallationsPage(
                pageSize = 25,
                startAfter = if (reset) null else lastSnapshot,
                statusFilter = current.statusFilter,
                versionFilter = current.versionFilter,
                searchQuery = current.searchQuery
            )

            if (res.isSuccess) {
                val page = res.getOrNull()!!
                lastSnapshot = page.lastSnapshot
                val newItems = if (reset) page.items else (current.installations + page.items).distinctBy { it.id }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    installations = newItems,
                    hasMore = page.hasMore,
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to load installations"
                )
            }
        }
    }

    fun loadNextPage() {
        if (!_uiState.value.isLoading && !_uiState.value.isLoadingMore && _uiState.value.hasMore) {
            loadInstallations(reset = false)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(300)
            loadInstallations(reset = true)
        }
    }

    fun setStatusFilter(filter: String) {
        if (_uiState.value.statusFilter != filter) {
            _uiState.value = _uiState.value.copy(statusFilter = filter)
            loadInstallations(reset = true)
        }
    }

    fun setVersionFilter(filter: String) {
        if (_uiState.value.versionFilter != filter) {
            _uiState.value = _uiState.value.copy(versionFilter = filter)
            loadInstallations(reset = true)
        }
    }

    fun loadInstallationDetails(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val res = repository.getInstallationDetails(id)
            if (res.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    selectedDetailBundle = res.getOrNull()
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Could not load installation details"
                )
            }
        }
    }

    fun deleteInstallation(id: String, adminEmail: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            val res = repository.deleteInstallation(id)
            if (res.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isDeleting = false,
                    installations = _uiState.value.installations.filter { it.id != id },
                    successMessage = "Installation deleted successfully."
                )
                logRepository.logActivity(
                    adminUid = "admin",
                    adminEmail = adminEmail,
                    action = "INSTALLATION_DELETED",
                    targetType = "INSTALLATION",
                    targetId = id,
                    details = "Deleted installation record $id"
                )
                loadStats()
                onComplete()
            } else {
                _uiState.value = _uiState.value.copy(
                    isDeleting = false,
                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to delete installation"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }
}
