package com.oneclaw.shadow.feature.skill

import com.oneclaw.shadow.core.model.SkillParameter
import com.oneclaw.shadow.core.util.AppResult

data class SkillEditorUiState(
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val version: String = "1.0",
    val parameters: List<SkillParameter> = emptyList(),
    val toolsRequired: List<String> = emptyList(),
    val promptContent: String = "",
    val isBuiltIn: Boolean = false,
    val isEditMode: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val validationErrors: Map<String, String> = emptyMap(),
    val saveResult: AppResult<Unit>? = null
)
