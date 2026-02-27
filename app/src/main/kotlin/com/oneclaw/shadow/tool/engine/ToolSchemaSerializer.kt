package com.oneclaw.shadow.tool.engine

import com.oneclaw.shadow.core.model.ToolParametersSchema

/**
 * Serializes ToolParametersSchema to a JSON Schema map.
 * Used by all provider adapters when formatting tool definitions for the API.
 */
object ToolSchemaSerializer {

    fun toJsonSchemaMap(schema: ToolParametersSchema): Map<String, Any> {
        val properties = schema.properties.map { (name, param) ->
            val paramMap = mutableMapOf<String, Any>(
                "type" to param.type,
                "description" to param.description
            )
            param.enum?.let { paramMap["enum"] = it }
            name to paramMap
        }.toMap()

        val result = mutableMapOf<String, Any>(
            "type" to "object",
            "properties" to properties
        )
        if (schema.required.isNotEmpty()) {
            result["required"] = schema.required
        }
        return result
    }

    /**
     * Same as toJsonSchemaMap but with uppercase type names, as required by Gemini.
     */
    fun toGeminiSchemaMap(schema: ToolParametersSchema): Map<String, Any> {
        val properties = schema.properties.map { (name, param) ->
            val paramMap = mutableMapOf<String, Any>(
                "type" to param.type.uppercase(),
                "description" to param.description
            )
            param.enum?.let { paramMap["enum"] = it }
            name to paramMap
        }.toMap()

        val result = mutableMapOf<String, Any>(
            "type" to "OBJECT",
            "properties" to properties
        )
        if (schema.required.isNotEmpty()) {
            result["required"] = schema.required
        }
        return result
    }
}
