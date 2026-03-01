package com.oneclaw.shadow.feature.settings

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.oneclaw.shadow.data.sync.BackupManager
import com.oneclaw.shadow.data.sync.SyncManager
import com.oneclaw.shadow.data.sync.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

class SyncSettingsViewModel(
    private val syncManager: SyncManager,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncSettingsUiState())
    val uiState: StateFlow<SyncSettingsUiState> = _uiState.asStateFlow()

    init {
        refreshSignInStatus()
    }

    fun getSignInIntent(): Intent = syncManager.getSignInIntent()

    fun handleSignInResult(result: ActivityResult) {
        viewModelScope.launch {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            if (task.isSuccessful) {
                val account = task.result
                _uiState.update {
                    it.copy(
                        isSignedIn = true,
                        accountEmail = account?.email,
                        signInError = null
                    )
                }
                // Check for existing backup
                val hasBackup = syncManager.hasRemoteBackup()
                if (hasBackup) {
                    _uiState.update { it.copy(showRestorePrompt = true) }
                } else {
                    // Trigger initial sync
                    syncNow()
                }
            } else {
                _uiState.update {
                    it.copy(signInError = "Sign-in failed. Please try again.")
                }
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncError = null) }
            when (val result = syncManager.upload()) {
                is SyncResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            lastSyncTimestamp = syncManager.getLastSyncTimestamp()
                        )
                    }
                }
                is SyncResult.Error -> {
                    _uiState.update {
                        it.copy(isSyncing = false, syncError = result.message)
                    }
                }
                else -> {
                    _uiState.update { it.copy(isSyncing = false) }
                }
            }
        }
    }

    fun restore() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true, showRestorePrompt = false) }
            when (val result = syncManager.restore()) {
                is SyncResult.RestoreSuccess -> {
                    _uiState.update {
                        it.copy(isRestoring = false, restoreComplete = true)
                    }
                }
                is SyncResult.Error -> {
                    _uiState.update {
                        it.copy(isRestoring = false, syncError = result.message)
                    }
                }
                else -> {
                    _uiState.update { it.copy(isRestoring = false) }
                }
            }
        }
    }

    fun dismissRestorePrompt() {
        _uiState.update { it.copy(showRestorePrompt = false) }
        // Trigger initial sync instead
        syncNow()
    }

    fun signOut() {
        viewModelScope.launch {
            syncManager.signOut()
            _uiState.update {
                it.copy(
                    isSignedIn = false,
                    accountEmail = null,
                    lastSyncTimestamp = 0L
                )
            }
        }
    }

    suspend fun exportBackup(): java.io.File {
        return backupManager.export()
    }

    suspend fun importBackup(inputStream: InputStream): Boolean {
        _uiState.update { it.copy(isRestoring = true) }
        val success = backupManager.import(inputStream)
        _uiState.update {
            it.copy(
                isRestoring = false,
                restoreComplete = if (success) true else it.restoreComplete,
                syncError = if (!success) "Import failed. The file may be invalid." else null
            )
        }
        return success
    }

    fun showImportConfirmation() {
        _uiState.update { it.copy(showImportConfirmation = true) }
    }

    fun dismissImportConfirmation() {
        _uiState.update { it.copy(showImportConfirmation = false) }
    }

    private fun refreshSignInStatus() {
        val account = syncManager.getSignedInAccount()
        _uiState.update {
            it.copy(
                isSignedIn = account != null,
                accountEmail = account?.email,
                lastSyncTimestamp = syncManager.getLastSyncTimestamp()
            )
        }
    }
}

data class SyncSettingsUiState(
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val isSyncing: Boolean = false,
    val isRestoring: Boolean = false,
    val lastSyncTimestamp: Long = 0L,
    val syncError: String? = null,
    val signInError: String? = null,
    val showRestorePrompt: Boolean = false,
    val showImportConfirmation: Boolean = false,
    val restoreComplete: Boolean = false
)
