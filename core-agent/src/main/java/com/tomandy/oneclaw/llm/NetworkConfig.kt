package com.tomandy.oneclaw.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object NetworkConfig {
    const val DEFAULT_CONNECT_TIMEOUT = 60L
    const val DEFAULT_READ_TIMEOUT = 60L
    const val DEFAULT_WRITE_TIMEOUT = 60L
    val TIMEOUT_UNIT = TimeUnit.SECONDS

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    fun createLoggingInterceptor(debug: Boolean = false): HttpLoggingInterceptor? {
        return if (debug) {
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
        } else {
            null
        }
    }
}

@Serializable
data class ApiError(
    val error: ErrorDetails
)

@Serializable
data class ErrorDetails(
    val message: String,
    val type: String? = null,
    val code: String? = null
)

fun parseApiError(errorBody: String?): String {
    if (errorBody == null) return "Unknown API error"

    return try {
        val apiError = NetworkConfig.json.decodeFromString<ApiError>(errorBody)
        apiError.error.message
    } catch (e: Exception) {
        errorBody
    }
}
