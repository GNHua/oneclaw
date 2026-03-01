package com.oneclaw.shadow.data.local.mapper

import com.oneclaw.shadow.core.model.Agent
import com.oneclaw.shadow.data.local.entity.AgentEntity

fun AgentEntity.toDomain(): Agent = Agent(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    preferredProviderId = preferredProviderId,
    preferredModelId = preferredModelId,
    isBuiltIn = isBuiltIn,
    createdAt = createdAt,
    updatedAt = updatedAt,
    webSearchEnabled = webSearchEnabled
)

fun Agent.toEntity(): AgentEntity = AgentEntity(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    toolIds = "[]",
    preferredProviderId = preferredProviderId,
    preferredModelId = preferredModelId,
    isBuiltIn = isBuiltIn,
    createdAt = createdAt,
    updatedAt = updatedAt,
    webSearchEnabled = webSearchEnabled
)
