package com.oneclaw.shadow.core.util

sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(
        val exception: Exception? = null,
        val message: String,
        val code: ErrorCode = ErrorCode.UNKNOWN
    ) : AppResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw exception ?: RuntimeException(message)
    }

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }
}

enum class ErrorCode {
    NETWORK_ERROR,
    AUTH_ERROR,
    TIMEOUT_ERROR,
    VALIDATION_ERROR,
    STORAGE_ERROR,
    PERMISSION_ERROR,
    PROVIDER_ERROR,
    TOOL_ERROR,
    NOT_FOUND,
    UNKNOWN
}
