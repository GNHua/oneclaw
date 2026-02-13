package com.tomandy.palmclaw.service

import com.tomandy.palmclaw.agent.AgentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that bridges execution state between [ChatExecutionService] and UI
 * (e.g. ChatViewModel). The service writes state here; the ViewModel reads it.
 */
object ChatExecutionTracker {

    private val _activeConversations = MutableStateFlow<Set<String>>(emptySet())
    val activeConversations: StateFlow<Set<String>> = _activeConversations.asStateFlow()

    private val _agentStates = MutableStateFlow<Map<String, AgentState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentState>> = _agentStates.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    fun markActive(conversationId: String) {
        _activeConversations.value = _activeConversations.value + conversationId
        _errors.value = _errors.value - conversationId
    }

    fun markInactive(conversationId: String) {
        _activeConversations.value = _activeConversations.value - conversationId
        _agentStates.value = _agentStates.value - conversationId
    }

    fun updateAgentState(conversationId: String, state: AgentState) {
        _agentStates.value = _agentStates.value + (conversationId to state)
    }

    fun setError(conversationId: String, error: String) {
        _errors.value = _errors.value + (conversationId to error)
    }

    fun clearError(conversationId: String) {
        _errors.value = _errors.value - conversationId
    }
}
