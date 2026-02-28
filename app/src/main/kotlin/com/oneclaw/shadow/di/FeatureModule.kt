package com.oneclaw.shadow.di

import com.oneclaw.shadow.core.lifecycle.AppLifecycleObserver
import com.oneclaw.shadow.core.notification.NotificationHelper
import com.oneclaw.shadow.feature.agent.AgentDetailViewModel
import com.oneclaw.shadow.feature.agent.AgentListViewModel
import com.oneclaw.shadow.feature.agent.usecase.CloneAgentUseCase
import com.oneclaw.shadow.feature.agent.usecase.CreateAgentUseCase
import com.oneclaw.shadow.feature.agent.usecase.DeleteAgentUseCase
import com.oneclaw.shadow.feature.agent.usecase.GetAgentToolsUseCase
import com.oneclaw.shadow.feature.agent.usecase.ResolveModelUseCase
import com.oneclaw.shadow.feature.chat.ChatViewModel
import com.oneclaw.shadow.feature.chat.usecase.AutoCompactUseCase
import com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCase
import com.oneclaw.shadow.feature.provider.ProviderDetailViewModel
import com.oneclaw.shadow.feature.provider.ProviderListViewModel
import com.oneclaw.shadow.feature.provider.SetupViewModel
import com.oneclaw.shadow.feature.provider.usecase.FetchModelsUseCase
import com.oneclaw.shadow.feature.provider.usecase.SetDefaultModelUseCase
import com.oneclaw.shadow.feature.provider.usecase.TestConnectionUseCase
import com.oneclaw.shadow.feature.session.SessionListViewModel
import com.oneclaw.shadow.feature.usage.UsageStatisticsViewModel
import com.oneclaw.shadow.feature.session.usecase.BatchDeleteSessionsUseCase
import com.oneclaw.shadow.feature.session.usecase.CleanupSoftDeletedUseCase
import com.oneclaw.shadow.feature.session.usecase.CreateSessionUseCase
import com.oneclaw.shadow.feature.session.usecase.DeleteSessionUseCase
import com.oneclaw.shadow.feature.session.usecase.GenerateTitleUseCase
import com.oneclaw.shadow.feature.session.usecase.RenameSessionUseCase
import com.oneclaw.shadow.feature.memory.ui.MemoryViewModel
import com.oneclaw.shadow.feature.settings.JsToolsViewModel
import com.oneclaw.shadow.feature.settings.SyncSettingsViewModel
import com.oneclaw.shadow.feature.skill.SkillEditorViewModel
import com.oneclaw.shadow.feature.skill.SkillListViewModel
import com.oneclaw.shadow.feature.skill.usecase.CreateSkillUseCase
import com.oneclaw.shadow.feature.skill.usecase.DeleteSkillUseCase
import com.oneclaw.shadow.feature.skill.usecase.ExportSkillUseCase
import com.oneclaw.shadow.feature.skill.usecase.GetAllSkillsUseCase
import com.oneclaw.shadow.feature.skill.usecase.ImportSkillUseCase
import com.oneclaw.shadow.feature.skill.usecase.LoadSkillContentUseCase
import com.oneclaw.shadow.feature.skill.usecase.UpdateSkillUseCase
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

val featureModule = module {
    // RFC-008: Notification dependencies
    single { AppLifecycleObserver() }
    single { NotificationHelper(androidContext()) }

    // RFC-003: Provider feature use cases
    factory { TestConnectionUseCase(get()) }
    factory { FetchModelsUseCase(get()) }
    factory { SetDefaultModelUseCase(get()) }

    // RFC-003: Provider feature view models
    viewModelOf(::ProviderListViewModel)
    viewModelOf(::ProviderDetailViewModel)
    viewModelOf(::SetupViewModel)

    // RFC-005: Session feature use cases
    factory { CreateSessionUseCase(get()) }
    factory { DeleteSessionUseCase(get()) }
    factory { BatchDeleteSessionsUseCase(get()) }
    factory { RenameSessionUseCase(get()) }
    factory { GenerateTitleUseCase(get(), get(), get(), get()) }
    factory { CleanupSoftDeletedUseCase(get()) }

    // RFC-005: Session feature view model
    viewModelOf(::SessionListViewModel)

    // RFC-002: Agent feature use cases
    factory { CreateAgentUseCase(get()) }
    factory { CloneAgentUseCase(get()) }
    factory { DeleteAgentUseCase(get(), get()) }
    factory { GetAgentToolsUseCase(get(), get()) }
    factory { ResolveModelUseCase(get(), get()) }

    // RFC-002: Agent feature view models
    viewModelOf(::AgentListViewModel)
    viewModelOf(::AgentDetailViewModel)

    // RFC-011: Auto Compact
    factory { AutoCompactUseCase(get(), get(), get(), get()) }

    // RFC-001 + RFC-013 + RFC-014: Chat feature use cases (with memory and skill injection)
    factory {
        SendMessageUseCase(
            agentRepository = get(),
            sessionRepository = get(),
            messageRepository = get(),
            providerRepository = get(),
            apiKeyStorage = get(),
            adapterFactory = get(),
            toolExecutionEngine = get(),
            toolRegistry = get(),
            autoCompactUseCase = get(),
            memoryInjector = get(),
            skillRegistry = get()
        )
    }

    // RFC-013: Memory ViewModel
    viewModelOf(::MemoryViewModel)

    // RFC-001 + RFC-014: Chat feature view model (with skill registry)
    viewModel {
        ChatViewModel(
            sendMessageUseCase = get(),
            sessionRepository = get(),
            messageRepository = get(),
            agentRepository = get(),
            providerRepository = get(),
            createSessionUseCase = get(),
            generateTitleUseCase = get(),
            appLifecycleObserver = get(),
            notificationHelper = get(),
            skillRegistry = get()
        )
    }

    // RFC-006: Usage Statistics
    viewModelOf(::UsageStatisticsViewModel)

    // RFC-007: Data & Backup
    viewModelOf(::SyncSettingsViewModel)

    // RFC-012: JS Tool Engine
    viewModelOf(::JsToolsViewModel)

    // RFC-014: Skill feature use cases
    factory { GetAllSkillsUseCase(get()) }
    factory { CreateSkillUseCase(get()) }
    factory { UpdateSkillUseCase(get()) }
    factory { DeleteSkillUseCase(get()) }
    factory { ExportSkillUseCase(get()) }
    factory { ImportSkillUseCase(get()) }
    factory { LoadSkillContentUseCase(get()) }

    // RFC-014: Skill feature view models
    viewModelOf(::SkillListViewModel)
    viewModelOf(::SkillEditorViewModel)
}
