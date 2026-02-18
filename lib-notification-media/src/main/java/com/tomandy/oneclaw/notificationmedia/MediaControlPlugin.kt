package com.tomandy.oneclaw.notificationmedia

import android.media.session.MediaController
import android.media.session.PlaybackState
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class MediaControlPlugin : Plugin {

    private lateinit var context: PluginContext

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        if (!NotificationMediaServiceManager.isServiceConnected()) {
            NotificationMediaServiceManager.promptEnableService()
            return ToolResult.Failure(
                "Notification listener service is not enabled. " +
                    "The user must enable OneClaw in Settings > Notifications > Notification access " +
                    "before media control tools can be used (media sessions require notification listener access)."
            )
        }

        return when (toolName) {
            "get_media_info" -> getMediaInfo(arguments)
            "media_play_pause" -> mediaPlayPause(arguments)
            "media_skip_next" -> mediaSkipNext(arguments)
            "media_skip_previous" -> mediaSkipPrevious(arguments)
            "media_stop" -> mediaStop(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private fun findSession(arguments: JsonObject): MediaController? {
        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val sessions = NotificationMediaServiceManager.getActiveMediaSessions(
            context.getApplicationContext()
        )
        if (sessions.isEmpty()) return null
        return if (sessionId != null) {
            sessions.find { it.packageName == sessionId }
        } else {
            sessions.first()
        }
    }

    private fun getMediaInfo(arguments: JsonObject): ToolResult {
        val sessions = NotificationMediaServiceManager.getActiveMediaSessions(
            context.getApplicationContext()
        )

        if (sessions.isEmpty()) {
            return ToolResult.Success("No active media sessions.")
        }

        val sessionId = arguments["session_id"]?.jsonPrimitive?.content
        val targetSessions = if (sessionId != null) {
            sessions.filter { it.packageName == sessionId }
        } else {
            sessions
        }

        if (targetSessions.isEmpty()) {
            return ToolResult.Failure("No media session found for package '$sessionId'.")
        }

        val output = buildString {
            appendLine("Active media sessions (${targetSessions.size}):")
            appendLine()
            for (controller in targetSessions) {
                appendLine("- Package: ${controller.packageName}")

                val metadata = controller.metadata
                if (metadata != null) {
                    val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                    val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                    val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
                    val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)

                    if (title != null) appendLine("  Title: $title")
                    if (artist != null) appendLine("  Artist: $artist")
                    if (album != null) appendLine("  Album: $album")
                    if (duration > 0) {
                        val mins = duration / 1000 / 60
                        val secs = (duration / 1000) % 60
                        appendLine("  Duration: ${mins}m ${secs}s")
                    }
                }

                val state = controller.playbackState
                if (state != null) {
                    val stateName = when (state.state) {
                        PlaybackState.STATE_PLAYING -> "playing"
                        PlaybackState.STATE_PAUSED -> "paused"
                        PlaybackState.STATE_STOPPED -> "stopped"
                        PlaybackState.STATE_BUFFERING -> "buffering"
                        PlaybackState.STATE_CONNECTING -> "connecting"
                        PlaybackState.STATE_ERROR -> "error"
                        PlaybackState.STATE_NONE -> "none"
                        else -> "unknown (${state.state})"
                    }
                    appendLine("  State: $stateName")

                    val position = state.position
                    if (position > 0) {
                        val mins = position / 1000 / 60
                        val secs = (position / 1000) % 60
                        appendLine("  Position: ${mins}m ${secs}s")
                    }
                }
                appendLine()
            }
        }

        return ToolResult.Success(output.trim())
    }

    private fun mediaPlayPause(arguments: JsonObject): ToolResult {
        val controller = findSession(arguments)
            ?: return ToolResult.Failure("No active media session found.")

        val state = controller.playbackState?.state
        if (state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
            return ToolResult.Success("Paused playback on ${controller.packageName}.")
        } else {
            controller.transportControls.play()
            return ToolResult.Success("Resumed playback on ${controller.packageName}.")
        }
    }

    private fun mediaSkipNext(arguments: JsonObject): ToolResult {
        val controller = findSession(arguments)
            ?: return ToolResult.Failure("No active media session found.")
        controller.transportControls.skipToNext()
        return ToolResult.Success("Skipped to next track on ${controller.packageName}.")
    }

    private fun mediaSkipPrevious(arguments: JsonObject): ToolResult {
        val controller = findSession(arguments)
            ?: return ToolResult.Failure("No active media session found.")
        controller.transportControls.skipToPrevious()
        return ToolResult.Success("Skipped to previous track on ${controller.packageName}.")
    }

    private fun mediaStop(arguments: JsonObject): ToolResult {
        val controller = findSession(arguments)
            ?: return ToolResult.Failure("No active media session found.")
        controller.transportControls.stop()
        return ToolResult.Success("Stopped playback on ${controller.packageName}.")
    }
}
