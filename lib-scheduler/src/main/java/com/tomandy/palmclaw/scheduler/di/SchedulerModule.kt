package com.tomandy.palmclaw.scheduler.di

import com.tomandy.palmclaw.scheduler.worker.AgentTaskWorker
import org.koin.androidx.workmanager.dsl.worker
import org.koin.dsl.module

val schedulerModule = module {
    worker { AgentTaskWorker(get(), get(), get(), get()) }
}
