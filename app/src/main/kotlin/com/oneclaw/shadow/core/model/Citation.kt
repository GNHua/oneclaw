package com.oneclaw.shadow.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Citation(
    val url: String,
    val title: String,
    val domain: String
)
