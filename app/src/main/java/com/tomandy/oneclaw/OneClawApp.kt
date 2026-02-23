package com.tomandy.oneclaw

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.tomandy.oneclaw.di.appModule
import com.tomandy.oneclaw.di.viewModelModule
import com.tomandy.oneclaw.llm.LlmClientProvider
import com.tomandy.oneclaw.plugin.PluginCoordinator
import com.tomandy.oneclaw.scheduler.CronjobManager
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

        // Ensure all enabled tasks have active alarms (covers app update,
        // force-stop, or any other scenario where alarms are cleared)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cronjobManager = CronjobManager(this@OneClawApp)
                val count = cronjobManager.rescheduleAlarms()
                if (count > 0) {
                    Log.i("OneClawApp", "Rescheduled $count alarm(s) on startup")
                }
            } catch (e: Exception) {
                Log.e("OneClawApp", "Failed to reschedule alarms on startup", e)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
