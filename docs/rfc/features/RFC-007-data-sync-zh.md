# RFC-007: 数据存储与同步

## 文档信息
- **RFC ID**: RFC-007
- **关联 PRD**: [FEAT-007 (Data Storage & Sync)](../../prd/features/FEAT-007-data-sync.md)
- **关联架构**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **依赖**: [RFC-005 (Session Management)](RFC-005-session-management.md)
- **被依赖**: 无
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

OneClaw 将所有用户数据存储在本地 Room 数据库（`oneclaw.db`）中，包括会话、消息、Agent 配置、Provider 配置、模型偏好和应用设置。API key 单独存储在 `EncryptedSharedPreferences` 中（通过 `ApiKeyStorage`），不属于 Room 数据库的一部分。

目前用户无法备份数据或将数据迁移到另一台设备。如果用户清除应用数据、卸载应用或更换手机，所有会话、Agent 配置和设置都会丢失。唯一容易恢复的数据是 API key 本身（通过重新输入）。

本 RFC 引入两种备份机制：(1) 自动 Google Drive 同步，每小时将整个 SQLite 数据库文件上传到用户 Drive 的 `appdata` 隐藏文件夹；(2) 手动本地导出/导入包含数据库的 ZIP 文件。两种机制均天然排除 API key，因为 key 存储在 `EncryptedSharedPreferences` 中，不在 Room 数据库内。

### 目标

1. 允许用户使用 Google 账号登录，每小时自动将 Room 数据库同步到 Google Drive。
2. 通过时间戳比较（`updatedAt`/`createdAt` 与 `lastSyncTimestamp`）检测变更，无变更时跳过上传。
3. 支持在新设备上从 Google Drive 恢复（下载 `.db` 文件，替换本地数据库，重启应用）。
4. 在 Settings 中提供手动"Export Backup"（导出包含 `.db` 文件的 ZIP）和"Import Backup"（选择 ZIP，解压，替换本地数据库）。
5. 在 Settings 页面新增"Data & Backup"分区，显示 Google Drive 登录状态、上次同步时间、Sync Now 按钮以及导出/导入入口。
6. 确保 API key 永远不会被包含在任何同步或导出操作中 -- 这是设计上天然保证的，因为 key 不在 Room 数据库中。

### 非目标

- 选择性同步（选择同步哪些表或记录）。
- 支持其他云存储（Dropbox、OneDrive、iCloud）。
- 同步数据的端到端加密（依赖 Google Drive 内置加密）。
- 合并冲突 UI（始终使用 last-write-wins，不对单条记录弹出冲突提示）。
- 同步频率配置（固定为 1 小时）。
- 逐条记录合并或 JSON 序列化。

## 技术设计

### 架构概览

```
同步和备份是两条独立路径，均操作同一个 SQLite .db 文件：

1. Google Drive Sync（自动，每小时）
   SyncWorker (WorkManager) -> SyncManager.sync()
     -> 检查：是否有记录 updatedAt/createdAt > lastSyncTimestamp？
     -> 如果是：关闭 DB checkpoint -> 复制 .db 文件 -> 上传到 Drive appdata
     -> 如果否：跳过
   恢复：SyncManager.restore() -> 下载 .db -> 替换本地 -> 重启

2. Local Export/Import（手动）
   BackupManager.export() -> 复制 .db 文件 -> ZIP -> 分享面板
   BackupManager.import() -> 选择 ZIP -> 解压 .db -> 确认对话框 -> 替换本地 -> 重启

3. Settings UI
   SettingsScreen "Data & Backup" 分区
     -> Google 登录状态、上次同步时间、Sync Now 按钮
     -> Export Backup、Import Backup 入口
```

### 数据库文件详情

Room 数据库存储在标准 Android 路径：

```
/data/data/com.oneclaw.shadow/databases/oneclaw.db
```

数据库名称 `"oneclaw.db"` 在 `DatabaseModule.kt` 中定义：

```kotlin
Room.databaseBuilder(
    androidContext(),
    AppDatabase::class.java,
    "oneclaw.db"
)
```

数据库包含六张表：`agents`、`providers`、`models`、`sessions`、`messages` 和 `app_settings`。所有用户数据都在这里。API key 单独存储在 `EncryptedSharedPreferences` 中（文件 `oneclaw_api_keys`），不属于此数据库。

### Change 1: SyncManager -- Google Drive 上传/下载

新文件：`data/sync/SyncManager.kt`

`SyncManager` 负责 Google Drive 认证、上传、下载和恢复逻辑。

```kotlin
package com.oneclaw.shadow.data.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.oneclaw.shadow.data.local.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

class SyncManager(
    private val context: Context,
    private val database: AppDatabase
) {
    companion object {
        private const val BACKUP_FILE_NAME = "oneclaw_backup.db"
        private const val MIME_TYPE = "application/x-sqlite3"
        private const val PREF_NAME = "oneclaw_sync_prefs"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
    }

    private val syncPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // --- Google Sign-In ---

    fun buildGoogleSignInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    fun getSignInIntent(): Intent {
        return buildGoogleSignInClient().signInIntent
    }

    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            buildGoogleSignInClient().signOut()
        }
    }

    // --- Drive Service ---

    private fun buildDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("OneClaw")
            .build()
    }

    // --- Sync Logic ---

    /**
     * 检查自上次同步以来是否有任何记录被创建或更新。
     * 查询所有相关表中 updatedAt/createdAt 的最大值。
     */
    suspend fun hasChangedSinceLastSync(): Boolean = withContext(Dispatchers.IO) {
        val lastSync = syncPrefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
        val db = database.openHelper.readableDatabase
        val cursor = db.query(
            """
            SELECT MAX(ts) FROM (
                SELECT MAX(updated_at) AS ts FROM sessions
                UNION ALL
                SELECT MAX(created_at) AS ts FROM messages
                UNION ALL
                SELECT MAX(updated_at) AS ts FROM agents
                UNION ALL
                SELECT MAX(updated_at) AS ts FROM providers
            )
            """.trimIndent()
        )
        val maxTimestamp = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        cursor.close()
        maxTimestamp > lastSync
    }

    /**
     * 将整个 .db 文件上传到 Google Drive appdata 文件夹。
     * 步骤：
     * 1. Checkpoint WAL，确保所有数据都在主 .db 文件中。
     * 2. 将 .db 文件复制到临时位置（避免锁定问题）。
     * 3. 在 Drive 上上传或更新文件。
     */
    suspend fun upload(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val account = getSignedInAccount() ?: return@withContext SyncResult.NotSignedIn

            // Step 1: Checkpoint WAL
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { /* execute */ }

            // Step 2: 复制 .db 到临时文件
            val dbFile = context.getDatabasePath("oneclaw.db")
            val tempFile = File(context.cacheDir, "oneclaw_sync_temp.db")
            dbFile.copyTo(tempFile, overwrite = true)

            // Step 3: 上传到 Drive
            val driveService = buildDriveService(account)
            val existingFileId = findBackupFileId(driveService)

            val mediaContent = FileContent(MIME_TYPE, tempFile)

            if (existingFileId != null) {
                // 更新已有文件
                driveService.files().update(existingFileId, null, mediaContent).execute()
            } else {
                // 在 appdata 中创建新文件
                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = BACKUP_FILE_NAME
                    parents = listOf("appDataFolder")
                }
                driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id")
                    .execute()
            }

            // 更新上次同步时间戳
            syncPrefs.edit()
                .putLong(KEY_LAST_SYNC_TIMESTAMP, System.currentTimeMillis())
                .apply()

            tempFile.delete()
            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 从 Drive 下载备份 .db 文件并替换本地数据库。
     * 替换后应用必须重启或重新初始化数据库。
     */
    suspend fun restore(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val account = getSignedInAccount() ?: return@withContext SyncResult.NotSignedIn
            val driveService = buildDriveService(account)
            val fileId = findBackupFileId(driveService)
                ?: return@withContext SyncResult.NoBackupFound

            // 下载到临时文件
            val tempFile = File(context.cacheDir, "oneclaw_restore_temp.db")
            FileOutputStream(tempFile).use { outputStream ->
                driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            }

            // 关闭当前数据库
            database.close()

            // 替换本地数据库
            val dbFile = context.getDatabasePath("oneclaw.db")
            tempFile.copyTo(dbFile, overwrite = true)
            tempFile.delete()

            // 删除 WAL 和 SHM 文件以避免过期状态
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()

            SyncResult.RestoreSuccess
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 检查 Drive 上是否存在备份。
     */
    suspend fun hasRemoteBackup(): Boolean = withContext(Dispatchers.IO) {
        val account = getSignedInAccount() ?: return@withContext false
        val driveService = buildDriveService(account)
        findBackupFileId(driveService) != null
    }

    fun getLastSyncTimestamp(): Long {
        return syncPrefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
    }

    private fun findBackupFileId(driveService: Drive): String? {
        val result = driveService.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$BACKUP_FILE_NAME'")
            .setFields("files(id, name)")
            .execute()
        return result.files?.firstOrNull()?.id
    }
}

sealed class SyncResult {
    data object Success : SyncResult()
    data object RestoreSuccess : SyncResult()
    data object NotSignedIn : SyncResult()
    data object NoBackupFound : SyncResult()
    data class Error(val message: String) : SyncResult()
}
```

### Change 2: SyncWorker -- 周期性后台同步

新文件：`data/sync/SyncWorker.kt`

`SyncWorker` 是一个 WorkManager `CoroutineWorker`，每 1 小时运行一次。它检查自上次同步以来是否有数据变更，如有则上传数据库。

```kotlin
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
        // 未登录则跳过
        if (syncManager.getSignedInAccount() == null) {
            return Result.success()
        }

        // 无变更则跳过
        if (!syncManager.hasChangedSinceLastSync()) {
            return Result.success()
        }

        // 上传
        return when (syncManager.upload()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.NotSignedIn -> Result.success() // 无需操作
            is SyncResult.Error -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "oneclaw_sync_periodic"
    }
}
```

定时调度在 `OneclawApplication.onCreate()` 中完成：

```kotlin
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.oneclaw.shadow.data.sync.SyncWorker
import java.util.concurrent.TimeUnit

// 在 OneclawApplication.onCreate() 中，startKoin 之后：
val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
    .build()
WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    SyncWorker.WORK_NAME,
    ExistingPeriodicWorkPolicy.KEEP,
    syncRequest
)
```

### Change 3: BackupManager -- 本地 ZIP 导出/导入

新文件：`data/sync/BackupManager.kt`

`BackupManager` 负责本地 ZIP 文件的导出和导入。

```kotlin
package com.oneclaw.shadow.data.sync

import android.content.Context
import com.oneclaw.shadow.data.local.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val database: AppDatabase
) {
    companion object {
        private const val DB_ENTRY_NAME = "oneclaw.db"
        private const val EXPORT_FILE_PREFIX = "oneclaw-backup-"
    }

    /**
     * 将数据库导出为 ZIP 文件。
     * 返回生成的 ZIP 文件在缓存目录中的路径。
     */
    suspend fun export(): File = withContext(Dispatchers.IO) {
        // Checkpoint WAL
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { /* execute */ }

        val dbFile = context.getDatabasePath("oneclaw.db")
        val timestamp = System.currentTimeMillis()
        val zipFile = File(context.cacheDir, "${EXPORT_FILE_PREFIX}${timestamp}.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry(DB_ENTRY_NAME))
            FileInputStream(dbFile).use { fis ->
                fis.copyTo(zos)
            }
            zos.closeEntry()
        }

        zipFile
    }

    /**
     * 从 ZIP 文件输入流中导入数据库。
     * 替换本地数据库。之后应用必须重启或重新初始化。
     */
    suspend fun import(inputStream: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempDbFile = File(context.cacheDir, "oneclaw_import_temp.db")

            // 从 ZIP 中解压 .db 文件
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                var found = false
                while (entry != null) {
                    if (entry.name == DB_ENTRY_NAME) {
                        FileOutputStream(tempDbFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        found = true
                        break
                    }
                    entry = zis.nextEntry
                }
                if (!found) return@withContext false
            }

            // 关闭当前数据库
            database.close()

            // 替换本地数据库
            val dbFile = context.getDatabasePath("oneclaw.db")
            tempDbFile.copyTo(dbFile, overwrite = true)
            tempDbFile.delete()

            // 删除 WAL 和 SHM 文件
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()

            true
        } catch (e: Exception) {
            false
        }
    }
}
```

### Change 4: Settings UI -- Data & Backup 分区

#### 4a. 新增 `SyncSettingsViewModel`

新文件：`feature/settings/SyncSettingsViewModel.kt`

```kotlin
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
                // 检查是否存在远程备份
                val hasBackup = syncManager.hasRemoteBackup()
                if (hasBackup) {
                    _uiState.update { it.copy(showRestorePrompt = true) }
                } else {
                    // 触发初始同步
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
        // 改为触发初始同步
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
```

#### 4b. 在 `SettingsScreen` 中添加"Data & Backup"入口

更新 `SettingsScreen` 以包含新分区和回调：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onManageProviders: () -> Unit,
    onManageAgents: () -> Unit = {},
    onDataBackup: () -> Unit = {}  // NEW: 导航到 Data & Backup
) {
    Scaffold(
        topBar = { /* ... 不变 ... */ }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SettingsItem(
                title = "Manage Agents",
                subtitle = "Create and configure AI agents",
                onClick = onManageAgents
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                title = "Manage Providers",
                subtitle = "Add API keys, configure models",
                onClick = onManageProviders
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            // NEW
            SettingsItem(
                title = "Data & Backup",
                subtitle = "Google Drive sync, export/import backup",
                onClick = onDataBackup
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
```

#### 4c. 新增 `DataBackupScreen`

新文件：`feature/settings/DataBackupScreen.kt`

此页面显示 Google Drive 同步状态、Sync Now 按钮以及导出/导入入口。

```kotlin
package com.oneclaw.shadow.feature.settings

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataBackupScreen(
    onNavigateBack: () -> Unit,
    onRestartApp: () -> Unit,
    viewModel: SyncSettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Google Sign-In launcher
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleSignInResult(result)
    }

    // 导入文件选择器
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.showImportConfirmation()
        }
    }

    // 恢复提示对话框
    if (uiState.showRestorePrompt) {
        RestoreConfirmationDialog(
            onConfirm = {
                viewModel.restore()
            },
            onDismiss = { viewModel.dismissRestorePrompt() }
        )
    }

    // 导入确认对话框
    if (uiState.showImportConfirmation) {
        RestoreConfirmationDialog(
            onConfirm = {
                viewModel.dismissImportConfirmation()
                // 确认后触发实际导入逻辑
            },
            onDismiss = { viewModel.dismissImportConfirmation() }
        )
    }

    // 恢复完成后重启应用
    if (uiState.restoreComplete) {
        onRestartApp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data & Backup") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // --- Google Drive Sync 分区 ---
            Text(
                text = "Google Drive Sync",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            if (uiState.isSignedIn) {
                // 已登录状态
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "Connected: ${uiState.accountEmail ?: ""}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (uiState.lastSyncTimestamp > 0L) {
                        Text(
                            text = "Last synced: ${
                                DateUtils.getRelativeTimeSpanString(
                                    uiState.lastSyncTimestamp,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS
                                )
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Not yet synced",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { viewModel.syncNow() },
                            enabled = !uiState.isSyncing
                        ) {
                            if (uiState.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Sync Now")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.signOut() }) {
                            Text("Sign Out")
                        }
                    }

                    if (uiState.syncError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.syncError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                // 未登录状态
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "Not connected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { signInLauncher.launch(viewModel.getSignInIntent()) }
                    ) {
                        Text("Sign in with Google")
                    }
                    if (uiState.signInError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.signInError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // --- Local Backup 分区 ---
            Text(
                text = "Local Backup",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            val zipFile = viewModel.exportBackup()
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                zipFile
                            )
                            val shareIntent = android.content.Intent(
                                android.content.Intent.ACTION_SEND
                            ).apply {
                                type = "application/zip"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                android.content.Intent.createChooser(shareIntent, "Export Backup")
                            )
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Export Backup", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Save all data to a file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        importLauncher.launch("application/zip")
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Import Backup", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Restore from a backup file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // 恢复中指示器
            if (uiState.isRestoring) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Restoring data...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoreConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore from Backup?") },
        text = {
            Text(
                "This will replace all current data with the backup. " +
                    "API keys will not be restored -- you will need to " +
                    "re-enter them in provider settings."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

#### 4d. 新增 Data & Backup Route

在 `Routes.kt` 中添加：

```kotlin
sealed class Route(val path: String) {
    // ... 已有 route ...
    data object DataBackup : Route("data-backup")  // NEW
}
```

#### 4e. 在 NavGraph 中注册

```kotlin
composable(Route.DataBackup.path) {
    DataBackupScreen(
        onNavigateBack = { navController.popBackStack() },
        onRestartApp = {
            // 重启应用以重新初始化数据库
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
    )
}
```

更新 `SettingsScreen` 的 composable 调用：

```kotlin
composable(Route.Settings.path) {
    SettingsScreen(
        onNavigateBack = { navController.popBackStack() },
        onManageProviders = { navController.navigate(Route.ProviderList.path) },
        onManageAgents = { navController.navigate(Route.AgentList.path) },
        onDataBackup = { navController.navigate(Route.DataBackup.path) }
    )
}
```

### Change 5: DI 注册

#### 5a. 在 `AppModule` 中注册 `SyncManager` 和 `BackupManager`

```kotlin
val appModule = module {
    single { ApiKeyStorage(androidContext()) }
    single { ModelApiAdapterFactory(get()) }
    // RFC-007: Sync and backup
    single { SyncManager(androidContext(), get()) }
    single { BackupManager(androidContext(), get()) }
}
```

#### 5b. 在 `FeatureModule` 中注册 `SyncSettingsViewModel`

```kotlin
val featureModule = module {
    // ... 已有注册 ...

    // RFC-007: Data & Backup
    viewModelOf(::SyncSettingsViewModel)
}
```

### Change 6: Dependencies -- build.gradle.kts

添加以下新依赖：

```kotlin
dependencies {
    // ... 已有依赖 ...

    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    // Google Drive API
    implementation("com.google.api-client:google-api-client-android:2.7.1")
    implementation("com.google.apis:google-api-services-drive:v3-rev20241206-2.0.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
```

### Change 7: AndroidManifest.xml -- FileProvider

为了使本地导出能配合系统分享面板工作，需要声明 `FileProvider`：

```xml
<application ...>
    <!-- 已有内容 ... -->

    <provider
        android:name="androidx.core.content.FileProvider"
        android:authorities="${applicationId}.fileprovider"
        android:exported="false"
        android:grantUriPermissions="true">
        <meta-data
            android:name="android.support.FILE_PROVIDER_PATHS"
            android:resource="@xml/file_paths" />
    </provider>
</application>
```

新文件：`app/src/main/res/xml/file_paths.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="backups" path="." />
</paths>
```

## 实现步骤

### 步骤 1: 添加 Gradle 依赖
- 文件：`app/build.gradle.kts`
- 添加 `play-services-auth`、`google-api-client-android`、`google-api-services-drive`、`work-runtime-ktx`

### 步骤 2: 创建 `SyncManager`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/data/sync/SyncManager.kt`（新建）
- Google Sign-In 客户端设置、登录/登出方法
- `buildDriveService()`：使用已登录账号创建 Drive API 客户端
- `hasChangedSinceLastSync()`：查询所有表中 `updatedAt`/`createdAt` 的最大值，与 SharedPreferences 中的 `lastSyncTimestamp` 比较
- `upload()`：checkpoint WAL、复制 `.db` 到临时文件、上传到 Drive `appdata` 文件夹
- `restore()`：从 Drive 下载 `.db`、关闭数据库、替换本地文件、删除 WAL/SHM
- `hasRemoteBackup()`：检查 Drive 上是否存在备份文件
- `SyncResult` sealed class 用于结果类型

### 步骤 3: 创建 `SyncWorker`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/data/sync/SyncWorker.kt`（新建）
- `CoroutineWorker`，在已登录且数据有变更时运行 `SyncManager.upload()`
- 错误时返回 `Result.retry()`，其他情况返回 `Result.success()`

### 步骤 4: 在 `OneclawApplication` 中调度周期性同步
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/OneclawApplication.kt`
- 在 `onCreate()` 中添加 `WorkManager.enqueueUniquePeriodicWork()`，设置 1 小时间隔

### 步骤 5: 创建 `BackupManager`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/data/sync/BackupManager.kt`（新建）
- `export()`：checkpoint WAL、将 `.db` 打入 ZIP、返回 ZIP 文件路径
- `import()`：从 ZIP 中解压 `.db`、关闭数据库、替换本地文件、删除 WAL/SHM

### 步骤 6: 添加 FileProvider 用于分享导出文件
- 文件：`app/src/main/AndroidManifest.xml`
  - 添加 `FileProvider` 的 `<provider>` 声明
- 文件：`app/src/main/res/xml/file_paths.xml`（新建）
  - 声明 `<cache-path>` 用于备份 ZIP 文件

### 步骤 7: 创建 `SyncSettingsViewModel`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/settings/SyncSettingsViewModel.kt`（新建）
- 管理登录流程、sync-now、restore、export、import、UI 状态
- `SyncSettingsUiState` data class，包含登录状态、同步状态、错误消息、对话框标志

### 步骤 8: 创建 `DataBackupScreen`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/settings/DataBackupScreen.kt`（新建）
- "Google Drive Sync" 分区：登录/登出、连接状态、上次同步时间、Sync Now 按钮
- "Local Backup" 分区：Export Backup（分享面板）、Import Backup（文件选择器）
- 恢复确认对话框
- 恢复进度指示器

### 步骤 9: 添加 route、导航和 Settings 入口
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/navigation/Routes.kt`
  - 添加 `data object DataBackup : Route("data-backup")`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/provider/SettingsScreen.kt`
  - 添加 `onDataBackup` 回调参数
  - 添加"Data & Backup"设置项
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/navigation/NavGraph.kt`
  - 为 `DataBackupScreen` 添加 `composable(Route.DataBackup.path)` 块
  - 向 `SettingsScreen` 传递 `onDataBackup`

### 步骤 10: 注册 DI 组件
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/di/AppModule.kt`
  - 添加 `single { SyncManager(androidContext(), get()) }`
  - 添加 `single { BackupManager(androidContext(), get()) }`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/di/FeatureModule.kt`
  - 添加 `viewModelOf(::SyncSettingsViewModel)`

## 测试策略

### Layer 1A -- 单元测试

**`SyncManagerTest`** (`app/src/test/kotlin/.../data/sync/`):
- 测试：当某个 session 的 `updatedAt` 超过 `lastSyncTimestamp` 时，`hasChangedSinceLastSync()` 返回 `true`
- 测试：当所有时间戳都在 `lastSyncTimestamp` 之前或相等时，`hasChangedSinceLastSync()` 返回 `false`
- 测试：未登录时 `getSignedInAccount()` 返回 `null`
- 测试：未进行过同步时 `getLastSyncTimestamp()` 返回 `0L`
- 测试：未登录时 `upload()` 返回 `SyncResult.NotSignedIn`

**`SyncWorkerTest`** (`app/src/test/kotlin/.../data/sync/`):
- 测试：未登录时 `doWork()` 返回 `Result.success()`（无操作）
- 测试：已登录但无变更时 `doWork()` 返回 `Result.success()`
- 测试：上传返回错误时 `doWork()` 返回 `Result.retry()`

**`BackupManagerTest`** (`app/src/test/kotlin/.../data/sync/`):
- 测试：`export()` 生成一个包含 `oneclaw.db` 的 ZIP 文件
- 测试：`import()` 使用有效 ZIP 返回 `true` 并替换数据库文件
- 测试：`import()` 使用缺少预期条目的 ZIP 返回 `false`
- 测试：`import()` 使用损坏的输入流返回 `false`

**`SyncSettingsViewModelTest`** (`app/src/test/kotlin/.../feature/settings/`):
- 测试：无账号时初始状态 `isSignedIn = false`
- 测试：`syncNow()` 先将 `isSyncing` 设为 `true`，成功后设为 `false`
- 测试：`syncNow()` 失败时设置 `syncError`
- 测试：`signOut()` 将 `isSignedIn` 重置为 `false`
- 测试：`dismissRestorePrompt()` 将 `showRestorePrompt` 设为 `false`
- 测试：`exportBackup()` 委托给 `BackupManager.export()`

### Layer 1C -- 截图测试

- `DataBackupScreen` 未登录：验证显示"Not connected"文本和"Sign in with Google"按钮
- `DataBackupScreen` 已登录：验证显示已连接邮箱、上次同步时间、"Sync Now"按钮、"Sign Out"按钮
- `DataBackupScreen` 同步中：验证 `CircularProgressIndicator` 在"Sync Now"旁可见
- `DataBackupScreen` 同步错误：验证红色错误文本
- `RestoreConfirmationDialog`：验证标题、正文文本、Cancel 和 Restore 按钮
- `SettingsScreen` 包含"Data & Backup"入口：验证新项目出现

### Layer 2 -- adb 可视化验证

**Flow 7-1: Google Drive 登录和初始同步**
1. 打开 Settings
2. 点击"Data & Backup"
3. 验证显示"Not connected"和"Sign in with Google"按钮
4. 点击"Sign in with Google"
5. 完成 Google 登录流程
6. 验证状态变为"Connected (user@gmail.com)"
7. 验证"Sync Now"和"Sign Out"按钮可见
8. 等待初始同步完成
9. 验证显示"Last synced: just now"

**Flow 7-2: 手动触发同步**
1. Google Drive 已连接状态下，在聊天中发送一些消息
2. 导航到 Settings > Data & Backup
3. 点击"Sync Now"
4. 验证同步指示器出现
5. 验证完成后"Last synced: just now"更新

**Flow 7-3: 本地导出和导入**
1. 进入 Settings > Data & Backup
2. 点击"Export Backup"
3. 验证系统分享面板打开，包含一个 ZIP 文件
4. 保存文件
5. 清除应用数据：`adb shell pm clear com.oneclaw.shadow`
6. 完成初始设置，然后进入 Settings > Data & Backup
7. 点击"Import Backup"
8. 选择之前保存的 ZIP 文件
9. 验证确认对话框出现
10. 点击"Restore"
11. 验证应用重启且数据已恢复
12. 验证 API key 为空（provider 显示未配置 key）

**Flow 7-4: 在新设备上从 Google Drive 恢复**
1. 在新/重置设备上完成初始设置
2. 进入 Settings > Data & Backup
3. 使用相同 Google 账号登录
4. 验证出现"Restore from Backup?"对话框
5. 点击"Restore"
6. 验证应用重启并恢复数据
7. 验证 API key 为空

**Flow 7-5: API key 不在导出中**
1. 为某个 provider 配置 API key
2. 进入 Settings > Data & Backup > Export Backup
3. 保存 ZIP 文件
4. 解压 ZIP 并使用 SQLite 查看器打开 `.db` 文件
5. 验证 `providers` 表中没有 API key 列
6. 验证数据库中任何地方都不存在 API key 值

## 安全考量

1. **API key 在设计上被排除。** API key 存储在 `EncryptedSharedPreferences`（`ApiKeyStorage`）中，与 Room 数据库完全分离。上传 `.db` 文件到 Drive 或导出为 ZIP 时，API key 永远不会被包含。

2. **Google Drive `appdata` scope。** 应用仅请求 `drive.appdata` scope，该权限仅授予对用户 Drive 上一个隐藏的应用专用文件夹的访问。其他应用无法读取此文件夹。用户在正常 Drive UI 中也看不到（仅能通过"管理存储空间"查看）。

3. **传输中的数据。** Google Drive API 对所有传输使用 HTTPS。数据库文件在传输过程中被加密。

4. **静态数据。** Google Drive 对其服务器上的数据进行静态加密。本地 ZIP 导出不加密 -- 这是可接受的，因为用户明确选择了保存，并且可以控制存储位置。

5. **数据库文件不包含密钥。** `ProviderEntity` schema 存储 `id`、`name`、`type`、`api_base_url`、`is_pre_configured`、`is_active`、`created_at`、`updated_at`。Room schema 中不存在 API key 字段。

## 变更历史

| 日期 | 版本 | 变更内容 | 作者 |
|------|------|----------|------|
| 2026-02-28 | 0.1 | 初始草稿 | TBD |
