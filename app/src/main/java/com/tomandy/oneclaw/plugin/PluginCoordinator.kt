package com.tomandy.oneclaw.plugin

import android.content.Context
import com.tomandy.oneclaw.agent.ToolRegistry
import com.tomandy.oneclaw.engine.LoadedPlugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.PluginEngine
import com.tomandy.oneclaw.pluginmanager.BuiltInPluginManager
import com.tomandy.oneclaw.pluginmanager.PluginManagementTool
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
        registerPluginManagementTool()
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
            val deviceControlLoaded = LoadedPlugin(
                metadata = DeviceControlPluginMetadata.get(),
                instance = deviceControlPlugin
            )
            if (pluginPreferences.isPluginEnabled("device_control")) {
                toolRegistry.registerPlugin(deviceControlLoaded)
            }
            pluginEngine.registerLoadedPlugin(deviceControlLoaded)
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
            locationPlugin.permissionCallback = object : com.tomandy.oneclaw.location.LocationPermissionCallback {
                override fun onLocationPermissionNeeded() {
                    android.util.Log.d("PluginCoordinator", "Location permission needed, emitting UI event")
                    ChatExecutionTracker.emitEvent(ChatExecutionTracker.UiEvent.LocationPermissionNeeded)
                }
            }
            val locationLoaded = LoadedPlugin(
                metadata = com.tomandy.oneclaw.location.LocationPluginMetadata.get(),
                instance = locationPlugin
            )
            if (pluginPreferences.isPluginEnabled("location")) {
                toolRegistry.registerPlugin(locationLoaded)
            }
            pluginEngine.registerLoadedPlugin(locationLoaded)
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
            val qrCodeLoaded = LoadedPlugin(
                metadata = com.tomandy.oneclaw.qrcode.QrCodePluginMetadata.get(),
                instance = qrCodePlugin
            )
            if (pluginPreferences.isPluginEnabled("qrcode")) {
                toolRegistry.registerPlugin(qrCodeLoaded)
            }
            pluginEngine.registerLoadedPlugin(qrCodeLoaded)
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
            val smsPhoneLoaded = LoadedPlugin(
                metadata = SmsPhonePluginMetadata.get(),
                instance = smsPhonePlugin
            )
            if (pluginPreferences.isPluginEnabled("sms-phone")) {
                toolRegistry.registerPlugin(smsPhoneLoaded)
            }
            pluginEngine.registerLoadedPlugin(smsPhoneLoaded)
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
            val pdfToolsLoaded = LoadedPlugin(
                metadata = com.tomandy.oneclaw.pdf.PdfToolsPluginMetadata.get(),
                instance = pdfToolsPlugin
            )
            if (pluginPreferences.isPluginEnabled("pdf-tools")) {
                toolRegistry.registerPlugin(pdfToolsLoaded)
            }
            pluginEngine.registerLoadedPlugin(pdfToolsLoaded)
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
            val notificationLoaded = LoadedPlugin(
                metadata = NotificationPluginMetadata.get(),
                instance = notificationPlugin
            )
            if (pluginPreferences.isPluginEnabled("notifications")) {
                toolRegistry.registerPlugin(notificationLoaded)
            }
            pluginEngine.registerLoadedPlugin(notificationLoaded)
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
            val mediaControlLoaded = LoadedPlugin(
                metadata = MediaControlPluginMetadata.get(),
                instance = mediaControlPlugin
            )
            if (pluginPreferences.isPluginEnabled("media_control")) {
                toolRegistry.registerPlugin(mediaControlLoaded)
            }
            pluginEngine.registerLoadedPlugin(mediaControlLoaded)
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
            val cameraLoaded = LoadedPlugin(
                metadata = CameraPluginMetadata.get(),
                instance = cameraPlugin
            )
            if (pluginPreferences.isPluginEnabled("camera")) {
                toolRegistry.registerPlugin(cameraLoaded)
            }
            pluginEngine.registerLoadedPlugin(cameraLoaded)
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
            val voiceMemoLoaded = LoadedPlugin(
                metadata = VoiceMemoPluginMetadata.get(),
                instance = voiceMemoPlugin
            )
            if (pluginPreferences.isPluginEnabled("voice_memo")) {
                toolRegistry.registerPlugin(voiceMemoLoaded)
            }
            pluginEngine.registerLoadedPlugin(voiceMemoLoaded)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun registerPluginManagementTool() {
        val managementTool = PluginManagementTool(userPluginManager, pluginEngine)
        val loadedPlugin = LoadedPlugin(
            metadata = PluginManagementTool.metadata(),
            instance = managementTool
        )
        toolRegistry.registerPlugin(loadedPlugin)
    }
}
