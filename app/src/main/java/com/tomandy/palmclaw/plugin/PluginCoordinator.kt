package com.tomandy.palmclaw.plugin

import android.content.Context
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.engine.LoadedPlugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.PluginEngine
import com.tomandy.palmclaw.pluginmanager.BuiltInPluginManager
import com.tomandy.palmclaw.pluginmanager.InstallPluginTool
import com.tomandy.palmclaw.pluginmanager.PluginPreferences
import com.tomandy.palmclaw.pluginmanager.UserPluginManager
import com.tomandy.palmclaw.scheduler.plugin.SchedulerPlugin
import com.tomandy.palmclaw.scheduler.plugin.SchedulerPluginMetadata
import com.tomandy.palmclaw.agent.MessageStore
import com.tomandy.palmclaw.agent.profile.AgentProfileRepository
import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.data.dao.ConversationDao
import com.tomandy.palmclaw.data.dao.MessageDao
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.security.CredentialVault
import com.tomandy.palmclaw.skill.SkillRepository
import com.tomandy.palmclaw.workspace.MemoryPlugin
import com.tomandy.palmclaw.workspace.MemoryPluginMetadata
import com.tomandy.palmclaw.workspace.WorkspacePlugin
import com.tomandy.palmclaw.workspace.WorkspacePluginMetadata
import com.tomandy.palmclaw.devicecontrol.AbortCallback
import com.tomandy.palmclaw.devicecontrol.AccessibilityPromptCallback
import com.tomandy.palmclaw.devicecontrol.DeviceControlManager
import com.tomandy.palmclaw.devicecontrol.DeviceControlPlugin
import com.tomandy.palmclaw.devicecontrol.DeviceControlPluginMetadata
import com.tomandy.palmclaw.service.ChatExecutionService
import com.tomandy.palmclaw.service.ChatExecutionTracker

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
