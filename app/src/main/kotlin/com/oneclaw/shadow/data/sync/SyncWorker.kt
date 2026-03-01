package com.oneclaw.shadow.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val syncManager: SyncManager by inject()

    override suspend fun doWork(): Result {
        // Skip if not signed in
        if (syncManager.getSignedInAccount() == null) {
            return Result.success()
        }

        // Skip if no changes since last sync
        if (!syncManager.hasChangedSinceLastSync()) {
            return Result.success()
        }

        // Upload
        return when (syncManager.upload()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.NotSignedIn -> Result.success()
            is SyncResult.Error -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "oneclaw_sync_periodic"
    }
}
