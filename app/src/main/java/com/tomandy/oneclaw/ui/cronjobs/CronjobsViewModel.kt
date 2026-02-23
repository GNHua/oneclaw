package com.tomandy.oneclaw.ui.cronjobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.data.entity.MessageEntity
import com.tomandy.oneclaw.scheduler.CronjobManager
import com.tomandy.oneclaw.scheduler.data.CronjobEntity
import com.tomandy.oneclaw.scheduler.data.ExecutionLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CronjobsViewModel(
    private val cronjobManager: CronjobManager,
    private val messageDao: MessageDao? = null
) : ViewModel() {

    companion object {
        private const val HISTORY_PAGE_SIZE = 20
        private const val DETAIL_LOGS_PAGE_SIZE = 20
    }

    val cronjobs: StateFlow<List<CronjobEntity>> = cronjobManager.getAllEnabled()
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

    // History state
    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory.asStateFlow()

    private val _historyCronjobs = MutableStateFlow<List<CronjobEntity>>(emptyList())
    val historyCronjobs: StateFlow<List<CronjobEntity>> = _historyCronjobs.asStateFlow()

    private val _historyCanLoadMore = MutableStateFlow(false)
    val historyCanLoadMore: StateFlow<Boolean> = _historyCanLoadMore.asStateFlow()

    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()

    private var historyOffset = 0

    // Detail execution logs pagination
    private val _detailLogs = MutableStateFlow<List<ExecutionLog>>(emptyList())
    val detailLogs: StateFlow<List<ExecutionLog>> = _detailLogs.asStateFlow()

    private val _detailLogsCanLoadMore = MutableStateFlow(false)
    val detailLogsCanLoadMore: StateFlow<Boolean> = _detailLogsCanLoadMore.asStateFlow()

    private val _detailLogsLoading = MutableStateFlow(false)
    val detailLogsLoading: StateFlow<Boolean> = _detailLogsLoading.asStateFlow()

    private var detailLogsOffset = 0
    private var detailLogsCronjobId: String? = null

    // Conversation viewer state
    private val _conversationMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val conversationMessages: StateFlow<List<MessageEntity>> = _conversationMessages.asStateFlow()

    private val _showConversation = MutableStateFlow(false)
    val showConversation: StateFlow<Boolean> = _showConversation.asStateFlow()

    private val _conversationLoading = MutableStateFlow(false)
    val conversationLoading: StateFlow<Boolean> = _conversationLoading.asStateFlow()

    fun toggleEnabled(cronjob: CronjobEntity) {
        viewModelScope.launch {
            try {
                cronjobManager.setEnabled(cronjob.id, !cronjob.enabled)
                if (!cronjob.enabled) {
                    // Re-enabling: remove from history list immediately
                    _historyCronjobs.value = _historyCronjobs.value.filter { it.id != cronjob.id }
                }
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
                _historyCronjobs.value = _historyCronjobs.value.filter { it.id != id }
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

    fun openHistory() {
        historyOffset = 0
        _historyCronjobs.value = emptyList()
        _historyCanLoadMore.value = false
        _showHistory.value = true
        loadMoreHistory()
    }

    fun closeHistory() {
        _showHistory.value = false
        _historyCronjobs.value = emptyList()
        _historyCanLoadMore.value = false
        historyOffset = 0
    }

    fun loadMoreHistory() {
        if (_historyLoading.value) return
        _historyLoading.value = true
        viewModelScope.launch {
            try {
                val page = cronjobManager.getDisabledPaged(HISTORY_PAGE_SIZE, historyOffset)
                _historyCronjobs.value = _historyCronjobs.value + page
                historyOffset += page.size
                _historyCanLoadMore.value = page.size == HISTORY_PAGE_SIZE
            } catch (e: Exception) {
                _error.value = "Failed to load history: ${e.message}"
            } finally {
                _historyLoading.value = false
            }
        }
    }

    fun getCronjobById(id: String): StateFlow<CronjobEntity?> {
        val flow = MutableStateFlow<CronjobEntity?>(null)
        viewModelScope.launch {
            flow.value = cronjobManager.getById(id)
        }
        return flow.asStateFlow()
    }

    fun loadExecutionLogs(cronjobId: String) {
        detailLogsCronjobId = cronjobId
        detailLogsOffset = 0
        _detailLogs.value = emptyList()
        _detailLogsCanLoadMore.value = false
        loadMoreExecutionLogs()
    }

    fun loadMoreExecutionLogs() {
        val cronjobId = detailLogsCronjobId ?: return
        if (_detailLogsLoading.value) return
        _detailLogsLoading.value = true
        viewModelScope.launch {
            try {
                val page = cronjobManager.getExecutionLogsPaged(
                    cronjobId, DETAIL_LOGS_PAGE_SIZE, detailLogsOffset
                )
                _detailLogs.value = _detailLogs.value + page
                detailLogsOffset += page.size
                _detailLogsCanLoadMore.value = page.size == DETAIL_LOGS_PAGE_SIZE
            } catch (e: Exception) {
                _error.value = "Failed to load execution logs: ${e.message}"
            } finally {
                _detailLogsLoading.value = false
            }
        }
    }

    fun openConversation(conversationId: String?) {
        if (conversationId == null || messageDao == null) return
        _showConversation.value = true
        _conversationLoading.value = true
        _conversationMessages.value = emptyList()
        viewModelScope.launch {
            try {
                _conversationMessages.value = messageDao.getMessagesOnce(conversationId)
            } catch (e: Exception) {
                _error.value = "Failed to load conversation: ${e.message}"
            } finally {
                _conversationLoading.value = false
            }
        }
    }

    fun closeConversation() {
        _showConversation.value = false
        _conversationMessages.value = emptyList()
    }

    fun clearError() {
        _error.value = null
    }
}
