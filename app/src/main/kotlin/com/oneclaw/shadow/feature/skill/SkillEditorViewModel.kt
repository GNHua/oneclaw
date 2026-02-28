package com.oneclaw.shadow.feature.skill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.core.model.SkillParameter
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.skill.usecase.CreateSkillUseCase
import com.oneclaw.shadow.feature.skill.usecase.LoadSkillContentUseCase
import com.oneclaw.shadow.feature.skill.usecase.UpdateSkillUseCase
import com.oneclaw.shadow.tool.skill.SkillRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SkillEditorViewModel(
    private val createSkillUseCase: CreateSkillUseCase,
    private val updateSkillUseCase: UpdateSkillUseCase,
    private val loadSkillContentUseCase: LoadSkillContentUseCase,
    private val skillRegistry: SkillRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillEditorUiState())
    val uiState: StateFlow<SkillEditorUiState> = _uiState.asStateFlow()

    /**
     * Load an existing skill for editing or read-only viewing.
     */
    fun loadSkill(skillName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val definition = withContext(Dispatchers.IO) { skillRegistry.getSkill(skillName) }
            if (definition == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val contentResult = withContext(Dispatchers.IO) { loadSkillContentUseCase(skillName) }
            val promptContent = when (contentResult) {
                is AppResult.Success -> contentResult.data
                is AppResult.Error -> ""
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    name = definition.name,
                    displayName = definition.displayName,
                    description = definition.description,
                    version = definition.version,
                    parameters = definition.parameters,
                    toolsRequired = definition.toolsRequired,
                    promptContent = promptContent,
                    isBuiltIn = definition.isBuiltIn,
                    isEditMode = true
                )
            }
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name, validationErrors = it.validationErrors - "name") }
    }

    fun updateDisplayName(displayName: String) {
        _uiState.update {
            it.copy(
                displayName = displayName,
                validationErrors = it.validationErrors - "displayName"
            )
        }
    }

    fun updateDescription(description: String) {
        _uiState.update {
            it.copy(
                description = description,
                validationErrors = it.validationErrors - "description"
            )
        }
    }

    fun updateVersion(version: String) {
        _uiState.update { it.copy(version = version) }
    }

    fun updatePromptContent(content: String) {
        _uiState.update {
            it.copy(
                promptContent = content,
                validationErrors = it.validationErrors - "promptContent"
            )
        }
    }

    fun addParameter(parameter: SkillParameter) {
        _uiState.update { it.copy(parameters = it.parameters + parameter) }
    }

    fun removeParameter(name: String) {
        _uiState.update { it.copy(parameters = it.parameters.filter { p -> p.name != name }) }
    }

    fun addRequiredTool(toolName: String) {
        if (!_uiState.value.toolsRequired.contains(toolName)) {
            _uiState.update { it.copy(toolsRequired = it.toolsRequired + toolName) }
        }
    }

    fun removeRequiredTool(toolName: String) {
        _uiState.update { it.copy(toolsRequired = it.toolsRequired.filter { t -> t != toolName }) }
    }

    fun save() {
        val state = _uiState.value
        val errors = validate(state)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(validationErrors = errors) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveResult = null) }

            val definition = SkillDefinition(
                name = state.name.trim(),
                displayName = state.displayName.trim(),
                description = state.description.trim(),
                version = state.version.trim().ifEmpty { "1.0" },
                toolsRequired = state.toolsRequired,
                parameters = state.parameters,
                isBuiltIn = false,
                directoryPath = ""  // filled by registry
            )

            val result = withContext(Dispatchers.IO) {
                if (state.isEditMode) {
                    updateSkillUseCase(state.name, definition, state.promptContent)
                        .let { r ->
                            when (r) {
                                is AppResult.Success -> AppResult.Success(Unit)
                                is AppResult.Error -> r
                            }
                        }
                } else {
                    createSkillUseCase(definition, state.promptContent)
                        .let { r ->
                            when (r) {
                                is AppResult.Success -> AppResult.Success(Unit)
                                is AppResult.Error -> r
                            }
                        }
                }
            }

            _uiState.update { it.copy(isSaving = false, saveResult = result) }
        }
    }

    fun clearSaveResult() {
        _uiState.update { it.copy(saveResult = null) }
    }

    private fun validate(state: SkillEditorUiState): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        val nameRegex = Regex("^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$")
        val name = state.name.trim()
        if (name.isEmpty()) {
            errors["name"] = "Name is required"
        } else if (!nameRegex.matches(name)) {
            errors["name"] = "Name must be lowercase letters/digits/hyphens, 2-50 chars"
        }
        if (state.displayName.trim().isEmpty()) {
            errors["displayName"] = "Display name is required"
        }
        if (state.description.trim().isEmpty()) {
            errors["description"] = "Description is required"
        }
        if (state.promptContent.trim().isEmpty()) {
            errors["promptContent"] = "Prompt content is required"
        }
        return errors
    }
}
