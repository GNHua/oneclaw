package com.oneclaw.shadow.data.remote.dto.gemini

import kotlinx.serialization.Serializable

@Serializable
data class GeminiModelListResponse(
    val models: List<GeminiModelDto> = emptyList()
)

@Serializable
data class GeminiModelDto(
    val name: String,
    val displayName: String? = null,
    val supportedGenerationMethods: List<String> = emptyList()
)
