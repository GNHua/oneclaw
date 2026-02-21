package com.tomandy.oneclaw.agent.profile

import android.util.Log

object AgentProfileParser {

    private const val TAG = "AgentProfileParser"
    private const val MAX_NAME_LENGTH = 64
    private val NAME_PATTERN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?\$")

    data class ParseResult(
        val name: String,
        val description: String,
        val model: String?,
        val allowedTools: List<String>?,
        val enabledSkills: List<String>?,
        val temperature: Float?,
        val maxIterations: Int?,
        val body: String
    )

    fun parse(content: String, expectedName: String? = null): ParseResult {
        val trimmed = content.trimStart()
        require(trimmed.startsWith("---")) { "AGENT.md must start with ---" }

        val afterFirst = trimmed.removePrefix("---")
        val endIndex = afterFirst.indexOf("\n---")
        require(endIndex >= 0) { "AGENT.md must have a closing --- delimiter" }

        val frontmatter = afterFirst.substring(0, endIndex)
        val body = afterFirst.substring(endIndex + 4).trimStart('\n', '\r')

        var name = ""
        var description = ""
        var model: String? = null
        var allowedTools: List<String>? = null
        var enabledSkills: List<String>? = null
        var temperature: Float? = null
        var maxIterations: Int? = null

        for (line in frontmatter.lines()) {
            if (line.isBlank()) continue

            val colonIndex = line.indexOf(':')
            if (colonIndex < 0) continue

            val key = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            if (value.isEmpty()) continue

            when (key) {
                "name" -> name = unquote(value)
                "description" -> description = unquote(value)
                "model" -> model = unquote(value)
                "allowed-tools" -> allowedTools = parseList(value)
                "enabled-skills" -> enabledSkills = parseList(value)
                "temperature" -> {
                    val parsed = unquote(value).toFloatOrNull()
                    if (parsed != null && parsed in 0.0f..2.0f) {
                        temperature = parsed
                    } else {
                        Log.w(TAG, "Invalid temperature value '$value' (must be 0.0-2.0)")
                    }
                }
                "max-iterations" -> {
                    val parsed = unquote(value).toIntOrNull()
                    if (parsed != null && parsed in 1..500) {
                        maxIterations = parsed
                    } else {
                        Log.w(TAG, "Invalid max-iterations value '$value' (must be 1-500)")
                    }
                }
            }
        }

        require(name.isNotBlank()) { "Agent name is required" }
        require(description.isNotBlank()) { "Agent description is required" }

        validateName(name, expectedName)

        return ParseResult(name, description, model, allowedTools, enabledSkills, temperature, maxIterations, body)
    }

    private fun validateName(name: String, expectedName: String?) {
        if (name.length > MAX_NAME_LENGTH) {
            Log.w(TAG, "Agent name '$name' exceeds $MAX_NAME_LENGTH characters (${name.length})")
        }
        if (!NAME_PATTERN.matches(name)) {
            Log.w(
                TAG,
                "Agent name '$name' does not match standard pattern " +
                    "(lowercase a-z, 0-9, hyphens; no leading/trailing/consecutive hyphens)"
            )
        }
        if (name.contains("--")) {
            Log.w(TAG, "Agent name '$name' contains consecutive hyphens")
        }
        if (expectedName != null && name != expectedName) {
            Log.w(TAG, "Agent name '$name' does not match expected name '$expectedName'")
        }
    }

    private fun parseList(value: String): List<String>? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(",").map { unquote(it.trim()) }.filter { it.isNotBlank() }
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            ) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }
}
