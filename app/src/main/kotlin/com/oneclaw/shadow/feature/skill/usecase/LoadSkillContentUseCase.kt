package com.oneclaw.shadow.feature.skill.usecase

import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.skill.SkillRegistry

class LoadSkillContentUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(name: String): AppResult<String> =
        skillRegistry.loadSkillContent(name)
}
