# RFC-026: 文件附件

## 文档信息
- **RFC ID**: RFC-026
- **关联 PRD**: [FEAT-026 (文件附件)](../../prd/features/FEAT-026-file-attachments.md)
- **关联架构**: [RFC-000 (整体架构)](../architecture/RFC-000-overall-architecture.md)
- **关联 RFC**: [RFC-001 (聊天交互)](RFC-001-chat-interaction.md), [RFC-016 (聊天输入重设计)](RFC-016-chat-input-redesign.md)
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

OneClaw 目前仅支持纯文本聊天消息。三个受支持的 AI 服务商（OpenAI、Anthropic、Gemini）都支持多模态输入——至少支持图片，其中 Gemini 还支持视频。用户需要能够在发送文本消息的同时附带图片、视频和文件，以充分利用这些服务商的能力。

当前的 `ApiMessage.User` 类只保存 `content: String`，`Message` 领域模型也没有附件字段。本 RFC 设计了所需的完整技术栈变更：数据模型、文件存储、UI 组件、API 适配器扩展以及媒体查看。

### 目标

1. 在 `ChatInput` 中添加附件按钮，通过底部弹窗提供选择器（照片、视频、相机、文件）
2. 设计 `Attachment` 数据模型以及带有消息外键的 Room 数据表
3. 在 `files/attachments/{sessionId}/` 路径实现文件存储，并生成缩略图
4. 在发送前于聊天输入框中显示附件预览
5. 在消息气泡中内联显示附件（图片、视频缩略图、文件卡片）
6. 实现支持缩放/平移的全屏图片查看器
7. 通过系统 Intent 打开视频和文件
8. 扩展 `ApiMessage.User` 以支持多模态内容块
9. 扩展全部三个 API 适配器，以服务商特定的格式发送附件
10. 针对不支持的附件类型进行用户提示

### 非目标

- 使用相机录制视频
- 图片编辑、裁剪或标注
- 拖拽或剪贴板粘贴附件
- 录音或语音消息
- 内联 PDF 查看器或文档预览
- 云存储集成
- AI 生成图片渲染（文本转图片模型）
- 附件压缩设置界面

## 技术设计

### 架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                         UI Layer                                  │
│                                                                  │
│  ChatScreen                                                      │
│  ├── ChatMessageList                                             │
│  │   └── MessageBubble                                           │
│  │       └── AttachmentDisplay [NEW]                             │
│  │           ├── ImageAttachment (clickable -> ImageViewer)       │
│  │           ├── VideoAttachment (clickable -> system player)    │
│  │           └── FileAttachment  (clickable -> system app)       │
│  │                                                               │
│  ├── ChatInput [MODIFIED]                                        │
│  │   ├── AttachmentPreviewRow [NEW]                              │
│  │   ├── BasicTextField (unchanged)                              │
│  │   └── ActionRow                                               │
│  │       ├── SkillButton (unchanged)                             │
│  │       ├── AttachButton [NEW] ("+")                            │
│  │       ├── Spacer                                              │
│  │       ├── StopButton (unchanged)                              │
│  │       └── SendButton (unchanged)                              │
│  │                                                               │
│  ├── AttachmentPickerSheet [NEW] (ModalBottomSheet)              │
│  └── ImageViewerDialog [NEW] (full-screen overlay)               │
│                                                                  │
│  ChatViewModel [MODIFIED]                                        │
│  ├── pendingAttachments: StateFlow<List<PendingAttachment>>      │
│  ├── addAttachment(uri) / removeAttachment(id)                   │
│  └── sendMessage() -- includes attachments                       │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│                       Domain Layer                                │
│                                                                  │
│  Attachment [NEW] (core/model/)                                  │
│  AttachmentType [NEW] (core/model/)                              │
│  AttachmentRepository [NEW] (core/repository/)                   │
│  SaveAttachmentUseCase [NEW] (feature/chat/usecase/)             │
│  ProviderCapability [NEW] (core/model/)                          │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│                        Data Layer                                 │
│                                                                  │
│  AttachmentEntity [NEW] (data/local/entity/)                     │
│  AttachmentDao [NEW] (data/local/dao/)                           │
│  AttachmentMapper [NEW] (data/local/mapper/)                     │
│  AttachmentRepositoryImpl [NEW] (data/repository/)               │
│  AttachmentFileManager [NEW] (data/local/)                       │
│  AppDatabase [MODIFIED] -- add AttachmentEntity + migration      │
│                                                                  │
│  ApiMessage.User [MODIFIED] -- multimodal content parts          │
│  OpenAiAdapter [MODIFIED] -- image content parts                 │
│  AnthropicAdapter [MODIFIED] -- image/PDF content parts          │
│  GeminiAdapter [MODIFIED] -- image/video content parts           │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### 核心组件

**新增：**
1. `Attachment` -- 附件元数据的领域模型
2. `AttachmentEntity` + `AttachmentDao` -- Room 持久化
3. `AttachmentRepository` / `AttachmentRepositoryImpl` -- 数据访问
4. `AttachmentFileManager` -- 文件复制、缩略图生成、清理
5. `AttachmentDisplay` -- 在消息气泡中渲染附件的 Composable
6. `AttachmentPreviewRow` -- 发送前附件预览的 Composable
7. `AttachmentPickerSheet` -- 带选择选项的底部弹窗
8. `ImageViewerDialog` -- 支持缩放/平移的全屏图片查看器
9. `SaveAttachmentUseCase` -- 协调文件复制与数据库保存

**修改：**
10. `ChatInput` -- 添加附件按钮、预览行
11. `ChatViewModel` -- 管理待发附件，在发送时包含附件
12. `ChatScreen` -- 接入附件 UI、媒体查看器
13. `ChatUiState` / `ChatMessageItem` -- 添加附件数据
14. `ApiMessage.User` -- 支持多模态内容
15. `OpenAiAdapter` / `AnthropicAdapter` / `GeminiAdapter` -- 多模态格式化
16. `SendMessageUseCase` -- 在 API 消息中包含附件
17. `AppDatabase` -- 迁移以添加 `attachments` 数据表
18. `MessageMapper` -- 与消息一起映射附件
19. `DatabaseModule` -- 提供 `AttachmentDao`
20. `RepositoryModule` -- 提供 `AttachmentRepository`

## 详细设计

### 目录结构（新增与修改的文件）

```
app/src/main/kotlin/com/oneclaw/shadow/
├── core/
│   ├── model/
│   │   ├── Attachment.kt                    # NEW
│   │   └── Message.kt                       # unchanged
│   └── repository/
│       ├── AttachmentRepository.kt          # NEW
│       └── MessageRepository.kt             # unchanged
├── data/
│   ├── local/
│   │   ├── dao/
│   │   │   ├── AttachmentDao.kt             # NEW
│   │   │   └── MessageDao.kt               # unchanged
│   │   ├── entity/
│   │   │   ├── AttachmentEntity.kt          # NEW
│   │   │   └── MessageEntity.kt             # unchanged
│   │   ├── mapper/
│   │   │   ├── AttachmentMapper.kt          # NEW
│   │   │   └── MessageMapper.kt             # unchanged
│   │   ├── db/
│   │   │   └── AppDatabase.kt              # MODIFIED (add entity + migration)
│   │   └── AttachmentFileManager.kt         # NEW
│   ├── remote/
│   │   └── adapter/
│   │       ├── ApiMessage.kt                # MODIFIED
│   │       ├── OpenAiAdapter.kt             # MODIFIED
│   │       ├── AnthropicAdapter.kt          # MODIFIED
│   │       └── GeminiAdapter.kt             # MODIFIED
│   └── repository/
│       └── AttachmentRepositoryImpl.kt      # NEW
├── feature/
│   └── chat/
│       ├── ChatScreen.kt                    # MODIFIED
│       ├── ChatViewModel.kt                 # MODIFIED
│       ├── ChatUiState.kt                   # MODIFIED
│       ├── components/
│       │   ├── AttachmentDisplay.kt         # NEW
│       │   ├── AttachmentPreviewRow.kt      # NEW
│       │   ├── AttachmentPickerSheet.kt     # NEW
│       │   └── ImageViewerDialog.kt         # NEW
│       └── usecase/
│           ├── SaveAttachmentUseCase.kt     # NEW
│           └── SendMessageUseCase.kt        # MODIFIED
└── di/
    ├── DatabaseModule.kt                    # MODIFIED
    ├── RepositoryModule.kt                  # MODIFIED
    └── FeatureModule.kt                     # MODIFIED

app/src/test/kotlin/com/oneclaw/shadow/
├── data/
│   ├── local/
│   │   └── AttachmentFileManagerTest.kt     # NEW
│   └── repository/
│       └── AttachmentRepositoryImplTest.kt  # NEW
└── feature/
    └── chat/
        └── usecase/
            └── SaveAttachmentUseCaseTest.kt # NEW
```

### 数据模型

#### Attachment 领域模型

```kotlin
/**
 * Located in: core/model/Attachment.kt
 */
data class Attachment(
    val id: String,
    val messageId: String,
    val type: AttachmentType,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val filePath: String,
    val thumbnailPath: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val createdAt: Long
)

enum class AttachmentType {
    IMAGE,
    VIDEO,
    FILE
}
```

#### Attachment Room 实体

```kotlin
/**
 * Located in: data/local/entity/AttachmentEntity.kt
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["message_id"])]
)
data class AttachmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "type")
    val type: String,  // "IMAGE", "VIDEO", "FILE"

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String?,

    @ColumnInfo(name = "width")
    val width: Int?,

    @ColumnInfo(name = "height")
    val height: Int?,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
```

#### AttachmentDao

```kotlin
/**
 * Located in: data/local/dao/AttachmentDao.kt
 */
@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE message_id = :messageId ORDER BY created_at ASC")
    fun getAttachmentsForMessage(messageId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE message_id IN (:messageIds) ORDER BY created_at ASC")
    suspend fun getAttachmentsForMessages(messageIds: List<String>): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Delete
    suspend fun deleteAttachment(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE message_id = :messageId")
    suspend fun deleteAttachmentsForMessage(messageId: String)
}
```

#### AttachmentMapper

```kotlin
/**
 * Located in: data/local/mapper/AttachmentMapper.kt
 */
fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = id,
    messageId = messageId,
    type = AttachmentType.valueOf(type),
    fileName = fileName,
    mimeType = mimeType,
    fileSize = fileSize,
    filePath = filePath,
    thumbnailPath = thumbnailPath,
    width = width,
    height = height,
    durationMs = durationMs,
    createdAt = createdAt
)

fun Attachment.toEntity(): AttachmentEntity = AttachmentEntity(
    id = id,
    messageId = messageId,
    type = type.name,
    fileName = fileName,
    mimeType = mimeType,
    fileSize = fileSize,
    filePath = filePath,
    thumbnailPath = thumbnailPath,
    width = width,
    height = height,
    durationMs = durationMs,
    createdAt = createdAt
)
```

### 数据库迁移

```kotlin
/**
 * In AppDatabase.kt -- add migration
 */
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS attachments (
                id TEXT NOT NULL PRIMARY KEY,
                message_id TEXT NOT NULL,
                type TEXT NOT NULL,
                file_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                file_path TEXT NOT NULL,
                thumbnail_path TEXT,
                width INTEGER,
                height INTEGER,
                duration_ms INTEGER,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_message_id ON attachments(message_id)")
    }
}
```

### 仓库层

#### AttachmentRepository 接口

```kotlin
/**
 * Located in: core/repository/AttachmentRepository.kt
 */
interface AttachmentRepository {
    fun getAttachmentsForMessage(messageId: String): Flow<List<Attachment>>
    suspend fun getAttachmentsForMessages(messageIds: List<String>): List<Attachment>
    suspend fun addAttachment(attachment: Attachment)
    suspend fun addAttachments(attachments: List<Attachment>)
    suspend fun deleteAttachment(id: String)
    suspend fun deleteAttachmentsForMessage(messageId: String)
}
```

#### AttachmentRepositoryImpl

```kotlin
/**
 * Located in: data/repository/AttachmentRepositoryImpl.kt
 */
class AttachmentRepositoryImpl(
    private val attachmentDao: AttachmentDao
) : AttachmentRepository {

    override fun getAttachmentsForMessage(messageId: String): Flow<List<Attachment>> {
        return attachmentDao.getAttachmentsForMessage(messageId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getAttachmentsForMessages(messageIds: List<String>): List<Attachment> {
        return attachmentDao.getAttachmentsForMessages(messageIds)
            .map { it.toDomain() }
    }

    override suspend fun addAttachment(attachment: Attachment) {
        attachmentDao.insertAttachment(attachment.toEntity())
    }

    override suspend fun addAttachments(attachments: List<Attachment>) {
        attachmentDao.insertAttachments(attachments.map { it.toEntity() })
    }

    override suspend fun deleteAttachment(id: String) {
        // Note: File cleanup handled by AttachmentFileManager
        attachmentDao.deleteAttachment(
            AttachmentEntity(id = id, messageId = "", type = "", fileName = "",
                mimeType = "", fileSize = 0, filePath = "", thumbnailPath = null,
                width = null, height = null, durationMs = null, createdAt = 0)
        )
    }

    override suspend fun deleteAttachmentsForMessage(messageId: String) {
        attachmentDao.deleteAttachmentsForMessage(messageId)
    }
}
```

### 文件管理

#### AttachmentFileManager

```kotlin
/**
 * Located in: data/local/AttachmentFileManager.kt
 *
 * Handles file operations for attachments: copy from URI,
 * generate thumbnails, delete files, and clean up session directories.
 */
class AttachmentFileManager(
    private val context: Context
) {
    companion object {
        private const val ATTACHMENTS_DIR = "attachments"
        private const val THUMBS_DIR = "thumbs"
        private const val THUMB_SIZE = 256
        private const val MAX_FILE_SIZE = 20L * 1024 * 1024  // 20MB
    }

    /**
     * Copy a file from a content URI to internal storage.
     * Returns the Attachment metadata (without messageId, which is set later).
     */
    suspend fun copyFromUri(
        uri: Uri,
        sessionId: String
    ): AppResult<PendingAttachment> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            // Get file metadata
            val fileName = getFileName(contentResolver, uri)
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val fileSize = getFileSize(contentResolver, uri)

            // Validate file size
            if (fileSize > MAX_FILE_SIZE) {
                return@withContext AppResult.Error(
                    "File is too large (${fileSize / 1024 / 1024}MB). Maximum is 20MB."
                )
            }

            // Determine attachment type
            val type = when {
                mimeType.startsWith("image/") -> AttachmentType.IMAGE
                mimeType.startsWith("video/") -> AttachmentType.VIDEO
                else -> AttachmentType.FILE
            }

            // Create directories
            val sessionDir = getSessionDir(sessionId)
            sessionDir.mkdirs()

            // Generate UUID filename
            val id = UUID.randomUUID().toString()
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType) ?: fileName.substringAfterLast('.', "bin")
            val destFile = File(sessionDir, "$id.$extension")

            // Copy file
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext AppResult.Error("Cannot open file")

            // Generate thumbnail
            val thumbnailPath = when (type) {
                AttachmentType.IMAGE -> generateImageThumbnail(destFile, sessionId, id)
                AttachmentType.VIDEO -> generateVideoThumbnail(uri, sessionId, id)
                AttachmentType.FILE -> null
            }

            // Get image/video dimensions
            val dimensions = when (type) {
                AttachmentType.IMAGE -> getImageDimensions(destFile)
                AttachmentType.VIDEO -> getVideoDimensions(uri)
                AttachmentType.FILE -> null
            }

            // Get video duration
            val durationMs = if (type == AttachmentType.VIDEO) {
                getVideoDuration(uri)
            } else null

            AppResult.Success(
                PendingAttachment(
                    id = id,
                    type = type,
                    fileName = fileName,
                    mimeType = mimeType,
                    fileSize = fileSize,
                    filePath = destFile.absolutePath,
                    thumbnailPath = thumbnailPath,
                    width = dimensions?.first,
                    height = dimensions?.second,
                    durationMs = durationMs
                )
            )
        } catch (e: Exception) {
            AppResult.Error("Failed to save attachment: ${e.message}")
        }
    }

    /**
     * Copy a camera-captured photo to internal storage.
     */
    suspend fun copyFromCameraFile(
        file: File,
        sessionId: String
    ): AppResult<PendingAttachment> = withContext(Dispatchers.IO) {
        // Similar to copyFromUri but reads from a File directly
        // ...implementation follows same pattern...
    }

    /**
     * Delete all attachment files for a session.
     */
    suspend fun deleteSessionAttachments(sessionId: String) = withContext(Dispatchers.IO) {
        val sessionDir = getSessionDir(sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
    }

    /**
     * Read a file as base64-encoded string for API transmission.
     */
    suspend fun readAsBase64(filePath: String): String = withContext(Dispatchers.IO) {
        val file = File(filePath)
        val bytes = file.readBytes()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // --- Private helpers ---

    private fun getSessionDir(sessionId: String): File {
        return File(context.filesDir, "$ATTACHMENTS_DIR/$sessionId")
    }

    private fun getThumbsDir(sessionId: String): File {
        return File(getSessionDir(sessionId), THUMBS_DIR).also { it.mkdirs() }
    }

    private fun generateImageThumbnail(
        sourceFile: File,
        sessionId: String,
        id: String
    ): String? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourceFile.absolutePath, options)

            val sampleSize = calculateInSampleSize(options, THUMB_SIZE, THUMB_SIZE)
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize

            val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
                ?: return null

            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                minOf(THUMB_SIZE, bitmap.width),
                minOf(THUMB_SIZE, bitmap.height),
                true
            )
            if (scaled != bitmap) bitmap.recycle()

            val thumbFile = File(getThumbsDir(sessionId), "${id}_thumb.jpg")
            thumbFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            scaled.recycle()

            thumbFile.absolutePath
        } catch (e: Exception) {
            null  // Thumbnail failure is non-fatal
        }
    }

    private fun generateVideoThumbnail(
        uri: Uri,
        sessionId: String,
        id: String
    ): String? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getFrameAtTime(0) ?: return null
            retriever.release()

            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                minOf(THUMB_SIZE, bitmap.width),
                minOf(THUMB_SIZE, bitmap.height),
                true
            )
            if (scaled != bitmap) bitmap.recycle()

            val thumbFile = File(getThumbsDir(sessionId), "${id}_thumb.jpg")
            thumbFile.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            scaled.recycle()

            thumbFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }

    private fun getFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0) return cursor.getLong(sizeIndex)
            }
        }
        return 0L
    }

    private fun getImageDimensions(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            Pair(options.outWidth, options.outHeight)
        } else null
    }

    private fun getVideoDimensions(uri: Uri): Pair<Int, Int>? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()
            retriever.release()
            if (width != null && height != null) Pair(width, height) else null
        } catch (e: Exception) { null }
    }

    private fun getVideoDuration(uri: Uri): Long? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            retriever.release()
            duration
        } catch (e: Exception) { null }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

/**
 * Pending attachment before it is associated with a message.
 * Created when user selects a file, before message is sent.
 */
data class PendingAttachment(
    val id: String,
    val type: AttachmentType,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val filePath: String,
    val thumbnailPath: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?
)
```

### API 适配器层

#### ApiMessage 变更

```kotlin
/**
 * Located in: data/remote/adapter/ApiMessage.kt
 *
 * MODIFIED: User message now supports multimodal content parts.
 */
sealed class ApiMessage {
    data class User(
        val content: String,
        val attachments: List<ApiAttachment> = emptyList()
    ) : ApiMessage()

    data class Assistant(
        val content: String?,
        val toolCalls: List<ApiToolCall>? = null
    ) : ApiMessage()

    data class ToolResult(
        val toolCallId: String,
        val content: String
    ) : ApiMessage()
}

/**
 * Attachment data prepared for API transmission.
 * Contains pre-encoded base64 data.
 */
data class ApiAttachment(
    val type: AttachmentType,
    val mimeType: String,
    val base64Data: String,
    val fileName: String
)
```

#### 服务商专用格式化

每个适配器对多模态消息的格式化方式不同。核心变更在于消息序列化方法。

**OpenAI 适配器** -- 图片以 `image_url` 加 base64 数据 URI 的形式传递：

```kotlin
// In OpenAiAdapter -- when building the messages JSON array:

private fun buildUserMessage(message: ApiMessage.User): JsonObject {
    if (message.attachments.isEmpty()) {
        // Text-only: simple format
        return buildJsonObject {
            put("role", "user")
            put("content", message.content)
        }
    }

    // Multimodal: content array format
    return buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            // Add text part (if non-empty)
            if (message.content.isNotBlank()) {
                addJsonObject {
                    put("type", "text")
                    put("text", message.content)
                }
            }
            // Add image parts (skip unsupported types)
            message.attachments
                .filter { it.type == AttachmentType.IMAGE }
                .forEach { attachment ->
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:${attachment.mimeType};base64,${attachment.base64Data}")
                        }
                    }
                }
        }
    }
}
```

**Anthropic 适配器** -- 图片以 `image` 内容块的形式传递：

```kotlin
// In AnthropicAdapter:

private fun buildUserMessage(message: ApiMessage.User): JsonObject {
    if (message.attachments.isEmpty()) {
        return buildJsonObject {
            put("role", "user")
            put("content", message.content)
        }
    }

    return buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            // Add image parts
            message.attachments
                .filter { it.type == AttachmentType.IMAGE ||
                         (it.type == AttachmentType.FILE && it.mimeType == "application/pdf") }
                .forEach { attachment ->
                    addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", attachment.mimeType)
                            put("data", attachment.base64Data)
                        }
                    }
                }
            // Add text part
            if (message.content.isNotBlank()) {
                addJsonObject {
                    put("type", "text")
                    put("text", message.content)
                }
            }
        }
    }
}
```

**Gemini 适配器** -- 使用 `inlineData` 块：

```kotlin
// In GeminiAdapter:

private fun buildUserParts(message: ApiMessage.User): JsonArray {
    return buildJsonArray {
        // Add text part
        if (message.content.isNotBlank()) {
            addJsonObject {
                put("text", message.content)
            }
        }
        // Add attachment parts (Gemini supports images and video)
        message.attachments
            .filter { it.type == AttachmentType.IMAGE || it.type == AttachmentType.VIDEO }
            .forEach { attachment ->
                addJsonObject {
                    putJsonObject("inlineData") {
                        put("mimeType", attachment.mimeType)
                        put("data", attachment.base64Data)
                    }
                }
            }
    }
}
```

#### 服务商能力检查

```kotlin
/**
 * Located in: core/model/ProviderCapability.kt
 *
 * Defines what attachment types each provider supports.
 */
object ProviderCapability {
    fun supportsAttachmentType(providerType: ProviderType, attachmentType: AttachmentType): Boolean {
        return when (providerType) {
            ProviderType.OPENAI -> attachmentType == AttachmentType.IMAGE
            ProviderType.ANTHROPIC -> attachmentType in listOf(AttachmentType.IMAGE, AttachmentType.FILE)
            ProviderType.GEMINI -> attachmentType in listOf(AttachmentType.IMAGE, AttachmentType.VIDEO)
        }
    }

    fun getUnsupportedTypes(
        providerType: ProviderType,
        attachments: List<PendingAttachment>
    ): List<AttachmentType> {
        return attachments
            .map { it.type }
            .distinct()
            .filter { !supportsAttachmentType(providerType, it) }
    }
}
```

### UI 层

#### ChatInput 变更

```kotlin
/**
 * Located in: feature/chat/ChatScreen.kt
 *
 * MODIFIED: Add attachment button and preview row to ChatInput.
 */
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSkillClick: () -> Unit = {},
    onAttachClick: () -> Unit = {},              // NEW
    pendingAttachments: List<PendingAttachment> = emptyList(),  // NEW
    onRemoveAttachment: (String) -> Unit = {},   // NEW
    isStreaming: Boolean,
    hasConfiguredProvider: Boolean,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    // ... existing TextFieldValue setup ...

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // NEW: Attachment preview row (above text field)
            if (pendingAttachments.isNotEmpty()) {
                AttachmentPreviewRow(
                    attachments = pendingAttachments,
                    onRemove = onRemoveAttachment,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Layer 1: Text Field (unchanged)
            BasicTextField(/* ... existing code ... */)

            // Layer 2: Action Row
            Row(/* ... existing modifiers ... */) {
                // Skill button (unchanged)
                Box(/* ... */) { /* Skill icon */ }

                // NEW: Attachment button
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onAttachClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Attach",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Stop button (unchanged)
                // Send button -- MODIFIED: also enabled when attachments present
                val sendEnabled = (text.isNotBlank() || pendingAttachments.isNotEmpty())
                    && hasConfiguredProvider
                // ... rest unchanged ...
            }
        }
    }
}
```

#### AttachmentPreviewRow

```kotlin
/**
 * Located in: feature/chat/components/AttachmentPreviewRow.kt
 *
 * Horizontally scrollable row of attachment previews in the chat input.
 */
@Composable
fun AttachmentPreviewRow(
    attachments: List<PendingAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Box(modifier = Modifier.size(72.dp)) {
                when (attachment.type) {
                    AttachmentType.IMAGE -> {
                        AsyncImage(
                            model = attachment.thumbnailPath ?: attachment.filePath,
                            contentDescription = attachment.fileName,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    AttachmentType.VIDEO -> {
                        Box {
                            AsyncImage(
                                model = attachment.thumbnailPath,
                                contentDescription = attachment.fileName,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            // Play icon overlay
                            Icon(
                                Icons.Default.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(28.dp)
                                    .align(Alignment.Center),
                                tint = Color.White.copy(alpha = 0.9f)
                            )
                            // Duration label
                            attachment.durationMs?.let { ms ->
                                Text(
                                    text = formatDuration(ms),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .background(
                                            Color.Black.copy(alpha = 0.6f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    AttachmentType.FILE -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = attachment.fileName,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Remove button (top-right corner)
                IconButton(
                    onClick = { onRemove(attachment.id) },
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .background(
                            MaterialTheme.colorScheme.surface,
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
```

#### AttachmentPickerSheet

```kotlin
/**
 * Located in: feature/chat/components/AttachmentPickerSheet.kt
 *
 * Bottom sheet for selecting attachment source.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = "Attach",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PickerOption(
                    icon = Icons.Default.Image,
                    label = "Photo",
                    onClick = { onPickPhoto(); onDismiss() }
                )
                PickerOption(
                    icon = Icons.Default.Videocam,
                    label = "Video",
                    onClick = { onPickVideo(); onDismiss() }
                )
                PickerOption(
                    icon = Icons.Default.CameraAlt,
                    label = "Camera",
                    onClick = { onTakePhoto(); onDismiss() }
                )
                PickerOption(
                    icon = Icons.Default.Folder,
                    label = "File",
                    onClick = { onPickFile(); onDismiss() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PickerOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
```

#### AttachmentDisplay（消息气泡）

```kotlin
/**
 * Located in: feature/chat/components/AttachmentDisplay.kt
 *
 * Displays attachments within a message bubble.
 */
@Composable
fun AttachmentDisplay(
    attachments: List<Attachment>,
    onImageClick: (Attachment) -> Unit,
    onVideoClick: (Attachment) -> Unit,
    onFileClick: (Attachment) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        attachments.forEach { attachment ->
            when (attachment.type) {
                AttachmentType.IMAGE -> {
                    AsyncImage(
                        model = attachment.filePath,
                        contentDescription = attachment.fileName,
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageClick(attachment) },
                        contentScale = ContentScale.FitWidth
                    )
                }
                AttachmentType.VIDEO -> {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onVideoClick(attachment) }
                    ) {
                        AsyncImage(
                            model = attachment.thumbnailPath,
                            contentDescription = attachment.fileName,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FitWidth
                        )
                        Icon(
                            Icons.Default.PlayCircleFilled,
                            contentDescription = "Play video",
                            modifier = Modifier
                                .size(48.dp)
                                .align(Alignment.Center),
                            tint = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
                AttachmentType.FILE -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFileClick(attachment) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = attachment.fileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatFileSize(attachment.fileSize),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

#### ImageViewerDialog

```kotlin
/**
 * Located in: feature/chat/components/ImageViewerDialog.kt
 *
 * Full-screen image viewer with pinch-to-zoom and pan.
 */
@Composable
fun ImageViewerDialog(
    imagePath: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss)
        ) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            AsyncImage(
                model = imagePath,
                contentDescription = "Full-screen image",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale > 1f) {
                                Offset(
                                    x = offset.x + pan.x,
                                    y = offset.y + pan.y
                                )
                            } else {
                                Offset.Zero
                            }
                        }
                    },
                contentScale = ContentScale.Fit
            )

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .statusBarsPadding()
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
```

### ChatViewModel 变更

```kotlin
/**
 * Located in: feature/chat/ChatViewModel.kt
 *
 * Key additions for attachment management.
 */
class ChatViewModel(
    // ... existing dependencies ...
    private val attachmentFileManager: AttachmentFileManager,  // NEW
    private val attachmentRepository: AttachmentRepository     // NEW
) : ViewModel() {

    // NEW: Pending attachments (before message is sent)
    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachment>> = _pendingAttachments.asStateFlow()

    // NEW: Image viewer state
    private val _viewingImage = MutableStateFlow<String?>(null)
    val viewingImage: StateFlow<String?> = _viewingImage.asStateFlow()

    // NEW: Attachment picker visibility
    private val _showAttachmentPicker = MutableStateFlow(false)
    val showAttachmentPicker: StateFlow<Boolean> = _showAttachmentPicker.asStateFlow()

    fun showAttachmentPicker() {
        _showAttachmentPicker.value = true
    }

    fun hideAttachmentPicker() {
        _showAttachmentPicker.value = false
    }

    /**
     * Add an attachment from a content URI (gallery/file picker).
     */
    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            val sessionId = currentSessionId ?: return@launch
            when (val result = attachmentFileManager.copyFromUri(uri, sessionId)) {
                is AppResult.Success -> {
                    _pendingAttachments.update { it + result.data }
                }
                is AppResult.Error -> {
                    // Show error via snackbar event
                    _uiState.update { it.copy(snackbarMessage = result.message) }
                }
            }
        }
    }

    /**
     * Add a camera-captured photo.
     */
    fun addCameraPhoto(file: File) {
        viewModelScope.launch {
            val sessionId = currentSessionId ?: return@launch
            when (val result = attachmentFileManager.copyFromCameraFile(file, sessionId)) {
                is AppResult.Success -> {
                    _pendingAttachments.update { it + result.data }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(snackbarMessage = result.message) }
                }
            }
        }
    }

    /**
     * Remove a pending attachment.
     */
    fun removeAttachment(id: String) {
        _pendingAttachments.update { list -> list.filter { it.id != id } }
        // Note: File remains on disk -- cleaned up on session delete or if never sent
    }

    fun openImageViewer(imagePath: String) {
        _viewingImage.value = imagePath
    }

    fun closeImageViewer() {
        _viewingImage.value = null
    }

    /**
     * MODIFIED: sendMessage now includes pending attachments.
     */
    fun sendMessage() {
        val text = _uiState.value.inputText
        val attachments = _pendingAttachments.value

        if (text.isBlank() && attachments.isEmpty()) return

        // Check for unsupported attachment types
        val providerType = currentProviderType
        if (providerType != null && attachments.isNotEmpty()) {
            val unsupported = ProviderCapability.getUnsupportedTypes(providerType, attachments)
            if (unsupported.isNotEmpty()) {
                val typeNames = unsupported.joinToString(", ") { it.name.lowercase() }
                _uiState.update {
                    it.copy(snackbarMessage =
                        "${typeNames.replaceFirstChar { c -> c.uppercase() }} attachments " +
                        "are not supported by this provider and will be skipped")
                }
            }

            // If all attachments unsupported and no text, block send
            val supported = attachments.filter {
                ProviderCapability.supportsAttachmentType(providerType, it.type)
            }
            if (supported.isEmpty() && text.isBlank()) {
                _uiState.update {
                    it.copy(snackbarMessage = "No supported content to send")
                }
                return
            }
        }

        // Clear pending state
        _pendingAttachments.value = emptyList()
        _uiState.update { it.copy(inputText = "") }

        viewModelScope.launch {
            // Create user message and save attachments to DB
            // Then invoke SendMessageUseCase with attachments
            // ...
        }
    }
}
```

### ChatUiState 变更

```kotlin
/**
 * Located in: feature/chat/ChatUiState.kt
 *
 * MODIFIED: Add attachments to ChatMessageItem.
 */
data class ChatMessageItem(
    val id: String,
    val type: MessageType,
    val content: String,
    val thinkingContent: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolStatus: ToolCallStatus? = null,
    val toolDurationMs: Long? = null,
    val modelId: String? = null,
    val tokenCountInput: Int? = null,
    val tokenCountOutput: Int? = null,
    val isRetryable: Boolean = false,
    val timestamp: Long = 0,
    val attachments: List<Attachment> = emptyList()  // NEW
)
```

### SendMessageUseCase 变更

```kotlin
/**
 * In SendMessageUseCase:
 *
 * MODIFIED: Accept attachments and include in API message.
 *
 * When building the ApiMessage.User, attach base64-encoded files
 * that are supported by the current provider.
 */

// In the message building step:
val apiAttachments = pendingAttachments
    .filter { ProviderCapability.supportsAttachmentType(providerType, it.type) }
    .map { attachment ->
        ApiAttachment(
            type = attachment.type,
            mimeType = attachment.mimeType,
            base64Data = attachmentFileManager.readAsBase64(attachment.filePath),
            fileName = attachment.fileName
        )
    }

val userMessage = ApiMessage.User(
    content = messageText,
    attachments = apiAttachments
)
```

### 随消息加载附件

在加载消息以供展示时，需要同步加载附件：

```kotlin
/**
 * In ChatViewModel, when collecting messages from the repository:
 *
 * For each message, also load its attachments.
 * Use getAttachmentsForMessages() to batch-load efficiently.
 */
private fun observeMessages(sessionId: String) {
    viewModelScope.launch {
        messageRepository.getMessagesForSession(sessionId).collect { messages ->
            val messageIds = messages.map { it.id }
            val allAttachments = attachmentRepository
                .getAttachmentsForMessages(messageIds)
            val attachmentsByMessage = allAttachments.groupBy { it.messageId }

            val items = messages.map { message ->
                message.toChatMessageItem(
                    attachments = attachmentsByMessage[message.id] ?: emptyList()
                )
            }
            _uiState.update { it.copy(messages = items) }
        }
    }
}
```

## 实施计划

### 阶段一：数据基础

1. 创建 `Attachment` 领域模型和 `AttachmentType` 枚举
2. 创建带有 Room 注解的 `AttachmentEntity`
3. 创建 `AttachmentDao`
4. 创建 `AttachmentMapper`
5. 添加 `attachments` 数据表的数据库迁移
6. 创建 `AttachmentRepository` 接口和 `AttachmentRepositoryImpl`
7. 创建含复制、缩略图及清理逻辑的 `AttachmentFileManager`
8. 创建 `PendingAttachment` 数据类
9. 更新 DI 模块（`DatabaseModule`、`RepositoryModule`）
10. 为 `AttachmentFileManager` 和 `AttachmentRepositoryImpl` 编写单元测试

### 阶段二：API 适配器扩展

1. 添加 `ApiAttachment` 数据类
2. 修改 `ApiMessage.User` 以包含 `attachments` 列表
3. 更新 `OpenAiAdapter` 的消息格式化以支持多模态
4. 更新 `AnthropicAdapter` 的消息格式化以支持多模态
5. 更新 `GeminiAdapter` 的消息格式化以支持多模态
6. 创建 `ProviderCapability` 工具类
7. 为适配器多模态格式化编写单元测试

### 阶段三：ViewModel 与 Use Case

1. 向 `ChatViewModel` 添加 `pendingAttachments` 状态
2. 实现 `addAttachment()`、`removeAttachment()`、`addCameraPhoto()`
3. 修改 `sendMessage()` 以包含附件及服务商能力检查
4. 修改 `SendMessageUseCase` 以接受并编码附件
5. 实现随附件加载消息（批量查询）
6. 为不支持的附件类型警告添加 `snackbarMessage`
7. 更新 DI（`FeatureModule`）以注入新依赖

### 阶段四：UI 组件

1. 创建 `AttachmentPickerSheet` 底部弹窗
2. 创建聊天输入框中使用的 `AttachmentPreviewRow`
3. 修改 `ChatInput`，添加附件按钮和预览行
4. 创建消息气泡中使用的 `AttachmentDisplay`
5. 修改消息气泡渲染以包含 `AttachmentDisplay`
6. 创建支持缩放/平移的 `ImageViewerDialog`
7. 接入视频/文件点击跳转系统 Intent
8. 将 `ChatScreen` 与新 ViewModel 状态和回调关联
9. 添加通过 `FileProvider` 和 `TakePicture` 合约拍摄相机照片的功能

### 阶段五：测试与验证

1. Layer 1A：`./gradlew test` -- 所有单元测试通过
2. Layer 1B：数据库操作的 Instrumented 测试
3. 手动测试：通过各选择器附加图片、视频、文件
4. 手动测试：相机照片拍摄
5. 手动测试：各类型附件的消息气泡显示
6. 手动测试：图片查看器缩放/平移
7. 手动测试：通过系统播放器播放视频
8. 手动测试：不支持类型的警告流程
9. 手动测试：删除会话时清理文件
10. 编写测试报告

## 错误处理

| 错误 | 原因 | 处理方式 |
|------|------|----------|
| 文件过大 | 所选文件 > 20MB | `AppResult.Error` -> Snackbar "File is too large (max 20MB)" |
| 复制失败 | I/O 错误、存储空间不足 | `AppResult.Error` -> Snackbar "Failed to save attachment" |
| 相机不可用 | 无相机硬件或权限被拒绝 | 发起权限请求或显示错误 |
| 缩略图生成失败 | 图片/视频损坏 | 非致命错误；使用占位图标 |
| Base64 编码失败 | 文件在选择与发送之间被删除 | 跳过该附件，提示用户 |
| 服务商拒绝文件 | 文件格式或大小被 API 拒绝 | 在 AI 回复气泡中显示错误 |
| 所有附件均不支持 | 服务商不支持任何已选类型，且无文本 | 阻止发送，显示错误消息 |
| 部分附件不支持 | 部分附件不被支持 | Snackbar 警告，发送支持的附件及文本 |

## 性能考量

| 操作 | 预期耗时 | 备注 |
|------|---------|------|
| 文件复制（10MB） | ~500ms | I/O 密集，运行于 Dispatchers.IO |
| 缩略图生成（图片） | ~200ms | Bitmap 解码 + 缩放 + 压缩 |
| 缩略图生成（视频） | ~300ms | MediaMetadataRetriever + 缩放 |
| Base64 编码（10MB） | ~200ms | 内存转换 |
| 附件预览渲染 | < 16ms | Coil 异步图片加载 |
| 含图片的消息气泡 | < 16ms | 通过 Coil 预加载缩略图 |

### 内存

| 资源 | 峰值用量 | 备注 |
|------|---------|------|
| 内存中的 Base64（20MB 文件） | ~27MB | API 调用期间临时存在，调用后由 GC 回收 |
| 缩略图 Bitmap | ~256KB | 256x256 JPEG |
| 查看器中的全分辨率图片 | ~10-30MB | 单张图片，Coil 管理缓存 |
| 待发附件列表 | 可忽略 | 仅元数据，文件存储于磁盘 |

## 安全考量

1. **文件隔离**：所有附件文件存储于应用内部存储（`context.filesDir`），其他应用无法在未经显式共享的情况下访问
2. **URI 权限**：来自选择器的内容 URI 使用临时读取权限，使用后即过期
3. **不使用外部存储**：从不写入共享/外部存储
4. **文件验证**：选择时检查 MIME 类型；文件大小限制为 20MB
5. **Base64 编码**：仅在内存中执行，从不以 base64 形式写入磁盘
6. **相机 FileProvider**：相机照片通过 `FileProvider` 支持的 URI 拍摄，不写入公共目录

## 依赖项

### 外部依赖

- **Coil**（图片加载）：用于预览和消息气泡中的异步图片加载。若项目中尚未引入，添加 `io.coil-kt:coil-compose`
- **Android Photo Picker**：系统组件（`ActivityResultContracts.PickVisualMedia`）
- **Android Camera**：系统组件（`ActivityResultContracts.TakePicture`）
- **Android SAF**：系统组件（`ActivityResultContracts.OpenDocument`）
- **MediaMetadataRetriever**：Android 框架类，用于获取视频元数据

### 内部依赖

- `Tool` 系统不涉及此功能（附件由用户主动发起，非 AI 工具触发）
- `Message` 模型与 `MessageRepository`
- `ApiMessage` 及全部三个适配器实现
- `AppDatabase`（Room 迁移）
- `ChatViewModel` 与 `ChatScreen`

## 已考虑的替代方案

### 1. JSON 列而非独立数据表

**方案**：将附件以 JSON 字符串列的形式存储于 `messages` 表中。
**拒绝原因**：难以查询（如"查找会话 X 中的所有图片"），无外键约束，无索引支持。独立数据表更清晰，且与项目中现有的 Room 模式一致。

### 2. 仅内存存储（不使用数据库）

**方案**：仅在内存中保存附件元数据，加载时重新扫描文件系统。
**拒绝原因**：会丢失元数据（原始文件名、尺寸、时长）。文件系统扫描慢于数据库查询。应用重启后消息与附件的关联关系将丢失。

### 3. 使用 Coil 按需生成缩略图而非预先生成

**方案**：让 Coil 动态生成并缓存缩略图。
**拒绝原因**：视频缩略图需要 `MediaMetadataRetriever`，Coil 原生不支持。预生成的缩略图初次显示更快，且可离线使用。

## 未来扩展

- 发送前的图片压缩/调整大小选项
- 剪贴板粘贴图片支持
- 相机视频录制
- 从其他应用拖拽
- 内联 PDF 预览
- AI 生成图片展示
- 跨会话的附件搜索
- 云存储集成（Google Drive、Dropbox）

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
