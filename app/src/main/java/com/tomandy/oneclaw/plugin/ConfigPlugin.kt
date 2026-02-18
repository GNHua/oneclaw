package com.tomandy.oneclaw.plugin

import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConfigPlugin(
    private val registry: ConfigRegistry
) : Plugin {

    override suspend fun onLoad(context: PluginContext) {}

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "set_app_config" -> setAppConfig(arguments)
            "get_app_config" -> getAppConfig()
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private suspend fun setAppConfig(arguments: JsonObject): ToolResult {
        val settings = arguments.filterKeys { !it.startsWith("_") }

        if (settings.isEmpty()) {
            return ToolResult.Failure(
                "No settings provided. Pass key-value pairs, e.g. " +
                    "{\"max_iterations\": \"100\"}. " +
                    "Call get_app_config to see available settings."
            )
        }

        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for ((key, jsonValue) in settings) {
            val entry = registry.get(key)
            if (entry == null) {
                errors.add("Unknown setting: \"$key\"")
                continue
            }

            val rawValue = jsonValue.jsonPrimitive.content

            val result = if (entry.customHandler != null) {
                entry.customHandler.invoke(rawValue)
            } else {
                validateAndSet(entry, rawValue)
            }

            when (result) {
                is ToolResult.Success -> results.add(result.output)
                is ToolResult.Failure -> errors.add(result.error)
            }
        }

        val output = buildString {
            if (results.isNotEmpty()) {
                appendLine("Updated:")
                results.forEach { appendLine("  - $it") }
            }
            if (errors.isNotEmpty()) {
                appendLine("Errors:")
                errors.forEach { appendLine("  - $it") }
            }
        }.trim()

        return if (errors.isNotEmpty() && results.isEmpty()) {
            ToolResult.Failure(output)
        } else {
            ToolResult.Success(output)
        }
    }

    private suspend fun getAppConfig(): ToolResult {
        val entries = registry.all()
        if (entries.isEmpty()) {
            return ToolResult.Success("No configuration settings available.")
        }

        val output = buildString {
            appendLine("Current configuration:")
            appendLine()
            for (entry in entries) {
                val currentValue = entry.getter()
                appendLine("  ${entry.key}: $currentValue")
                appendLine("    ${entry.description}")
                appendLine("    Type: ${formatType(entry.type)}")
                appendLine()
            }
        }.trim()

        return ToolResult.Success(output)
    }

    private fun formatType(type: ConfigType): String = when (type) {
        is ConfigType.StringType -> "string"
        is ConfigType.IntType -> buildString {
            append("integer")
            if (type.min != null || type.max != null) {
                append(" (")
                if (type.min != null) append("min: ${type.min}")
                if (type.min != null && type.max != null) append(", ")
                if (type.max != null) append("max: ${type.max}")
                append(")")
            }
        }
        is ConfigType.BooleanType -> "boolean"
        is ConfigType.EnumType -> "enum [${type.values.joinToString(", ")}]"
    }

    private fun validateAndSet(entry: ConfigEntry, rawValue: String): ToolResult {
        return when (val type = entry.type) {
            is ConfigType.StringType -> {
                entry.setter(rawValue)
                ToolResult.Success("${entry.displayName} set to \"$rawValue\".")
            }
            is ConfigType.IntType -> {
                val intVal = rawValue.toIntOrNull()
                    ?: return ToolResult.Failure(
                        "Invalid value \"$rawValue\" for ${entry.key}. Expected an integer."
                    )
                if (type.min != null && intVal < type.min) {
                    return ToolResult.Failure(
                        "${entry.key} must be >= ${type.min}. Got: $intVal"
                    )
                }
                if (type.max != null && intVal > type.max) {
                    return ToolResult.Failure(
                        "${entry.key} must be <= ${type.max}. Got: $intVal"
                    )
                }
                entry.setter(intVal.toString())
                ToolResult.Success("${entry.displayName} changed to $intVal.")
            }
            is ConfigType.BooleanType -> {
                val boolVal = when (rawValue.lowercase()) {
                    "true", "1", "yes", "on" -> "true"
                    "false", "0", "no", "off" -> "false"
                    else -> return ToolResult.Failure(
                        "Invalid value \"$rawValue\" for ${entry.key}. Expected true/false."
                    )
                }
                entry.setter(boolVal)
                ToolResult.Success("${entry.displayName} set to $boolVal.")
            }
            is ConfigType.EnumType -> {
                if (rawValue !in type.values) {
                    return ToolResult.Failure(
                        "Invalid value \"$rawValue\" for ${entry.key}. " +
                            "Allowed: ${type.values.joinToString(", ")}"
                    )
                }
                entry.setter(rawValue)
                ToolResult.Success("${entry.displayName} set to \"$rawValue\".")
            }
        }
    }
}
