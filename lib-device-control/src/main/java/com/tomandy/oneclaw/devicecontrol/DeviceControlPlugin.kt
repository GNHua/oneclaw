package com.tomandy.oneclaw.devicecontrol

import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class DeviceControlPlugin : Plugin {

    private lateinit var context: PluginContext

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        if (!DeviceControlManager.isServiceConnected()) {
            DeviceControlManager.promptEnableService()
            return ToolResult.Failure(
                "Accessibility service is not enabled. " +
                    "The user must enable OneClaw Device Control in Settings > Accessibility before device control tools can be used."
            )
        }

        return when (toolName) {
            "observe_screen" -> observeScreen()
            "tap" -> tap(arguments)
            "type_text" -> typeText(arguments)
            "swipe" -> swipe(arguments)
            "press_back" -> pressBack()
            "press_home" -> pressHome()
            "launch_app" -> launchApp(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {
        // No cleanup needed
    }

    private fun observeScreen(): ToolResult {
        val content = DeviceControlManager.getScreenContent()
        return ToolResult.Success(output = content)
    }

    private fun tap(arguments: JsonObject): ToolResult {
        val text = arguments["text"]?.jsonPrimitive?.content
        val contentDesc = arguments["content_description"]?.jsonPrimitive?.content
        val resourceId = arguments["resource_id"]?.jsonPrimitive?.content
        val x = arguments["x"]?.jsonPrimitive?.int
        val y = arguments["y"]?.jsonPrimitive?.int

        if (text == null && contentDesc == null && resourceId == null && (x == null || y == null)) {
            return ToolResult.Failure(
                "Provide at least one of: text, content_description, resource_id, or both x and y coordinates."
            )
        }

        val success = DeviceControlManager.tap(text, contentDesc, resourceId, x, y)
        return if (success) {
            ToolResult.Success(output = "Tapped successfully.")
        } else {
            ToolResult.Failure("Could not find or tap the target element. Use observe_screen to see available elements.")
        }
    }

    private fun typeText(arguments: JsonObject): ToolResult {
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: text")
        val targetHint = arguments["target_hint"]?.jsonPrimitive?.content

        val success = DeviceControlManager.typeText(text, targetHint)
        return if (success) {
            ToolResult.Success(output = "Text entered successfully.")
        } else {
            ToolResult.Failure("Could not find an editable field or set text. Use observe_screen to identify input fields.")
        }
    }

    private fun swipe(arguments: JsonObject): ToolResult {
        val direction = arguments["direction"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: direction")

        if (direction !in listOf("up", "down", "left", "right")) {
            return ToolResult.Failure("Invalid direction. Must be one of: up, down, left, right.")
        }

        val success = DeviceControlManager.swipe(direction)
        return if (success) {
            ToolResult.Success(output = "Swiped $direction successfully.")
        } else {
            ToolResult.Failure("Swipe failed.")
        }
    }

    private fun pressBack(): ToolResult {
        val success = DeviceControlManager.pressBack()
        return if (success) {
            ToolResult.Success(output = "Back button pressed.")
        } else {
            ToolResult.Failure("Failed to press back button.")
        }
    }

    private fun pressHome(): ToolResult {
        val success = DeviceControlManager.pressHome()
        return if (success) {
            ToolResult.Success(output = "Home button pressed.")
        } else {
            ToolResult.Failure("Failed to press home button.")
        }
    }

    private fun launchApp(arguments: JsonObject): ToolResult {
        val packageName = arguments["package_name"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: package_name")

        val success = DeviceControlManager.launchApp(context.getApplicationContext(), packageName)
        return if (success) {
            ToolResult.Success(output = "Launched $packageName. Use observe_screen to see the app's UI.")
        } else {
            ToolResult.Failure("Could not launch app with package name '$packageName'. The app may not be installed.")
        }
    }
}
