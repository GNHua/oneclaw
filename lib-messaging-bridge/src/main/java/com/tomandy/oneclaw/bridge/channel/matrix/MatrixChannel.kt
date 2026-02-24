package com.tomandy.oneclaw.bridge.channel.matrix

import android.util.Log
import com.tomandy.oneclaw.bridge.BridgeAgentExecutor
import com.tomandy.oneclaw.bridge.BridgeConversationManager
import com.tomandy.oneclaw.bridge.BridgeMessage
import com.tomandy.oneclaw.bridge.BridgeMessageObserver
import com.tomandy.oneclaw.bridge.BridgePreferences
import com.tomandy.oneclaw.bridge.BridgeStateTracker
import com.tomandy.oneclaw.bridge.ChannelType
import com.tomandy.oneclaw.bridge.ConversationMapper
import com.tomandy.oneclaw.bridge.channel.ChannelMessage
import com.tomandy.oneclaw.bridge.channel.MessagingChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MatrixChannel(
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope,
    private val homeserverUrl: String,
    private val accessToken: String
) : MessagingChannel(
    channelType = ChannelType.MATRIX,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private var syncJob: Job? = null
    private lateinit var api: MatrixApi
    private var ownUserId: String? = null

    override suspend fun start() {
        api = MatrixApi(homeserverUrl, accessToken)

        // Resolve our own user ID so we can ignore our own messages
        try {
            ownUserId = api.whoAmI()
            Log.i(TAG, "Matrix bot user: $ownUserId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Matrix user ID", e)
        }

        BridgeStateTracker.updateChannelState(
            ChannelType.MATRIX,
            BridgeStateTracker.ChannelState(
                isRunning = true,
                connectedSince = System.currentTimeMillis()
            )
        )

        syncJob = scope.launch {
            // Initial sync to get the since token (don't process old messages)
            var nextBatch: String? = null
            try {
                val initial = api.sync(since = null, timeout = 0)
                nextBatch = initial.nextBatch
                Log.i(TAG, "Matrix initial sync complete, batch: $nextBatch")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Matrix initial sync failed", e)
            }

            var backoffMs = INITIAL_BACKOFF_MS

            while (isActive) {
                try {
                    val response = api.sync(since = nextBatch, timeout = POLL_TIMEOUT_MS)
                    nextBatch = response.nextBatch
                    backoffMs = INITIAL_BACKOFF_MS

                    // Process timeline events from joined rooms
                    response.rooms?.join?.forEach { (roomId, room) ->
                        room.timeline?.events?.forEach { event ->
                            handleRoomEvent(roomId, event)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Matrix sync error", e)
                    BridgeStateTracker.updateChannelState(
                        ChannelType.MATRIX,
                        BridgeStateTracker.ChannelState(
                            isRunning = true,
                            error = e.message
                        )
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private fun handleRoomEvent(roomId: String, event: RoomEvent) {
        if (event.type != "m.room.message") return

        val sender = event.sender ?: return
        val content = event.content ?: return
        val msgtype = content.msgtype ?: return
        val body = content.body ?: return

        // Ignore our own messages
        if (sender == ownUserId) return

        // Only handle text messages
        if (msgtype != "m.text") return

        if (body.isBlank()) return

        // Access control
        val allowedUsers = preferences.getAllowedMatrixUserIds()
        if (allowedUsers.isNotEmpty() && sender !in allowedUsers) {
            Log.d(TAG, "Ignoring message from unauthorized Matrix user: $sender")
            return
        }

        scope.launch {
            processInboundMessage(
                ChannelMessage(
                    externalChatId = roomId,
                    senderName = sender.substringBefore(':'),
                    senderId = sender,
                    text = body,
                    messageId = event.eventId
                )
            )
        }
    }

    override suspend fun sendTypingIndicator(externalChatId: String) {
        try {
            val userId = ownUserId ?: return
            api.sendTyping(externalChatId, userId, typing = true)
        } catch (_: Exception) {
            // Best-effort: typing indicators should not fail message processing
        }
    }

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        api.sendLongMessage(externalChatId, message.content)
    }

    override suspend fun stop() {
        syncJob?.cancel()
        syncJob = null
        if (::api.isInitialized) {
            api.shutdown()
        }
        ownUserId = null
        BridgeStateTracker.removeChannelState(ChannelType.MATRIX)
    }

    override fun isRunning(): Boolean = syncJob?.isActive == true

    companion object {
        private const val TAG = "MatrixChannel"
        private const val POLL_TIMEOUT_MS = 30000
        private const val INITIAL_BACKOFF_MS = 3000L
        private const val MAX_BACKOFF_MS = 60000L
    }
}
