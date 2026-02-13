package com.tomandy.palmclaw.engine

/**
 * A successfully loaded plugin with its metadata and instance.
 *
 * @param metadata The plugin's metadata from plugin.json
 * @param instance The instantiated Plugin implementation
 */
data class LoadedPlugin(
    val metadata: PluginMetadata,
    val instance: Plugin
)

/**
 * Exception thrown when plugin loading fails.
 */
class PluginException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
