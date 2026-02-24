package com.tomandy.oneclaw.bridge.channel

import android.util.Log
import com.tomandy.oneclaw.bridge.BridgeAgentExecutor
import com.tomandy.oneclaw.bridge.BridgeConversationManager
import com.tomandy.oneclaw.bridge.BridgeMessage
import com.tomandy.oneclaw.bridge.BridgeMessageObserver
import com.tomandy.oneclaw.bridge.BridgePreferences
import com.tomandy.oneclaw.bridge.BridgeStateTracker
import com.tomandy.oneclaw.bridge.ChannelType
import com.tomandy.oneclaw.bridge.ConversationMapper
import kotlinx.coroutines.CoroutineScope

abstract class MessagingChannel(
    val channelType: ChannelType,
    protected val preferences: BridgePreferences,
    protected val conversationMapper: ConversationMapper,
    protected val agentExecutor: BridgeAgentExecutor,
    protected val messageObserver: BridgeMessageObserver,
    protected val conversationManager: BridgeConversationManager,
    protected val scope: CoroutineScope
) {
    abstract suspend fun start()
    abstract suspend fun stop()
    abstract fun isRunning(): Boolean

    protected suspend fun processInboundMessage(msg: ChannelMessage) {
        // Persist last chat ID for broadcast
        preferences.setLastChatId(channelType, msg.externalChatId)

        // Handle /clear command: create a new conversation
        if (msg.text.trim().equals("/clear", ignoreCase = true)) {
            val newId = conversationMapper.createNewConversation()
            sendResponse(
                msg.externalChatId,
                BridgeMessage(
                    content = "New conversation started.",
                    timestamp = System.currentTimeMillis()
                )
            )
            Log.i(TAG, "New conversation created via /clear: $newId")
            return
        }

        try {
            val beforeExecution = System.currentTimeMillis()

            val conversationId = conversationMapper.resolveConversationId()

            conversationManager.insertUserMessage(
                conversationId = conversationId,
                content = msg.text,
                imagePaths = msg.imagePaths
            )

            agentExecutor.executeMessage(
                conversationId = conversationId,
                userMessage = msg.text,
                imagePaths = msg.imagePaths
            )

            val response = messageObserver.awaitNextAssistantMessage(
                conversationId = conversationId,
                afterTimestamp = beforeExecution
            )

            sendResponse(msg.externalChatId, response)

            BridgeStateTracker.updateChannelState(
                channelType,
                BridgeStateTracker.ChannelState(
                    isRunning = true,
                    lastMessageAt = System.currentTimeMillis(),
                    messageCount = (BridgeStateTracker.channelStates.value[channelType]?.messageCount ?: 0) + 1
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message from ${channelType.name}", e)
            try {
                sendResponse(
                    msg.externalChatId,
                    BridgeMessage(
                        content = "Error: ${e.message ?: "Unknown error occurred"}",
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (sendError: Exception) {
                Log.e(TAG, "Failed to send error response", sendError)
            }
        }
    }

    open suspend fun broadcast(message: BridgeMessage) {
        val chatId = preferences.getLastChatId(channelType) ?: return
        sendResponse(chatId, message)
    }

    protected abstract suspend fun sendResponse(
        externalChatId: String,
        message: BridgeMessage
    )

    companion object {
        private const val TAG = "MessagingChannel"
    }
}
