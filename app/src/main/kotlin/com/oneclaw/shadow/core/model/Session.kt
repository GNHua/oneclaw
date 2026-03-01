package com.oneclaw.shadow.core.model

data class Session(
    val id: String,
    val title: String,
    val currentAgentId: String,
    val messageCount: Int,
    val lastMessagePreview: String?,
    val isActive: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val compactedSummary: String? = null,
    val compactBoundaryTimestamp: Long? = null,
    val lastLoggedMessageId: String? = null  // RFC-013: ID of the last message processed for daily log
)
