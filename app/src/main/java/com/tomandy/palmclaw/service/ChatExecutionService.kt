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
import com.tomandy.palmclaw.llm.MediaData
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.llm.NetworkConfig
import kotlinx.serialization.json.jsonArray
import com.tomandy.palmclaw.llm.LlmProvider
import com.tomandy.palmclaw.llm.Message
import com.tomandy.palmclaw.notification.ChatNotificationHelper
import com.tomandy.palmclaw.agent.profile.AgentProfileEntry
import com.tomandy.palmclaw.agent.profile.AgentProfileRepository
import com.tomandy.palmclaw.skill.SkillRepository
import com.tomandy.palmclaw.skill.SystemPromptBuilder
import com.tomandy.palmclaw.util.DocumentStorageHelper
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
import java.io.File
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
    private val agentProfileRepository: AgentProfileRepository by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXECUTE -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                val userMessage = intent.getStringExtra(EXTRA_USER_MESSAGE)
                val imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS) ?: emptyList()
                val audioPaths = intent.getStringArrayListExtra(EXTRA_AUDIO_PATHS) ?: emptyList()
                val videoPaths = intent.getStringArrayListExtra(EXTRA_VIDEO_PATHS) ?: emptyList()
                val documentPaths = intent.getStringArrayListExtra(EXTRA_DOCUMENT_PATHS) ?: emptyList()
                val documentNames = intent.getStringArrayListExtra(EXTRA_DOCUMENT_NAMES) ?: emptyList()
                val documentMimeTypes = intent.getStringArrayListExtra(EXTRA_DOCUMENT_MIMETYPES) ?: emptyList()
                if (conversationId == null || userMessage == null) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, createNotification("Processing message..."))
                executeChat(conversationId, userMessage, imagePaths, audioPaths, videoPaths, documentPaths, documentNames, documentMimeTypes)
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

    private fun executeChat(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String> = emptyList(),
        audioPaths: List<String> = emptyList(),
        videoPaths: List<String> = emptyList(),
        documentPaths: List<String> = emptyList(),
        documentNames: List<String> = emptyList(),
        documentMimeTypes: List<String> = emptyList()
    ) {
        ChatExecutionTracker.markActive(conversationId)

        val job = serviceScope.launch {
            try {
                // Resolve global active agent profile
                agentProfileRepository.reload()
                val activeAgentName = modelPreferences.getActiveAgent()
                val profile: AgentProfileEntry? = activeAgentName?.let {
                    agentProfileRepository.findByName(it)
                } ?: agentProfileRepository.findByName("main")

                val selectedModel = profile?.model
                    ?: modelPreferences.getSelectedModel()
                    ?: modelPreferences.getModel(llmClientProvider.selectedProvider.value)
                val contextWindow = LlmProvider.getContextWindow(selectedModel)
                val toolFilter = profile?.allowedTools?.toSet()

                lateinit var coordinator: AgentCoordinator
                coordinator = AgentCoordinator(
                    clientProvider = { llmClientProvider.getCurrentLlmClient() },
                    toolRegistry = toolRegistry,
                    toolExecutor = toolExecutor,
                    messageStore = messageStore,
                    conversationId = conversationId,
                    contextWindow = contextWindow,
                    toolFilter = toolFilter,
                    onBeforeSummarize = {
                        memoryFlush(
                            clientProvider = { llmClientProvider.getCurrentLlmClient() },
                            toolExecutor = toolExecutor,
                            toolRegistry = toolRegistry,
                            messageStore = messageStore,
                            conversationHistory = coordinator.getConversationHistory(),
                            conversationId = conversationId,
                            model = selectedModel
                        )
                    }
                )

                synchronized(activeCoordinators) {
                    activeCoordinators[conversationId] = coordinator
                }

                launch {
                    coordinator.state.collect { state ->
                        ChatExecutionTracker.updateAgentState(conversationId, state)
                    }
                }

                val messageDao = database.messageDao()
                val dbMessages = messageDao.getMessagesOnce(conversationId)
                val (summaryContent, lastSummaryIndex) = findLastSummary(dbMessages)
                val history = buildConversationHistory(dbMessages, lastSummaryIndex)
                    .dropLast(1) // exclude the latest user message; it's passed to execute() separately
                coordinator.seedHistory(history, summaryContent)

                val baseSystemPrompt = profile?.systemPrompt
                    ?: modelPreferences.getSystemPrompt()
                val systemPrompt = buildSystemPrompt(baseSystemPrompt, profile)
                val (effectiveMessage, allMediaData) = loadMediaData(
                    userMessage, imagePaths, audioPaths, videoPaths,
                    documentPaths, documentNames, documentMimeTypes
                )

                val result = coordinator.execute(
                    userMessage = effectiveMessage,
                    systemPrompt = systemPrompt,
                    model = selectedModel,
                    maxIterations = modelPreferences.getMaxIterations(),
                    temperature = modelPreferences.getTemperature(),
                    mediaData = allMediaData.takeIf { it.isNotEmpty() }
                )

                persistResult(conversationId, result, messageDao)
            } catch (e: CancellationException) {
                Log.d(TAG, "Execution cancelled for $conversationId")
                withContext(NonCancellable) {
                    database.messageDao().insert(
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

    // -- executeChat helpers --------------------------------------------------

    private fun findLastSummary(
        dbMessages: List<MessageEntity>
    ): Pair<String?, Int> {
        val index = dbMessages.indexOfLast {
            it.role == "meta" && it.toolName == "summary"
        }
        val content = if (index >= 0) dbMessages[index].content else null
        return content to index
    }

    private fun buildConversationHistory(
        dbMessages: List<MessageEntity>,
        lastSummaryIndex: Int
    ): List<Message> {
        val messagesAfterSummary = if (lastSummaryIndex >= 0) {
            dbMessages.subList(lastSummaryIndex + 1, dbMessages.size)
        } else {
            dbMessages
        }
        return buildList {
            for (msg in messagesAfterSummary) {
                when {
                    msg.role == "user" || msg.role == "assistant" -> {
                        var content = msg.content
                        if (msg.role == "user") {
                            content = annotateMediaAttachments(content, msg)
                        }
                        add(Message(role = msg.role, content = content))
                    }
                    msg.role == "meta" && msg.toolName == "stopped" ->
                        add(Message(role = "assistant", content = "[The previous response was cancelled by the user. Do not continue or retry the cancelled task unless explicitly asked.]"))
                }
            }
        }
    }

    private fun annotateMediaAttachments(
        content: String,
        msg: MessageEntity
    ): String {
        var result = content
        if (!msg.imagePaths.isNullOrEmpty()) {
            val count = try {
                NetworkConfig.json.decodeFromString<List<String>>(msg.imagePaths).size
            } catch (_: Exception) { 1 }
            result = "$result\n[$count image(s) were attached to this message]"
        }
        if (!msg.audioPaths.isNullOrEmpty()) {
            val count = try {
                NetworkConfig.json.decodeFromString<List<String>>(msg.audioPaths).size
            } catch (_: Exception) { 1 }
            result = "$result\n[$count audio file(s) were attached to this message]"
        }
        if (!msg.videoPaths.isNullOrEmpty()) {
            val count = try {
                NetworkConfig.json.decodeFromString<List<String>>(msg.videoPaths).size
            } catch (_: Exception) { 1 }
            result = "$result\n[$count video(s) were attached to this message]"
        }
        if (!msg.documentPaths.isNullOrEmpty()) {
            val count = try {
                NetworkConfig.json.parseToJsonElement(msg.documentPaths)
                    .jsonArray.size
            } catch (_: Exception) { 1 }
            result = "$result\n[$count document(s) were attached to this message]"
        }
        return result
    }

    private fun buildSystemPrompt(
        basePrompt: String,
        profile: AgentProfileEntry? = null
    ): String {
        skillRepository.reload()
        val enabledSkills = if (profile?.enabledSkills != null) {
            val allowed = profile.enabledSkills.toSet()
            skillRepository.getEnabledSkills().filter { it.metadata.name in allowed }
        } else {
            skillRepository.getEnabledSkills()
        }
        val workspaceRoot = File(filesDir, "workspace")
        val memoryContext = MemoryBootstrap.loadMemoryContext(workspaceRoot)
        return SystemPromptBuilder.buildFullSystemPrompt(
            basePrompt = basePrompt,
            skills = enabledSkills,
            memoryContext = memoryContext
        )
    }

    private fun loadMediaData(
        userMessage: String,
        imagePaths: List<String>,
        audioPaths: List<String>,
        videoPaths: List<String>,
        documentPaths: List<String>,
        documentNames: List<String>,
        documentMimeTypes: List<String>
    ): Pair<String, List<MediaData>> {
        var effectiveMessage = userMessage
        val mediaData = buildList {
            imagePaths.forEach { path ->
                ImageStorageHelper.readAsBase64(path)?.let { (base64, mime) ->
                    add(MediaData(base64 = base64, mimeType = mime))
                }
            }
            audioPaths.forEach { path ->
                com.tomandy.palmclaw.util.AudioStorageHelper.readAsBase64(path)?.let { (base64, mime) ->
                    add(MediaData(base64 = base64, mimeType = mime))
                }
            }
            videoPaths.forEach { path ->
                com.tomandy.palmclaw.util.VideoStorageHelper.readAsBase64(path)?.let { (base64, mime) ->
                    add(MediaData(base64 = base64, mimeType = mime))
                }
            }
            documentPaths.forEachIndexed { index, path ->
                val mimeType = documentMimeTypes.getOrNull(index) ?: "application/octet-stream"
                val name = documentNames.getOrNull(index) ?: "document"
                if (DocumentStorageHelper.isTextFile(mimeType)) {
                    DocumentStorageHelper.readAsText(path)?.let { text ->
                        effectiveMessage = "$effectiveMessage\n\n--- $name ---\n$text\n---"
                    }
                } else {
                    DocumentStorageHelper.readAsBase64(path, mimeType)?.let { (base64, mime) ->
                        add(MediaData(base64 = base64, mimeType = mime, fileName = name))
                    }
                }
            }
        }
        return effectiveMessage to mediaData
    }

    private suspend fun persistResult(
        conversationId: String,
        result: Result<String>,
        messageDao: com.tomandy.palmclaw.data.dao.MessageDao
    ) {
        val conversationDao = database.conversationDao()
        result.fold(
            onSuccess = { response ->
                messageDao.insert(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = response,
                        timestamp = System.currentTimeMillis()
                    )
                )

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
    }

    // -- Summarization --------------------------------------------------------

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

                val messageDao = database.messageDao()
                val dbMessages = messageDao.getMessagesOnce(conversationId)
                val (summaryContent, lastSummaryIndex) = findLastSummary(dbMessages)

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

    // -- Memory flush ---------------------------------------------------------

    /**
     * Runs a short ReAct loop asking the model to persist important context
     * to memory files before conversation history is summarized/compacted.
     */
    private suspend fun memoryFlush(
        clientProvider: () -> com.tomandy.palmclaw.llm.LlmClient,
        toolExecutor: ToolExecutor,
        toolRegistry: ToolRegistry,
        messageStore: MessageStore,
        conversationHistory: List<Message>,
        conversationId: String,
        model: String
    ) {
        if (conversationHistory.size <= 2) return

        val flushMessages = buildList {
            add(
                Message(
                    role = "system",
                    content = "You are about to lose older conversation context due to " +
                        "summarization. Review the conversation below and use write_file " +
                        "to save any important information (decisions, preferences, facts, " +
                        "pending tasks) to memory files (memory/YYYY-MM-DD.md or MEMORY.md). " +
                        "If nothing is worth saving, respond with just 'OK'."
                )
            )
            addAll(conversationHistory)
        }

        val flushLoop = com.tomandy.palmclaw.agent.ReActLoop(
            llmClient = clientProvider(),
            toolExecutor = toolExecutor,
            messageStore = messageStore
        )
        flushLoop.step(
            messages = flushMessages,
            toolsProvider = { toolRegistry.getToolDefinitions() },
            conversationId = conversationId,
            model = model,
            maxIterations = 5,
            temperature = 0.3f
        )
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
        const val EXTRA_AUDIO_PATHS = "audio_paths"
        const val EXTRA_VIDEO_PATHS = "video_paths"
        const val EXTRA_DOCUMENT_PATHS = "document_paths"
        const val EXTRA_DOCUMENT_NAMES = "document_names"
        const val EXTRA_DOCUMENT_MIMETYPES = "document_mimetypes"
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

        fun startExecution(
            context: Context,
            conversationId: String,
            userMessage: String,
            imagePaths: List<String> = emptyList(),
            audioPaths: List<String> = emptyList(),
            videoPaths: List<String> = emptyList(),
            documentPaths: List<String> = emptyList(),
            documentNames: List<String> = emptyList(),
            documentMimeTypes: List<String> = emptyList()
        ) {
            val intent = Intent(context, ChatExecutionService::class.java).apply {
                action = ACTION_EXECUTE
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_USER_MESSAGE, userMessage)
                if (imagePaths.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_IMAGE_PATHS, ArrayList(imagePaths))
                }
                if (audioPaths.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_AUDIO_PATHS, ArrayList(audioPaths))
                }
                if (videoPaths.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_VIDEO_PATHS, ArrayList(videoPaths))
                }
                if (documentPaths.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_DOCUMENT_PATHS, ArrayList(documentPaths))
                    putStringArrayListExtra(EXTRA_DOCUMENT_NAMES, ArrayList(documentNames))
                    putStringArrayListExtra(EXTRA_DOCUMENT_MIMETYPES, ArrayList(documentMimeTypes))
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
