package com.tomandy.palmclaw.skill

/**
 * Parses SKILL.md files with YAML frontmatter.
 *
 * Expected format:
 * ```
 * ---
 * name: skill-name
 * description: A short description
 * command: /cmd
 * enabled: true
 * requirements:
 *   - credential: Anthropic
 * tags:
 *   - development
 *   - code
 * tool: null
 * ---
 *
 * Markdown body content here...
 * ```
 */
object SkillFrontmatterParser {

    data class ParseResult(
        val metadata: SkillMetadata,
        val body: String
    )

    fun parse(content: String): ParseResult {
        val trimmed = content.trimStart()
        require(trimmed.startsWith("---")) { "SKILL.md must start with ---" }

        val afterFirst = trimmed.removePrefix("---")
        val endIndex = afterFirst.indexOf("\n---")
        require(endIndex >= 0) { "SKILL.md must have a closing --- delimiter" }

        val frontmatter = afterFirst.substring(0, endIndex)
        val body = afterFirst.substring(endIndex + 4).trimStart('\n', '\r')

        val metadata = parseFrontmatter(frontmatter)
        return ParseResult(metadata, body)
    }

    private fun parseFrontmatter(raw: String): SkillMetadata {
        val lines = raw.lines()

        var name = ""
        var description = ""
        var command = ""
        var enabled = true
        var tool: String? = null
        val tags = mutableListOf<String>()
        val requirements = mutableListOf<SkillRequirement>()

        var currentListKey: String? = null

        for (line in lines) {
            // Blank line resets list context
            if (line.isBlank()) {
                currentListKey = null
                continue
            }

            // Indented line = list item under currentListKey
            if (line.startsWith("  ") && currentListKey != null) {
                val item = line.trim()
                if (item.startsWith("- ")) {
                    val value = item.removePrefix("- ").trim()
                    when (currentListKey) {
                        "tags" -> tags.add(value)
                        "requirements" -> {
                            // Format: credential: ProviderName
                            if (value.startsWith("credential:")) {
                                val provider = value.removePrefix("credential:").trim()
                                if (provider.isNotEmpty()) {
                                    requirements.add(SkillRequirement.Credential(provider))
                                }
                            }
                        }
                    }
                }
                continue
            }

            // Top-level key: value
            val colonIndex = line.indexOf(':')
            if (colonIndex < 0) {
                currentListKey = null
                continue
            }

            val key = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()

            // If value is empty, this starts a list
            if (value.isEmpty()) {
                currentListKey = key
                continue
            }

            currentListKey = null

            when (key) {
                "name" -> name = unquote(value)
                "description" -> description = unquote(value)
                "command" -> command = unquote(value)
                "enabled" -> enabled = value.toBooleanStrictOrNull() ?: true
                "tool" -> tool = if (value == "null" || value.isEmpty()) null else unquote(value)
            }
        }

        require(name.isNotBlank()) { "Skill name is required" }
        require(description.isNotBlank()) { "Skill description is required" }
        require(command.isNotBlank()) { "Skill command is required" }

        return SkillMetadata(
            name = name,
            description = description,
            command = if (command.startsWith("/")) command else "/$command",
            defaultEnabled = enabled,
            requirements = requirements,
            tags = tags,
            tool = tool
        )
    }

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }
}
