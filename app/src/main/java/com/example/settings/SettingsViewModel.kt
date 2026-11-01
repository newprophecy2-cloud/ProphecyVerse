package com.example.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.ApiClient
import com.example.data.api.BackendHealthReport
import com.example.data.repository.BackendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = ApiClient.activeBaseUrl,
    val isTestingConnection: Boolean = false,
    val testReport: BackendHealthReport? = null,
    val saveMessage: String? = null
)

class SettingsViewModel(
    private val backendRepo: BackendRepository = BackendRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun updateBaseUrl(newUrl: String) {
        ApiClient.updateBaseUrl(newUrl)
        _uiState.value = _uiState.value.copy(
            baseUrl = ApiClient.activeBaseUrl,
            saveMessage = "Base URL updated."
        )
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingConnection = true, testReport = null)
            val report = backendRepo.checkHealth()
            _uiState.value = _uiState.value.copy(
                isTestingConnection = false,
                testReport = report
            )
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(saveMessage = null)
    }
}
