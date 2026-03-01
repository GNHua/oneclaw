package com.oneclaw.shadow.feature.agent

import com.oneclaw.shadow.core.model.AgentConstants

data class AgentListUiState(
    val agents: List<AgentListItem> = emptyList(),
    val isLoading: Boolean = true
)

data class AgentListItem(
    val id: String,
    val name: String,
    val description: String?,
    val isBuiltIn: Boolean,
    val toolCount: Int
)

data class AgentDetailUiState(
    val agentId: String? = null,
    val isBuiltIn: Boolean = false,
    val isNewAgent: Boolean = false,

    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val selectedToolIds: List<String> = emptyList(),
    val preferredProviderId: String? = null,
    val preferredModelId: String? = null,

    // Snapshot of persisted values used to derive hasUnsavedChanges
    val savedName: String = "",
    val savedDescription: String = "",
    val savedSystemPrompt: String = "",
    val savedToolIds: List<String> = emptyList(),
    val savedPreferredProviderId: String? = null,
    val savedPreferredModelId: String? = null,

    val availableTools: List<ToolOptionItem> = emptyList(),
    val availableModels: List<ModelOptionItem> = emptyList(),

    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val showDeleteDialog: Boolean = false,
    val navigateBack: Boolean = false
) {
    val hasUnsavedChanges: Boolean
        get() = if (isNewAgent) {
            name.isNotBlank()
        } else {
            name != savedName ||
            description != savedDescription ||
            systemPrompt != savedSystemPrompt ||
            selectedToolIds.toSet() != savedToolIds.toSet() ||
            preferredProviderId != savedPreferredProviderId ||
            preferredModelId != savedPreferredModelId
        }
}

data class ToolOptionItem(
    val name: String,
    val description: String,
    val isSelected: Boolean,
    val isGloballyDisabled: Boolean = false
)

data class ModelOptionItem(
    val modelId: String,
    val modelDisplayName: String?,
    val providerId: String,
    val providerName: String
)

data class AgentSelectorUiState(
    val agents: List<AgentSelectorItem> = emptyList(),
    val currentAgentId: String = AgentConstants.GENERAL_ASSISTANT_ID,
    val isLoading: Boolean = true
)

data class AgentSelectorItem(
    val id: String,
    val name: String,
    val description: String?,
    val isBuiltIn: Boolean,
    val isSelected: Boolean
)
