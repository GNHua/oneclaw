package com.oneclaw.shadow

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.oneclaw.shadow.core.lifecycle.AppLifecycleObserver
import com.oneclaw.shadow.core.notification.NotificationHelper
import com.oneclaw.shadow.core.theme.ThemeManager
import com.oneclaw.shadow.data.sync.SyncWorker
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.feature.schedule.alarm.AlarmScheduler
import com.oneclaw.shadow.feature.schedule.usecase.CleanupExecutionHistoryUseCase
import com.oneclaw.shadow.di.appModule
import com.oneclaw.shadow.di.databaseModule
import com.oneclaw.shadow.di.featureModule
import com.oneclaw.shadow.di.memoryModule
import com.oneclaw.shadow.di.networkModule
import com.oneclaw.shadow.di.repositoryModule
import com.oneclaw.shadow.di.toolModule
import com.oneclaw.shadow.feature.memory.trigger.MemoryTriggerManager
import com.oneclaw.shadow.feature.session.usecase.CleanupSoftDeletedUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.java.KoinJavaComponent.get
import java.util.concurrent.TimeUnit

class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@OneclawApplication)
            modules(
                appModule,
                databaseModule,
                networkModule,
                repositoryModule,
                toolModule,
                featureModule,
                memoryModule
            )
        }

        // RFC-008: Register app lifecycle observer for foreground detection
        get<AppLifecycleObserver>(AppLifecycleObserver::class.java).register()

        // RFC-008: Create notification channel (required for Android 8+)
        get<NotificationHelper>(NotificationHelper::class.java).createNotificationChannel()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            get<CleanupSoftDeletedUseCase>(CleanupSoftDeletedUseCase::class.java)()
        }

        // RFC-009: Initialize theme from persisted setting
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            get<ThemeManager>(ThemeManager::class.java).initialize()
        }

        // RFC-013: Register memory trigger for app background event
        val memoryTriggerManager = get<MemoryTriggerManager>(MemoryTriggerManager::class.java)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    memoryTriggerManager.onAppBackground()
                }
            }
        )

        // RFC-019: Re-register all enabled scheduled task alarms on app start
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val scheduledTaskRepository = get<ScheduledTaskRepository>(ScheduledTaskRepository::class.java)
            val alarmScheduler = get<AlarmScheduler>(AlarmScheduler::class.java)
            val enabledTasks = scheduledTaskRepository.getEnabledTasks()
            alarmScheduler.rescheduleAllEnabled(enabledTasks)
        }

        // RFC-028: Cleanup old execution history records on app start
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            get<CleanupExecutionHistoryUseCase>(CleanupExecutionHistoryUseCase::class.java)()
        }

        // RFC-007: Schedule periodic Google Drive sync every 1 hour
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}
