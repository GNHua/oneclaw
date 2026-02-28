package com.oneclaw.shadow.feature.skill.usecase

import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.tool.skill.SkillRegistry

class GetAllSkillsUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(): List<SkillDefinition> = skillRegistry.getAllSkills()
}
