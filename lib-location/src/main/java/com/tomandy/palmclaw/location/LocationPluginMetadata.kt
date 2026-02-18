package com.tomandy.palmclaw.location

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object LocationPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "location",
            name = "Location & Places",
            version = "1.0.0",
            description = "Get current GPS location, search nearby places, and get directions URLs",
            author = "PalmClaw",
            entryPoint = "LocationPlugin",
            tools = listOf(
                getLocationTool(),
                searchNearbyTool(),
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

    private fun searchNearbyTool() = ToolDefinition(
        name = "search_nearby",
        description = """Search for nearby places using Google Places API.
            |
            |If latitude/longitude are not provided, the device's current location
            |is used automatically. Returns place names, addresses, ratings, phone
            |numbers, and websites. Requires a Google Maps API key.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Search query (e.g. 'coffee shops', 'gas stations', 'Italian restaurant')")
                    )
                }
                putJsonObject("latitude") {
                    put("type", JsonPrimitive("number"))
                    put(
                        "description",
                        JsonPrimitive("Center latitude for search (auto-detected if omitted)")
                    )
                }
                putJsonObject("longitude") {
                    put("type", JsonPrimitive("number"))
                    put(
                        "description",
                        JsonPrimitive("Center longitude for search (auto-detected if omitted)")
                    )
                }
                putJsonObject("radius_meters") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Search radius in meters (default 5000, max 50000)")
                    )
                }
                putJsonObject("max_results") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Maximum number of results (default 10, max 20)")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("query"))
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
