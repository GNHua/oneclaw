package com.tomandy.oneclaw.skill

import android.util.Log

/**
 * Parses SKILL.md files with YAML frontmatter per the Agent Skills standard.
 *
 * Expected format:
 * ```
 * ---
 * name: skill-name
 * description: A short description
 * disable-model-invocation: false
 * ---
 *
 * Markdown body content here...
 * ```
 *
 * Standard fields: name, description, disable-model-invocation.
 * Unknown fields are silently ignored per the Agent Skills standard.
 */
object SkillFrontmatterParser {

    private const val TAG = "SkillFrontmatterParser"
    private const val MAX_NAME_LENGTH = 64
    private val NAME_PATTERN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?\$")

    data class ParseResult(
        val metadata: SkillMetadata,
        val body: String
    )

    fun parse(content: String, dirName: String? = null): ParseResult {
        val trimmed = content.trimStart()
        require(trimmed.startsWith("---")) { "SKILL.md must start with ---" }

        val afterFirst = trimmed.removePrefix("---")
        val endIndex = afterFirst.indexOf("\n---")
        require(endIndex >= 0) { "SKILL.md must have a closing --- delimiter" }

        val frontmatter = afterFirst.substring(0, endIndex)
        val body = afterFirst.substring(endIndex + 4).trimStart('\n', '\r')

        val metadata = parseFrontmatter(frontmatter, dirName)
        return ParseResult(metadata, body)
    }

    /**
     * Parse only the frontmatter metadata, skipping the body.
     * Used for lightweight skill discovery without loading full content.
     */
    fun parseMetadataOnly(content: String, dirName: String? = null): SkillMetadata {
        val trimmed = content.trimStart()
        require(trimmed.startsWith("---")) { "SKILL.md must start with ---" }

        val afterFirst = trimmed.removePrefix("---")
        val endIndex = afterFirst.indexOf("\n---")
        require(endIndex >= 0) { "SKILL.md must have a closing --- delimiter" }

        val frontmatter = afterFirst.substring(0, endIndex)
        return parseFrontmatter(frontmatter, dirName)
    }

    private fun parseFrontmatter(raw: String, dirName: String?): SkillMetadata {
        val lines = raw.lines()

        var name = ""
        var description = ""
        var disableModelInvocation = false

        for (line in lines) {
            if (line.isBlank()) continue

            val colonIndex = line.indexOf(':')
            if (colonIndex < 0) continue

            val key = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            if (value.isEmpty()) continue

            when (key) {
                "name" -> name = unquote(value)
                "description" -> description = unquote(value)
                "disable-model-invocation" ->
                    disableModelInvocation = value.toBooleanStrictOrNull() ?: false
            }
        }

        require(name.isNotBlank()) { "Skill name is required" }
        require(description.isNotBlank()) { "Skill description is required" }

        validateName(name, dirName)

        return SkillMetadata(
            name = name,
            description = description,
            disableModelInvocation = disableModelInvocation
        )
    }

    private fun validateName(name: String, dirName: String?) {
        if (name.length > MAX_NAME_LENGTH) {
            Log.w(TAG, "Skill name '$name' exceeds $MAX_NAME_LENGTH characters (${name.length})")
        }
        if (!NAME_PATTERN.matches(name)) {
            Log.w(
                TAG,
                "Skill name '$name' does not match standard pattern " +
                    "(lowercase a-z, 0-9, hyphens; no leading/trailing/consecutive hyphens)"
            )
        }
        if (name.contains("--")) {
            Log.w(TAG, "Skill name '$name' contains consecutive hyphens")
        }
        if (dirName != null && name != dirName) {
            Log.w(TAG, "Skill name '$name' does not match parent directory '$dirName'")
        }
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
