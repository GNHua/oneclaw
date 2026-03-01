package com.oneclaw.shadow.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.data.local.dao.MessageDao
import com.oneclaw.shadow.feature.session.usecase.BatchDeleteSessionsUseCase
import com.oneclaw.shadow.feature.session.usecase.DeleteSessionUseCase
import com.oneclaw.shadow.feature.session.usecase.RenameSessionUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionListViewModel(
    private val sessionRepository: SessionRepository,
    private val agentRepository: AgentRepository,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val batchDeleteSessionsUseCase: BatchDeleteSessionsUseCase,
    private val renameSessionUseCase: RenameSessionUseCase,
    private val messageDao: MessageDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionListUiState())
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    private var undoJob: Job? = null
    private val agentNameCache = mutableMapOf<String, String>()

    init {
        loadAgentNames()
        loadSessions()
    }

    private fun loadAgentNames() {
        viewModelScope.launch {
            agentRepository.getAllAgents().collect { agents ->
                agentNameCache.clear()
                agents.forEach { agentNameCache[it.id] = it.name }
                // Re-map sessions with updated agent names
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions.map { item ->
                            item.copy(agentName = agentNameCache[item.id] ?: item.agentName)
                        }
                    )
                }
            }
        }
    }

    private fun loadSessions() {
        viewModelScope.launch {
            sessionRepository.getAllSessions().collect { sessions ->
                val selected = _uiState.value.selectedSessionIds
                val items = sessions.map { session ->
                    val totalTokens = messageDao.getTotalTokensForSession(session.id)
                    SessionListItem(
                        id = session.id,
                        title = session.title,
                        agentName = agentNameCache[session.currentAgentId] ?: "Agent",
                        lastMessagePreview = session.lastMessagePreview,
                        relativeTime = formatRelativeTime(session.updatedAt),
                        isActive = session.isActive,
                        isSelected = session.id in selected,
                        totalTokens = totalTokens
                    )
                }
                _uiState.update { it.copy(sessions = items, isLoading = false) }
            }
        }
    }

    // --- Delete ---

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            when (deleteSessionUseCase(sessionId)) {
                is AppResult.Success -> startUndoTimer(listOf(sessionId), "Session deleted")
                is AppResult.Error -> _uiState.update { it.copy(errorMessage = "Failed to delete session.") }
            }
        }
    }

    fun deleteSelectedSessions() {
        val ids = _uiState.value.selectedSessionIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            when (batchDeleteSessionsUseCase(ids)) {
                is AppResult.Success -> {
                    exitSelectionMode()
                    val msg = if (ids.size == 1) "Session deleted" else "${ids.size} sessions deleted"
                    startUndoTimer(ids, msg)
                }
                is AppResult.Error -> _uiState.update { it.copy(errorMessage = "Failed to delete sessions.") }
            }
        }
    }

    private fun startUndoTimer(deletedIds: List<String>, message: String) {
        undoJob?.cancel()
        _uiState.update {
            it.copy(undoState = UndoState(deletedSessionIds = deletedIds, message = message))
        }
        undoJob = viewModelScope.launch {
            delay(5_000L)
            deletedIds.forEach { id -> sessionRepository.hardDeleteSession(id) }
            _uiState.update { it.copy(undoState = null) }
        }
    }

    fun undoDelete() {
        val undoState = _uiState.value.undoState ?: return
        undoJob?.cancel()
        viewModelScope.launch {
            if (undoState.deletedSessionIds.size == 1) {
                sessionRepository.restoreSession(undoState.deletedSessionIds.first())
            } else {
                sessionRepository.restoreSessions(undoState.deletedSessionIds)
            }
            _uiState.update { it.copy(undoState = null) }
        }
    }

    // --- Selection mode ---

    fun enterSelectionMode(sessionId: String) {
        _uiState.update {
            it.copy(isSelectionMode = true, selectedSessionIds = setOf(sessionId))
        }
    }

    fun toggleSelection(sessionId: String) {
        _uiState.update { state ->
            val newSelection = if (sessionId in state.selectedSessionIds) {
                state.selectedSessionIds - sessionId
            } else {
                state.selectedSessionIds + sessionId
            }
            if (newSelection.isEmpty()) {
                state.copy(isSelectionMode = false, selectedSessionIds = emptySet())
            } else {
                state.copy(selectedSessionIds = newSelection)
            }
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedSessionIds = state.sessions.map { it.id }.toSet())
        }
    }

    fun exitSelectionMode() {
        _uiState.update { it.copy(isSelectionMode = false, selectedSessionIds = emptySet()) }
    }

    // --- Rename ---

    fun showRenameDialog(sessionId: String, currentTitle: String) {
        _uiState.update { it.copy(renameDialog = RenameDialogState(sessionId, currentTitle)) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(renameDialog = null) }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            when (renameSessionUseCase(sessionId, newTitle)) {
                is AppResult.Success -> _uiState.update { it.copy(renameDialog = null) }
                is AppResult.Error -> _uiState.update { it.copy(errorMessage = "Failed to rename session.") }
            }
        }
    }

    // --- Misc ---

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    internal fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000L -> "Just now"
            diff < 3_600_000L -> "${diff / 60_000L} min ago"
            diff < 86_400_000L -> {
                val hours = diff / 3_600_000L
                if (hours == 1L) "1 hour ago" else "$hours hours ago"
            }
            diff < 172_800_000L -> "Yesterday"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
