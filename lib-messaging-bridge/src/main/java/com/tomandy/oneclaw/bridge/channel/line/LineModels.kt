package com.tomandy.oneclaw.bridge.channel.line

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LineWebhookBody(
    val events: List<LineWebhookEvent> = emptyList()
)

@Serializable
data class LineWebhookEvent(
    val type: String,
    @SerialName("replyToken") val replyToken: String? = null,
    val source: LineEventSource? = null,
    val message: LineEventMessage? = null,
    val timestamp: Long? = null
)

@Serializable
data class LineEventSource(
    val type: String,
    @SerialName("userId") val userId: String? = null,
    @SerialName("groupId") val groupId: String? = null,
    @SerialName("roomId") val roomId: String? = null
)

@Serializable
data class LineEventMessage(
    val id: String,
    val type: String,
    val text: String? = null
)

@Serializable
data class LinePushRequest(
    val to: String,
    val messages: List<LineTextMessage>
)

@Serializable
data class LineTextMessage(
    val type: String = "text",
    val text: String
)
