package com.tomandy.oneclaw.bridge.channel.matrix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncResponse(
    @SerialName("next_batch") val nextBatch: String,
    val rooms: SyncRooms? = null
)

@Serializable
data class SyncRooms(
    val join: Map<String, JoinedRoom>? = null
)

@Serializable
data class JoinedRoom(
    val timeline: RoomTimeline? = null
)

@Serializable
data class RoomTimeline(
    val events: List<RoomEvent>? = null
)

@Serializable
data class RoomEvent(
    val type: String,
    val sender: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    val content: RoomEventContent? = null
)

@Serializable
data class RoomEventContent(
    val msgtype: String? = null,
    val body: String? = null
)

@Serializable
data class SendMessageBody(
    val msgtype: String = "m.text",
    val body: String
)

@Serializable
data class WhoAmIResponse(
    @SerialName("user_id") val userId: String
)
