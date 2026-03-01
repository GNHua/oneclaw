package com.oneclaw.shadow.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String,
    @ColumnInfo(name = "tool_ids")
    val toolIds: String, // JSON array
    @ColumnInfo(name = "preferred_provider_id")
    val preferredProviderId: String?,
    @ColumnInfo(name = "preferred_model_id")
    val preferredModelId: String?,
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "web_search_enabled", defaultValue = "0")
    val webSearchEnabled: Boolean = false
)
