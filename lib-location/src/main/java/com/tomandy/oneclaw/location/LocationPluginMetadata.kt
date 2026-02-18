package com.tomandy.oneclaw.location

import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object LocationPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "location",
            name = "Location",
            version = "1.1.0",
            description = "Get current GPS location and get directions URLs",
            author = "OneClaw",
            entryPoint = "LocationPlugin",
            tools = listOf(
                getLocationTool(),
                getDirectionsUrlTool()
            ),
            permissions = listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"),
            category = "location"
        )
    }

    private fun getLocationTool() = ToolDefinition(
        name = "get_location",
        description = """Get the device's current GPS location.
            |
            |Returns latitude, longitude, accuracy, and reverse-geocoded address.
            |Requires location permission.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("accuracy") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive(
                            "Location accuracy: high (GPS), balanced (default, WiFi/cell), or low (cell only)"
                        )
                    )
                    putJsonArray("enum") {
                        add(JsonPrimitive("high"))
                        add(JsonPrimitive("balanced"))
                        add(JsonPrimitive("low"))
                    }
                }
            }
        }
    )

    private fun getDirectionsUrlTool() = ToolDefinition(
        name = "get_directions_url",
        description = """Generate a Google Maps directions URL.
            |
            |Creates a URL that opens Google Maps with directions. No API key
            |or permissions needed. The user can open the URL to see the route.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("destination") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Destination address or place name")
                    )
                }
                putJsonObject("origin") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Starting address (uses current location if omitted)")
                    )
                }
                putJsonObject("travel_mode") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Travel mode: driving (default), walking, bicycling, or transit")
                    )
                    putJsonArray("enum") {
                        add(JsonPrimitive("driving"))
                        add(JsonPrimitive("walking"))
                        add(JsonPrimitive("bicycling"))
                        add(JsonPrimitive("transit"))
                    }
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("destination"))
            }
        }
    )
}
