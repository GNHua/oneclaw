package com.tomandy.palmclaw.di

import com.tomandy.palmclaw.agent.ScheduledAgentExecutor
import com.tomandy.palmclaw.agent.ToolExecutor
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.agent.MessageStore
import com.tomandy.palmclaw.backup.BackupManager
import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.data.ConversationPreferences
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.data.RoomMessageStore
import com.tomandy.palmclaw.engine.PluginEngine
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.navigation.NavigationState
import com.tomandy.palmclaw.plugin.ConfigContributor
import com.tomandy.palmclaw.plugin.ConfigRegistry
import com.tomandy.palmclaw.plugin.PluginCoordinator
import com.tomandy.palmclaw.plugin.config.ModelConfigContributor
import com.tomandy.palmclaw.plugin.config.ModelPreferencesConfigContributor
import com.tomandy.palmclaw.plugin.config.PluginConfigContributor
import com.tomandy.palmclaw.plugin.config.SkillConfigContributor
import com.tomandy.palmclaw.pluginmanager.BuiltInPluginManager
import com.tomandy.palmclaw.pluginmanager.PluginPreferences
import com.tomandy.palmclaw.pluginmanager.UserPluginManager
import com.tomandy.palmclaw.scheduler.AgentExecutor
import com.tomandy.palmclaw.scheduler.CronjobManager
import com.tomandy.palmclaw.scheduler.data.CronjobDatabase
import com.tomandy.palmclaw.google.GoogleAuthManager
import com.tomandy.palmclaw.security.CredentialVaultImpl
import com.tomandy.palmclaw.skill.SkillLoader
import com.tomandy.palmclaw.skill.SkillPreferences
import com.tomandy.palmclaw.skill.SkillRepository
import com.tomandy.palmclaw.skill.SlashCommandRouter
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module
import com.tomandy.palmclaw.security.CredentialVault as AppCredentialVault
import com.tomandy.palmclaw.engine.CredentialVault as EngineCredentialVault
import com.tomandy.palmclaw.engine.GoogleAuthProvider

val appModule = module {
    // Database
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().conversationDao() }
    // Agent Profiles
    single {
        com.tomandy.palmclaw.agent.profile.AgentProfileLoader(
            context = androidContext(),
            userAgentsDir = java.io.File(androidContext().filesDir, "workspace/agents")
        )
    }
    single { com.tomandy.palmclaw.agent.profile.AgentProfileRepository(loader = get()) }

    // Security -- bind both interface types to the same instance
    single<AppCredentialVault> { CredentialVaultImpl(androidContext()) }
    single<EngineCredentialVault> { get<AppCredentialVault>() }

    // Google OAuth
    single { GoogleAuthManager(androidContext()) }
    single<GoogleAuthProvider> { get<GoogleAuthManager>() }

    // Preferences
    single { ModelPreferences(androidContext()) }
    single { ConversationPreferences(androidContext()) }
    single { PluginPreferences(androidContext()) }

    // Skills
    single { SkillPreferences(androidContext()) }
    single {
        SkillLoader(
            context = androidContext(),
            userSkillsDir = java.io.File(androidContext().filesDir, "workspace/skills")
        )
    }
    single { SkillRepository(loader = get(), preferences = get()) }
    single { SlashCommandRouter(repository = get()) }

    // Plugin Engine
    single { PluginEngine(androidContext()) }

    // Tool Registry
    single { ToolRegistry() }

    // Message Store
    single<MessageStore> { RoomMessageStore(get()) }

    // Tool Executor
    single { ToolExecutor(toolRegistry = get(), messageStore = get()) }

    // LLM Client Provider
    single { LlmClientProvider(credentialVault = get(), modelPreferences = get()) }

    // Cronjob Database & Manager
    single { CronjobDatabase.getDatabase(androidContext()) }
    single { CronjobManager(androidContext(), get()) }

    // Plugin Managers
    single {
        BuiltInPluginManager(
            context = androidContext(),
            pluginEngine = get(),
            toolRegistry = get(),
            pluginPreferences = get(),
            credentialVault = get<EngineCredentialVault>(),
            googleAuthProvider = get<GoogleAuthProvider>()
        )
    }
    single {
        UserPluginManager(
            context = androidContext(),
            pluginEngine = get(),
            toolRegistry = get(),
            pluginPreferences = get(),
            credentialVault = get<EngineCredentialVault>(),
            googleAuthProvider = get<GoogleAuthProvider>()
        )
    }

    // Config Contributors
    single { ModelPreferencesConfigContributor(modelPreferences = get()) } bind ConfigContributor::class
    single { ModelConfigContributor(llmClientProvider = get(), modelPreferences = get()) } bind ConfigContributor::class
    single { PluginConfigContributor(pluginEngine = get(), pluginPreferences = get(), toolRegistry = get()) } bind ConfigContributor::class
    single { SkillConfigContributor(skillRepository = get(), skillPreferences = get()) } bind ConfigContributor::class

    // Config Registry
    single { ConfigRegistry(contributors = getAll()) }

    // Plugin Coordinator
    single {
        PluginCoordinator(
            context = androidContext(),
            pluginEngine = get(),
            toolRegistry = get(),
            pluginPreferences = get(),
            credentialVault = get(),
            builtInPluginManager = get(),
            userPluginManager = get(),
            configRegistry = get(),
            skillRepository = get(),
            messageDao = get(),
            conversationDao = get(),
            agentProfileRepository = get(),
            llmClientProvider = get(),
            modelPreferences = get(),
            database = get(),
            messageStore = get()
        )
    }

    // Audio
    single { com.tomandy.palmclaw.audio.AudioRecorder(androidContext()) }
    single { com.tomandy.palmclaw.audio.AndroidSttProvider(androidContext()) }
    single {
        com.tomandy.palmclaw.audio.AudioInputController(
            audioRecorder = get(),
            sttProvider = get(),
            modelPreferences = get()
        )
    }

    // Navigation State
    single { NavigationState() }

    // Agent Executor (for scheduled tasks)
    single<AgentExecutor> {
        ScheduledAgentExecutor(
            database = get(),
            llmClientProvider = get(),
            toolRegistry = get(),
            toolExecutor = get(),
            messageStore = get(),
            modelPreferences = get(),
            skillRepository = get()
        )
    }

    // Backup
    single {
        BackupManager(
            context = androidContext(),
            appDatabase = get(),
            cronjobDatabase = get(),
            modelPreferences = get(),
            pluginPreferences = get(),
            skillPreferences = get()
        )
    }
}
