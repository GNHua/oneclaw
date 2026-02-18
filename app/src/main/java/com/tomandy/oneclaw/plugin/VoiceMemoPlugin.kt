package com.tomandy.oneclaw.plugin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceMemoPlugin(
    private val getOpenAiApiKey: suspend () -> String?,
    private val getOpenAiBaseUrl: suspend () -> String?
) : Plugin {

    private lateinit var context: PluginContext
    private lateinit var recordingsDir: File

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
        recordingsDir = File(getWorkspaceDir(context.getApplicationContext()), "recordings")
        recordingsDir.mkdirs()
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "record_audio" -> recordAudio(arguments)
            "transcribe_audio" -> transcribeAudio(arguments)
            "list_recordings" -> listRecordings()
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private suspend fun recordAudio(arguments: JsonObject): ToolResult {
        val appContext = context.getApplicationContext()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Failure(
                "Microphone permission is not granted. The user must grant RECORD_AUDIO permission in app settings before recording."
            )
        }

        val durationSeconds = arguments["duration_seconds"]?.jsonPrimitive?.int ?: 10
        if (durationSeconds !in 1..300) {
            return ToolResult.Failure("duration_seconds must be between 1 and 300.")
        }

        val customFilename = arguments["filename"]?.jsonPrimitive?.content
        val filename = if (customFilename != null) {
            "$customFilename.m4a"
        } else {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            "recording_$timestamp.m4a"
        }

        val outputFile = File(recordingsDir, filename)

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            delay(durationSeconds * 1000L)

            recorder.stop()
            recorder.release()

            val relativePath = "workspace/recordings/$filename"
            val sizeKb = outputFile.length() / 1024
            ToolResult.Success("Recorded ${durationSeconds}s of audio to $relativePath (${sizeKb} KB)")
        } catch (e: Exception) {
            try {
                recorder.stop()
            } catch (_: Exception) {}
            recorder.release()
            outputFile.delete()
            ToolResult.Failure("Recording failed: ${e.message}")
        }
    }

    private suspend fun transcribeAudio(arguments: JsonObject): ToolResult {
        val path = arguments["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: path")
        val language = arguments["language"]?.jsonPrimitive?.content

        val apiKey = getOpenAiApiKey()
        if (apiKey.isNullOrBlank()) {
            return ToolResult.Failure("OpenAI API key is not configured. Set it in Settings to use transcription.")
        }

        val baseUrl = getOpenAiBaseUrl()?.trimEnd('/') ?: "https://api.openai.com"

        val workspaceDir = getWorkspaceDir(context.getApplicationContext())
        val audioFile = File(workspaceDir, path)
        if (!audioFile.exists()) {
            return ToolResult.Failure("Audio file not found: $path")
        }
        // Verify file is within workspace
        if (!audioFile.canonicalPath.startsWith(workspaceDir.canonicalPath)) {
            return ToolResult.Failure("Path must be within the workspace directory.")
        }

        val mimeType = when (audioFile.extension.lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            "ogg", "oga" -> "audio/ogg"
            else -> "audio/mpeg"
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody(mimeType.toMediaType())
            )
            .addFormDataPart("model", "whisper-1")
            .apply {
                if (language != null) {
                    addFormDataPart("language", language)
                }
                addFormDataPart("response_format", "text")
            }
            .build()

        val request = Request.Builder()
            .url("$baseUrl/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return try {
            val response = context.httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                if (body.isBlank()) {
                    ToolResult.Success("Transcription returned empty text (no speech detected).")
                } else {
                    ToolResult.Success(body.trim())
                }
            } else {
                ToolResult.Failure("Transcription API error (${response.code}): $body")
            }
        } catch (e: Exception) {
            ToolResult.Failure("Transcription request failed: ${e.message}")
        }
    }

    private fun listRecordings(): ToolResult {
        val files = recordingsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            return ToolResult.Success("No recordings found in workspace/recordings/.")
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val output = buildString {
            appendLine("Recordings (${files.size}):")
            for (file in files) {
                val sizeKb = file.length() / 1024
                val date = dateFormat.format(Date(file.lastModified()))
                appendLine("- ${file.name} (${sizeKb} KB, $date)")
            }
        }
        return ToolResult.Success(output.trim())
    }

    private fun getWorkspaceDir(context: Context): File {
        return File(context.filesDir, "workspace")
    }
}
