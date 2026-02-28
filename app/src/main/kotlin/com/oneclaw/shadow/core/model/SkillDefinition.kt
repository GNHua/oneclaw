package com.oneclaw.shadow.core.model

/**
 * In-memory representation of a skill's metadata, parsed from SKILL.md frontmatter.
 * RFC-014
 */
data class SkillDefinition(
    val name: String,
    val displayName: String,
    val description: String,
    val version: String = "1.0",
    val toolsRequired: List<String> = emptyList(),
    val parameters: List<SkillParameter> = emptyList(),
    val isBuiltIn: Boolean,
    val directoryPath: String
)

data class SkillParameter(
    val name: String,
    val type: String = "string",
    val required: Boolean,
    val description: String
)
