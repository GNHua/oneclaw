package com.tomandy.palmclaw.plugin

import com.tomandy.palmclaw.engine.ToolResult

sealed class ConfigType {
    data object StringType : ConfigType()
    data class IntType(val min: Int? = null, val max: Int? = null) : ConfigType()
    data object BooleanType : ConfigType()
    data class EnumType(val values: List<String>) : ConfigType()
}

data class ConfigEntry(
    val key: String,
    val displayName: String,
    val description: String,
    val type: ConfigType,
    val getter: suspend () -> String,
    val setter: (String) -> Unit,
    val customHandler: (suspend (String) -> ToolResult)? = null
)

class ConfigRegistry(contributors: List<ConfigContributor>) {
    private val entries = mutableMapOf<String, ConfigEntry>()

    init {
        for (contributor in contributors) {
            for (entry in contributor.contribute()) {
                entries[entry.key] = entry
            }
        }
    }

    fun get(key: String): ConfigEntry? = entries[key]

    fun all(): List<ConfigEntry> = entries.values.toList()
}
