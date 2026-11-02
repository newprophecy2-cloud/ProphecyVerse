package com.example.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.UserAccount
import com.example.data.repository.ActivityLogRepository
import com.example.data.repository.UserRepository
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UserUiState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMoreUsers: Boolean = false,
    val users: List<UserAccount> = emptyList(),
    val selectedUser: UserAccount? = null,
    val searchQuery: String = "",
    val isActionInProgress: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class UserViewModel(
    private val repository: UserRepository = UserRepository(),
    private val logRepository: ActivityLogRepository = ActivityLogRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserUiState(isLoading = true))
    val uiState: StateFlow<UserUiState> = _uiState.asStateFlow()
    private var lastUserDocument: DocumentSnapshot? = null
    private var searchDebounceJob: Job? = null

    init {
        loadUsers()
    }

    fun loadUsers() {
        loadUserPage(reset = true)
    }

    fun loadMoreUsers() {
        if (_uiState.value.searchQuery.isBlank() &&
            !_uiState.value.isLoading &&
            !_uiState.value.isLoadingMore &&
            _uiState.value.hasMoreUsers
        ) {
            loadUserPage(reset = false)
        }
    }

    private fun loadUserPage(reset: Boolean) {
        viewModelScope.launch {
            val before = _uiState.value
            _uiState.value = before.copy(
                isLoading = reset,
                isLoadingMore = !reset,
                users = if (reset) emptyList() else before.users,
                hasMoreUsers = if (reset) false else before.hasMoreUsers,
                errorMessage = null
            )
            if (reset) lastUserDocument = null
            val result = repository.getUserPage(startAfter = if (reset) null else lastUserDocument)
            if (result.isSuccess) {
                val page = result.getOrNull()!!
                lastUserDocument = page.lastDocument
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    users = if (reset) page.users else (_uiState.value.users + page.users).distinctBy { it.uid },
                    hasMoreUsers = page.hasMore,
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to fetch users"
                )
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query, errorMessage = null, hasMoreUsers = false)
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(300)
            if (query.isBlank()) {
                loadUserPage(reset = true)
                return@launch
            }
            _uiState.value = _uiState.value.copy(isLoading = true, users = emptyList())
            val result = repository.searchUsers(query)
            if (_uiState.value.searchQuery != query) return@launch
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    users = result.getOrNull() ?: emptyList(),
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    users = emptyList(),
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to search users"
                )
            }
        }
    }

    fun loadUserDetails(uid: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, selectedUser = null, errorMessage = null)
            val result = repository.getUserDetails(uid)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    selectedUser = result.getOrNull()
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun moderateUserStatus(uid: String, newStatus: String, reason: String, adminEmail: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isActionInProgress = true)
            val normalized = newStatus.lowercase()
            val result = repository.moderateUser(uid, normalized, reason, adminEmail)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isActionInProgress = false,
                    users = _uiState.value.users.map {
                        if (it.uid == uid) it.copy(status = normalized, suspensionReason = reason) else it
                    },
                    selectedUser = _uiState.value.selectedUser?.let {
                        if (it.uid == uid) it.copy(status = normalized, suspensionReason = reason) else it
                    },
                    successMessage = "User status updated to $normalized."
                )
                logRepository.logActivity(
                    adminUid = "admin",
                    adminEmail = adminEmail,
                    action = "USER_STATUS_CHANGE",
                    targetType = "USER",
                    targetId = uid,
                    details = "Changed user status to $normalized (reason: $reason)"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isActionInProgress = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to update user status"
                )
            }
        }
    }

    fun toggleDownloadBlock(uid: String, currentBlocked: Boolean, adminEmail: String) {
        viewModelScope.launch {
            val newBlocked = !currentBlocked
            _uiState.value = _uiState.value.copy(isActionInProgress = true)
            val result = repository.toggleDownloadBlock(uid, newBlocked, adminEmail)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isActionInProgress = false,
                    users = _uiState.value.users.map {
                        if (it.uid == uid) it.copy(downloadBlocked = newBlocked) else it
                    },
                    selectedUser = _uiState.value.selectedUser?.let {
                        if (it.uid == uid) it.copy(downloadBlocked = newBlocked) else it
                    },
                    successMessage = if (newBlocked) "Downloads blocked for user." else "Downloads restored."
                )
                logRepository.logActivity(
                    adminUid = "admin",
                    adminEmail = adminEmail,
                    action = if (newBlocked) "DOWNLOAD_BLOCKED" else "DOWNLOAD_RESTORED",
                    targetType = "USER",
                    targetId = uid,
                    details = "Download permission changed to blocked=$newBlocked"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isActionInProgress = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to update download permission"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }
}
