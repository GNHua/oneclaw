package com.oneclaw.shadow.di

import androidx.room.Room
import com.oneclaw.shadow.data.local.db.AppDatabase
import com.oneclaw.shadow.data.local.db.MIGRATION_1_2
import com.oneclaw.shadow.data.local.db.MIGRATION_2_3
import com.oneclaw.shadow.data.local.db.MIGRATION_3_4
import com.oneclaw.shadow.data.local.db.MIGRATION_4_5
import com.oneclaw.shadow.data.local.db.MIGRATION_5_6
import com.oneclaw.shadow.data.local.db.MIGRATION_6_7
import com.oneclaw.shadow.data.local.db.MIGRATION_7_8
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "oneclaw.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
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
    single { get<AppDatabase>().scheduledTaskDao() }
    // RFC-028: Execution record DAO
    single { get<AppDatabase>().taskExecutionRecordDao() }
}
