package com.tomandy.oneclaw.di

import com.tomandy.oneclaw.backup.BackupViewModel
import com.tomandy.oneclaw.engine.GoogleAuthProvider
import com.tomandy.oneclaw.engine.PluginEngine
import com.tomandy.oneclaw.llm.LlmClientProvider
import com.tomandy.oneclaw.plugin.PluginCoordinator
import com.tomandy.oneclaw.ui.chat.ChatViewModel
import com.tomandy.oneclaw.ui.cronjobs.CronjobsViewModel
import com.tomandy.oneclaw.ui.history.ConversationHistoryViewModel
import com.tomandy.oneclaw.ui.settings.AgentProfilesViewModel
import com.tomandy.oneclaw.ui.settings.MemoryViewModel
import com.tomandy.oneclaw.ui.settings.SettingsViewModel
import com.tomandy.oneclaw.ui.settings.SkillsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { params ->
        ChatViewModel(
            messageDao = get(),
            conversationDao = get(),
            conversationPreferences = get(),
            modelPreferences = get(),
            appContext = androidContext(),
            slashCommandRouter = get(),
            skillRepository = get(),
            agentProfileRepository = get(),
            conversationId = params.getOrNull()
        )
    }

    viewModel {
        val llmClientProvider: LlmClientProvider = get()
        val pluginCoordinator: PluginCoordinator = get()
        SettingsViewModel(
            credentialVault = get(),
            modelPreferences = get(),
            pluginPreferences = get(),
            pluginEngine = get(),
            userPluginManager = get(),
            onApiKeyChanged = { llmClientProvider.reloadApiKeys() },
            onPluginToggled = { id, enabled -> pluginCoordinator.setPluginEnabled(id, enabled) },
            googleAuthProvider = get<GoogleAuthProvider>()
        )
    }

    viewModel {
        SkillsViewModel(
            skillRepository = get(),
            skillPreferences = get(),
            userSkillsDir = java.io.File(androidContext().filesDir, "workspace/skills"),
            navigationState = get()
        )
    }

    viewModel {
        MemoryViewModel(
            workspaceDir = java.io.File(androidContext().filesDir, "workspace")
        )
    }

    viewModel { CronjobsViewModel(cronjobManager = get(), messageDao = get()) }

    viewModel {
        ConversationHistoryViewModel(
            conversationDao = get(),
            messageDao = get()
        )
    }

    viewModel { BackupViewModel(backupManager = get()) }

    viewModel {
        AgentProfilesViewModel(
            agentProfileRepository = get(),
            modelPreferences = get(),
            toolRegistry = get(),
            skillRepository = get(),
            llmClientProvider = get(),
            userAgentsDir = java.io.File(androidContext().filesDir, "workspace/agents")
        )
    }
}
