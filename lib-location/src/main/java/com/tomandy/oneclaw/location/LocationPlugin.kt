package com.tomandy.oneclaw.location

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Locale

class LocationPlugin : Plugin {

    private lateinit var pluginContext: PluginContext

    override suspend fun onLoad(context: PluginContext) {
        pluginContext = context
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "get_location" -> getLocation(arguments)
            "get_directions_url" -> getDirectionsUrl(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private suspend fun getLocation(arguments: JsonObject): ToolResult {
        if (!hasLocationPermission()) {
            return ToolResult.Failure(
                "Location permission not granted. Please grant location permission in app settings."
            )
        }

        val accuracyParam = arguments["accuracy"]?.jsonPrimitive?.content ?: "balanced"
        val priority = when (accuracyParam) {
            "high" -> Priority.PRIORITY_HIGH_ACCURACY
            "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            "low" -> Priority.PRIORITY_LOW_POWER
            else -> return ToolResult.Failure(
                "Invalid accuracy: $accuracyParam. Use high, balanced, or low."
            )
        }

        return try {
            val context = pluginContext.getApplicationContext()
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val cancellationToken = CancellationTokenSource()

            @Suppress("MissingPermission")
            val location = fusedClient.getCurrentLocation(
                priority, cancellationToken.token
            ).await()

            if (location == null) {
                return ToolResult.Failure(
                    "Could not determine location. Ensure GPS/location services are enabled."
                )
            }

            val output = buildString {
                append("Latitude: ${location.latitude}\n")
                append("Longitude: ${location.longitude}\n")
                append("Accuracy: ${location.accuracy}m\n")
                if (location.hasAltitude()) {
                    append("Altitude: ${location.altitude}m\n")
                }

                val address = reverseGeocode(location.latitude, location.longitude)
                if (address != null) {
                    append("Address: $address\n")
                }
            }

            ToolResult.Success(output.trimEnd())
        } catch (e: SecurityException) {
            ToolResult.Failure("Location permission denied: ${e.message}", e)
        } catch (e: Exception) {
            ToolResult.Failure("Failed to get location: ${e.message}", e)
        }
    }

    private fun getDirectionsUrl(arguments: JsonObject): ToolResult {
        val destination = arguments["destination"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: destination")
        val origin = arguments["origin"]?.jsonPrimitive?.content
        val travelMode = arguments["travel_mode"]?.jsonPrimitive?.content ?: "driving"

        val validModes = setOf("driving", "walking", "bicycling", "transit")
        if (travelMode !in validModes) {
            return ToolResult.Failure(
                "Invalid travel_mode: $travelMode. Use driving, walking, bicycling, or transit."
            )
        }

        val encodedDest = URLEncoder.encode(destination, "UTF-8")
        val url = buildString {
            append("https://www.google.com/maps/dir/?api=1")
            append("&destination=$encodedDest")
            append("&travelmode=$travelMode")
            if (origin != null) {
                append("&origin=${URLEncoder.encode(origin, "UTF-8")}")
            }
        }

        return ToolResult.Success("Google Maps directions URL:\n$url")
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            val context = pluginContext.getApplicationContext()
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            addresses?.firstOrNull()?.let { addr ->
                (0..addr.maxAddressLineIndex)
                    .joinToString(", ") { addr.getAddressLine(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun hasLocationPermission(): Boolean {
        val context = pluginContext.getApplicationContext()
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }
}
