# RFC-025: File Browsing

## Document Information
- **RFC ID**: RFC-025
- **Related PRD**: [FEAT-025 (File Browsing)](../../prd/features/FEAT-025-file-browsing.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClaw's AI agents can generate and save files via the `write_file` tool (currently only available through the JavaScript FsBridge). However, there is no way for users to browse, preview, or manage these files within the app. Users cannot see what files the AI has created, preview their contents, or share them with other apps.

Additionally, the current `FsBridge.writeFile()` writes to arbitrary paths with only a restricted-path blocklist. There is no standard "user files" directory that the app manages, making it difficult to present a coherent file browsing experience.

### Goals

1. Establish a standard `user_files/` directory under `context.filesDir` as the canonical location for AI-generated files
2. Build a file browser screen that lists files and directories under `user_files/`
3. Support file preview for text files and images
4. Support file deletion and sharing via Android share sheet
5. Add a tappable file reference in chat messages when the AI saves a file

### Non-Goals

- Browsing files outside the app's private storage
- File editing, creation, or rename from the file browser UI
- File search or filtering
- Multi-file selection or batch operations
- Modifying the FsBridge itself (it will continue to work as-is; a future RFC can update it to default to `user_files/`)

## Technical Design

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Navigation Layer                         │
│                                                              │
│  Routes.kt                                                   │
│  └── FileBrowser         (new route)                        │
│  └── FilePreview(path)   (new route)                        │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     UI Layer                                 │
│                                                              │
│  FileBrowserScreen.kt                    (new)              │
│  FileBrowserViewModel.kt                 (new)              │
│  FileBrowserUiState                      (new)              │
│  FilePreviewScreen.kt                   (new)              │
│  FilePreviewViewModel.kt                (new)              │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     Use Case Layer                           │
│                                                              │
│  ListFilesUseCase.kt                    (new)              │
│  DeleteFileUseCase.kt                   (new)              │
│  ShareFileUseCase.kt                    (new)              │
│  ReadFileContentUseCase.kt              (new)              │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     Data Layer                               │
│                                                              │
│  FileRepository              (new interface in core/)       │
│  FileRepositoryImpl          (new impl in data/)            │
│  FileInfo                    (new domain model)             │
│  UserFileStorage             (new, manages user_files dir)  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Data Model

#### FileInfo (Domain Model)

```kotlin
/**
 * Represents a file or directory entry in the user_files storage.
 */
data class FileInfo(
    val name: String,
    val absolutePath: String,
    val relativePath: String,    // relative to user_files root
    val isDirectory: Boolean,
    val size: Long,              // 0 for directories
    val lastModified: Long,      // epoch millis
    val mimeType: String?,       // null for directories
    val childCount: Int = 0      // number of items inside, for directories only
)
```

No Room database tables are needed -- files are read directly from the filesystem.

#### FileContent (sealed class for preview)

```kotlin
sealed class FileContent {
    data class Text(val content: String, val lineCount: Int) : FileContent()
    data class Image(val file: File) : FileContent()
    data class Unsupported(val mimeType: String?) : FileContent()
}
```

### Components

#### UserFileStorage

Central class managing the `user_files/` directory:

```kotlin
class UserFileStorage(private val context: Context) {

    val rootDir: File
        get() = File(context.filesDir, "user_files").also { it.mkdirs() }

    /**
     * List files and directories in the given relative path.
     * Returns sorted: directories first (alphabetical), then files (alphabetical).
     */
    fun listFiles(relativePath: String = ""): List<FileInfo> {
        val dir = resolveDir(relativePath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()
            ?.map { file -> fileToInfo(file) }
            ?.sortedWith(compareByDescending<FileInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }

    /**
     * Read file content for preview.
     * Text files: read up to 1MB as string.
     * Image files: return the File reference.
     * Others: return Unsupported.
     */
    fun readFileContent(relativePath: String): FileContent {
        val file = resolveFile(relativePath)
        val mimeType = getMimeType(file)

        return when {
            isTextFile(mimeType, file) -> {
                if (file.length() > MAX_TEXT_SIZE) {
                    FileContent.Text(
                        content = file.inputStream().bufferedReader()
                            .use { it.readText().take(MAX_TEXT_SIZE.toInt()) },
                        lineCount = -1  // truncated
                    )
                } else {
                    val content = file.readText()
                    FileContent.Text(content, content.lines().size)
                }
            }
            isImageFile(mimeType) -> FileContent.Image(file)
            else -> FileContent.Unsupported(mimeType)
        }
    }

    /** Delete a file or directory recursively. */
    fun delete(relativePath: String): Boolean {
        val file = resolveFile(relativePath)
        if (!isUnderRoot(file)) return false
        return file.deleteRecursively()
    }

    /** Get total storage size of user_files. */
    fun getTotalSize(): Long {
        return rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Resolve a relative path to a File under user_files, with path traversal protection. */
    fun resolveFile(relativePath: String): File {
        val file = File(rootDir, relativePath).canonicalFile
        require(isUnderRoot(file)) { "Path traversal detected: $relativePath" }
        return file
    }

    private fun resolveDir(relativePath: String): File = resolveFile(relativePath)

    private fun isUnderRoot(file: File): Boolean =
        file.canonicalPath.startsWith(rootDir.canonicalPath)

    private fun fileToInfo(file: File): FileInfo {
        val relativePath = file.canonicalPath.removePrefix(rootDir.canonicalPath).trimStart('/')
        return FileInfo(
            name = file.name,
            absolutePath = file.canonicalPath,
            relativePath = relativePath,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0,
            lastModified = file.lastModified(),
            mimeType = if (file.isFile) getMimeType(file) else null,
            childCount = if (file.isDirectory) (file.listFiles()?.size ?: 0) else 0
        )
    }

    companion object {
        const val MAX_TEXT_SIZE = 1024 * 1024L  // 1MB

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "py", "kt", "java", "xml", "csv",
            "log", "yaml", "yml", "toml", "html", "css", "js", "ts",
            "sh", "bat", "ini", "cfg", "conf", "properties", "gradle",
            "sql", "r", "rb", "go", "rs", "c", "cpp", "h", "swift"
        )

        private val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp"
        )

        fun isTextFile(mimeType: String?, file: File): Boolean {
            if (mimeType?.startsWith("text/") == true) return true
            return file.extension.lowercase() in TEXT_EXTENSIONS
        }

        fun isImageFile(mimeType: String?): Boolean {
            return mimeType?.startsWith("image/") == true
        }

        fun getMimeType(file: File): String? {
            val ext = file.extension.lowercase()
            return when (ext) {
                "txt" -> "text/plain"
                "md" -> "text/markdown"
                "json" -> "application/json"
                "py" -> "text/x-python"
                "kt" -> "text/x-kotlin"
                "java" -> "text/x-java"
                "xml" -> "text/xml"
                "html" -> "text/html"
                "css" -> "text/css"
                "js" -> "text/javascript"
                "csv" -> "text/csv"
                "yaml", "yml" -> "text/yaml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                else -> null
            }
        }
    }
}
```

Key security considerations:
- `resolveFile()` uses `canonicalFile` and validates the result is under `rootDir` to prevent path traversal attacks
- `delete()` checks `isUnderRoot()` before proceeding
- Text preview is capped at 1MB

#### FileRepository

```kotlin
// core/repository/FileRepository.kt
interface FileRepository {
    fun listFiles(relativePath: String = ""): List<FileInfo>
    fun readFileContent(relativePath: String): FileContent
    fun deleteFile(relativePath: String): Boolean
    fun getFileForSharing(relativePath: String): File
    fun getTotalSize(): Long
    fun getRootPath(): String
}
```

```kotlin
// data/repository/FileRepositoryImpl.kt
class FileRepositoryImpl(
    private val userFileStorage: UserFileStorage
) : FileRepository {

    override fun listFiles(relativePath: String): List<FileInfo> =
        userFileStorage.listFiles(relativePath)

    override fun readFileContent(relativePath: String): FileContent =
        userFileStorage.readFileContent(relativePath)

    override fun deleteFile(relativePath: String): Boolean =
        userFileStorage.delete(relativePath)

    override fun getFileForSharing(relativePath: String): File =
        userFileStorage.resolveFile(relativePath)

    override fun getTotalSize(): Long =
        userFileStorage.getTotalSize()

    override fun getRootPath(): String =
        userFileStorage.rootDir.absolutePath
}
```

#### Use Cases

```kotlin
class ListFilesUseCase(private val fileRepository: FileRepository) {
    operator fun invoke(relativePath: String = ""): AppResult<List<FileInfo>> {
        return try {
            AppResult.Success(fileRepository.listFiles(relativePath))
        } catch (e: Exception) {
            AppResult.Error(ErrorCode.FILE_ERROR, e.message ?: "Failed to list files")
        }
    }
}

class ReadFileContentUseCase(private val fileRepository: FileRepository) {
    operator fun invoke(relativePath: String): AppResult<FileContent> {
        return try {
            AppResult.Success(fileRepository.readFileContent(relativePath))
        } catch (e: Exception) {
            AppResult.Error(ErrorCode.FILE_ERROR, e.message ?: "Failed to read file")
        }
    }
}

class DeleteFileUseCase(private val fileRepository: FileRepository) {
    operator fun invoke(relativePath: String): AppResult<Unit> {
        return try {
            val success = fileRepository.deleteFile(relativePath)
            if (success) AppResult.Success(Unit)
            else AppResult.Error(ErrorCode.FILE_ERROR, "Failed to delete")
        } catch (e: Exception) {
            AppResult.Error(ErrorCode.FILE_ERROR, e.message ?: "Failed to delete")
        }
    }
}
```

`ShareFileUseCase` is not needed as a separate use case -- sharing is handled directly in the ViewModel using Android's `Intent.ACTION_SEND` and `FileProvider`.

#### FileBrowserScreen

The main file browsing screen:

```
┌─────────────────────────────────────┐
│ TopAppBar                           │
│ ← Files                            │
├─────────────────────────────────────┤
│                                     │
│ Breadcrumb: user_files > scripts    │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ [folder] python/          3 items │ │
│ │ [folder] shell/           1 item  │ │
│ │ [doc]  notes.md       2.4 KB  │ │
│ │        Mar 1, 2026             │ │
│ │ [code] sort.py        1.1 KB  │ │
│ │        Feb 28, 2026            │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ── Storage: 156 KB used ────────── │
│                                     │
└─────────────────────────────────────┘
```

```kotlin
@Composable
fun FileBrowserScreen(
    onNavigateBack: () -> Unit,
    onPreviewFile: (String) -> Unit,   // relativePath
    viewModel: FileBrowserViewModel = koinViewModel()
)
```

Key UI details:
- TopAppBar with back button and title "Files"
- Breadcrumb bar showing path segments, each tappable to navigate to that level
- `LazyColumn` listing directory entries:
  - Directories: folder icon, name, child count, chevron
  - Files: type-specific icon, name, formatted size, formatted date
- Swipe-to-delete on file/directory items with confirmation
- Long-press context menu: Share, Delete, Copy Path
- Storage summary at the bottom showing total size used
- Empty state: centered icon + "No files yet" text + description "Files saved by AI will appear here"

#### FileBrowserViewModel

```kotlin
class FileBrowserViewModel(
    private val listFilesUseCase: ListFilesUseCase,
    private val deleteFileUseCase: DeleteFileUseCase,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    init {
        loadFiles()
    }

    fun navigateTo(relativePath: String) {
        _uiState.update { it.copy(currentPath = relativePath) }
        loadFiles()
    }

    fun navigateUp() {
        val parent = File(_uiState.value.currentPath).parent ?: ""
        navigateTo(parent)
    }

    fun deleteFile(fileInfo: FileInfo) {
        viewModelScope.launch {
            when (deleteFileUseCase(fileInfo.relativePath)) {
                is AppResult.Success -> loadFiles()
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = "Failed to delete") }
                }
            }
        }
    }

    fun getFileForSharing(relativePath: String): File =
        fileRepository.getFileForSharing(relativePath)

    private fun loadFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = listFilesUseCase(_uiState.value.currentPath)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            files = result.data,
                            isLoading = false,
                            totalSize = fileRepository.getTotalSize(),
                            errorMessage = null
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = result.message)
                    }
                }
            }
        }
    }
}
```

#### FileBrowserUiState

```kotlin
data class FileBrowserUiState(
    val currentPath: String = "",
    val pathSegments: List<String> = emptyList(),  // derived from currentPath
    val files: List<FileInfo> = emptyList(),
    val isLoading: Boolean = true,
    val totalSize: Long = 0,
    val errorMessage: String? = null
) {
    val pathSegments: List<PathSegment>
        get() {
            if (currentPath.isEmpty()) return listOf(PathSegment("Files", ""))
            val parts = currentPath.split("/")
            val segments = mutableListOf(PathSegment("Files", ""))
            var accumulated = ""
            for (part in parts) {
                accumulated = if (accumulated.isEmpty()) part else "$accumulated/$part"
                segments.add(PathSegment(part, accumulated))
            }
            return segments
        }
}

data class PathSegment(val name: String, val relativePath: String)
```

#### FilePreviewScreen

Displays file content based on type:

```
┌─────────────────────────────────────┐
│ TopAppBar                           │
│ ← sort.py          [Share] [More]  │
├─────────────────────────────────────┤
│                                     │
│ ┌─ File Info ─────────────────────┐ │
│ │ Size: 1.1 KB                    │ │
│ │ Modified: Mar 1, 2026 14:30     │ │
│ │ Type: text/x-python             │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─ Content ───────────────────────┐ │
│ │ def sort_list(items):           │ │
│ │     return sorted(items)        │ │
│ │                                 │ │
│ │ if __name__ == "__main__":      │ │
│ │     data = [3, 1, 4, 1, 5]     │ │
│ │     print(sort_list(data))      │ │
│ └─────────────────────────────────┘ │
│                                     │
└─────────────────────────────────────┘
```

```kotlin
@Composable
fun FilePreviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: FilePreviewViewModel = koinViewModel()
)
```

Key UI details:
- TopAppBar with file name, share icon, and overflow menu (Delete, Copy Path)
- File metadata card: size, last modified, MIME type
- Content area depends on file type:
  - **Text**: `SelectionContainer` with `Text` in monospace font, horizontally scrollable
  - **Image**: `AsyncImage` (Coil) with `ZoomableState` for pinch-to-zoom and pan
  - **Unsupported**: Message "Preview not available for this file type" with Share button

#### FilePreviewViewModel

```kotlin
class FilePreviewViewModel(
    savedStateHandle: SavedStateHandle,
    private val readFileContentUseCase: ReadFileContentUseCase,
    private val deleteFileUseCase: DeleteFileUseCase,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val encodedPath: String = savedStateHandle["path"] ?: ""
    private val relativePath = Uri.decode(encodedPath)

    private val _uiState = MutableStateFlow(FilePreviewUiState())
    val uiState: StateFlow<FilePreviewUiState> = _uiState.asStateFlow()

    init {
        loadFileContent()
    }

    fun deleteFile() { /* ... */ }
    fun getShareFile(): File = fileRepository.getFileForSharing(relativePath)
    fun copyPath() { /* returns absolute path string */ }

    private fun loadFileContent() {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = readFileContentUseCase(relativePath)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            fileName = File(relativePath).name,
                            fileContent = result.data,
                            fileInfo = fileRepository.listFiles(
                                File(relativePath).parent ?: ""
                            ).find { f -> f.relativePath == relativePath },
                            isLoading = false
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = result.message)
                    }
                }
            }
        }
    }
}

data class FilePreviewUiState(
    val fileName: String = "",
    val fileInfo: FileInfo? = null,
    val fileContent: FileContent? = null,
    val isLoading: Boolean = true,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null
)
```

### File Sharing with FileProvider

To share files from the app's private storage, a `FileProvider` is needed:

```xml
<!-- AndroidManifest.xml -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

```xml
<!-- res/xml/file_paths.xml -->
<paths>
    <files-path name="user_files" path="user_files/" />
</paths>
```

Sharing helper in the screen:

```kotlin
fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = UserFileStorage.getMimeType(file) ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
```

### Navigation

New routes added to `Routes.kt`:

```kotlin
data object FileBrowser : Route("files")

data class FilePreview(val path: String) : Route("files/preview/{path}") {
    companion object {
        const val PATH = "files/preview/{path}"
        fun create(relativePath: String) = "files/preview/${Uri.encode(relativePath)}"
    }
}
```

NavGraph additions:

```kotlin
composable(Route.FileBrowser.path) {
    FileBrowserScreen(
        onNavigateBack = { navController.safePopBackStack() },
        onPreviewFile = { relativePath ->
            navController.safeNavigate(Route.FilePreview.create(relativePath))
        }
    )
}

composable(
    route = Route.FilePreview.PATH,
    arguments = listOf(navArgument("path") { type = NavType.StringType })
) {
    FilePreviewScreen(
        onNavigateBack = { navController.safePopBackStack() }
    )
}
```

### DI Registration

**RepositoryModule**:
```kotlin
// RFC-025: File browsing
single { UserFileStorage(get()) }
single<FileRepository> { FileRepositoryImpl(get()) }
```

**FeatureModule**:
```kotlin
// RFC-025: File browsing
single { ListFilesUseCase(get()) }
single { DeleteFileUseCase(get()) }
single { ReadFileContentUseCase(get()) }
viewModel { FileBrowserViewModel(get(), get(), get()) }
viewModel { FilePreviewViewModel(get(), get(), get(), get()) }
```

### Chat Integration (File Reference Chip)

When the AI agent saves a file via `write_file`, the tool result message in the chat should include a tappable reference. This is handled by modifying the tool result rendering in the chat UI:

```kotlin
// In the chat message rendering, detect write_file tool results
// and render a clickable file chip:
@Composable
fun FileReferenceChip(
    fileName: String,
    relativePath: String,
    onClick: () -> Unit
) {
    AssistCard(
        onClick = onClick,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.InsertDriveFile, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(fileName, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
```

This requires the `write_file` tool to return the relative path in its result, which can be extracted by the chat message renderer.

### Settings Entry Point

Add a "Files" item to the Settings screen or the main navigation drawer:

```kotlin
// In SettingsScreen or NavigationDrawer
SettingsItem(
    icon = Icons.Default.Folder,
    title = "Files",
    subtitle = "Browse files saved by AI",
    onClick = { onNavigateToFileBrowser() }
)
```

## Implementation Steps

### Phase 1: Data Layer (file storage infrastructure)
1. [ ] Create `FileInfo` domain model in `core/model/`
2. [ ] Create `FileContent` sealed class in `core/model/`
3. [ ] Create `FileRepository` interface in `core/repository/`
4. [ ] Create `UserFileStorage` in `data/repository/` (or `data/storage/`)
5. [ ] Create `FileRepositoryImpl` in `data/repository/`
6. [ ] Register `UserFileStorage` and `FileRepository` in DI modules

### Phase 2: Use Cases
1. [ ] Create `ListFilesUseCase` in `feature/file/usecase/`
2. [ ] Create `ReadFileContentUseCase` in `feature/file/usecase/`
3. [ ] Create `DeleteFileUseCase` in `feature/file/usecase/`
4. [ ] Register use cases in DI

### Phase 3: File Browser Screen
1. [ ] Create `FileBrowserUiState` data class
2. [ ] Create `FileBrowserViewModel`
3. [ ] Create `FileBrowserScreen` composable with breadcrumb, file list, empty state
4. [ ] Add `FileBrowser` route to `Routes.kt`
5. [ ] Register in `NavGraph.kt`
6. [ ] Add "Files" entry point in settings/navigation
7. [ ] Register ViewModel in DI

### Phase 4: File Preview Screen
1. [ ] Create `FilePreviewUiState` data class
2. [ ] Create `FilePreviewViewModel`
3. [ ] Create `FilePreviewScreen` with text, image, and unsupported content handlers
4. [ ] Add `FilePreview` route to `Routes.kt`
5. [ ] Register in `NavGraph.kt`
6. [ ] Register ViewModel in DI

### Phase 5: File Sharing
1. [ ] Add `FileProvider` declaration in `AndroidManifest.xml`
2. [ ] Create `res/xml/file_paths.xml`
3. [ ] Implement share action in `FilePreviewScreen`
4. [ ] Implement share from long-press context menu in `FileBrowserScreen`

### Phase 6: Chat Integration
1. [ ] Detect `write_file` tool results in chat message rendering
2. [ ] Render `FileReferenceChip` for saved files
3. [ ] Wire chip click to navigate to file preview

## Testing Strategy

### Unit Tests

**UserFileStorageTest:**
- `listFiles` returns sorted entries (dirs first, then files alphabetically)
- `listFiles` returns empty list for non-existent directory
- `readFileContent` returns `Text` for .txt, .py, .md files
- `readFileContent` returns `Image` for .png, .jpg files
- `readFileContent` returns `Unsupported` for unknown types
- `readFileContent` truncates text files larger than 1MB
- `delete` removes files and directories recursively
- `resolveFile` throws on path traversal attempts (e.g., `../../etc/passwd`)
- `getMimeType` returns correct types for known extensions

**FileBrowserViewModelTest:**
- Init loads files for root directory
- `navigateTo` updates currentPath and reloads files
- `navigateUp` goes to parent directory
- `deleteFile` removes file and reloads list
- Error state is set when listing fails

**FilePreviewViewModelTest:**
- Loads file content on init
- Sets correct fileName from path
- `deleteFile` marks isDeleted

### Integration Tests

- Verify FileProvider serves files correctly for sharing
- Verify file browser loads after write_file tool creates files

### Manual Tests

- Open file browser, verify empty state
- Have AI save a file, verify it appears in file browser
- Navigate into directories, verify breadcrumb updates
- Preview text file, verify content displayed correctly
- Preview image file, verify zoom/pan works
- Share a file, verify Android share sheet opens
- Delete a file, verify it disappears from list
- Delete a directory, verify confirmation shows item count
- Tap file reference chip in chat, verify preview opens

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
