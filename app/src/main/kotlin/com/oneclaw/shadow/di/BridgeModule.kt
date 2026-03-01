package com.oneclaw.shadow.di

import com.oneclaw.shadow.bridge.BridgeAgentExecutor
import com.oneclaw.shadow.bridge.BridgeConversationManager
import com.oneclaw.shadow.bridge.BridgeMessageObserver
import com.oneclaw.shadow.bridge.BridgePreferences
import com.oneclaw.shadow.bridge.service.BridgeCredentialProvider
import com.oneclaw.shadow.feature.bridge.BridgeAgentExecutorImpl
import com.oneclaw.shadow.feature.bridge.BridgeConversationManagerImpl
import com.oneclaw.shadow.feature.bridge.BridgeMessageObserverImpl
import com.oneclaw.shadow.feature.bridge.BridgeSettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.dsl.module

val bridgeModule = module {
    // Preferences and credentials
    single { BridgePreferences(androidContext()) }
    single { BridgeCredentialProvider(androidContext()) }

    // Bridge interface implementations
    single<BridgeAgentExecutor> {
        BridgeAgentExecutorImpl(
            sendMessageUseCase = get(),
            agentRepository = get()
        )
    }
    single<BridgeMessageObserver> {
        BridgeMessageObserverImpl(messageRepository = get())
    }
    single<BridgeConversationManager> {
        BridgeConversationManagerImpl(
            sessionRepository = get(),
            messageRepository = get(),
            agentRepository = get()
        )
    }

    // ViewModel
    viewModelOf(::BridgeSettingsViewModel)
}
