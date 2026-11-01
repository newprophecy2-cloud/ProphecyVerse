package com.example.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AdminActivityLog
import com.example.data.repository.ActivityLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LogsUiState(
    val isLoading: Boolean = false,
    val logs: List<AdminActivityLog> = emptyList(),
    val filteredLogs: List<AdminActivityLog> = emptyList(),
    val selectedFilter: String = "ALL", // ALL, NOTICE, USER, VERSION, BACKEND
    val searchQuery: String = "",
    val selectedLogForDetail: AdminActivityLog? = null,
    val errorMessage: String? = null
)

class LogsViewModel(
    private val repository: ActivityLogRepository = ActivityLogRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogsUiState(isLoading = true))
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        loadLogs()
    }

    fun loadLogs() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = repository.getRecentLogs()
            if (result.isSuccess) {
                val list = result.getOrNull() ?: emptyList()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    logs = list,
                    filteredLogs = applyFilterAndSearch(list, _uiState.value.selectedFilter, _uiState.value.searchQuery)
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to load activity logs"
                )
            }
        }
    }

    fun setFilter(filter: String) {
        _uiState.value = _uiState.value.copy(
            selectedFilter = filter,
            filteredLogs = applyFilterAndSearch(_uiState.value.logs, filter, _uiState.value.searchQuery)
        )
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            filteredLogs = applyFilterAndSearch(_uiState.value.logs, _uiState.value.selectedFilter, query)
        )
    }

    fun selectLogForDetail(log: AdminActivityLog?) {
        _uiState.value = _uiState.value.copy(selectedLogForDetail = log)
    }

    private fun applyFilterAndSearch(list: List<AdminActivityLog>, filter: String, query: String): List<AdminActivityLog> {
        return list.filter { log ->
            val matchesFilter = when (filter) {
                "ALL" -> true
                else -> log.targetType.equals(filter, ignoreCase = true)
            }
            val matchesQuery = if (query.isBlank()) true else {
                log.action.contains(query, ignoreCase = true) ||
                log.details.contains(query, ignoreCase = true) ||
                log.adminEmail.contains(query, ignoreCase = true) ||
                log.targetId.contains(query, ignoreCase = true)
            }
            matchesFilter && matchesQuery
        }
    }
}
