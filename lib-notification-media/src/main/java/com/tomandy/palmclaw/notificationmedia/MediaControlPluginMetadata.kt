package com.tomandy.palmclaw.notificationmedia

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import kotlinx.serialization.json.*

object MediaControlPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "media_control",
            name = "Media Control",
            version = "1.0.0",
            description = "Control media playback and get current track information",
            author = "PalmClaw Team",
            entryPoint = "MediaControlPlugin",
            tools = listOf(
                getMediaInfoTool(),
                mediaPlayPauseTool(),
                mediaSkipNextTool(),
                mediaSkipPreviousTool(),
                mediaStopTool()
            ),
            category = "media_control"
        )
    }

    private fun sessionIdProperty(): Pair<String, JsonObject> {
        return "session_id" to buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive("Package name of the media app to target. If omitted, targets the first active session."))
        }
    }

    private fun getMediaInfoTool() = ToolDefinition(
        name = "get_media_info",
        description = """Get current media playback information.
            |
            |Returns the playback state (playing/paused/stopped), track title, artist, album,
            |and duration for active media sessions.
            |
            |Requires the PalmClaw notification listener service to be enabled in system settings.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                val (name, schema) = sessionIdProperty()
                put(name, schema)
            }
        }
    )

    private fun mediaPlayPauseTool() = ToolDefinition(
        name = "media_play_pause",
        description = "Toggle play/pause on the active media session.",
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                val (name, schema) = sessionIdProperty()
                put(name, schema)
            }
        }
    )

    private fun mediaSkipNextTool() = ToolDefinition(
        name = "media_skip_next",
        description = "Skip to the next track in the active media session.",
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                val (name, schema) = sessionIdProperty()
                put(name, schema)
            }
        }
    )

    private fun mediaSkipPreviousTool() = ToolDefinition(
        name = "media_skip_previous",
        description = "Skip to the previous track in the active media session.",
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                val (name, schema) = sessionIdProperty()
                put(name, schema)
            }
        }
    )

    private fun mediaStopTool() = ToolDefinition(
        name = "media_stop",
        description = "Stop playback on the active media session.",
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                val (name, schema) = sessionIdProperty()
                put(name, schema)
            }
        }
    )
}
