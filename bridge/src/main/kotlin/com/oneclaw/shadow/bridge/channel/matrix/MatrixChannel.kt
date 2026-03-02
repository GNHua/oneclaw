package com.oneclaw.shadow.bridge.channel.matrix

import android.util.Log
import com.oneclaw.shadow.bridge.BridgeAgentExecutor
import com.oneclaw.shadow.bridge.BridgeConversationManager
import com.oneclaw.shadow.bridge.BridgeMessage
import com.oneclaw.shadow.bridge.BridgeMessageObserver
import com.oneclaw.shadow.bridge.BridgePreferences
import com.oneclaw.shadow.bridge.BridgeStateTracker
import com.oneclaw.shadow.bridge.channel.ChannelMessage
import com.oneclaw.shadow.bridge.channel.ChannelType
import com.oneclaw.shadow.bridge.channel.ConversationMapper
import com.oneclaw.shadow.bridge.channel.MessagingChannel
import com.oneclaw.shadow.bridge.channel.telegram.TelegramHtmlRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class MatrixChannel(
    private val homeserverUrl: String,
    private val accessToken: String,
    private val okHttpClient: OkHttpClient,
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope
) : MessagingChannel(
    channelType = ChannelType.MATRIX,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private val api = MatrixApi(homeserverUrl, accessToken, okHttpClient)
    private var syncJob: Job? = null
    private var running = false
    private var nextBatch: String? = null
    private var userId: String? = null

    override suspend fun start() {
        running = true
        userId = api.whoAmI()
        BridgeStateTracker.updateChannelState(
            ChannelType.MATRIX,
            BridgeStateTracker.ChannelState(isRunning = true, connectedSince = System.currentTimeMillis())
        )
        syncJob = scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive) {
                try {
                    val syncResponse = api.sync(since = nextBatch)
                    if (syncResponse != null) {
                        nextBatch = syncResponse["next_batch"]?.jsonPrimitive?.content
                        processSync(syncResponse)
                    }
                    backoffMs = INITIAL_BACKOFF_MS
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Sync error: ${e.message}")
                    BridgeStateTracker.updateChannelState(
                        ChannelType.MATRIX,
                        BridgeStateTracker.ChannelState(isRunning = true, error = e.message)
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private suspend fun processSync(syncResponse: kotlinx.serialization.json.JsonObject) {
        val rooms = syncResponse["rooms"]?.jsonObject ?: return
        val joinedRooms = rooms["join"]?.jsonObject ?: return

        joinedRooms.entries.forEach { (roomId, roomData) ->
            val timeline = roomData.jsonObject["timeline"]?.jsonObject ?: return@forEach
            val events = timeline["events"]?.jsonArray ?: return@forEach

            events.forEach { event ->
                val eventObj = event.jsonObject
                val type = eventObj["type"]?.jsonPrimitive?.content ?: return@forEach
                if (type != "m.room.message") return@forEach

                val senderId = eventObj["sender"]?.jsonPrimitive?.content ?: return@forEach
                if (senderId == userId) return@forEach

                val content = eventObj["content"]?.jsonObject ?: return@forEach
                val msgtype = content["msgtype"]?.jsonPrimitive?.content ?: return@forEach
                if (msgtype != "m.text") return@forEach

                val body = content["body"]?.jsonPrimitive?.content ?: return@forEach
                val eventId = eventObj["event_id"]?.jsonPrimitive?.content

                processInboundMessage(
                    ChannelMessage(
                        externalChatId = roomId,
                        senderName = senderId,
                        senderId = senderId,
                        text = body,
                        messageId = eventId
                    )
                )
            }
        }
    }

    override suspend fun stop() {
        running = false
        syncJob?.cancel()
        syncJob = null
        BridgeStateTracker.removeChannelState(ChannelType.MATRIX)
    }

    override fun isRunning(): Boolean = running && syncJob?.isActive == true

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        val htmlBody = try {
            TelegramHtmlRenderer.render(message.content)
        } catch (e: Exception) {
            Log.w(TAG, "HTML rendering failed, sending plain text", e)
            null
        }
        api.sendMessage(roomId = externalChatId, text = message.content, htmlBody = htmlBody)
    }

    override suspend fun sendTypingIndicator(externalChatId: String) {
        val uid = userId ?: return
        api.sendTyping(externalChatId, uid, typing = true, timeout = 5000)
    }

    companion object {
        private const val TAG = "MatrixChannel"
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
