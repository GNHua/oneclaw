package com.tomandy.oneclaw.plugin

import com.tomandy.oneclaw.agent.profile.AgentProfileEntry
import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object DelegateAgentPluginMetadata {

    private const val DELEGATION_TIMEOUT_MS = 600_000L // 10 minutes

    fun get(profiles: List<AgentProfileEntry>): PluginMetadata {
        val delegatable = profiles.filter { it.name != "main" }
        return PluginMetadata(
            id = "delegate_agent",
            name = "Agent Delegation",
            version = "1.0.0",
            description = "Delegate tasks to specialized agent profiles",
            author = "OneClaw",
            entryPoint = "DelegateAgentPlugin",
            tools = listOf(delegateToolDefinition(delegatable))
        )
    }

    private fun delegateToolDefinition(
        profiles: List<AgentProfileEntry>
    ): ToolDefinition {
        val profileList = if (profiles.isNotEmpty()) {
            profiles.joinToString("\n") { "- ${it.name}: ${it.description}" }
        } else {
            "(no agent profiles available for delegation)"
        }

        return ToolDefinition(
            name = "delegate_to_agent",
            description = """Delegate a task to a specialized agent profile.
                |
                |The sub-agent runs independently with its own system prompt and tools.
                |It does NOT see the current conversation history -- you must describe
                |the task fully in the 'task' parameter.
                |
                |Available agents for delegation:
                |$profileList
                |
                |IMPORTANT: Only delegate ONCE per task. After receiving the sub-agent's
                |result, use it directly in your response to the user. Do NOT delegate
                |the same task again or call this tool multiple times for the same request.
            """.trimMargin(),
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {
                    putJsonObject("agent") {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive("Name of the agent profile to delegate to")
                        )
                        if (profiles.isNotEmpty()) {
                            putJsonArray("enum") {
                                profiles.forEach { add(JsonPrimitive(it.name)) }
                            }
                        }
                    }
                    putJsonObject("task") {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive(
                                "Complete description of the task. Be specific -- " +
                                    "the sub-agent has no access to the current conversation."
                            )
                        )
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("agent"))
                    add(JsonPrimitive("task"))
                }
            },
            timeoutMs = DELEGATION_TIMEOUT_MS
        )
    }
}
