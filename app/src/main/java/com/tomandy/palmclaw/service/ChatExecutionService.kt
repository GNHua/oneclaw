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
import com.tomandy.palmclaw.agent.AgentCoordinator
import com.tomandy.palmclaw.agent.MessageStore
import com.tomandy.palmclaw.agent.ToolExecutor
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.llm.ImageData
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.llm.NetworkConfig
import com.tomandy.palmclaw.llm.LlmProvider
import com.tomandy.palmclaw.llm.Message
import com.tomandy.palmclaw.notification.ChatNotificationHelper
import com.tomandy.palmclaw.skill.SkillRepository
import com.tomandy.palmclaw.skill.SystemPromptBuilder
import com.tomandy.palmclaw.util.ImageStorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

class ChatExecutionService : Service(), KoinComponent {

    private val llmClientProvider: LlmClientProvider by inject()
    private val toolRegistry: ToolRegistry by inject()
    private val toolExecutor: ToolExecutor by inject()
    private val messageStore: MessageStore by inject()
    private val modelPreferences: ModelPreferences by inject()
    private val database: AppDatabase by inject()
    private val skillRepository: SkillRepository by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXECUTE -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                val userMessage = intent.getStringExtra(EXTRA_USER_MESSAGE)
                val imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS) ?: emptyList()
                if (conversationId == null || userMessage == null) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, createNotification("Processing message..."))
                executeChat(conversationId, userMessage, imagePaths)
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
            ACTION_SUMMARIZE -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                if (conversationId == null) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, createNotification("Summarizing conversation..."))
                executeSummarize(conversationId)
            }
            else -> stopSelfIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun executeChat(conversationId: String, userMessage: String, imagePaths: List<String> = emptyList()) {
        ChatExecutionTracker.markActive(conversationId)

        val job = serviceScope.launch {
            try {
                val selectedModel = modelPreferences.getSelectedModel()
                    ?: modelPreferences.getModel(llmClientProvider.selectedProvider.value)
                val contextWindow = LlmProvider.getContextWindow(selectedModel)

                val coordinator = AgentCoordinator(
                    clientProvider = { llmClientProvider.getCurrentLlmClient() },
                    toolRegistry = toolRegistry,
                    toolExecutor = toolExecutor,
                    messageStore = messageStore,
                    conversationId = conversationId,
                    contextWindow = contextWindow
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
                val messageDao = database.messageDao()
                val dbMessages = messageDao.getMessagesOnce(conversationId)

                // Find the latest summary meta message
                val lastSummaryIndex = dbMessages.indexOfLast {
                    it.role == "meta" && it.toolName == "summary"
                }
                val summaryContent = if (lastSummaryIndex >= 0) {
                    dbMessages[lastSummaryIndex].content
                } else null

                // Seed only messages after the last summary (or all if no summary)
                val messagesAfterSummary = if (lastSummaryIndex >= 0) {
                    dbMessages.subList(lastSummaryIndex + 1, dbMessages.size)
                } else {
                    dbMessages
                }
                val history = buildList {
                    for (msg in messagesAfterSummary) {
                        when {
                            msg.role == "user" || msg.role == "assistant" -> {
                                // Annotate old user messages that had images
                                val content = if (msg.role == "user" && !msg.imagePaths.isNullOrEmpty()) {
                                    val count = try {
                                        NetworkConfig.json.decodeFromString<List<String>>(msg.imagePaths).size
                                    } catch (_: Exception) { 1 }
                                    "${msg.content}\n[$count image(s) were attached to this message]"
                                } else {
                                    msg.content
                                }
                                add(Message(role = msg.role, content = content))
                            }
                            msg.role == "meta" && msg.toolName == "stopped" ->
                                add(Message(role = "assistant", content = "[The previous response was cancelled by the user. Do not continue or retry the cancelled task unless explicitly asked.]"))
                        }
                    }
                }.dropLast(1)
                coordinator.seedHistory(history, summaryContent)
                val maxIterations = modelPreferences.getMaxIterations()
                val temperature = modelPreferences.getTemperature()
                val baseSystemPrompt = modelPreferences.getSystemPrompt()

                // Reload skills and augment system prompt (only when
                // read_file is available so the model can load skill files)
                skillRepository.reload()
                val enabledSkills = skillRepository.getEnabledSkills()
                val systemPrompt = if (
                    toolRegistry.hasTool("read_file") && enabledSkills.isNotEmpty()
                ) {
                    SystemPromptBuilder.augmentSystemPrompt(
                        baseSystemPrompt, enabledSkills
                    )
                } else {
                    baseSystemPrompt
                }

                // Load current message's images as base64
                val currentImageData = imagePaths.mapNotNull { path ->
                    ImageStorageHelper.readAsBase64(path)?.let { (base64, mime) ->
                        ImageData(base64 = base64, mimeType = mime)
                    }
                }

                val result = coordinator.execute(
                    userMessage = userMessage,
                    systemPrompt = systemPrompt,
                    model = selectedModel,
                    maxIterations = maxIterations,
                    temperature = temperature,
                    imageData = currentImageData.takeIf { it.isNotEmpty() }
                )

                val conversationDao = database.conversationDao()

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
                val messageDao = database.messageDao()
                withContext(NonCancellable) {
                    messageDao.insert(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = "meta",
                            content = "stopped",
                            toolName = "stopped",
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
                val removed = synchronized(activeCoordinators) { activeCoordinators.remove(conversationId) }
                removed?.cleanup()
                synchronized(activeJobs) { activeJobs.remove(conversationId) }
                ChatExecutionTracker.markInactive(conversationId)
                stopSelfIfIdle()
            }
        }

        synchronized(activeJobs) { activeJobs[conversationId] = job }
    }

    private fun executeSummarize(conversationId: String) {
        ChatExecutionTracker.markActive(conversationId)

        val job = serviceScope.launch {
            try {
                val selectedModel = modelPreferences.getSelectedModel()
                    ?: modelPreferences.getModel(llmClientProvider.selectedProvider.value)
                val contextWindow = LlmProvider.getContextWindow(selectedModel)

                val coordinator = AgentCoordinator(
                    clientProvider = { llmClientProvider.getCurrentLlmClient() },
                    toolRegistry = toolRegistry,
                    toolExecutor = toolExecutor,
                    messageStore = messageStore,
                    conversationId = conversationId,
                    contextWindow = contextWindow
                )

                // Seed history from DB (all messages, no "latest" to drop)
                val messageDao = database.messageDao()
                val dbMessages = messageDao.getMessagesOnce(conversationId)

                val lastSummaryIndex = dbMessages.indexOfLast {
                    it.role == "meta" && it.toolName == "summary"
                }
                val summaryContent = if (lastSummaryIndex >= 0) {
                    dbMessages[lastSummaryIndex].content
                } else null

                val messagesAfterSummary = if (lastSummaryIndex >= 0) {
                    dbMessages.subList(lastSummaryIndex + 1, dbMessages.size)
                } else {
                    dbMessages
                }
                val history = messagesAfterSummary
                    .filter { it.role == "user" || it.role == "assistant" }
                    .map { Message(role = it.role, content = it.content) }
                coordinator.seedHistory(history, summaryContent)

                val result = coordinator.forceSummarize(model = selectedModel)
                Log.d(TAG, "Summarize result: $result")
            } catch (e: CancellationException) {
                Log.d(TAG, "Summarization cancelled for $conversationId")
            } catch (e: Exception) {
                Log.e(TAG, "Exception in executeSummarize: ${e.message}", e)
                ChatExecutionTracker.setError(
                    conversationId,
                    e.message ?: "Summarization failed"
                )
            } finally {
                val removed = synchronized(activeCoordinators) { activeCoordinators.remove(conversationId) }
                removed?.cleanup()
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
        const val ACTION_SUMMARIZE = "com.tomandy.palmclaw.service.SUMMARIZE_CHAT"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_USER_MESSAGE = "user_message"
        const val EXTRA_IMAGE_PATHS = "image_paths"
        private const val CHANNEL_ID = "chat_processing_channel"
        private const val NOTIFICATION_ID = 1002

        internal val activeCoordinators = mutableMapOf<String, AgentCoordinator>()
        internal val activeJobs = mutableMapOf<String, Job>()

        fun cancelExecutionDirect(conversationId: String) {
            val coordinator = synchronized(activeCoordinators) {
                activeCoordinators.remove(conversationId)
            }
            coordinator?.cleanup()
            coordinator?.cancel()

            val job = synchronized(activeJobs) {
                activeJobs.remove(conversationId)
            }
            job?.cancel()
        }

        fun startExecution(context: Context, conversationId: String, userMessage: String, imagePaths: List<String> = emptyList()) {
            val intent = Intent(context, ChatExecutionService::class.java).apply {
                action = ACTION_EXECUTE
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_USER_MESSAGE, userMessage)
                if (imagePaths.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_IMAGE_PATHS, ArrayList(imagePaths))
                }
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

        fun startSummarization(context: Context, conversationId: String) {
            val intent = Intent(context, ChatExecutionService::class.java).apply {
                action = ACTION_SUMMARIZE
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
