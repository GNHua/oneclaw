package com.tomandy.oneclaw.bridge.channel.slack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ConnectionsOpenResponse(
    val ok: Boolean,
    val url: String? = null,
    val error: String? = null
)

@Serializable
data class SocketModeEnvelope(
    val type: String,
    @SerialName("envelope_id") val envelopeId: String? = null,
    val payload: JsonElement? = null,
    @SerialName("retry_attempt") val retryAttempt: Int? = null,
    @SerialName("retry_reason") val retryReason: String? = null
)

@Serializable
data class SocketModeAck(
    @SerialName("envelope_id") val envelopeId: String
)

@Serializable
data class EventPayload(
    val event: SlackEvent? = null
)

@Serializable
data class SlackEvent(
    val type: String,
    val user: String? = null,
    val text: String? = null,
    val channel: String? = null,
    val ts: String? = null,
    @SerialName("bot_id") val botId: String? = null,
    val subtype: String? = null
)

@Serializable
data class ChatPostMessageRequest(
    val channel: String,
    val text: String
)

@Serializable
data class SlackApiResponse(
    val ok: Boolean,
    val error: String? = null
)
