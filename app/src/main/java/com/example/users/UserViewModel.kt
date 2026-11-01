package com.example.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.UserAccount
import com.example.data.repository.ActivityLogRepository
import com.example.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UserUiState(
    val isLoading: Boolean = false,
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

    init {
        loadUsers()
    }

    fun loadUsers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = repository.getUsers()
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    users = result.getOrNull() ?: emptyList(),
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to fetch users"
                )
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.searchUsers(query)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    users = result.getOrNull() ?: emptyList()
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun loadUserDetails(uid: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
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
            val result = repository.moderateUser(uid, newStatus, reason)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isActionInProgress = false,
                    users = _uiState.value.users.map {
                        if (it.uid == uid) it.copy(status = newStatus) else it
                    },
                    selectedUser = _uiState.value.selectedUser?.let {
                        if (it.uid == uid) it.copy(status = newStatus) else it
                    },
                    successMessage = "User status updated to $newStatus."
                )
                logRepository.logActivity(
                    adminUid = "admin",
                    adminEmail = adminEmail,
                    action = "USER_STATUS_CHANGE",
                    targetType = "USER",
                    targetId = uid,
                    details = "Changed user status to $newStatus (reason: $reason)"
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
            val result = repository.toggleDownloadBlock(uid, newBlocked)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isActionInProgress = false,
                    users = _uiState.value.users.map {
                        if (it.uid == uid) it.copy(isDownloadBlocked = newBlocked) else it
                    },
                    selectedUser = _uiState.value.selectedUser?.let {
                        if (it.uid == uid) it.copy(isDownloadBlocked = newBlocked) else it
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
