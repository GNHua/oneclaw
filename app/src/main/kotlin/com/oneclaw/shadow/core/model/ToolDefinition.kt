package com.oneclaw.shadow.core.model

data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: ToolParametersSchema,
    val requiredPermissions: List<String>,
    val timeoutSeconds: Int
)

data class ToolParametersSchema(
    val properties: Map<String, ToolParameter>,
    val required: List<String> = emptyList()
)

data class ToolParameter(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val default: Any? = null,
    val items: ToolParameter? = null
)
