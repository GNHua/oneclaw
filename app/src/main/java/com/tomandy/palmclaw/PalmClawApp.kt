package com.tomandy.palmclaw

import android.app.Application
import androidx.work.Configuration
import com.tomandy.palmclaw.di.appModule
import com.tomandy.palmclaw.di.viewModelModule
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.plugin.PluginCoordinator
import com.tomandy.palmclaw.scheduler.di.schedulerModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

class PalmClawApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@PalmClawApp)
            workManagerFactory()
            modules(appModule, viewModelModule, schedulerModule)
        }

        val llmClientProvider: LlmClientProvider = get()
        val pluginCoordinator: PluginCoordinator = get()

        CoroutineScope(Dispatchers.Main).launch {
            llmClientProvider.loadApiKeys()
        }
        CoroutineScope(Dispatchers.Main).launch {
            pluginCoordinator.initializePlugins()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
