package com.oneclaw.shadow.bridge.channel.slack

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SlackChannel(
    private val appToken: String,
    private val botToken: String,
    private val okHttpClient: OkHttpClient,
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope
) : MessagingChannel(
    channelType = ChannelType.SLACK,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private var socketMode: SlackSocketMode? = null
    private var running = false

    override suspend fun start() {
        running = true
        BridgeStateTracker.updateChannelState(
            ChannelType.SLACK,
            BridgeStateTracker.ChannelState(isRunning = true, connectedSince = System.currentTimeMillis())
        )
        val sm = SlackSocketMode(appToken, botToken, okHttpClient, ::onEnvelope, scope)
        socketMode = sm
        scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            while (running) {
                try {
                    sm.connect()
                    backoffMs = INITIAL_BACKOFF_MS
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Slack connection error: ${e.message}")
                    BridgeStateTracker.updateChannelState(
                        ChannelType.SLACK,
                        BridgeStateTracker.ChannelState(isRunning = true, error = e.message)
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private suspend fun onEnvelope(envelope: JsonObject, ack: () -> Unit) {
        ack()
        val type = envelope["type"]?.jsonPrimitive?.content ?: return
        if (type != "events_api") return

        val payload = envelope["payload"]?.jsonObject ?: return
        val event = payload["event"]?.jsonObject ?: return
        val eventType = event["type"]?.jsonPrimitive?.content ?: return
        if (eventType != "message") return

        // Ignore bot messages and subtypes (edits, deletes)
        val botId = event["bot_id"]?.jsonPrimitive?.content
        if (botId != null) return
        val subtype = event["subtype"]?.jsonPrimitive?.content
        if (subtype != null) return

        val channelId = event["channel"]?.jsonPrimitive?.content ?: return
        val userId = event["user"]?.jsonPrimitive?.content ?: return
        val text = event["text"]?.jsonPrimitive?.content ?: return
        val ts = event["ts"]?.jsonPrimitive?.content ?: ""

        processInboundMessage(
            ChannelMessage(
                externalChatId = channelId,
                senderName = null,
                senderId = userId,
                text = text,
                messageId = "$channelId:$ts"
            )
        )
    }

    override suspend fun stop() {
        running = false
        socketMode?.disconnect()
        socketMode = null
        BridgeStateTracker.removeChannelState(ChannelType.SLACK)
    }

    override fun isRunning(): Boolean = running && socketMode?.isConnected() == true

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        try {
            val body = buildJsonObject {
                put("channel", externalChatId)
                put("text", message.content)
            }
            val request = Request.Builder()
                .url("https://slack.com/api/chat.postMessage")
                .addHeader("Authorization", "Bearer $botToken")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "sendResponse error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SlackChannel"
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
