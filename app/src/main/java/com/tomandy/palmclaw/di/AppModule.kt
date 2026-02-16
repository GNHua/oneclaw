package com.tomandy.palmclaw.di

import com.tomandy.palmclaw.agent.ScheduledAgentExecutor
import com.tomandy.palmclaw.agent.ToolExecutor
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.agent.MessageStore
import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.data.ConversationPreferences
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.data.RoomMessageStore
import com.tomandy.palmclaw.engine.PluginEngine
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.navigation.NavigationState
import com.tomandy.palmclaw.plugin.PluginCoordinator
import com.tomandy.palmclaw.pluginmanager.BuiltInPluginManager
import com.tomandy.palmclaw.pluginmanager.PluginPreferences
import com.tomandy.palmclaw.pluginmanager.UserPluginManager
import com.tomandy.palmclaw.scheduler.AgentExecutor
import com.tomandy.palmclaw.scheduler.CronjobManager
import com.tomandy.palmclaw.security.CredentialVaultImpl
import com.tomandy.palmclaw.skill.SkillEligibilityChecker
import com.tomandy.palmclaw.skill.SkillLoader
import com.tomandy.palmclaw.skill.SkillPreferences
import com.tomandy.palmclaw.skill.SkillRepository
import com.tomandy.palmclaw.skill.SlashCommandRouter
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File
import com.tomandy.palmclaw.security.CredentialVault as AppCredentialVault
import com.tomandy.palmclaw.engine.CredentialVault as EngineCredentialVault

val appModule = module {
    // Database
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().conversationDao() }

    // Security -- bind both interface types to the same instance
    single<AppCredentialVault> { CredentialVaultImpl(androidContext()) }
    single<EngineCredentialVault> { get<AppCredentialVault>() }

    // Preferences
    single { ModelPreferences(androidContext()) }
    single { ConversationPreferences(androidContext()) }
    single { PluginPreferences(androidContext()) }

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

    // Cronjob Manager
    single { CronjobManager(androidContext()) }

    // Plugin Managers
    single {
        BuiltInPluginManager(
            context = androidContext(),
            pluginEngine = get(),
            toolRegistry = get(),
            pluginPreferences = get(),
            credentialVault = get<EngineCredentialVault>()
        )
    }
    single {
        UserPluginManager(
            context = androidContext(),
            pluginEngine = get(),
            toolRegistry = get(),
            pluginPreferences = get(),
            credentialVault = get<EngineCredentialVault>()
        )
    }

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
            llmClientProvider = get(),
            modelPreferences = get()
        )
    }

    // Skill Engine
    single { SkillPreferences(androidContext()) }
    single {
        SkillLoader(
            context = androidContext(),
            userSkillsDir = File(androidContext().filesDir, "workspace/skills")
        )
    }
    single {
        val vault = get<AppCredentialVault>()
        SkillEligibilityChecker { provider ->
            runBlocking { vault.getApiKey(provider) != null }
        }
    }
    single {
        SkillRepository(
            loader = get(),
            preferences = get(),
            eligibilityChecker = get()
        ).also { it.loadAll() }
    }
    single { SlashCommandRouter(repository = get()) }

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
}
