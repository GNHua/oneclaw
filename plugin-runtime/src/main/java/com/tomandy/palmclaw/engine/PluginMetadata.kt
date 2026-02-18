package com.tomandy.palmclaw.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Metadata describing a plugin's capabilities and requirements.
 *
 * This data is typically loaded from a `plugin.json` file alongside the plugin's .kts source.
 *
 * Example:
 * ```json
 * {
 *   "id": "gmail_api",
 *   "name": "Gmail API",
 *   "version": "1.0.0",
 *   "description": "Search and send emails via Gmail API",
 *   "author": "PalmClaw Team",
 *   "entryPoint": "GmailPlugin",
 *   "tools": [...],
 *   "permissions": ["INTERNET"],
 *   "dependencies": []
 * }
 * ```
 */
@Serializable
data class PluginMetadata(
    /**
     * Unique identifier for the plugin (e.g., "gmail_api").
     * Must be unique across all plugins.
     */
    val id: String,

    /**
     * Human-readable display name (e.g., "Gmail API").
     */
    val name: String,

    /**
     * Semantic version string (e.g., "1.0.0").
     */
    val version: String,

    /**
     * Short description of what the plugin does.
     */
    val description: String,

    /**
     * Author or organization name.
     */
    val author: String,

    /**
     * Kotlin class name that implements the Plugin interface (e.g., "GmailPlugin").
     * This class must have a no-arg constructor.
     */
    val entryPoint: String,

    /**
     * List of tools provided by this plugin.
     * Each tool can be invoked by the LLM agent.
     */
    val tools: List<ToolDefinition>,

    /**
     * Android permissions required by this plugin (e.g., ["INTERNET", "READ_CONTACTS"]).
     * Users must grant these permissions for the plugin to function.
     */
    val permissions: List<String> = emptyList(),

    /**
     * IDs of other plugins this plugin depends on.
     * Dependencies must be loaded before this plugin.
     */
    val dependencies: List<String> = emptyList(),

    /**
     * Tool category for the two-tier tool system.
     *
     * "core" tools are always passed to the LLM. Other categories (e.g., "gmail",
     * "calendar") are only loaded when the LLM calls activate_tools.
     * Defaults to "core" so existing plugins work without changes.
     */
    val category: String = "core",

    /**
     * Credential definitions for plugin-specific configuration.
     * Each entry describes a credential the user can configure in Settings.
     */
    @kotlinx.serialization.Transient
    val credentials: List<CredentialDefinition> = emptyList()
)

/**
 * Describes a configurable credential for a plugin.
 */
data class CredentialDefinition(
    val key: String,
    val label: String,
    val description: String = "",
    val options: List<String> = emptyList(),
    /** When set, this credential's storage key is prefixed by the current value of the referenced dropdown credential. */
    val scopedBy: String = ""
)

/**
 * Definition of a tool that can be invoked by the LLM agent.
 *
 * Tools are the "hands" of the agent - they allow the LLM to perform actions
 * like searching Gmail, fetching web pages, or creating calendar events.
 */
@Serializable
data class ToolDefinition(
    /**
     * Unique name of the tool (e.g., "search_gmail").
     * Must be unique within the plugin.
     */
    val name: String,

    /**
     * Natural language description for the LLM.
     * The LLM uses this to understand when and how to use the tool.
     *
     * Example: "Search Gmail messages by query. Returns a list of matching emails
     * with subject, from, date, and snippet."
     */
    val description: String,

    /**
     * JSON Schema defining the tool's parameters.
     *
     * Example:
     * ```json
     * {
     *   "type": "object",
     *   "properties": {
     *     "query": {
     *       "type": "string",
     *       "description": "Gmail search query"
     *     },
     *     "maxResults": {
     *       "type": "integer",
     *       "description": "Maximum results (default: 10)"
     *     }
     *   },
     *   "required": ["query"]
     * }
     * ```
     */
    val parameters: JsonObject,

    /**
     * Custom timeout in milliseconds for this tool's execution.
     * 0 means use the default timeout from ToolExecutor.
     * Useful for long-running tools like agent delegation.
     */
    @kotlinx.serialization.Transient
    val timeoutMs: Long = 0
)
