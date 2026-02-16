package com.tomandy.palmclaw.backup

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BackupViewModel(
    private val backupManager: BackupManager
) : ViewModel() {

    private val _exportStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val exportStatus: StateFlow<BackupStatus> = _exportStatus.asStateFlow()

    private val _importStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val importStatus: StateFlow<BackupStatus> = _importStatus.asStateFlow()

    private val _importManifest = MutableStateFlow<BackupManifest?>(null)
    val importManifest: StateFlow<BackupManifest?> = _importManifest.asStateFlow()

    /** URI of the selected import file, kept for confirm step */
    private var pendingImportUri: Uri? = null

    var includeMedia by mutableStateOf(true)

    fun exportBackup(outputUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _exportStatus.value = BackupStatus.InProgress(0, 0)
            backupManager.exportBackup(outputUri, includeMedia) { current, total ->
                _exportStatus.value = BackupStatus.InProgress(current, total)
            }.fold(
                onSuccess = { manifest ->
                    _exportStatus.value = BackupStatus.Success(manifest)
                },
                onFailure = { error ->
                    _exportStatus.value = BackupStatus.Error(
                        error.message ?: "Export failed"
                    )
                }
            )
        }
    }

    fun previewImport(inputUri: Uri) {
        pendingImportUri = inputUri
        viewModelScope.launch(Dispatchers.IO) {
            backupManager.readManifest(inputUri).fold(
                onSuccess = { manifest ->
                    _importManifest.value = manifest
                },
                onFailure = { error ->
                    _importStatus.value = BackupStatus.Error(
                        error.message ?: "Cannot read backup file"
                    )
                }
            )
        }
    }

    fun confirmImport() {
        val uri = pendingImportUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _importManifest.value = null
            _importStatus.value = BackupStatus.InProgress(0, 0)
            backupManager.importBackup(uri) { current, total ->
                _importStatus.value = BackupStatus.InProgress(current, total)
            }.fold(
                onSuccess = {
                    _importStatus.value = BackupStatus.NeedsRestart
                },
                onFailure = { error ->
                    _importStatus.value = BackupStatus.Error(
                        error.message ?: "Import failed"
                    )
                }
            )
        }
    }

    fun cancelImport() {
        pendingImportUri = null
        _importManifest.value = null
    }

    fun resetExportStatus() {
        _exportStatus.value = BackupStatus.Idle
    }

    fun resetImportStatus() {
        _importStatus.value = BackupStatus.Idle
        _importManifest.value = null
        pendingImportUri = null
    }
}

sealed class BackupStatus {
    data object Idle : BackupStatus()
    data class InProgress(val current: Int, val total: Int) : BackupStatus()
    data class Success(val manifest: BackupManifest) : BackupStatus()
    data class Error(val message: String) : BackupStatus()
    data object NeedsRestart : BackupStatus()
}
