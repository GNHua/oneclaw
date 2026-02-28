package com.oneclaw.shadow.feature.skill

import com.oneclaw.shadow.core.model.SkillDefinition

data class SkillListUiState(
    val builtInSkills: List<SkillDefinition> = emptyList(),
    val userSkills: List<SkillDefinition> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val exportedContent: String? = null
)
