package com.tomandy.oneclaw.scheduler.di

import com.tomandy.oneclaw.scheduler.worker.AgentTaskWorker
import org.koin.androidx.workmanager.dsl.worker
import org.koin.dsl.module

val schedulerModule = module {
    worker { AgentTaskWorker(get(), get(), get(), get()) }
}
