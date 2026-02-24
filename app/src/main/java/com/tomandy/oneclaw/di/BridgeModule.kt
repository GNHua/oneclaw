package com.tomandy.oneclaw.di

import com.tomandy.oneclaw.bridge.BridgeAgentExecutor
import com.tomandy.oneclaw.bridge.BridgeAgentExecutorImpl
import com.tomandy.oneclaw.bridge.BridgeConversationManager
import com.tomandy.oneclaw.bridge.BridgeMessageObserver
import com.tomandy.oneclaw.bridge.RoomBridgeConversationManager
import com.tomandy.oneclaw.bridge.RoomBridgeMessageObserver
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val bridgeModule = module {
    single<BridgeAgentExecutor> { BridgeAgentExecutorImpl(androidContext()) }
    single<BridgeMessageObserver> { RoomBridgeMessageObserver(get()) }
    single<BridgeConversationManager> { RoomBridgeConversationManager(get(), get(), get()) }
}
