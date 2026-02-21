package com.tomandy.oneclaw.agent.profile

data class AgentProfileEntry(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val model: String? = null,
    val allowedTools: List<String>? = null,
    val enabledSkills: List<String>? = null,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    val source: AgentProfileSource = AgentProfileSource.USER,
    val filePath: String? = null
)

enum class AgentProfileSource {
    BUNDLED,
    USER
}

const val DEFAULT_TEMPERATURE = 0.2f
const val DEFAULT_MAX_ITERATIONS = 200
