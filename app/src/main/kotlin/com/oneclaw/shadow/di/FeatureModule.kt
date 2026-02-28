package com.oneclaw.shadow.di

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
import com.oneclaw.shadow.feature.session.usecase.BatchDeleteSessionsUseCase
import com.oneclaw.shadow.feature.session.usecase.CleanupSoftDeletedUseCase
import com.oneclaw.shadow.feature.session.usecase.CreateSessionUseCase
import com.oneclaw.shadow.feature.session.usecase.DeleteSessionUseCase
import com.oneclaw.shadow.feature.session.usecase.GenerateTitleUseCase
import com.oneclaw.shadow.feature.session.usecase.RenameSessionUseCase
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

val featureModule = module {
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

    // RFC-001: Chat feature use cases
    factory { SendMessageUseCase(get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    // RFC-001: Chat feature view model
    viewModelOf(::ChatViewModel)
}
