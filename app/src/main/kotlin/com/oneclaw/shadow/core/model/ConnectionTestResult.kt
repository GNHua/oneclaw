package com.oneclaw.shadow.core.model

data class ConnectionTestResult(
    val success: Boolean,
    val modelCount: Int?,
    val errorType: ConnectionErrorType?,
    val errorMessage: String?
)

enum class ConnectionErrorType {
    AUTH_FAILURE,
    NETWORK_FAILURE,
    TIMEOUT,
    UNKNOWN
}
