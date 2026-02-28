package com.oneclaw.shadow.feature.skill.usecase

import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.skill.SkillRegistry

class UpdateSkillUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(
        name: String,
        definition: SkillDefinition,
        promptContent: String
    ): AppResult<SkillDefinition> = skillRegistry.updateSkill(name, definition, promptContent)
}
