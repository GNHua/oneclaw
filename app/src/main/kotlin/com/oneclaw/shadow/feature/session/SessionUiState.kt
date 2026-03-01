package com.oneclaw.shadow.feature.session

data class SessionListUiState(
    val sessions: List<SessionListItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSelectionMode: Boolean = false,
    val selectedSessionIds: Set<String> = emptySet(),
    val undoState: UndoState? = null,
    val renameDialog: RenameDialogState? = null,
    val errorMessage: String? = null
)

data class SessionListItem(
    val id: String,
    val title: String,
    val agentName: String,
    val lastMessagePreview: String?,
    val relativeTime: String,
    val isActive: Boolean,
    val isSelected: Boolean,
    val totalTokens: Long = 0
)

data class UndoState(
    val deletedSessionIds: List<String>,
    val message: String
)

data class RenameDialogState(
    val sessionId: String,
    val currentTitle: String
)
