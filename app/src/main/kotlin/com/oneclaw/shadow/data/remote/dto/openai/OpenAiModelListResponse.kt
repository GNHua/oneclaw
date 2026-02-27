package com.oneclaw.shadow.data.remote.dto.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAiModelListResponse(
    val data: List<OpenAiModelDto>
)

@Serializable
data class OpenAiModelDto(
    val id: String,
    @SerialName("owned_by")
    val ownedBy: String? = null
)
