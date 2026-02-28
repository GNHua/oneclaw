package com.oneclaw.shadow.di

import androidx.room.Room
import com.oneclaw.shadow.data.local.db.AppDatabase
import com.oneclaw.shadow.data.local.db.MIGRATION_1_2
import com.oneclaw.shadow.data.local.db.MIGRATION_2_3
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "oneclaw.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .addCallback(AppDatabase.createSeedCallback())
            .build()
    }
    single { get<AppDatabase>().agentDao() }
    single { get<AppDatabase>().providerDao() }
    single { get<AppDatabase>().modelDao() }
    single { get<AppDatabase>().sessionDao() }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().settingsDao() }
    single { get<AppDatabase>().memoryIndexDao() }
}
