package com.tomandy.palmclaw.llm

/**
 * Carries image data for a message attachment.
 * Not serialized -- used in-memory only between the app layer and LLM clients.
 */
data class ImageData(
    val base64: String,
    val mimeType: String
)
