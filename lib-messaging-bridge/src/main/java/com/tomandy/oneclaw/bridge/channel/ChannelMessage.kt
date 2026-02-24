package com.tomandy.oneclaw.bridge.channel

data class ChannelMessage(
    val externalChatId: String,
    val senderName: String?,
    val senderId: String?,
    val text: String,
    val imagePaths: List<String> = emptyList()
)
