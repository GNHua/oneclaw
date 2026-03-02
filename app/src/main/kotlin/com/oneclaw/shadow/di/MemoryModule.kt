package com.oneclaw.shadow.di

import com.oneclaw.shadow.feature.memory.MemoryManager
import com.oneclaw.shadow.feature.memory.compaction.MemoryCompactor
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingEngine
import com.oneclaw.shadow.feature.memory.injection.MemoryInjector
import com.oneclaw.shadow.feature.memory.log.DailyLogWriter
import com.oneclaw.shadow.feature.memory.longterm.LongTermMemoryManager
import com.oneclaw.shadow.feature.memory.search.BM25Scorer
import com.oneclaw.shadow.feature.memory.search.HybridSearchEngine
import com.oneclaw.shadow.feature.memory.search.VectorSearcher
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import com.oneclaw.shadow.feature.memory.trigger.MemoryTriggerManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val memoryModule = module {
    // Data layer
    single { MemoryFileStorage(androidContext()) }
    single { EmbeddingEngine(androidContext()) }

    // Search components
    factory { BM25Scorer() }
    factory { VectorSearcher(get()) }
    factory { HybridSearchEngine(get(), get(), get(), get()) }

    // Domain layer
    single { LongTermMemoryManager(get()) }
    single {
        DailyLogWriter(
            messageRepository = get(),
            sessionRepository = get(),
            agentRepository = get(),
            providerRepository = get(),
            apiKeyStorage = get(),
            adapterFactory = get(),
            memoryFileStorage = get(),
            longTermMemoryManager = get(),
            memoryIndexDao = get(),
            embeddingEngine = get()
        )
    }
    single { MemoryInjector(get(), get()) }

    // RFC-049: Memory compactor (Phase 3)
    single {
        MemoryCompactor(
            longTermMemoryManager = get(),
            memoryFileStorage = get(),
            providerRepository = get(),
            apiKeyStorage = get(),
            adapterFactory = get()
        )
    }

    single {
        MemoryManager(
            dailyLogWriter = get(),
            longTermMemoryManager = get(),
            hybridSearchEngine = get(),
            memoryInjector = get(),
            memoryIndexDao = get(),
            memoryFileStorage = get(),
            embeddingEngine = get(),
            memoryCompactor = get()
        )
    }

    // Trigger manager
    single { MemoryTriggerManager(get(), get()) }
}
