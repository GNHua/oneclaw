package com.oneclaw.shadow.core.model

/**
 * Metadata for a tool group. Used to display group information
 * in the system prompt and validate load_tool_group calls.
 */
data class ToolGroupDefinition(
    /** Unique group identifier, e.g. "google_gmail" */
    val name: String,
    /** Human-readable name, e.g. "Google Gmail" */
    val displayName: String,
    /** One-line description of group capabilities */
    val description: String
)
