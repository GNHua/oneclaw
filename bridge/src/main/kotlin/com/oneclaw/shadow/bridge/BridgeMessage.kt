package com.oneclaw.shadow.bridge

data class BridgeMessage(
    val content: String,
    val timestamp: Long,
    val imagePaths: List<String> = emptyList()
)
