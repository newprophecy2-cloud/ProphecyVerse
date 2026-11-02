package com.example.version

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.VersionConfig
import com.example.data.repository.ActivityLogRepository
import com.example.data.repository.VersionControlRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VersionUiState(
    val isLoading: Boolean = false,
    val config: VersionConfig = VersionConfig(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class VersionViewModel(
    private val repository: VersionControlRepository = VersionControlRepository(),
    private val logRepository: ActivityLogRepository = ActivityLogRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(VersionUiState(isLoading = true))
    val uiState: StateFlow<VersionUiState> = _uiState.asStateFlow()

    init {
        loadVersionConfig()
    }

    fun loadVersionConfig() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = repository.getVersionConfig()
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    config = result.getOrNull() ?: VersionConfig(),
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to load version configuration"
                )
            }
        }
    }

    fun saveVersionConfig(newConfig: VersionConfig, adminEmail: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            val result = repository.saveVersionConfig(newConfig, adminEmail)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    config = newConfig,
                    successMessage = "Version configuration published successfully to User APK channel."
                )
                logRepository.logActivity(
                    adminUid = "admin",
                    adminEmail = adminEmail,
                    action = "VERSION_CONFIG_UPDATED",
                    targetType = "VERSION",
                    targetId = newConfig.latestVersionName,
                    details = "Published update: v${newConfig.latestVersionName} (code: ${newConfig.latestVersionCode}, min: ${newConfig.minSupportedVersionCode}, mandatory: ${newConfig.forceUpdate})"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to save version configuration"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }
}
