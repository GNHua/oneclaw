package com.tomandy.palmclaw.di

import com.tomandy.palmclaw.engine.PluginEngine
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.plugin.PluginCoordinator
import com.tomandy.palmclaw.ui.chat.ChatViewModel
import com.tomandy.palmclaw.ui.cronjobs.CronjobsViewModel
import com.tomandy.palmclaw.ui.history.ConversationHistoryViewModel
import com.tomandy.palmclaw.ui.settings.SettingsViewModel
import com.tomandy.palmclaw.ui.settings.SkillsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { params ->
        ChatViewModel(
            messageDao = get(),
            conversationDao = get(),
            conversationPreferences = get(),
            appContext = androidContext(),
            slashCommandRouter = get(),
            skillRepository = get(),
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
            loadedPlugins = get<PluginEngine>().getAllPlugins(),
            userPluginManager = get(),
            onApiKeyChanged = { llmClientProvider.reloadApiKeys() },
            onPluginToggled = { id, enabled -> pluginCoordinator.setPluginEnabled(id, enabled) }
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

    viewModel { CronjobsViewModel(cronjobManager = get()) }

    viewModel {
        ConversationHistoryViewModel(
            conversationDao = get(),
            messageDao = get()
        )
    }
}
