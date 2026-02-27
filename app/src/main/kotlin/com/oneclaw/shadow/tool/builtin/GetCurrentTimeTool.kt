package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class GetCurrentTimeTool : Tool {

    override val definition = ToolDefinition(
        name = "get_current_time",
        description = "Get the current date and time",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "timezone" to ToolParameter(
                    type = "string",
                    description = "Timezone identifier (e.g., 'America/New_York', 'Asia/Shanghai'). Defaults to device timezone.",
                    default = null
                ),
                "format" to ToolParameter(
                    type = "string",
                    description = "Output format: 'iso8601' or 'human_readable'. Defaults to 'iso8601'.",
                    enum = listOf("iso8601", "human_readable"),
                    default = "iso8601"
                )
            ),
            required = emptyList()
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val timezoneId = parameters["timezone"] as? String
            val format = parameters["format"] as? String ?: "iso8601"

            val zone = if (timezoneId != null) {
                try {
                    ZoneId.of(timezoneId)
                } catch (e: Exception) {
                    return ToolResult.error(
                        "validation_error",
                        "Invalid timezone: '$timezoneId'. Use IANA timezone format (e.g., 'America/New_York')."
                    )
                }
            } else {
                ZoneId.systemDefault()
            }

            val now = ZonedDateTime.now(zone)

            val result = when (format) {
                "human_readable" -> {
                    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm:ss a z")
                    now.format(formatter)
                }
                else -> now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }

            ToolResult.success(result)
        } catch (e: Exception) {
            ToolResult.error("execution_error", "Failed to get current time: ${e.message}")
        }
    }
}
