package com.oneclaw.shadow.tool.skill

import android.util.Log
import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.core.model.SkillParameter
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import java.io.File

/**
 * Parses SKILL.md files into SkillDefinition metadata and raw prompt content.
 * Uses a minimal hand-rolled YAML frontmatter parser for the known schema.
 * RFC-014
 */
class SkillFileParser {

    companion object {
        private const val TAG = "SkillFileParser"
        private const val FRONTMATTER_DELIMITER = "---"
        private const val MAX_SKILL_SIZE = 100 * 1024  // 100KB

        // Name validation: lowercase letters/digits, hyphens inside, 2-50 chars total
        private val NAME_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$")
    }

    data class ParseResult(
        val definition: SkillDefinition,
        val promptContent: String
    )

    /**
     * Parse a SKILL.md file from the file system into metadata + prompt content.
     */
    fun parse(filePath: String, isBuiltIn: Boolean): AppResult<ParseResult> {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return AppResult.Error(
                    message = "SKILL.md not found at: $filePath",
                    code = ErrorCode.STORAGE_ERROR
                )
            }
            if (file.length() > MAX_SKILL_SIZE) {
                return AppResult.Error(
                    message = "SKILL.md exceeds maximum size (100KB): $filePath",
                    code = ErrorCode.VALIDATION_ERROR
                )
            }
            val content = file.readText(Charsets.UTF_8)
            val directoryPath = file.parentFile?.absolutePath ?: ""
            parseContent(content, isBuiltIn, directoryPath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read SKILL.md at $filePath", e)
            AppResult.Error(
                exception = e,
                message = "Failed to read SKILL.md: ${e.message}",
                code = ErrorCode.STORAGE_ERROR
            )
        }
    }

    /**
     * Parse from raw content string (used for import validation or asset reading).
     */
    fun parseContent(
        content: String,
        isBuiltIn: Boolean,
        directoryPath: String
    ): AppResult<ParseResult> {
        val lines = content.lines()

        // Must start with ---
        if (lines.isEmpty() || lines[0].trim() != FRONTMATTER_DELIMITER) {
            return AppResult.Error(
                message = "SKILL.md must start with --- frontmatter delimiter",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        // Find closing ---
        val closingIndex = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
        if (closingIndex < 0) {
            return AppResult.Error(
                message = "SKILL.md frontmatter is not closed with ---",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        val frontmatterLines = lines.drop(1).take(closingIndex)
        val promptLines = lines.drop(1 + closingIndex + 1)  // after the second ---

        val promptContent = promptLines.joinToString("\n").trim()
        if (promptContent.isEmpty()) {
            return AppResult.Error(
                message = "SKILL.md prompt content (after frontmatter) must not be empty",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        return try {
            val frontmatter = parseFrontmatter(frontmatterLines)
            val definition = buildDefinition(frontmatter, isBuiltIn, directoryPath)
            AppResult.Success(ParseResult(definition = definition, promptContent = promptContent))
        } catch (e: IllegalArgumentException) {
            AppResult.Error(
                exception = e,
                message = e.message ?: "Invalid frontmatter",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
    }

    /**
     * Serialize a SkillDefinition + prompt content back to SKILL.md format.
     */
    fun serialize(definition: SkillDefinition, promptContent: String): String {
        return buildString {
            appendLine("---")
            appendLine("name: ${definition.name}")
            appendLine("display_name: \"${definition.displayName.replace("\"", "\\\"")}\"")
            appendLine("description: \"${definition.description.replace("\"", "\\\"")}\"")
            appendLine("version: \"${definition.version}\"")
            if (definition.toolsRequired.isNotEmpty()) {
                appendLine("tools_required:")
                definition.toolsRequired.forEach { appendLine("  - $it") }
            }
            if (definition.parameters.isNotEmpty()) {
                appendLine("parameters:")
                definition.parameters.forEach { param ->
                    appendLine("  - name: ${param.name}")
                    appendLine("    type: ${param.type}")
                    appendLine("    required: ${param.required}")
                    appendLine("    description: \"${param.description.replace("\"", "\\\"")}\"")
                }
            }
            appendLine("---")
            appendLine()
            append(promptContent)
        }
    }

    /**
     * Substitute {{param_name}} placeholders in prompt content with actual values.
     */
    fun substituteParameters(
        promptContent: String,
        parameterValues: Map<String, String>
    ): String {
        var result = promptContent
        parameterValues.forEach { (name, value) ->
            result = result.replace("{{$name}}", value)
        }
        return result
    }

    // ---- Internal parsing helpers ----

    private fun parseFrontmatter(lines: List<String>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                i++
                continue
            }

            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) { i++; continue }

            val rawKey = line.substring(0, colonIdx).trim()
            val rawValue = line.substring(colonIdx + 1).trim()

            when (rawKey) {
                "tools_required", "parameters" -> {
                    // Parse list under this key
                    val listItems = mutableListOf<Any>()
                    i++
                    while (i < lines.size) {
                        val itemLine = lines[i]
                        if (itemLine.isBlank()) { i++; continue }
                        if (!itemLine.startsWith("  ") && !itemLine.startsWith("\t")) break
                        val trimmed = itemLine.trim()
                        if (trimmed.startsWith("- ") && !trimmed.startsWith("- name:")) {
                            // Simple list item like "  - read_file"
                            listItems.add(trimmed.removePrefix("- ").trim())
                            i++
                        } else if (trimmed.startsWith("- name:")) {
                            // Start of a parameter object
                            val paramMap = mutableMapOf<String, String>()
                            // parse "- name: foo" on the same line
                            paramMap["name"] = trimmed.removePrefix("- name:").trim()
                            i++
                            while (i < lines.size) {
                                val pLine = lines[i]
                                if (pLine.isBlank()) { i++; continue }
                                if (!pLine.startsWith("  ") && !pLine.startsWith("\t")) break
                                val pTrimmed = pLine.trim()
                                if (pTrimmed.startsWith("- ")) break  // next list item
                                val pColon = pTrimmed.indexOf(':')
                                if (pColon >= 0) {
                                    val pKey = pTrimmed.substring(0, pColon).trim()
                                    val pVal = pTrimmed.substring(pColon + 1).trim()
                                    paramMap[pKey] = unquote(pVal)
                                }
                                i++
                            }
                            listItems.add(paramMap)
                        } else {
                            i++
                        }
                    }
                    result[rawKey] = listItems
                }
                else -> {
                    result[rawKey] = unquote(rawValue)
                    i++
                }
            }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildDefinition(
        frontmatter: Map<String, Any>,
        isBuiltIn: Boolean,
        directoryPath: String
    ): SkillDefinition {
        val name = (frontmatter["name"] as? String)?.trim()
            ?: throw IllegalArgumentException("'name' field is required in SKILL.md frontmatter")
        if (!NAME_REGEX.matches(name)) {
            throw IllegalArgumentException(
                "Skill name '$name' is invalid. Must match ^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]\$"
            )
        }

        val displayName = (frontmatter["display_name"] as? String)?.trim()
            ?: throw IllegalArgumentException("'display_name' field is required in SKILL.md frontmatter")
        if (displayName.isBlank()) {
            throw IllegalArgumentException("'display_name' must not be empty")
        }

        val description = (frontmatter["description"] as? String)?.trim()
            ?: throw IllegalArgumentException("'description' field is required in SKILL.md frontmatter")
        if (description.isBlank()) {
            throw IllegalArgumentException("'description' must not be empty")
        }

        val version = (frontmatter["version"] as? String)?.trim() ?: "1.0"

        val toolsRequired = (frontmatter["tools_required"] as? List<*>)
            ?.filterIsInstance<String>() ?: emptyList()

        val parameters = (frontmatter["parameters"] as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?.map { paramMap ->
                val pName = paramMap["name"]?.toString()?.trim()
                    ?: throw IllegalArgumentException("Parameter missing 'name' field")
                val pType = paramMap["type"]?.toString()?.trim() ?: "string"
                val pRequired = paramMap["required"]?.toString()?.trim()?.lowercase() == "true"
                val pDesc = paramMap["description"]?.toString()?.trim() ?: ""
                SkillParameter(name = pName, type = pType, required = pRequired, description = pDesc)
            } ?: emptyList()

        return SkillDefinition(
            name = name,
            displayName = displayName,
            description = description,
            version = version,
            toolsRequired = toolsRequired,
            parameters = parameters,
            isBuiltIn = isBuiltIn,
            directoryPath = directoryPath
        )
    }

    /** Strip surrounding quotes from a YAML scalar value. */
    private fun unquote(value: String): String {
        val v = value.trim()
        return when {
            v.startsWith('"') && v.endsWith('"') -> v.drop(1).dropLast(1).replace("\\\"", "\"")
            v.startsWith('\'') && v.endsWith('\'') -> v.drop(1).dropLast(1)
            else -> v
        }
    }
}
