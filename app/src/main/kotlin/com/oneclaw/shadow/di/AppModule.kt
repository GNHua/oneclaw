package com.oneclaw.shadow.di

import com.oneclaw.shadow.core.theme.ThemeManager
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapterFactory
import com.oneclaw.shadow.data.security.ApiKeyStorage
import com.oneclaw.shadow.data.security.GoogleAuthManager
import com.oneclaw.shadow.data.sync.BackupManager
import com.oneclaw.shadow.data.sync.SyncManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { ApiKeyStorage(androidContext()) }
    single { ModelApiAdapterFactory(get()) }
    single { ThemeManager(get()) }
    // RFC-007: Sync and backup
    single { SyncManager(androidContext(), get()) }
    single { BackupManager(androidContext(), get()) }
    // RFC-030: Google OAuth Manager
    single { GoogleAuthManager(androidContext(), get()) }
}
