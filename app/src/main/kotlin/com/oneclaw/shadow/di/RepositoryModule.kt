package com.oneclaw.shadow.di

import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.FileRepository
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.repository.SettingsRepository
import com.oneclaw.shadow.core.repository.TaskExecutionRecordRepository
import com.oneclaw.shadow.data.repository.AgentRepositoryImpl
import com.oneclaw.shadow.data.repository.FileRepositoryImpl
import com.oneclaw.shadow.data.repository.MessageRepositoryImpl
import com.oneclaw.shadow.data.repository.ProviderRepositoryImpl
import com.oneclaw.shadow.data.repository.ScheduledTaskRepositoryImpl
import com.oneclaw.shadow.data.repository.SessionRepositoryImpl
import com.oneclaw.shadow.data.repository.SettingsRepositoryImpl
<<<<<<< HEAD
import com.oneclaw.shadow.data.repository.TaskExecutionRecordRepositoryImpl
=======
import com.oneclaw.shadow.data.storage.UserFileStorage
import org.koin.android.ext.koin.androidContext
>>>>>>> worktree-agent-a73de3a2
import org.koin.dsl.module

val repositoryModule = module {
    single<AgentRepository> { AgentRepositoryImpl(get()) }
    single<ProviderRepository> { ProviderRepositoryImpl(get(), get(), get(), get()) }
    single<SessionRepository> { SessionRepositoryImpl(get()) }
    single<MessageRepository> { MessageRepositoryImpl(get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get()) }
    single<ScheduledTaskRepository> { ScheduledTaskRepositoryImpl(get()) }
<<<<<<< HEAD
    // RFC-028: Execution record repository
    single<TaskExecutionRecordRepository> { TaskExecutionRecordRepositoryImpl(get()) }
=======

    // RFC-025: File browsing
    single { UserFileStorage(androidContext()) }
    single<FileRepository> { FileRepositoryImpl(get()) }
>>>>>>> worktree-agent-a73de3a2
}
