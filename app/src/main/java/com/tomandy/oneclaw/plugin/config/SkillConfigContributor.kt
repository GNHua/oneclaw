package com.tomandy.oneclaw.plugin.config

import com.tomandy.oneclaw.engine.ToolResult
import com.tomandy.oneclaw.plugin.ConfigContributor
import com.tomandy.oneclaw.plugin.ConfigEntry
import com.tomandy.oneclaw.plugin.ConfigType
import com.tomandy.oneclaw.skill.SkillPreferences
import com.tomandy.oneclaw.skill.SkillRepository

class SkillConfigContributor(
    private val skillRepository: SkillRepository,
    private val skillPreferences: SkillPreferences
) : ConfigContributor {

    override fun contribute(): List<ConfigEntry> = listOf(
        ConfigEntry(
            key = "skills",
            displayName = "Skills",
            description = "Enable/disable skills. Set value as \"skill_name:true\" or \"skill_name:false\".",
            type = ConfigType.StringType,
            getter = {
                val all = skillRepository.skills.value
                if (all.isEmpty()) {
                    "No skills loaded."
                } else {
                    all.joinToString("\n    ") { s ->
                        val enabled = skillPreferences.isSkillEnabled(s.metadata.name)
                        "${s.metadata.name}: ${if (enabled) "enabled" else "disabled"} - ${s.metadata.description}"
                    }
                }
            },
            setter = {},
            customHandler = { value -> handleSkillToggle(value) }
        )
    )

    private fun handleSkillToggle(value: String): ToolResult {
        val parts = value.split(":", limit = 2)
        if (parts.size != 2) {
            return ToolResult.Failure(
                "Invalid format. Use \"skill_name:true\" or \"skill_name:false\"."
            )
        }
        val skillName = parts[0].trim()
        val enabled = when (parts[1].trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> return ToolResult.Failure(
                "Invalid value \"${parts[1]}\". Use true/false."
            )
        }
        val skill = skillRepository.skills.value.find {
            it.metadata.name.equals(skillName, ignoreCase = true)
        } ?: return ToolResult.Failure("Unknown skill: \"$skillName\".")
        skillPreferences.setSkillEnabled(skill.metadata.name, enabled)
        val state = if (enabled) "enabled" else "disabled"
        return ToolResult.Success(
            "Skill \"${skill.metadata.name}\" $state. Takes effect on the next message."
        )
    }
}
