package com.oneclaw.shadow.core.model

data class AiModel(
    val id: String,
    val displayName: String?,
    val providerId: String,
    val isDefault: Boolean,
    val source: ModelSource,
    val contextWindowSize: Int? = null  // max context window in tokens; null = unknown
)

enum class ModelSource {
    DYNAMIC,
    PRESET,
    MANUAL
}
