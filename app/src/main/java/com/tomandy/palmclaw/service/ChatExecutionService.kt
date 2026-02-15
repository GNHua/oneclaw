package com.tomandy.palmclaw.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tomandy.palmclaw.PalmClawApp
import com.tomandy.palmclaw.agent.AgentCoordinator
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.llm.Message
import com.tomandy.palmclaw.notification.ChatNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Foreground service that executes chat agent requests in the background.
 *
 * This keeps the process alive with foreground priority so that LLM calls
 * continue even when the user leaves the app. When the response arrives,
 * it is persisted to the database and a notification is sent.
 */
class ChatExecutionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXECUTE -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                val userMessage = intent.getStringExtra(EXTRA_USER_MESSAGE)
                if (conversationId == null || userMessage == null) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, createNotification("Processing message..."))
                executeChat(conversationId, userMessage)
            }
            ACTION_CANCEL -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                if (conversationId != null) {
                    cancelExecution(conversationId)
                }
            }
            ACTION_INJECT -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                val message = intent.getStringExtra(EXTRA_USER_MESSAGE)
                if (conversationId != null && message != null) {
                    synchronized(activeCoordinators) {
                        activeCoordinators[conversationId]
                    }?.injectMessage(message)
                }
            }
            else -> stopSelfIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun executeChat(conversationId: String, userMessage: String) {
        val app = application as PalmClawApp
        ChatExecutionTracker.markActive(conversationId)

        val job = serviceScope.launch {
            try {
                val coordinator = AgentCoordinator(
                    clientProvider = { app.getCurrentLlmClient() },
                    toolRegistry = app.toolRegistry,
                    toolExecutor = app.toolExecutor,
                    messageStore = app.messageStore,
                    conversationId = conversationId
                )

                synchronized(activeCoordinators) {
                    activeCoordinators[conversationId] = coordinator
                }

                // Forward agent state to the tracker
                launch {
                    coordinator.state.collect { state ->
                        ChatExecutionTracker.updateAgentState(conversationId, state)
                    }
                }

                // Seed history from DB, excluding the latest user message
                // (it will be passed to execute() separately)
                val messageDao = app.database.messageDao()
                val dbMessages = messageDao.getMessagesOnce(conversationId)
                val history = dbMessages
                    .filter { it.role == "user" || it.role == "assistant" }
                    .map { Message(role = it.role, content = it.content) }
                    .dropLast(1)
                coordinator.seedHistory(history)

                val selectedModel = app.modelPreferences.getSelectedModel()
                    ?: app.modelPreferences.getModel(app.selectedProvider.value)
                val maxIterations = app.modelPreferences.getMaxIterations()

                val result = coordinator.execute(
                    userMessage = userMessage,
                    systemPrompt = AgentCoordinator.DEFAULT_SYSTEM_PROMPT,
                    model = selectedModel,
                    maxIterations = maxIterations
                )

                val conversationDao = app.database.conversationDao()

                result.fold(
                    onSuccess = { response ->
                        // Persist assistant message
                        messageDao.insert(
                            MessageEntity(
                                id = UUID.randomUUID().toString(),
                                conversationId = conversationId,
                                role = "assistant",
                                content = response,
                                timestamp = System.currentTimeMillis()
                            )
                        )

                        // Update conversation metadata
                        val conv = conversationDao.getConversationOnce(conversationId)
                        conv?.let { c ->
                            conversationDao.update(
                                c.copy(
                                    updatedAt = System.currentTimeMillis(),
                                    messageCount = c.messageCount + 1,
                                    lastMessagePreview = response.take(100)
                                )
                            )
                        }

                        // Notify if user is not viewing this conversation
                        ChatNotificationHelper.notifyIfNeeded(
                            context = applicationContext,
                            conversationId = conversationId,
                            conversationTitle = conv?.title ?: "New response",
                            responseText = response
                        )
                    },
                    onFailure = { e ->
                        if (e is CancellationException) {
                            Log.d(TAG, "Execution result indicates cancellation for $conversationId")
                        } else {
                            Log.e(TAG, "Agent failure: ${e.message}", e)
                            ChatExecutionTracker.setError(
                                conversationId,
                                e.message ?: "Unknown error"
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "Execution cancelled for $conversationId")
                val messageDao = (application as PalmClawApp).database.messageDao()
                withContext(NonCancellable) {
                    messageDao.insert(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = "meta",
                            content = "stopped",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in executeChat: ${e.message}", e)
                ChatExecutionTracker.setError(
                    conversationId,
                    e.message ?: "Unknown error"
                )
            } finally {
                synchronized(activeCoordinators) { activeCoordinators.remove(conversationId) }
                synchronized(activeJobs) { activeJobs.remove(conversationId) }
                ChatExecutionTracker.markInactive(conversationId)
                stopSelfIfIdle()
            }
        }

        synchronized(activeJobs) { activeJobs[conversationId] = job }
    }

    private fun cancelExecution(conversationId: String) {
        cancelExecutionDirect(conversationId)
    }

    private fun stopSelfIfIdle() {
        synchronized(activeJobs) {
            if (activeJobs.isEmpty()) {
                stopSelf()
            }
        }
    }

    // -- Notification helpers ------------------------------------------------

    private fun createNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PalmClaw")
            .setContentText(text)
            .setSmallIcon(com.tomandy.palmclaw.scheduler.R.drawable.ic_notification)
            .setOngoing(true)
            .build()
            .also { ensureChannel() }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chat Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a chat message is being processed"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // -- Lifecycle ------------------------------------------------------------

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ChatExecService"
        const val ACTION_EXECUTE = "com.tomandy.palmclaw.service.EXECUTE_CHAT"
        const val ACTION_CANCEL = "com.tomandy.palmclaw.service.CANCEL_CHAT"
        const val ACTION_INJECT = "com.tomandy.palmclaw.service.INJECT_MESSAGE"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_USER_MESSAGE = "user_message"
        private const val CHANNEL_ID = "chat_processing_channel"
        private const val NOTIFICATION_ID = 1002

        internal val activeCoordinators = mutableMapOf<String, AgentCoordinator>()
        internal val activeJobs = mutableMapOf<String, Job>()

        /**
         * Cancel execution directly without going through an intent.
         * Called from the ViewModel for instant stop-button response.
         */
        fun cancelExecutionDirect(conversationId: String) {
            val coordinator = synchronized(activeCoordinators) {
                activeCoordinators.remove(conversationId)
            }
            coordinator?.cancel()

            val job = synchronized(activeJobs) {
                activeJobs.remove(conversationId)
            }
            job?.cancel()
        }

        fun startExecution(context: Context, conversationId: String, userMessage: String) {
            val intent = Intent(context, ChatExecutionService::class.java).apply {
                action = ACTION_EXECUTE
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_USER_MESSAGE, userMessage)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelExecution(context: Context, conversationId: String) {
            val intent = Intent(context, ChatExecutionService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            context.startService(intent)
        }

        fun injectMessage(context: Context, conversationId: String, message: String) {
            val intent = Intent(context, ChatExecutionService::class.java).apply {
                action = ACTION_INJECT
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_USER_MESSAGE, message)
            }
            context.startService(intent)
        }
    }
}
