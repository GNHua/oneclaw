package com.oneclaw.shadow.tool.engine

import android.content.Context

/**
 * Persists the global enabled/disabled state for tools and tool groups.
 *
 * Uses plain SharedPreferences (not EncryptedSharedPreferences) since enabled
 * states are non-sensitive configuration.
 *
 * Default for all tools and groups: enabled (true).
 */
class ToolEnabledStateStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "tool_enabled_state"
        private const val PREFIX_TOOL = "tool:"
        private const val PREFIX_GROUP = "group:"
    }

    /**
     * Whether the individual tool toggle is enabled.
     * Defaults to true if never set.
     */
    fun isToolEnabled(toolName: String): Boolean =
        prefs.getBoolean("$PREFIX_TOOL$toolName", true)

    /**
     * Set the enabled state for an individual tool.
     */
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        prefs.edit().putBoolean("$PREFIX_TOOL$toolName", enabled).apply()
    }

    /**
     * Whether the group-level toggle is enabled.
     * Defaults to true if never set.
     */
    fun isGroupEnabled(groupName: String): Boolean =
        prefs.getBoolean("$PREFIX_GROUP$groupName", true)

    /**
     * Set the enabled state for a group (affects all tools in the group).
     */
    fun setGroupEnabled(groupName: String, enabled: Boolean) {
        prefs.edit().putBoolean("$PREFIX_GROUP$groupName", enabled).apply()
    }

    /**
     * Effective enabled state: a tool is effectively enabled only if both its
     * individual toggle and (if applicable) its group toggle are enabled.
     *
     * @param toolName The tool name
     * @param groupName Optional group name; if provided the group toggle is also checked
     */
    fun isToolEffectivelyEnabled(toolName: String, groupName: String? = null): Boolean {
        if (!isToolEnabled(toolName)) return false
        if (groupName != null && !isGroupEnabled(groupName)) return false
        return true
    }
}
