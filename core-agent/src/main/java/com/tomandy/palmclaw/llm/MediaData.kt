package com.tomandy.palmclaw.llm

/**
 * Carries media data (image or audio) for a message attachment.
 * Not serialized -- used in-memory only between the app layer and LLM clients.
 */
data class MediaData(
    val base64: String,
    val mimeType: String,
    val fileName: String? = null
) {
    val isAudio: Boolean get() = mimeType.startsWith("audio/")
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isDocument: Boolean get() = mimeType == "application/pdf"
}
