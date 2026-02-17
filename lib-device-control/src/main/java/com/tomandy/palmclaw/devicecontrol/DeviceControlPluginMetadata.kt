package com.tomandy.palmclaw.devicecontrol

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import kotlinx.serialization.json.*

object DeviceControlPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "device_control",
            name = "Device Control",
            version = "1.0.0",
            description = "Observe and interact with the phone screen via Android Accessibility",
            author = "PalmClaw Team",
            entryPoint = "DeviceControlPlugin",
            tools = listOf(
                observeScreenTool(),
                tapTool(),
                typeTextTool(),
                swipeTool(),
                pressBackTool(),
                pressHomeTool(),
                launchAppTool()
            ),
            category = "device_control"
        )
    }

    private fun observeScreenTool() = ToolDefinition(
        name = "observe_screen",
        description = """Returns a structured text representation of the current screen's UI tree.
            |
            |Each element is listed with an index, class name, text content, and flags (clickable, scrollable, editable, etc.).
            |Use this to understand what is on screen before performing actions like tap or type_text.
            |
            |Requires the PalmClaw accessibility service to be enabled in system settings.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {}
        }
    )

    private fun tapTool() = ToolDefinition(
        name = "tap",
        description = """Tap an element on screen.
            |
            |You can identify the target by text, content description, resource ID, or screen coordinates.
            |Prefer text or content_description over coordinates for reliability.
            |Use observe_screen first to see available elements.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Visible text of the element to tap"))
                }
                putJsonObject("content_description") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Accessibility content description of the element"))
                }
                putJsonObject("resource_id") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Android resource ID (e.g., 'com.example:id/button')"))
                }
                putJsonObject("x") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("X coordinate for coordinate-based tap"))
                }
                putJsonObject("y") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Y coordinate for coordinate-based tap"))
                }
            }
        }
    )

    private fun typeTextTool() = ToolDefinition(
        name = "type_text",
        description = """Type text into an input field.
            |
            |If target_hint is provided, finds the input field matching that hint text.
            |Otherwise, types into the currently focused input field.
            |Use observe_screen first to identify input fields (marked as {editable}).
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The text to type"))
                }
                putJsonObject("target_hint") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Hint or label text near the input field to target"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("text"))
            }
        }
    )

    private fun swipeTool() = ToolDefinition(
        name = "swipe",
        description = """Swipe in a direction to scroll content.
            |
            |If a scrollable container is found, uses the accessibility scroll action.
            |Otherwise falls back to a gesture-based swipe.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("direction") {
                    put("type", JsonPrimitive("string"))
                    putJsonArray("enum") {
                        add(JsonPrimitive("up"))
                        add(JsonPrimitive("down"))
                        add(JsonPrimitive("left"))
                        add(JsonPrimitive("right"))
                    }
                    put("description", JsonPrimitive("Direction to swipe"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("direction"))
            }
        }
    )

    private fun pressBackTool() = ToolDefinition(
        name = "press_back",
        description = "Press the system back button.",
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {}
        }
    )

    private fun pressHomeTool() = ToolDefinition(
        name = "press_home",
        description = "Press the system home button to return to the home screen.",
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {}
        }
    )

    private fun launchAppTool() = ToolDefinition(
        name = "launch_app",
        description = """Launch an app by its package name.
            |
            |Opens the app's main activity. Common package names:
            |- com.android.settings (Settings)
            |- com.android.chrome (Chrome)
            |- com.google.android.gm (Gmail)
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("package_name") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The package name of the app to launch"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("package_name"))
            }
        }
    )
}
