package com.tomandy.oneclaw.plugin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import com.tomandy.oneclaw.plugin.camera.HeadlessCameraCapture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraPlugin : Plugin {

    private lateinit var context: PluginContext
    private lateinit var cameraCapture: HeadlessCameraCapture
    private lateinit var capturesDir: File

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
        cameraCapture = HeadlessCameraCapture(context.getApplicationContext())
        capturesDir = File(getWorkspaceDir(context.getApplicationContext()), "captures")
        capturesDir.mkdirs()
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "take_photo" -> takePhoto(arguments)
            "list_cameras" -> listCameras()
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private suspend fun takePhoto(arguments: JsonObject): ToolResult {
        val appContext = context.getApplicationContext()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.Failure(
                "Camera permission is not granted. The user must grant CAMERA permission in app settings before taking photos."
            )
        }

        val camera = arguments["camera"]?.jsonPrimitive?.content ?: "back"
        if (camera !in listOf("back", "front")) {
            return ToolResult.Failure("Invalid camera value. Must be 'back' or 'front'.")
        }

        val customFilename = arguments["filename"]?.jsonPrimitive?.content
        val filename = if (customFilename != null) {
            "$customFilename.jpg"
        } else {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            "photo_$timestamp.jpg"
        }

        val outputFile = File(capturesDir, filename)

        return try {
            cameraCapture.capture(outputFile, useFrontCamera = camera == "front")
            val relativePath = "workspace/captures/$filename"
            ToolResult.Success("Photo captured and saved to $relativePath")
        } catch (e: Exception) {
            outputFile.delete()
            ToolResult.Failure("Failed to capture photo: ${e.message}")
        }
    }

    private fun listCameras(): ToolResult {
        val cameras = cameraCapture.getAvailableCameras()
        if (cameras.isEmpty()) {
            return ToolResult.Success("No cameras available on this device.")
        }

        val output = buildString {
            appendLine("Available cameras:")
            for (cam in cameras) {
                appendLine("- ${cam.facing}: ${cam.description}")
            }
        }
        return ToolResult.Success(output.trim())
    }

    private fun getWorkspaceDir(context: Context): File {
        return File(context.filesDir, "workspace")
    }
}
