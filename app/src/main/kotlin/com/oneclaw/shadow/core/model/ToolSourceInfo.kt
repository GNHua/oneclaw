package com.oneclaw.shadow.core.model

/**
 * Classifies the origin of a registered tool.
 */
enum class ToolSourceType {
    /** Kotlin built-in tool (e.g., LoadSkillTool) */
    BUILTIN,
    /** Tool belonging to a JS tool group (RFC-018) */
    TOOL_GROUP,
    /** Standalone JS extension loaded from the file system */
    JS_EXTENSION
}

/**
 * Metadata about where a tool came from.
 *
 * @param type Classification of the tool source
 * @param groupName If [type] is [ToolSourceType.TOOL_GROUP], the name of the group (filename base)
 * @param filePath If [type] is [ToolSourceType.JS_EXTENSION] or [ToolSourceType.TOOL_GROUP],
 *                 the absolute path of the .js file on disk
 */
data class ToolSourceInfo(
    val type: ToolSourceType,
    val groupName: String? = null,
    val filePath: String? = null
) {
    companion object {
        val BUILTIN = ToolSourceInfo(type = ToolSourceType.BUILTIN)
    }
}
