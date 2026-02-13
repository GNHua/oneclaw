package com.tomandy.palmclaw.ui.cronjobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomandy.palmclaw.scheduler.CronjobManager
import com.tomandy.palmclaw.scheduler.data.CronjobEntity
import com.tomandy.palmclaw.scheduler.data.ExecutionLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CronjobsViewModel(
    private val cronjobManager: CronjobManager
) : ViewModel() {

    val cronjobs: StateFlow<List<CronjobEntity>> = cronjobManager.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedCronjobId = MutableStateFlow<String?>(null)
    val selectedCronjobId: StateFlow<String?> = _selectedCronjobId.asStateFlow()

    private val _executionLogs = MutableStateFlow<List<ExecutionLog>>(emptyList())
    val executionLogs: StateFlow<List<ExecutionLog>> = _executionLogs.asStateFlow()

    private val _deleteConfirmation = MutableStateFlow<String?>(null)
    val deleteConfirmation: StateFlow<String?> = _deleteConfirmation.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var logsCollectionJob: Job? = null

    fun toggleEnabled(cronjob: CronjobEntity) {
        viewModelScope.launch {
            try {
                cronjobManager.setEnabled(cronjob.id, !cronjob.enabled)
            } catch (e: Exception) {
                _error.value = "Failed to toggle: ${e.message}"
            }
        }
    }

    fun requestDelete(cronjobId: String) {
        _deleteConfirmation.value = cronjobId
    }

    fun confirmDelete() {
        val id = _deleteConfirmation.value ?: return
        _deleteConfirmation.value = null
        viewModelScope.launch {
            try {
                cronjobManager.delete(id)
                if (_selectedCronjobId.value == id) {
                    _selectedCronjobId.value = null
                    _executionLogs.value = emptyList()
                }
            } catch (e: Exception) {
                _error.value = "Failed to delete: ${e.message}"
            }
        }
    }

    fun dismissDelete() {
        _deleteConfirmation.value = null
    }

    fun toggleExecutionLogs(cronjobId: String) {
        if (_selectedCronjobId.value == cronjobId) {
            _selectedCronjobId.value = null
            _executionLogs.value = emptyList()
            logsCollectionJob?.cancel()
        } else {
            _selectedCronjobId.value = cronjobId
            logsCollectionJob?.cancel()
            logsCollectionJob = viewModelScope.launch {
                cronjobManager.getExecutionLogs(cronjobId).collect { logs ->
                    _executionLogs.value = logs
                }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
