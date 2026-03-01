package com.oneclaw.shadow.core.model

data class Agent(
    val id: String,
    val name: String,
    val description: String?,
    val systemPrompt: String,
    val preferredProviderId: String?,
    val preferredModelId: String?,
    val isBuiltIn: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val webSearchEnabled: Boolean = false
)
