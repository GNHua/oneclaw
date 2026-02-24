package com.tomandy.oneclaw.bridge.channel.discord

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class GatewayPayload(
    val op: Int,
    val d: JsonElement? = null,
    val s: Int? = null,
    val t: String? = null
)

@Serializable
data class IdentifyPayload(
    val token: String,
    val intents: Int,
    val properties: IdentifyProperties = IdentifyProperties()
)

@Serializable
data class IdentifyProperties(
    val os: String = "android",
    val browser: String = "oneclaw",
    val device: String = "oneclaw"
)

@Serializable
data class HelloEvent(
    @SerialName("heartbeat_interval") val heartbeatInterval: Long
)

@Serializable
data class ReadyEvent(
    @SerialName("session_id") val sessionId: String
)

@Serializable
data class MessageCreateEvent(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    val content: String,
    val author: DiscordAuthor,
    val attachments: List<DiscordAttachment> = emptyList()
)

@Serializable
data class DiscordAuthor(
    val id: String,
    val username: String,
    val bot: Boolean = false
)

@Serializable
data class DiscordAttachment(
    val id: String,
    val filename: String,
    val url: String,
    val size: Long,
    @SerialName("content_type") val contentType: String? = null
)

@Serializable
data class SendMessagePayload(
    val content: String
)
