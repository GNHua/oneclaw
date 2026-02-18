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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
            "search_nearby" -> searchNearby(arguments)
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

    private suspend fun searchNearby(arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: query")
        val maxResults = (arguments["max_results"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 20)
        val radiusMeters = (arguments["radius_meters"]?.jsonPrimitive?.intOrNull
            ?: 5000).coerceIn(100, 50000)

        var lat = arguments["latitude"]?.jsonPrimitive?.doubleOrNull
        var lng = arguments["longitude"]?.jsonPrimitive?.doubleOrNull

        // Auto-get current location if not provided
        if (lat == null || lng == null) {
            if (!hasLocationPermission()) {
                return ToolResult.Failure(
                    "No coordinates provided and location permission not granted. " +
                        "Either provide latitude/longitude or grant location permission."
                )
            }
            val currentLocation = getCurrentLocation()
                ?: return ToolResult.Failure(
                    "No coordinates provided and could not determine current location."
                )
            lat = currentLocation.first
            lng = currentLocation.second
        }

        val apiKey = pluginContext.getProviderCredential("GoogleMaps")
            ?: return ToolResult.Failure(
                "Google Maps API key not configured. " +
                    "Please set the GoogleMaps API key in Settings > API Keys."
            )

        return try {
            val requestBody = JSONObject().apply {
                put("textQuery", query)
                put("maxResultCount", maxResults)
                put("locationBias", JSONObject().apply {
                    put("circle", JSONObject().apply {
                        put("center", JSONObject().apply {
                            put("latitude", lat)
                            put("longitude", lng)
                        })
                        put("radius", radiusMeters.toDouble())
                    })
                })
            }

            val request = Request.Builder()
                .url("https://places.googleapis.com/v1/places:searchText")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("X-Goog-Api-Key", apiKey)
                .header(
                    "X-Goog-FieldMask",
                    "places.displayName,places.formattedAddress,places.rating," +
                        "places.userRatingCount,places.location,places.types," +
                        "places.currentOpeningHours,places.nationalPhoneNumber," +
                        "places.websiteUri"
                )
                .build()

            val responseBody = withContext(Dispatchers.IO) {
                pluginContext.httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        error("Places API error (HTTP ${resp.code}): $errBody")
                    }
                    resp.body?.string() ?: "{}"
                }
            }

            val json = JSONObject(responseBody)
            val places = json.optJSONArray("places")
            if (places == null || places.length() == 0) {
                return ToolResult.Success("No places found for \"$query\" near ($lat, $lng).")
            }

            val output = buildString {
                append("Found ${places.length()} place(s) for \"$query\":\n\n")
                for (i in 0 until places.length()) {
                    val place = places.getJSONObject(i)
                    val name = place.optJSONObject("displayName")?.optString("text") ?: "Unknown"
                    val address = place.optString("formattedAddress", "")
                    val rating = place.optDouble("rating", 0.0)
                    val ratingCount = place.optInt("userRatingCount", 0)
                    val phone = place.optString("nationalPhoneNumber", "")
                    val website = place.optString("websiteUri", "")
                    val loc = place.optJSONObject("location")

                    append("${i + 1}. $name\n")
                    if (address.isNotEmpty()) append("   Address: $address\n")
                    if (rating > 0) append("   Rating: $rating ($ratingCount reviews)\n")
                    if (phone.isNotEmpty()) append("   Phone: $phone\n")
                    if (website.isNotEmpty()) append("   Website: $website\n")
                    if (loc != null) {
                        append("   Coordinates: ${loc.optDouble("latitude")}, ${loc.optDouble("longitude")}\n")
                    }
                    append("\n")
                }
            }

            ToolResult.Success(output.trimEnd())
        } catch (e: Exception) {
            ToolResult.Failure("Failed to search nearby places: ${e.message}", e)
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

    private suspend fun getCurrentLocation(): Pair<Double, Double>? {
        return try {
            val context = pluginContext.getApplicationContext()
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val cancellationToken = CancellationTokenSource()

            @Suppress("MissingPermission")
            val location = fusedClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationToken.token
            ).await()

            location?.let { Pair(it.latitude, it.longitude) }
        } catch (_: Exception) {
            null
        }
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
