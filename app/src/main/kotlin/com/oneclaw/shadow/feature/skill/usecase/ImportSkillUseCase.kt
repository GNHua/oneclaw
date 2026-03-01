package com.oneclaw.shadow.feature.skill.usecase

import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.skill.SkillRegistry

class ImportSkillUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(content: String): AppResult<SkillDefinition> =
        skillRegistry.importSkill(content)
}
