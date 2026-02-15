package com.tomandy.palmclaw.skill

data class SkillMetadata(
    val name: String,
    val description: String,
    val command: String,
    val defaultEnabled: Boolean = true,
    val requirements: List<SkillRequirement> = emptyList(),
    val tags: List<String> = emptyList(),
    val tool: String? = null
)

sealed class SkillRequirement {
    data class Credential(val provider: String) : SkillRequirement()
}
