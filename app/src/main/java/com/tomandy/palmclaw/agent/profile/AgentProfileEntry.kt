package com.tomandy.palmclaw.agent.profile

data class AgentProfileEntry(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val model: String? = null,
    val allowedTools: List<String>? = null,
    val enabledSkills: List<String>? = null,
    val source: AgentProfileSource = AgentProfileSource.USER,
    val filePath: String? = null
)

enum class AgentProfileSource {
    BUNDLED,
    USER
}
