package com.example.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.PushNotification
import com.example.data.repository.ActivityLogRepository
import com.example.data.repository.NotificationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NotificationUiState(
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val notifications: List<PushNotification> = emptyList(),
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class NotificationViewModel(
    private val repository: NotificationRepository = NotificationRepository(),
    private val logRepository: ActivityLogRepository = ActivityLogRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState(isLoading = true))
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    init {
        loadNotifications()
    }

    fun loadNotifications() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val res = repository.getNotifications()
            if (res.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    notifications = res.getOrNull() ?: emptyList(),
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to load notifications"
                )
            }
        }
    }

    fun queueNotification(
        eventType: String,
        target: String,
        title: String,
        body: String,
        deepLink: String,
        imageUrl: String,
        adminEmail: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, errorMessage = null)
            val notification = PushNotification(
                eventType = eventType,
                target = target.ifBlank { "/topics/all" },
                title = title.trim(),
                body = body.trim(),
                deepLink = deepLink.trim(),
                imageUrl = imageUrl.trim(),
                status = "queued",
                createdBy = adminEmail
            )

            val res = repository.queueNotification(notification, adminEmail)
            if (res.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    successMessage = "Push notification successfully queued for dispatch."
                )
                logRepository.logActivity(
                    adminUid = "admin",
                    adminEmail = adminEmail,
                    action = "PUSH_NOTIFICATION_QUEUED",
                    targetType = "NOTIFICATION",
                    targetId = res.getOrNull() ?: "",
                    details = "Queued '$title' (type: $eventType, target: $target)"
                )
                loadNotifications()
                onSuccess()
            } else {
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to queue push notification"
                )
            }
        }
    }

    fun cancelNotification(id: String, adminEmail: String) {
        viewModelScope.launch {
            val res = repository.cancelQueuedNotification(id)
            if (res.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    notifications = _uiState.value.notifications.filter { it.id != id },
                    successMessage = "Notification removed."
                )
                logRepository.logActivity(
                    adminUid = "admin",
                    adminEmail = adminEmail,
                    action = "NOTIFICATION_CANCELLED",
                    targetType = "NOTIFICATION",
                    targetId = id,
                    details = "Cancelled queued notification $id"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Failed to remove notification"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }
}
