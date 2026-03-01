package com.oneclaw.shadow.data.local.mapper

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.model.ProviderType
import com.oneclaw.shadow.data.local.entity.ModelEntity
import com.oneclaw.shadow.data.local.entity.ProviderEntity

fun ProviderEntity.toDomain(): Provider = Provider(
    id = id,
    name = name,
    type = ProviderType.valueOf(type),
    apiBaseUrl = apiBaseUrl,
    isPreConfigured = isPreConfigured,
    isActive = isActive,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Provider.toEntity(): ProviderEntity = ProviderEntity(
    id = id,
    name = name,
    type = type.name,
    apiBaseUrl = apiBaseUrl,
    isPreConfigured = isPreConfigured,
    isActive = isActive,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ModelEntity.toDomain(): AiModel = AiModel(
    id = id,
    displayName = displayName,
    providerId = providerId,
    isDefault = isDefault,
    source = ModelSource.valueOf(source),
    contextWindowSize = contextWindowSize
)

fun AiModel.toEntity(): ModelEntity = ModelEntity(
    id = id,
    displayName = displayName,
    providerId = providerId,
    isDefault = isDefault,
    source = source.name,
    contextWindowSize = contextWindowSize
)
