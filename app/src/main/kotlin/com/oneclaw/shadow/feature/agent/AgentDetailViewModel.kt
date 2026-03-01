package com.oneclaw.shadow.feature.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.model.Agent
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.agent.usecase.CloneAgentUseCase
import com.oneclaw.shadow.feature.agent.usecase.CreateAgentUseCase
import com.oneclaw.shadow.feature.agent.usecase.DeleteAgentUseCase
import com.oneclaw.shadow.tool.engine.ToolEnabledStateStore
import com.oneclaw.shadow.tool.engine.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentDetailViewModel(
    private val agentRepository: AgentRepository,
    private val providerRepository: ProviderRepository,
    private val toolRegistry: ToolRegistry,
    private val enabledStateStore: ToolEnabledStateStore,
    private val createAgentUseCase: CreateAgentUseCase,
    private val cloneAgentUseCase: CloneAgentUseCase,
    private val deleteAgentUseCase: DeleteAgentUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val agentId: String? = savedStateHandle["agentId"]
    private val isCreateMode = agentId == null

    private val _uiState = MutableStateFlow(AgentDetailUiState(isNewAgent = isCreateMode))
    val uiState: StateFlow<AgentDetailUiState> = _uiState.asStateFlow()

    private var originalAgent: Agent? = null

    init {
        loadAvailableTools()
        loadAvailableModels()
        if (!isCreateMode) {
            loadAgent(agentId!!)
        } else {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun loadAgent(id: String) {
        viewModelScope.launch {
            val agent = agentRepository.getAgentById(id)
            if (agent == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Agent not found.") }
                return@launch
            }
            originalAgent = agent
            _uiState.update {
                it.copy(
                    agentId = agent.id,
                    isBuiltIn = agent.isBuiltIn,
                    name = agent.name,
                    description = agent.description ?: "",
                    systemPrompt = agent.systemPrompt,
                    selectedToolIds = agent.toolIds,
                    preferredProviderId = agent.preferredProviderId,
                    preferredModelId = agent.preferredModelId,
                    savedName = agent.name,
                    savedDescription = agent.description ?: "",
                    savedSystemPrompt = agent.systemPrompt,
                    savedToolIds = agent.toolIds,
                    savedPreferredProviderId = agent.preferredProviderId,
                    savedPreferredModelId = agent.preferredModelId,
                    availableTools = toolRegistry.getAllToolDefinitions().map { toolDef ->
                        val sourceInfo = toolRegistry.getToolSourceInfo(toolDef.name)
                        val isGloballyDisabled = !enabledStateStore.isToolEffectivelyEnabled(
                            toolDef.name, sourceInfo.groupName
                        )
                        ToolOptionItem(
                            name = toolDef.name,
                            description = toolDef.description,
                            isSelected = toolDef.name in agent.toolIds,
                            isGloballyDisabled = isGloballyDisabled
                        )
                    },
                    isLoading = false
                )
            }
        }
    }

    private fun loadAvailableTools() {
        val selectedIds = _uiState.value.selectedToolIds
        val tools = toolRegistry.getAllToolDefinitions().map { toolDef ->
            val sourceInfo = toolRegistry.getToolSourceInfo(toolDef.name)
            val isGloballyDisabled = !enabledStateStore.isToolEffectivelyEnabled(
                toolDef.name, sourceInfo.groupName
            )
            ToolOptionItem(
                name = toolDef.name,
                description = toolDef.description,
                isSelected = toolDef.name in selectedIds,
                isGloballyDisabled = isGloballyDisabled
            )
        }
        _uiState.update { it.copy(availableTools = tools) }
    }

    private fun loadAvailableModels() {
        viewModelScope.launch {
            providerRepository.getActiveProviders().collect { providers ->
                val modelOptions = mutableListOf<ModelOptionItem>()
                for (provider in providers) {
                    val models = providerRepository.getModelsForProvider(provider.id)
                    for (model in models) {
                        modelOptions.add(
                            ModelOptionItem(
                                modelId = model.id,
                                modelDisplayName = model.displayName,
                                providerId = provider.id,
                                providerName = provider.name
                            )
                        )
                    }
                }
                _uiState.update { it.copy(availableModels = modelOptions) }
            }
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(description = description) }
    }

    fun updateSystemPrompt(prompt: String) {
        _uiState.update { it.copy(systemPrompt = prompt) }
    }

    fun toggleTool(toolName: String) {
        _uiState.update { state ->
            val newToolIds = if (toolName in state.selectedToolIds) {
                state.selectedToolIds - toolName
            } else {
                state.selectedToolIds + toolName
            }
            val updatedTools = state.availableTools.map { tool ->
                tool.copy(isSelected = tool.name in newToolIds)
            }
            state.copy(selectedToolIds = newToolIds, availableTools = updatedTools)
        }
    }

    fun setPreferredModel(providerId: String?, modelId: String?) {
        _uiState.update {
            it.copy(preferredProviderId = providerId, preferredModelId = modelId)
        }
    }

    fun clearPreferredModel() = setPreferredModel(null, null)

    fun saveAgent() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            if (isCreateMode) {
                val result = createAgentUseCase(
                    name = state.name,
                    description = state.description.ifBlank { null },
                    systemPrompt = state.systemPrompt,
                    toolIds = state.selectedToolIds,
                    preferredProviderId = state.preferredProviderId,
                    preferredModelId = state.preferredModelId
                )
                when (result) {
                    is AppResult.Success -> _uiState.update {
                        it.copy(isSaving = false, successMessage = "Agent created.", navigateBack = true)
                    }
                    is AppResult.Error -> _uiState.update {
                        it.copy(isSaving = false, errorMessage = result.message)
                    }
                }
            } else {
                val updated = Agent(
                    id = state.agentId!!,
                    name = state.name.trim(),
                    description = state.description.trim().ifBlank { null },
                    systemPrompt = state.systemPrompt.trim(),
                    toolIds = state.selectedToolIds,
                    preferredProviderId = state.preferredProviderId,
                    preferredModelId = state.preferredModelId,
                    isBuiltIn = false,
                    createdAt = originalAgent?.createdAt ?: 0,
                    updatedAt = 0
                )
                when (val result = agentRepository.updateAgent(updated)) {
                    is AppResult.Success -> {
                        originalAgent = updated
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                savedName = updated.name,
                                savedDescription = updated.description ?: "",
                                savedSystemPrompt = updated.systemPrompt,
                                savedToolIds = updated.toolIds,
                                savedPreferredProviderId = updated.preferredProviderId,
                                savedPreferredModelId = updated.preferredModelId,
                                successMessage = "Agent saved."
                            )
                        }
                    }
                    is AppResult.Error -> _uiState.update {
                        it.copy(isSaving = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

    fun cloneAgent() {
        val id = _uiState.value.agentId ?: return
        viewModelScope.launch {
            when (val result = cloneAgentUseCase(id)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(successMessage = "Agent cloned.", navigateBack = true)
                }
                is AppResult.Error -> _uiState.update { it.copy(errorMessage = result.message) }
            }
        }
    }

    fun showDeleteConfirmation() { _uiState.update { it.copy(showDeleteDialog = true) } }
    fun dismissDeleteConfirmation() { _uiState.update { it.copy(showDeleteDialog = false) } }

    fun deleteAgent() {
        val id = _uiState.value.agentId ?: return
        viewModelScope.launch {
            when (val result = deleteAgentUseCase(id)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(showDeleteDialog = false, successMessage = "Agent deleted.", navigateBack = true)
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(showDeleteDialog = false, errorMessage = result.message)
                }
            }
        }
    }

    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }
    fun clearSuccess() { _uiState.update { it.copy(successMessage = null) } }
    fun onNavigatedBack() { _uiState.update { it.copy(navigateBack = false) } }
}
