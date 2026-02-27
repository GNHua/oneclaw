package com.oneclaw.shadow.data.remote.dto.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnthropicModelListResponse(
    val data: List<AnthropicModelDto>
)

@Serializable
data class AnthropicModelDto(
    val id: String,
    @SerialName("display_name")
    val displayName: String? = null,
    val type: String? = null
)
