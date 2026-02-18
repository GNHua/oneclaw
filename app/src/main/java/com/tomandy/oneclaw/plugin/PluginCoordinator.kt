package com.tomandy.oneclaw.plugin

import android.content.Context
import com.tomandy.oneclaw.agent.ToolRegistry
import com.tomandy.oneclaw.engine.LoadedPlugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.PluginEngine
import com.tomandy.oneclaw.pluginmanager.BuiltInPluginManager
import com.tomandy.oneclaw.pluginmanager.InstallPluginTool
import com.tomandy.oneclaw.pluginmanager.PluginPreferences
import com.tomandy.oneclaw.pluginmanager.UserPluginManager
import com.tomandy.oneclaw.scheduler.plugin.SchedulerPlugin
import com.tomandy.oneclaw.scheduler.plugin.SchedulerPluginMetadata
import com.tomandy.oneclaw.agent.MessageStore
import com.tomandy.oneclaw.agent.profile.AgentProfileRepository
import com.tomandy.oneclaw.data.AppDatabase
import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.data.dao.ConversationDao
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.llm.LlmClientProvider
import com.tomandy.oneclaw.security.CredentialVault
import com.tomandy.oneclaw.skill.SkillRepository
import com.tomandy.oneclaw.workspace.MemoryPlugin
import com.tomandy.oneclaw.workspace.MemoryPluginMetadata
import com.tomandy.oneclaw.workspace.WorkspacePlugin
import com.tomandy.oneclaw.workspace.WorkspacePluginMetadata
import com.tomandy.oneclaw.web.WebPlugin
import com.tomandy.oneclaw.web.WebPluginMetadata
import com.tomandy.oneclaw.devicecontrol.AbortCallback
import com.tomandy.oneclaw.devicecontrol.AccessibilityPromptCallback
import com.tomandy.oneclaw.devicecontrol.DeviceControlManager
import com.tomandy.oneclaw.devicecontrol.DeviceControlPlugin
import com.tomandy.oneclaw.devicecontrol.DeviceControlPluginMetadata
import com.tomandy.oneclaw.notificationmedia.MediaControlPlugin
import com.tomandy.oneclaw.notificationmedia.MediaControlPluginMetadata
import com.tomandy.oneclaw.notificationmedia.NotificationListenerPromptCallback
import com.tomandy.oneclaw.notificationmedia.NotificationMediaServiceManager
import com.tomandy.oneclaw.notificationmedia.NotificationPlugin
import com.tomandy.oneclaw.notificationmedia.NotificationPluginMetadata
import com.tomandy.oneclaw.service.ChatExecutionService
import com.tomandy.oneclaw.service.ChatExecutionTracker

class PluginCoordinator(
    private val context: Context,
    private val pluginEngine: PluginEngine,
    private val toolRegistry: ToolRegistry,
    private val pluginPreferences: PluginPreferences,
    private val credentialVault: CredentialVault,
    private val builtInPluginManager: BuiltInPluginManager,
    private val userPluginManager: UserPluginManager,
    private val configRegistry: ConfigRegistry,
    private val skillRepository: SkillRepository,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val agentProfileRepository: AgentProfileRepository,
    private val llmClientProvider: LlmClientProvider,
    private val modelPreferences: ModelPreferences,
    private val database: AppDatabase,
    private val messageStore: MessageStore
) {
    suspend fun initializePlugins() {
        registerBuiltInPlugins()
        registerInstallPluginTool()
        builtInPluginManager.loadBuiltInPlugins()
        userPluginManager.loadAllUserPlugins()
        skillRepository.loadAll()
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        pluginPreferences.setPluginEnabled(pluginId, enabled)
        if (enabled) {
            pluginEngine.getLoadedPlugin(pluginId)?.let { loadedPlugin ->
                toolRegistry.registerPlugin(loadedPlugin)
            }
        } else {
            toolRegistry.unregisterPlugin(pluginId)
        }
    }

    private suspend fun registerBuiltInPlugins() {
        try {
            val schedulerPlugin = SchedulerPlugin()
            val pluginContext = PluginContext.create(
                androidContext = context,
                pluginId = "scheduler",
                credentialVault = credentialVault
            )

            schedulerPlugin.onLoad(pluginContext)

            val loadedPlugin = LoadedPlugin(
                metadata = SchedulerPluginMetadata.get(),
                instance = schedulerPlugin
            )

            toolRegistry.registerPlugin(loadedPlugin)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register WorkspacePlugin
        try {
            val workspacePlugin = WorkspacePlugin()
            val workspaceContext = PluginContext.create(
                androidContext = context,
                pluginId = "workspace",
                credentialVault = credentialVault
            )
            workspacePlugin.onLoad(workspaceContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = WorkspacePluginMetadata.get(),
                    instance = workspacePlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register MemoryPlugin
        try {
            val memoryPlugin = MemoryPlugin()
            val memoryContext = PluginContext.create(
                androidContext = context,
                pluginId = "memory",
                credentialVault = credentialVault
            )
            memoryPlugin.onLoad(memoryContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = MemoryPluginMetadata.get(),
                    instance = memoryPlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register config plugin
        val configPlugin = ConfigPlugin(configRegistry)
        toolRegistry.registerPlugin(
            LoadedPlugin(
                metadata = ConfigPluginMetadata.get(),
                instance = configPlugin
            )
        )

        // Register search plugin
        val searchPlugin = SearchPlugin(messageDao, conversationDao)
        toolRegistry.registerPlugin(
            LoadedPlugin(
                metadata = SearchPluginMetadata.get(),
                instance = searchPlugin
            )
        )

        // Register agent delegation plugin (only if there are non-main profiles)
        try {
            agentProfileRepository.reload()
            val profiles = agentProfileRepository.profiles.value
            val delegatable = profiles.filter { it.name != "main" }
            if (delegatable.isNotEmpty()) {
                val delegatePlugin = DelegateAgentPlugin(
                    agentProfileRepository = agentProfileRepository,
                    llmClientProvider = llmClientProvider,
                    toolRegistry = toolRegistry,
                    messageStore = messageStore,
                    modelPreferences = modelPreferences,
                    database = database,
                    skillRepository = skillRepository,
                    filesDir = context.filesDir
                )
                toolRegistry.registerPlugin(
                    LoadedPlugin(
                        metadata = DelegateAgentPluginMetadata.get(profiles),
                        instance = delegatePlugin
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register DeviceControlPlugin
        try {
            val deviceControlPlugin = DeviceControlPlugin()
            val deviceControlContext = PluginContext.create(
                androidContext = context,
                pluginId = "device_control",
                credentialVault = credentialVault
            )
            deviceControlPlugin.onLoad(deviceControlContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = DeviceControlPluginMetadata.get(),
                    instance = deviceControlPlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register WebPlugin
        try {
            val webPlugin = WebPlugin()
            val webContext = PluginContext.create(
                androidContext = context,
                pluginId = "web",
                credentialVault = credentialVault
            )
            webPlugin.onLoad(webContext)
            val webLoaded = LoadedPlugin(
                metadata = WebPluginMetadata.get(),
                instance = webPlugin
            )
            // Auto-disable if search API key is not configured
            val searchProvider = credentialVault.getApiKey("plugin.web.search_provider") ?: "tavily"
            val hasWebApiKey = !credentialVault.getApiKey("plugin.web.${searchProvider}_api_key").isNullOrBlank()
            if (!hasWebApiKey && pluginPreferences.isPluginEnabled("web")) {
                pluginPreferences.setPluginEnabled("web", false)
            }
            if (pluginPreferences.isPluginEnabled("web")) {
                toolRegistry.registerPlugin(webLoaded)
            }
            pluginEngine.registerLoadedPlugin(webLoaded)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register LocationPlugin
        try {
            val locationPlugin = com.tomandy.oneclaw.location.LocationPlugin()
            val locationContext = PluginContext.create(
                androidContext = context,
                pluginId = "location",
                credentialVault = credentialVault
            )
            locationPlugin.onLoad(locationContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = com.tomandy.oneclaw.location.LocationPluginMetadata.get(),
                    instance = locationPlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register QrCodePlugin
        try {
            val qrCodePlugin = com.tomandy.oneclaw.qrcode.QrCodePlugin()
            val qrCodeContext = PluginContext.create(
                androidContext = context,
                pluginId = "qrcode",
                credentialVault = credentialVault
            )
            qrCodePlugin.onLoad(qrCodeContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = com.tomandy.oneclaw.qrcode.QrCodePluginMetadata.get(),
                    instance = qrCodePlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register SmsPhonePlugin
        try {
            val smsPhonePlugin = SmsPhonePlugin()
            val smsPhoneContext = PluginContext.create(
                androidContext = context,
                pluginId = "sms-phone",
                credentialVault = credentialVault
            )
            smsPhonePlugin.onLoad(smsPhoneContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = SmsPhonePluginMetadata.get(),
                    instance = smsPhonePlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register PdfToolsPlugin
        try {
            val pdfToolsPlugin = com.tomandy.oneclaw.pdf.PdfToolsPlugin()
            val pdfContext = PluginContext.create(
                androidContext = context,
                pluginId = "pdf-tools",
                credentialVault = credentialVault
            )
            pdfToolsPlugin.onLoad(pdfContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = com.tomandy.oneclaw.pdf.PdfToolsPluginMetadata.get(),
                    instance = pdfToolsPlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Wire abort callback for hardware button abort
        DeviceControlManager.setAbortCallback(object : AbortCallback {
            override fun abortAllExecutions() {
                val ids = synchronized(ChatExecutionService.activeCoordinators) {
                    ChatExecutionService.activeCoordinators.keys.toList()
                }
                ids.forEach { ChatExecutionService.cancelExecutionDirect(it) }
            }
        })

        // Wire accessibility prompt callback
        DeviceControlManager.setAccessibilityPromptCallback(object : AccessibilityPromptCallback {
            override fun onAccessibilityServiceNeeded() {
                ChatExecutionTracker.emitEvent(ChatExecutionTracker.UiEvent.AccessibilityServiceNeeded)
            }
        })

        // Register NotificationPlugin
        try {
            val notificationPlugin = NotificationPlugin()
            val notificationContext = PluginContext.create(
                androidContext = context,
                pluginId = "notifications",
                credentialVault = credentialVault
            )
            notificationPlugin.onLoad(notificationContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = NotificationPluginMetadata.get(),
                    instance = notificationPlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register MediaControlPlugin
        try {
            val mediaControlPlugin = MediaControlPlugin()
            val mediaControlContext = PluginContext.create(
                androidContext = context,
                pluginId = "media_control",
                credentialVault = credentialVault
            )
            mediaControlPlugin.onLoad(mediaControlContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = MediaControlPluginMetadata.get(),
                    instance = mediaControlPlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Wire notification listener prompt callback
        NotificationMediaServiceManager.setPromptCallback(object : NotificationListenerPromptCallback {
            override fun onNotificationListenerServiceNeeded() {
                ChatExecutionTracker.emitEvent(ChatExecutionTracker.UiEvent.NotificationListenerServiceNeeded)
            }
        })

        // Register CameraPlugin
        try {
            val cameraPlugin = CameraPlugin()
            val cameraContext = PluginContext.create(
                androidContext = context,
                pluginId = "camera",
                credentialVault = credentialVault
            )
            cameraPlugin.onLoad(cameraContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = CameraPluginMetadata.get(),
                    instance = cameraPlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register VoiceMemoPlugin
        try {
            val voiceMemoPlugin = VoiceMemoPlugin(
                getOpenAiApiKey = { credentialVault.getApiKey("OpenAI") },
                getOpenAiBaseUrl = { credentialVault.getApiKey("OpenAI_baseUrl") }
            )
            val voiceMemoContext = PluginContext.create(
                androidContext = context,
                pluginId = "voice_memo",
                credentialVault = credentialVault
            )
            voiceMemoPlugin.onLoad(voiceMemoContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = VoiceMemoPluginMetadata.get(),
                    instance = voiceMemoPlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun registerInstallPluginTool() {
        val installTool = InstallPluginTool(userPluginManager)
        val loadedPlugin = LoadedPlugin(
            metadata = InstallPluginTool.metadata(),
            instance = installTool
        )
        toolRegistry.registerPlugin(loadedPlugin)
    }
}
