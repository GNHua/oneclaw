package com.tomandy.oneclaw.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import androidx.room.withTransaction
import com.tomandy.oneclaw.data.AppDatabase
import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.pluginmanager.PluginPreferences
import com.tomandy.oneclaw.scheduler.data.CronjobDatabase
import com.tomandy.oneclaw.skill.SkillPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val appDatabase: AppDatabase,
    private val cronjobDatabase: CronjobDatabase,
    private val modelPreferences: ModelPreferences,
    private val pluginPreferences: PluginPreferences,
    private val skillPreferences: SkillPreferences
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val filesDir get() = context.filesDir.absolutePath

    suspend fun exportBackup(
        outputUri: Uri,
        includeMedia: Boolean,
        onProgress: (current: Int, total: Int) -> Unit
    ): Result<BackupManifest> = runCatching {
        val stagingDir = File(context.cacheDir, "backup_export_${System.currentTimeMillis()}")
        try {
            stagingDir.mkdirs()

            // 1. Export databases
            val dbDir = File(stagingDir, "databases").apply { mkdirs() }
            val conversations = appDatabase.conversationDao().getAllOnce()
            val messages = appDatabase.messageDao().getAllOnce()
            val cronjobs = cronjobDatabase.cronjobDao().getAllSnapshot()
            val executionLogs = cronjobDatabase.executionLogDao().getAllOnce()

            File(dbDir, "conversations.json").writeText(
                json.encodeToString(conversations.map { it.toBackup() })
            )
            File(dbDir, "messages.json").writeText(
                json.encodeToString(messages.map { it.toBackup(filesDir) })
            )
            File(dbDir, "cronjobs.json").writeText(
                json.encodeToString(cronjobs.map { it.toBackup() })
            )
            File(dbDir, "execution_logs.json").writeText(
                json.encodeToString(executionLogs.map { it.toBackup() })
            )

            // 2. Export preferences
            val prefsDir = File(stagingDir, "preferences").apply { mkdirs() }
            exportSharedPreferences("model_preferences", File(prefsDir, "model_preferences.json"))
            exportSharedPreferences("plugin_preferences", File(prefsDir, "plugin_preferences.json"))
            exportSharedPreferences("skill_preferences", File(prefsDir, "skill_preferences.json"))

            // 3. Copy file-based data (user plugins now live under workspace/plugins/)
            val filesOutDir = File(stagingDir, "files")
            val workspaceDir = File(context.filesDir, "workspace")
            val chatImagesDir = File(context.filesDir, "chat_images")
            val chatVideoDir = File(context.filesDir, "chat_video")

            if (workspaceDir.exists()) {
                workspaceDir.copyRecursively(File(filesOutDir, "workspace"))
            }

            var mediaFileCount = 0
            if (includeMedia) {
                if (chatImagesDir.exists()) {
                    chatImagesDir.copyRecursively(File(filesOutDir, "chat_images"))
                    mediaFileCount += chatImagesDir.walkTopDown().filter { it.isFile }.count()
                }
                if (chatVideoDir.exists()) {
                    chatVideoDir.copyRecursively(File(filesOutDir, "chat_video"))
                    mediaFileCount += chatVideoDir.walkTopDown().filter { it.isFile }.count()
                }
            }

            // 4. Write manifest
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val manifest = BackupManifest(
                appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo).toInt(),
                appVersionName = packageInfo.versionName ?: "",
                exportTimestamp = System.currentTimeMillis(),
                includesMedia = includeMedia,
                stats = BackupStats(
                    conversations = conversations.size,
                    messages = messages.size,
                    cronjobs = cronjobs.size,
                    executionLogs = executionLogs.size,
                    mediaFiles = mediaFileCount
                )
            )
            File(stagingDir, BackupManifest.FILENAME).writeText(json.encodeToString(manifest))

            // 5. Write ZIP
            val allFiles = stagingDir.walkTopDown().filter { it.isFile }.toList()
            val totalFiles = allFiles.size
            var currentFile = 0

            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                    allFiles.forEach { file ->
                        val entryName = file.relativeTo(stagingDir).path
                        zipOut.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { fis ->
                            fis.copyTo(zipOut, BUFFER_SIZE)
                        }
                        zipOut.closeEntry()
                        currentFile++
                        onProgress(currentFile, totalFiles)
                    }
                }
            } ?: error("Cannot open output stream for backup URI")

            manifest
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun readManifest(inputUri: Uri): Result<BackupManifest> = runCatching {
        context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
            ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (entry.name == BackupManifest.FILENAME) {
                        val content = zipIn.bufferedReader().readText()
                        return@runCatching json.decodeFromString<BackupManifest>(content)
                    }
                    entry = zipIn.nextEntry
                }
            }
        }
        error("No manifest.json found in backup file")
    }

    suspend fun importBackup(
        inputUri: Uri,
        onProgress: (current: Int, total: Int) -> Unit
    ): Result<BackupManifest> = runCatching {
        val stagingDir = File(context.cacheDir, "backup_import_${System.currentTimeMillis()}")
        try {
            stagingDir.mkdirs()

            // 1. Extract ZIP
            var entryCount = 0
            context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val outFile = File(stagingDir, entry.name)
                        // Prevent zip traversal
                        if (!outFile.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                            error("Invalid zip entry: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zipIn.copyTo(fos, BUFFER_SIZE)
                            }
                            entryCount++
                        }
                        entry = zipIn.nextEntry
                    }
                }
            } ?: error("Cannot open input stream for backup URI")

            // 2. Read and validate manifest
            val manifestFile = File(stagingDir, BackupManifest.FILENAME)
            if (!manifestFile.exists()) error("No manifest.json found in backup")
            val manifest = json.decodeFromString<BackupManifest>(manifestFile.readText())
            if (manifest.formatVersion > BackupManifest.CURRENT_FORMAT_VERSION) {
                error("Backup format version ${manifest.formatVersion} is newer than supported (${BackupManifest.CURRENT_FORMAT_VERSION}). Please update the app.")
            }

            val totalSteps = 4 // databases, preferences, files, cleanup
            var currentStep = 0

            // 3. Clear existing data
            appDatabase.clearAllTables()
            cronjobDatabase.clearAllTables()

            // 4. Import databases
            val dbDir = File(stagingDir, "databases")
            if (dbDir.exists()) {
                val conversationsFile = File(dbDir, "conversations.json")
                val messagesFile = File(dbDir, "messages.json")
                val cronjobsFile = File(dbDir, "cronjobs.json")
                val executionLogsFile = File(dbDir, "execution_logs.json")

                appDatabase.withTransaction {
                    if (conversationsFile.exists()) {
                        val conversations = json.decodeFromString<List<BackupConversation>>(
                            conversationsFile.readText()
                        )
                        appDatabase.conversationDao().insertAll(conversations.map { it.toEntity() })
                    }
                    if (messagesFile.exists()) {
                        val messages = json.decodeFromString<List<BackupMessage>>(
                            messagesFile.readText()
                        )
                        // Insert in batches to avoid exceeding SQLite variable limits
                        messages.map { it.toEntity(filesDir) }.chunked(500).forEach { batch ->
                            appDatabase.messageDao().insertAll(batch)
                        }
                    }
                }

                cronjobDatabase.withTransaction {
                    if (cronjobsFile.exists()) {
                        val cronjobs = json.decodeFromString<List<BackupCronjob>>(
                            cronjobsFile.readText()
                        )
                        cronjobDatabase.cronjobDao().insertAll(cronjobs.map { it.toEntity() })
                    }
                    if (executionLogsFile.exists()) {
                        val logs = json.decodeFromString<List<BackupExecutionLog>>(
                            executionLogsFile.readText()
                        )
                        logs.map { it.toEntity() }.chunked(500).forEach { batch ->
                            cronjobDatabase.executionLogDao().insertAll(batch)
                        }
                    }
                }
            }
            currentStep++
            onProgress(currentStep, totalSteps)

            // 5. Import preferences
            val prefsDir = File(stagingDir, "preferences")
            if (prefsDir.exists()) {
                importSharedPreferences("model_preferences", File(prefsDir, "model_preferences.json"))
                importSharedPreferences("plugin_preferences", File(prefsDir, "plugin_preferences.json"))
                importSharedPreferences("skill_preferences", File(prefsDir, "skill_preferences.json"))
            }
            currentStep++
            onProgress(currentStep, totalSteps)

            // 6. Import files
            val filesSourceDir = File(stagingDir, "files")
            if (filesSourceDir.exists()) {
                val targetDirs = listOf("workspace", "chat_images", "chat_video")
                for (dirName in targetDirs) {
                    val sourceDir = File(filesSourceDir, dirName)
                    val targetDir = File(context.filesDir, dirName)
                    if (sourceDir.exists()) {
                        targetDir.deleteRecursively()
                        sourceDir.copyRecursively(targetDir, overwrite = true)
                    }
                }
                // Legacy backups stored plugins in user_plugins/; merge into workspace/plugins/
                val legacyPlugins = File(filesSourceDir, "user_plugins")
                if (legacyPlugins.exists()) {
                    val target = File(context.filesDir, "workspace/plugins")
                    target.mkdirs()
                    legacyPlugins.copyRecursively(target, overwrite = true)
                }
            }
            currentStep++
            onProgress(currentStep, totalSteps)

            // 7. Cleanup
            currentStep++
            onProgress(currentStep, totalSteps)

            manifest
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun exportSharedPreferences(name: String, outputFile: File) {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val backup = BackupPreferences(
            strings = mutableMapOf<String, String>().also { map ->
                prefs.all.forEach { (key, value) ->
                    if (value is String) map[key] = value
                }
            },
            ints = mutableMapOf<String, Int>().also { map ->
                prefs.all.forEach { (key, value) ->
                    if (value is Int) map[key] = value
                }
            },
            floats = mutableMapOf<String, Float>().also { map ->
                prefs.all.forEach { (key, value) ->
                    if (value is Float) map[key] = value
                }
            },
            booleans = mutableMapOf<String, Boolean>().also { map ->
                prefs.all.forEach { (key, value) ->
                    if (value is Boolean) map[key] = value
                }
            },
            longs = mutableMapOf<String, Long>().also { map ->
                prefs.all.forEach { (key, value) ->
                    if (value is Long) map[key] = value
                }
            }
        )
        outputFile.writeText(json.encodeToString(backup))
    }

    private fun importSharedPreferences(name: String, inputFile: File) {
        if (!inputFile.exists()) return
        val backup = json.decodeFromString<BackupPreferences>(inputFile.readText())
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().apply {
            clear()
            backup.strings.forEach { (k, v) -> putString(k, v) }
            backup.ints.forEach { (k, v) -> putInt(k, v) }
            backup.floats.forEach { (k, v) -> putFloat(k, v) }
            backup.booleans.forEach { (k, v) -> putBoolean(k, v) }
            backup.longs.forEach { (k, v) -> putLong(k, v) }
            apply()
        }
    }

    companion object {
        private const val TAG = "BackupManager"
        private const val BUFFER_SIZE = 8192
    }
}
