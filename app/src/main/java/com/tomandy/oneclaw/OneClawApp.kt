package com.tomandy.oneclaw

import android.app.Application
import androidx.work.Configuration
import com.tomandy.oneclaw.di.appModule
import com.tomandy.oneclaw.di.viewModelModule
import com.tomandy.oneclaw.llm.LlmClientProvider
import com.tomandy.oneclaw.plugin.PluginCoordinator
import com.tomandy.oneclaw.scheduler.di.schedulerModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

class OneClawApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@OneClawApp)
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
