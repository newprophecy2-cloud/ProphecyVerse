package com.example.notices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.NoticeItem
import com.example.data.repository.ActivityLogRepository
import com.example.data.repository.NoticeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NoticeUiState(
    val isLoading: Boolean = false,
    val notices: List<NoticeItem> = emptyList(),
    val selectedNoticeForPreview: NoticeItem? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class NoticeViewModel(
    private val repository: NoticeRepository = NoticeRepository(),
    private val logRepository: ActivityLogRepository = ActivityLogRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoticeUiState(isLoading = true))
    val uiState: StateFlow<NoticeUiState> = _uiState.asStateFlow()

    init {
        loadNotices()
    }

    fun loadNotices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = repository.getNotices()
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    notices = result.getOrNull() ?: emptyList(),
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to load notices"
                )
            }
        }
    }

    fun togglePublish(notice: NoticeItem, adminEmail: String) {
        viewModelScope.launch {
            val result = repository.togglePublish(notice.id, notice.published)
            if (result.isSuccess) {
                val newPublished = result.getOrNull() ?: !notice.published
                _uiState.value = _uiState.value.copy(
                    notices = _uiState.value.notices.map {
                        if (it.id == notice.id) it.copy(published = newPublished) else it
                    },
                    successMessage = "Notice ${if (newPublished) "published" else "unpublished"}."
                )
                logRepository.logActivity(
                    adminUid = "admin",
                    adminEmail = adminEmail,
                    action = if (newPublished) "NOTICE_PUBLISHED" else "NOTICE_UNPUBLISHED",
                    targetType = "NOTICE",
                    targetId = notice.id,
                    details = "Notice '${notice.title}' status toggled to $newPublished"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to toggle publish state"
                )
            }
        }
    }

    fun deleteNotice(noticeId: String, noticeTitle: String, adminEmail: String) {
        viewModelScope.launch {
            val result = repository.deleteNotice(noticeId)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    notices = _uiState.value.notices.filter { it.id != noticeId },
                    successMessage = "Notice '$noticeTitle' deleted."
                )
                logRepository.logActivity(
                    adminUid = "admin",
                    adminEmail = adminEmail,
                    action = "NOTICE_DELETED",
                    targetType = "NOTICE",
                    targetId = noticeId,
                    details = "Deleted notice '$noticeTitle'"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to delete notice"
                )
            }
        }
    }

    fun saveNotice(notice: NoticeItem, adminEmail: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            val result = repository.saveNotice(notice)
            if (result.isSuccess) {
                val id = result.getOrNull() ?: notice.id
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    successMessage = "Notice successfully saved."
                )
                logRepository.logActivity(
                    adminUid = "admin",
                    adminEmail = adminEmail,
                    action = if (notice.id.isBlank()) "NOTICE_CREATED" else "NOTICE_EDITED",
                    targetType = "NOTICE",
                    targetId = id,
                    details = "Saved notice '${notice.title}' (type: ${notice.type})"
                )
                loadNotices()
                onComplete()
            } else {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to save notice"
                )
            }
        }
    }

    fun showPreview(notice: NoticeItem?) {
        _uiState.value = _uiState.value.copy(selectedNoticeForPreview = notice)
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }
}
