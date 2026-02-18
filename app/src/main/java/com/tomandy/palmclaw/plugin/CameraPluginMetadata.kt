package com.tomandy.palmclaw.plugin

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import kotlinx.serialization.json.*

object CameraPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "camera",
            name = "Camera",
            version = "1.0.0",
            description = "Capture photos using the device camera",
            author = "PalmClaw Team",
            entryPoint = "CameraPlugin",
            tools = listOf(
                takePhotoTool(),
                listCamerasTool()
            ),
            category = "camera"
        )
    }

    private fun takePhotoTool() = ToolDefinition(
        name = "take_photo",
        description = """Capture a photo using the device camera and save it to the workspace.
            |
            |The photo is saved to workspace/captures/ and the file path is returned.
            |Requires the CAMERA runtime permission to be granted by the user.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("camera") {
                    put("type", JsonPrimitive("string"))
                    putJsonArray("enum") {
                        add(JsonPrimitive("back"))
                        add(JsonPrimitive("front"))
                    }
                    put("description", JsonPrimitive("Which camera to use (default: back)"))
                }
                putJsonObject("filename") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Custom filename (without extension). If omitted, a timestamp-based name is used."))
                }
            }
        }
    )

    private fun listCamerasTool() = ToolDefinition(
        name = "list_cameras",
        description = "List available cameras on the device with their facing direction.",
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {}
        }
    )
}
