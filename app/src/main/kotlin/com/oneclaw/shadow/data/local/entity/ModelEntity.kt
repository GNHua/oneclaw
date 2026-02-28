package com.oneclaw.shadow.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "models",
    primaryKeys = ["id", "provider_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["provider_id"])
    ]
)
data class ModelEntity(
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String?,
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean,
    val source: String, // "DYNAMIC", "PRESET", "MANUAL"
    @ColumnInfo(name = "context_window_size")
    val contextWindowSize: Int? = null
)
