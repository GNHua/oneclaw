package com.tomandy.oneclaw.plugin

import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import kotlinx.serialization.json.*

object VoiceMemoPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "voice_memo",
            name = "Voice Memo",
            version = "1.0.0",
            description = "Record audio and transcribe speech to text",
            author = "OneClaw Team",
            entryPoint = "VoiceMemoPlugin",
            tools = listOf(
                recordAudioTool(),
                transcribeAudioTool(),
                listRecordingsTool()
            ),
            category = "voice_memo"
        )
    }

    private fun recordAudioTool() = ToolDefinition(
        name = "record_audio",
        description = """Record audio from the device microphone and save to the workspace.
            |
            |Records for the specified duration (default 10 seconds, max 300 seconds).
            |The recording is saved in M4A/AAC format to workspace/recordings/.
            |Requires the RECORD_AUDIO runtime permission to be granted by the user.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("duration_seconds") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Recording duration in seconds (1-300, default: 10)"))
                }
                putJsonObject("filename") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Custom filename (without extension). If omitted, a timestamp-based name is used."))
                }
            }
        }
    )

    private fun transcribeAudioTool() = ToolDefinition(
        name = "transcribe_audio",
        description = """Transcribe an audio file to text using OpenAI Whisper API.
            |
            |Supports common audio formats (m4a, mp3, wav, webm, mp4, mpeg, mpga, oga, ogg).
            |Requires an OpenAI API key to be configured.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Path to the audio file (relative to workspace root)"))
                }
                putJsonObject("language") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("ISO 639-1 language code (e.g., 'en', 'es', 'zh'). If omitted, auto-detected."))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
            }
        }
    )

    private fun listRecordingsTool() = ToolDefinition(
        name = "list_recordings",
        description = "List audio recordings in the workspace/recordings/ directory with file size and date.",
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {}
        }
    )
}
