# RFC-025: 文件浏览

## 文档信息
- **RFC ID**: RFC-025
- **Related PRD**: [FEAT-025 (文件浏览)](../../prd/features/FEAT-025-file-browsing.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## 概述

### 背景

OneClaw 的 AI 智能体可以通过 `write_file` 工具生成并保存文件（目前仅通过 JavaScript FsBridge 提供）。然而，用户在应用内没有任何方式可以浏览、预览或管理这些文件。用户无法看到 AI 创建了哪些文件，无法预览文件内容，也无法将文件分享给其他应用。

此外，当前的 `FsBridge.writeFile()` 仅通过一个受限路径黑名单来约束写入路径，并未限定为特定目录。没有统一的"用户文件"目录供应用管理，这使得构建一致的文件浏览体验相当困难。

### 目标

1. 在 `context.filesDir` 下建立标准的 `user_files/` 目录，作为 AI 生成文件的规范存储位置
2. 构建文件浏览页面，列出 `user_files/` 目录下的文件和子目录
3. 支持文本文件和图片文件的预览
4. 支持通过 Android 分享面板进行文件删除和分享
5. 在 AI 保存文件时，于对话消息中添加可点击的文件引用

### 非目标

- 浏览应用私有存储之外的文件
- 在文件浏览界面内编辑、创建或重命名文件
- 文件搜索或筛选
- 多文件选择或批量操作
- 修改 FsBridge 本身（其现有功能保持不变；未来的 RFC 可将其默认写入路径更新为 `user_files/`）

## 技术设计

### 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                     Navigation Layer                         │
│                                                              │
│  Routes.kt                                                   │
│  └── FileBrowser         (新路由)                           │
│  └── FilePreview(path)   (新路由)                           │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     UI Layer                                 │
│                                                              │
│  FileBrowserScreen.kt                    (新建)             │
│  FileBrowserViewModel.kt                 (新建)             │
│  FileBrowserUiState                      (新建)             │
│  FilePreviewScreen.kt                   (新建)             │
│  FilePreviewViewModel.kt                (新建)             │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     Use Case Layer                           │
│                                                              │
│  ListFilesUseCase.kt                    (新建)             │
│  DeleteFileUseCase.kt                   (新建)             │
│  ShareFileUseCase.kt                    (新建)             │
│  ReadFileContentUseCase.kt              (新建)             │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     Data Layer                               │
│                                                              │
│  FileRepository              (新建接口，位于 core/)         │
│  FileRepositoryImpl          (新建实现，位于 data/)         │
│  FileInfo                    (新建领域模型)                 │
│  UserFileStorage             (新建，管理 user_files 目录)   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 数据模型

#### FileInfo（领域模型）

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

无需 Room 数据库表——文件直接从文件系统读取。

#### FileContent（预览用密封类）

```kotlin
sealed class FileContent {
    data class Text(val content: String, val lineCount: Int) : FileContent()
    data class Image(val file: File) : FileContent()
    data class Unsupported(val mimeType: String?) : FileContent()
}
```

### 组件说明

#### UserFileStorage

管理 `user_files/` 目录的核心类：

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

关键安全考量：
- `resolveFile()` 使用 `canonicalFile` 并验证结果在 `rootDir` 下，以防止路径穿越攻击
- `delete()` 在执行前检查 `isUnderRoot()`
- 文本预览内容上限为 1MB

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

`ShareFileUseCase` 无需作为独立 Use Case 实现——分享操作直接在 ViewModel 中通过 Android 的 `Intent.ACTION_SEND` 和 `FileProvider` 处理。

#### FileBrowserScreen

文件浏览主页面：

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

关键 UI 细节：
- TopAppBar：返回按钮，标题显示"Files"
- 面包屑导航栏：显示各级路径节点，每个节点可点击以跳转到对应层级
- `LazyColumn` 列出目录内容：
  - 目录：文件夹图标、名称、子项数量、右箭头图标
  - 文件：按类型显示对应图标、名称、格式化大小、格式化日期
- 文件/目录列表项支持滑动删除，并弹出确认对话框
- 长按上下文菜单：分享、删除、复制路径
- 底部存储摘要，显示已使用的总空间
- 空状态：居中显示图标和"No files yet"文本，描述文案为"Files saved by AI will appear here"

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

根据文件类型展示对应内容：

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

关键 UI 细节：
- TopAppBar：显示文件名、分享图标及溢出菜单（删除、复制路径）
- 文件元信息卡片：大小、最后修改时间、MIME 类型
- 内容区域根据文件类型展示不同内容：
  - **文本**：`SelectionContainer` 包裹 `Text`，使用等宽字体，支持横向滚动
  - **图片**：`AsyncImage`（Coil），配合 `ZoomableState` 支持双指缩放和平移
  - **不支持**：显示提示文案"Preview not available for this file type"，并提供分享按钮

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

### 通过 FileProvider 分享文件

要从应用私有存储中分享文件，需要配置 `FileProvider`：

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

页面中的分享辅助方法：

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

### 导航

在 `Routes.kt` 中新增路由：

```kotlin
data object FileBrowser : Route("files")

data class FilePreview(val path: String) : Route("files/preview/{path}") {
    companion object {
        const val PATH = "files/preview/{path}"
        fun create(relativePath: String) = "files/preview/${Uri.encode(relativePath)}"
    }
}
```

NavGraph 新增内容：

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

### 依赖注入注册

**RepositoryModule**：
```kotlin
// RFC-025: File browsing
single { UserFileStorage(get()) }
single<FileRepository> { FileRepositoryImpl(get()) }
```

**FeatureModule**：
```kotlin
// RFC-025: File browsing
single { ListFilesUseCase(get()) }
single { DeleteFileUseCase(get()) }
single { ReadFileContentUseCase(get()) }
viewModel { FileBrowserViewModel(get(), get(), get()) }
viewModel { FilePreviewViewModel(get(), get(), get(), get()) }
```

### 聊天集成（文件引用卡片）

当 AI 智能体通过 `write_file` 保存文件时，聊天中的工具结果消息应包含一个可点击的文件引用。通过修改聊天 UI 中的工具结果渲染逻辑来实现：

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

这要求 `write_file` 工具在其返回结果中包含相对路径，聊天消息渲染器可从中提取该路径。

### 设置页入口

在设置页面或主导航抽屉中添加"Files"入口：

```kotlin
// In SettingsScreen or NavigationDrawer
SettingsItem(
    icon = Icons.Default.Folder,
    title = "Files",
    subtitle = "Browse files saved by AI",
    onClick = { onNavigateToFileBrowser() }
)
```

## 实现步骤

### 阶段一：数据层（文件存储基础设施）
1. [ ] 在 `core/model/` 中创建 `FileInfo` 领域模型
2. [ ] 在 `core/model/` 中创建 `FileContent` 密封类
3. [ ] 在 `core/repository/` 中创建 `FileRepository` 接口
4. [ ] 在 `data/repository/`（或 `data/storage/`）中创建 `UserFileStorage`
5. [ ] 在 `data/repository/` 中创建 `FileRepositoryImpl`
6. [ ] 在 DI 模块中注册 `UserFileStorage` 和 `FileRepository`

### 阶段二：Use Cases
1. [ ] 在 `feature/file/usecase/` 中创建 `ListFilesUseCase`
2. [ ] 在 `feature/file/usecase/` 中创建 `ReadFileContentUseCase`
3. [ ] 在 `feature/file/usecase/` 中创建 `DeleteFileUseCase`
4. [ ] 在 DI 中注册 Use Cases

### 阶段三：文件浏览页面
1. [ ] 创建 `FileBrowserUiState` 数据类
2. [ ] 创建 `FileBrowserViewModel`
3. [ ] 创建带面包屑、文件列表和空状态的 `FileBrowserScreen` Composable
4. [ ] 在 `Routes.kt` 中添加 `FileBrowser` 路由
5. [ ] 在 `NavGraph.kt` 中注册路由
6. [ ] 在设置/导航中添加"Files"入口
7. [ ] 在 DI 中注册 ViewModel

### 阶段四：文件预览页面
1. [ ] 创建 `FilePreviewUiState` 数据类
2. [ ] 创建 `FilePreviewViewModel`
3. [ ] 创建支持文本、图片和不支持类型处理的 `FilePreviewScreen`
4. [ ] 在 `Routes.kt` 中添加 `FilePreview` 路由
5. [ ] 在 `NavGraph.kt` 中注册路由
6. [ ] 在 DI 中注册 ViewModel

### 阶段五：文件分享
1. [ ] 在 `AndroidManifest.xml` 中声明 `FileProvider`
2. [ ] 创建 `res/xml/file_paths.xml`
3. [ ] 在 `FilePreviewScreen` 中实现分享操作
4. [ ] 在 `FileBrowserScreen` 的长按上下文菜单中实现分享功能

### 阶段六：聊天集成
1. [ ] 在聊天消息渲染中检测 `write_file` 工具结果
2. [ ] 为已保存文件渲染 `FileReferenceChip`
3. [ ] 将卡片点击事件连接到文件预览的导航跳转

## 测试策略

### 单元测试

**UserFileStorageTest：**
- `listFiles` 返回排序后的条目（目录在前，按字母顺序；文件在后，按字母顺序）
- `listFiles` 对不存在的目录返回空列表
- `readFileContent` 对 .txt、.py、.md 文件返回 `Text`
- `readFileContent` 对 .png、.jpg 文件返回 `Image`
- `readFileContent` 对未知类型返回 `Unsupported`
- `readFileContent` 对超过 1MB 的文本文件进行截断处理
- `delete` 递归删除文件和目录
- `resolveFile` 对路径穿越尝试（如 `../../etc/passwd`）抛出异常
- `getMimeType` 对已知扩展名返回正确的类型

**FileBrowserViewModelTest：**
- 初始化时加载根目录下的文件
- `navigateTo` 更新 currentPath 并重新加载文件
- `navigateUp` 跳转到父目录
- `deleteFile` 删除文件并重新加载列表
- 列表失败时设置错误状态

**FilePreviewViewModelTest：**
- 初始化时加载文件内容
- 从路径中正确设置 fileName
- `deleteFile` 将 isDeleted 标记为 true

### 集成测试

- 验证 FileProvider 正确提供文件以供分享
- 验证 `write_file` 工具创建文件后，文件浏览器可正确加载

### 手动测试

- 打开文件浏览器，验证空状态显示
- 让 AI 保存一个文件，验证其出现在文件浏览器中
- 进入子目录，验证面包屑正确更新
- 预览文本文件，验证内容正确显示
- 预览图片文件，验证缩放/平移功能正常
- 分享文件，验证 Android 分享面板弹出
- 删除文件，验证其从列表中消失
- 删除目录，验证确认对话框显示项目数量
- 点击聊天中的文件引用卡片，验证预览页面打开

## 变更历史

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
