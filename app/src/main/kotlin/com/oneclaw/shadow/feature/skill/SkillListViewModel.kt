package com.oneclaw.shadow.feature.skill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.skill.usecase.DeleteSkillUseCase
import com.oneclaw.shadow.feature.skill.usecase.ExportSkillUseCase
import com.oneclaw.shadow.feature.skill.usecase.GetAllSkillsUseCase
import com.oneclaw.shadow.feature.skill.usecase.ImportSkillUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SkillListViewModel(
    private val getAllSkillsUseCase: GetAllSkillsUseCase,
    private val deleteSkillUseCase: DeleteSkillUseCase,
    private val exportSkillUseCase: ExportSkillUseCase,
    private val importSkillUseCase: ImportSkillUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillListUiState())
    val uiState: StateFlow<SkillListUiState> = _uiState.asStateFlow()

    init {
        loadSkills()
    }

    fun loadSkills() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val allSkills = withContext(Dispatchers.IO) { getAllSkillsUseCase() }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    builtInSkills = allSkills.filter { s -> s.isBuiltIn },
                    userSkills = allSkills.filter { s -> !s.isBuiltIn }
                )
            }
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { deleteSkillUseCase(name) }
            when (result) {
                is AppResult.Success -> loadSkills()
                is AppResult.Error -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun exportSkill(name: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { exportSkillUseCase(name) }
            when (result) {
                is AppResult.Success -> _uiState.update { it.copy(exportedContent = result.data) }
                is AppResult.Error -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun importSkill(content: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { importSkillUseCase(content) }
            when (result) {
                is AppResult.Success -> loadSkills()
                is AppResult.Error -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearExportedContent() {
        _uiState.update { it.copy(exportedContent = null) }
    }
}
