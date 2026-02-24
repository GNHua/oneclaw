package com.tomandy.oneclaw.bridge.channel.telegram

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

class TelegramChannel(
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope,
    private val botToken: String
) : MessagingChannel(
    channelType = ChannelType.TELEGRAM,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private var pollingJob: Job? = null
    private lateinit var api: TelegramApi

    override suspend fun start() {
        api = TelegramApi(botToken)

        BridgeStateTracker.updateChannelState(
            ChannelType.TELEGRAM,
            BridgeStateTracker.ChannelState(
                isRunning = true,
                connectedSince = System.currentTimeMillis()
            )
        )

        pollingJob = scope.launch {
            var offset = preferences.getTelegramUpdateOffset()
            var backoffMs = INITIAL_BACKOFF_MS

            while (isActive) {
                try {
                    val updates = api.getUpdates(
                        offset = if (offset > 0) offset else null,
                        timeout = POLL_TIMEOUT_SECONDS
                    )

                    backoffMs = INITIAL_BACKOFF_MS // Reset backoff on success

                    for (update in updates) {
                        offset = update.updateId + 1
                        preferences.setTelegramUpdateOffset(offset)
                        handleUpdate(update)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Telegram polling error", e)
                    BridgeStateTracker.updateChannelState(
                        ChannelType.TELEGRAM,
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

    private suspend fun handleUpdate(update: TelegramUpdate) {
        val message = update.message ?: return
        val chatId = message.chat.id.toString()
        val userId = message.from?.id?.toString() ?: return

        // Access control
        val allowedUsers = preferences.getAllowedTelegramUserIds()
        if (allowedUsers.isNotEmpty() && userId !in allowedUsers) {
            Log.d(TAG, "Ignoring message from unauthorized user: $userId")
            return
        }

        val text = message.text ?: message.caption ?: return

        // Process in a separate coroutine to not block polling
        scope.launch {
            processInboundMessage(
                ChannelMessage(
                    externalChatId = chatId,
                    senderName = message.from?.firstName,
                    senderId = userId,
                    text = text,
                    messageId = "${message.chat.id}_${message.messageId}"
                )
            )
        }
    }

    override suspend fun sendTypingIndicator(externalChatId: String) {
        try {
            api.sendChatAction(externalChatId)
        } catch (_: Exception) {
            // Best-effort: typing indicators should not fail message processing
        }
    }

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        try {
            val rendered = TelegramHtmlRenderer.render(message.content)
            api.sendLongMessage(externalChatId, rendered, parseMode = "HTML")
        } catch (e: Exception) {
            Log.w(TAG, "HTML send failed, falling back to plain text", e)
            api.sendLongMessage(externalChatId, message.content)
        }
    }

    override suspend fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        if (::api.isInitialized) {
            api.shutdown()
        }
        BridgeStateTracker.removeChannelState(ChannelType.TELEGRAM)
    }

    override fun isRunning(): Boolean = pollingJob?.isActive == true

    companion object {
        private const val TAG = "TelegramChannel"
        private const val POLL_TIMEOUT_SECONDS = 30
        private const val INITIAL_BACKOFF_MS = 3000L
        private const val MAX_BACKOFF_MS = 60000L
    }
}
