package com.oneclaw.shadow.feature.skill.usecase

import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.skill.SkillRegistry

class DeleteSkillUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(name: String): AppResult<Unit> = skillRegistry.deleteSkill(name)
}
