package com.oneclaw.shadow.data.local.mapper

import com.oneclaw.shadow.core.model.Session
import com.oneclaw.shadow.data.local.entity.SessionEntity

fun SessionEntity.toDomain(): Session = Session(
    id = id,
    title = title,
    currentAgentId = currentAgentId,
    messageCount = messageCount,
    lastMessagePreview = lastMessagePreview,
    isActive = isActive,
    deletedAt = deletedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    compactedSummary = compactedSummary,
    compactBoundaryTimestamp = compactBoundaryTimestamp,
    lastLoggedMessageId = lastLoggedMessageId
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id,
    title = title,
    currentAgentId = currentAgentId,
    messageCount = messageCount,
    lastMessagePreview = lastMessagePreview,
    isActive = isActive,
    deletedAt = deletedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    compactedSummary = compactedSummary,
    compactBoundaryTimestamp = compactBoundaryTimestamp,
    lastLoggedMessageId = lastLoggedMessageId
)
