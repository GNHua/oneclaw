package com.tomandy.oneclaw.di

import com.tomandy.oneclaw.agent.ScheduledAgentExecutor
import com.tomandy.oneclaw.agent.ToolRegistry
import com.tomandy.oneclaw.agent.MessageStore
import com.tomandy.oneclaw.backup.BackupManager
import com.tomandy.oneclaw.data.AppDatabase
import com.tomandy.oneclaw.data.ConversationPreferences
import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.data.RoomMessageStore
import com.tomandy.oneclaw.engine.PluginEngine
import com.tomandy.oneclaw.llm.LlmClientProvider
import com.tomandy.oneclaw.navigation.NavigationState
import com.tomandy.oneclaw.plugin.ConfigContributor
import com.tomandy.oneclaw.plugin.ConfigRegistry
import com.tomandy.oneclaw.plugin.PluginCoordinator
import com.tomandy.oneclaw.plugin.config.ModelConfigContributor
import com.tomandy.oneclaw.plugin.config.ModelPreferencesConfigContributor
import com.tomandy.oneclaw.plugin.config.PluginConfigContributor
import com.tomandy.oneclaw.plugin.config.SkillConfigContributor
import com.tomandy.oneclaw.pluginmanager.BuiltInPluginManager
import com.tomandy.oneclaw.pluginmanager.PluginPreferences
import com.tomandy.oneclaw.pluginmanager.UserPluginManager
import com.tomandy.oneclaw.scheduler.AgentExecutor
import com.tomandy.oneclaw.scheduler.CronjobManager
import com.tomandy.oneclaw.scheduler.data.CronjobDatabase
import com.tomandy.oneclaw.google.OAuthGoogleAuthManager
import com.tomandy.oneclaw.security.CredentialVaultImpl
import com.tomandy.oneclaw.skill.SkillLoader
import com.tomandy.oneclaw.skill.SkillPreferences
import com.tomandy.oneclaw.skill.SkillRepository
import com.tomandy.oneclaw.skill.SlashCommandRouter
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module
import com.tomandy.oneclaw.security.CredentialVault as AppCredentialVault
import com.tomandy.oneclaw.engine.CredentialVault as EngineCredentialVault
import com.tomandy.oneclaw.engine.GoogleAuthProvider

val appModule = module {
    // Database
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().conversationDao() }
    // Agent Profiles
    single {
        com.tomandy.oneclaw.agent.profile.AgentProfileLoader(
            context = androidContext(),
            userAgentsDir = java.io.File(androidContext().filesDir, "workspace/agents")
        )
    }
    single { com.tomandy.oneclaw.agent.profile.AgentProfileRepository(loader = get()) }

    // Security -- bind both interface types to the same instance
    single<AppCredentialVault> { CredentialVaultImpl(androidContext()) }
    single<EngineCredentialVault> { get<AppCredentialVault>() }

    // Google Auth: BYOK OAuth only
    single { OAuthGoogleAuthManager(androidContext(), get<AppCredentialVault>()) }
    single<GoogleAuthProvider> { get<OAuthGoogleAuthManager>() }

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
    single { com.tomandy.oneclaw.audio.AudioRecorder(androidContext()) }
    single { com.tomandy.oneclaw.audio.AndroidSttProvider(androidContext()) }
    single {
        com.tomandy.oneclaw.audio.AudioInputController(
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
            messageStore = get(),
            modelPreferences = get(),
            skillRepository = get(),
            agentProfileRepository = get(),
            filesDir = androidContext().filesDir
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
