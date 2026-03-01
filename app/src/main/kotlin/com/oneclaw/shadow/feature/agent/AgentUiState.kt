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
    val isBuiltIn: Boolean
)

data class AgentDetailUiState(
    val agentId: String? = null,
    val isBuiltIn: Boolean = false,
    val isNewAgent: Boolean = false,

    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val preferredProviderId: String? = null,
    val preferredModelId: String? = null,
    val webSearchEnabled: Boolean = false,

    // Snapshot of persisted values used to derive hasUnsavedChanges
    val savedName: String = "",
    val savedDescription: String = "",
    val savedSystemPrompt: String = "",
    val savedPreferredProviderId: String? = null,
    val savedPreferredModelId: String? = null,
    val savedWebSearchEnabled: Boolean = false,

    val availableModels: List<ModelOptionItem> = emptyList(),

    val generatePrompt: String = "",
    val isGenerating: Boolean = false,

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
            preferredProviderId != savedPreferredProviderId ||
            preferredModelId != savedPreferredModelId ||
            webSearchEnabled != savedWebSearchEnabled
        }
}

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
