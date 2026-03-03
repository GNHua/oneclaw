# RFC-026: File Attachments

## Document Information
- **RFC ID**: RFC-026
- **Related PRD**: [FEAT-026 (File Attachments)](../../prd/features/FEAT-026-file-attachments.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-001 (Chat Interaction)](RFC-001-chat-interaction.md), [RFC-016 (Chat Input Redesign)](RFC-016-chat-input-redesign.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClaw currently supports text-only chat messages. All three supported AI providers (OpenAI, Anthropic, Gemini) support multimodal input -- at minimum images, with Gemini also supporting video. Users need the ability to send images, videos, and files alongside text messages to take full advantage of these provider capabilities.

The current `ApiMessage.User` class only holds a `content: String`, and the `Message` domain model has no attachment fields. This RFC designs the full stack of changes needed: data model, file storage, UI components, API adapter extensions, and media viewing.

### Goals

1. Add an attachment button to `ChatInput` with a bottom sheet picker (Photo, Video, Camera, File)
2. Design an `Attachment` data model and Room table with FK to messages
3. Implement file storage at `files/attachments/{sessionId}/` with thumbnail generation
4. Display attachment previews in the chat input before sending
5. Display attachments inline in message bubbles (images, video thumbnails, file cards)
6. Implement full-screen image viewer with zoom/pan
7. Open videos and files via system Intent
8. Extend `ApiMessage.User` to support multimodal content parts
9. Extend all three API adapters to format attachments in provider-specific formats
10. Handle unsupported attachment types with user notification

### Non-Goals

- Video recording from camera
- Image editing, cropping, or annotation
- Drag-and-drop or clipboard paste for attachments
- Audio recording or voice messages
- Inline PDF viewer or document preview
- Cloud storage integration
- AI-generated image rendering (text-to-image models)
- Attachment compression settings UI

## Technical Design

### Architecture Overview

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

### Core Components

**New:**
1. `Attachment` -- Domain model for attachment metadata
2. `AttachmentEntity` + `AttachmentDao` -- Room persistence
3. `AttachmentRepository` / `AttachmentRepositoryImpl` -- Data access
4. `AttachmentFileManager` -- File copy, thumbnail generation, cleanup
5. `AttachmentDisplay` -- Composable for rendering attachments in message bubbles
6. `AttachmentPreviewRow` -- Composable for pre-send attachment previews
7. `AttachmentPickerSheet` -- Bottom sheet with picker options
8. `ImageViewerDialog` -- Full-screen image viewer with zoom/pan
9. `SaveAttachmentUseCase` -- Orchestrates file copy + DB save

**Modified:**
10. `ChatInput` -- Add attachment button, preview row
11. `ChatViewModel` -- Manage pending attachments, include in send
12. `ChatScreen` -- Wire attachment UI, media viewer
13. `ChatUiState` / `ChatMessageItem` -- Add attachment data
14. `ApiMessage.User` -- Support multimodal content
15. `OpenAiAdapter` / `AnthropicAdapter` / `GeminiAdapter` -- Multimodal formatting
16. `SendMessageUseCase` -- Include attachments in API message
17. `AppDatabase` -- Migration to add `attachments` table
18. `MessageMapper` -- Map attachments alongside messages
19. `DatabaseModule` -- Provide `AttachmentDao`
20. `RepositoryModule` -- Provide `AttachmentRepository`

## Detailed Design

### Directory Structure (New & Changed Files)

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

### Data Model

#### Attachment Domain Model

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

#### Attachment Room Entity

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

### Database Migration

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

### Repository Layer

#### AttachmentRepository Interface

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

### File Management

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

### API Adapter Layer

#### ApiMessage Changes

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

#### Provider-Specific Formatting

Each adapter formats multimodal messages differently. The key changes are in the message serialization methods.

**OpenAI Adapter** -- images as `image_url` with base64 data URI:

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

**Anthropic Adapter** -- images as `image` content blocks:

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

**Gemini Adapter** -- `inlineData` parts:

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

#### Provider Capability Check

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

### UI Layer

#### ChatInput Changes

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

#### AttachmentDisplay (Message Bubble)

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

### ChatViewModel Changes

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

### ChatUiState Changes

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

### SendMessageUseCase Changes

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

### Loading Attachments with Messages

When loading messages for display, attachments must be loaded alongside:

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

## Implementation Plan

### Phase 1: Data Foundation

1. Create `Attachment` domain model and `AttachmentType` enum
2. Create `AttachmentEntity` with Room annotations
3. Create `AttachmentDao`
4. Create `AttachmentMapper`
5. Add database migration for `attachments` table
6. Create `AttachmentRepository` interface and `AttachmentRepositoryImpl`
7. Create `AttachmentFileManager` with copy, thumbnail, and cleanup logic
8. Create `PendingAttachment` data class
9. Update DI modules (`DatabaseModule`, `RepositoryModule`)
10. Write unit tests for `AttachmentFileManager` and `AttachmentRepositoryImpl`

### Phase 2: API Adapter Extensions

1. Add `ApiAttachment` data class
2. Modify `ApiMessage.User` to include `attachments` list
3. Update `OpenAiAdapter` message formatting for multimodal
4. Update `AnthropicAdapter` message formatting for multimodal
5. Update `GeminiAdapter` message formatting for multimodal
6. Create `ProviderCapability` utility
7. Write unit tests for adapter multimodal formatting

### Phase 3: ViewModel and Use Case

1. Add `pendingAttachments` state to `ChatViewModel`
2. Implement `addAttachment()`, `removeAttachment()`, `addCameraPhoto()`
3. Modify `sendMessage()` to include attachments and provider capability check
4. Modify `SendMessageUseCase` to accept and encode attachments
5. Implement message loading with attachments (batch query)
6. Add `snackbarMessage` for unsupported attachment warnings
7. Update DI (`FeatureModule`) for new dependencies

### Phase 4: UI Components

1. Create `AttachmentPickerSheet` bottom sheet
2. Create `AttachmentPreviewRow` for chat input
3. Modify `ChatInput` to add attachment button and preview row
4. Create `AttachmentDisplay` for message bubbles
5. Modify message bubble rendering to include `AttachmentDisplay`
6. Create `ImageViewerDialog` with zoom/pan
7. Wire video/file tap to system Intent
8. Wire `ChatScreen` to new ViewModel states and callbacks
9. Add camera photo capture with `FileProvider` and `TakePicture` contract

### Phase 5: Testing and Verification

1. Layer 1A: `./gradlew test` -- all unit tests pass
2. Layer 1B: Instrumented tests for database operations
3. Manual testing: attach image, video, file via each picker
4. Manual testing: camera photo capture
5. Manual testing: message bubble display for each type
6. Manual testing: image viewer zoom/pan
7. Manual testing: video playback via system player
8. Manual testing: unsupported type warning flow
9. Manual testing: session deletion cleans up files
10. Write test report

## Error Handling

| Error | Cause | Handling |
|-------|-------|----------|
| File too large | Selected file > 20MB | `AppResult.Error` -> Snackbar "File is too large (max 20MB)" |
| Copy failure | I/O error, no storage space | `AppResult.Error` -> Snackbar "Failed to save attachment" |
| Camera unavailable | No camera hardware or permission denied | Launch permission request or show error |
| Thumbnail failure | Corrupt image/video | Non-fatal; use placeholder icon |
| Base64 encoding failure | File deleted between select and send | Skip attachment, warn user |
| Provider rejects file | File format or size rejected by API | Error shown in AI response bubble |
| All attachments unsupported | Provider doesn't support any selected types, no text | Block send, show error message |
| Partial unsupported | Some attachments unsupported | Snackbar warning, send supported ones + text |

## Performance Considerations

| Operation | Expected Time | Notes |
|-----------|--------------|-------|
| File copy (10MB) | ~500ms | I/O bound, runs on Dispatchers.IO |
| Thumbnail generation (image) | ~200ms | Bitmap decode + scale + compress |
| Thumbnail generation (video) | ~300ms | MediaMetadataRetriever + scale |
| Base64 encoding (10MB) | ~200ms | In-memory conversion |
| Attachment preview rendering | < 16ms | Coil async image loading |
| Message bubble with images | < 16ms | Pre-loaded thumbnails via Coil |

### Memory

| Resource | Peak Usage | Notes |
|----------|-----------|-------|
| Base64 in memory (20MB file) | ~27MB | Temporary during API call, GC'd after |
| Thumbnail bitmap | ~256KB | 256x256 JPEG |
| Full-res image in viewer | ~10-30MB | Single image, Coil manages cache |
| Pending attachments list | Negligible | Metadata only, files on disk |

## Security Considerations

1. **File isolation**: All attachment files are stored in app-internal storage (`context.filesDir`), not accessible to other apps without explicit sharing
2. **URI permissions**: Content URIs from pickers use temporary read permissions that expire after use
3. **No external storage**: Never writes to shared/external storage
4. **File validation**: MIME type checked at selection time; file size limited to 20MB
5. **Base64 encoding**: Performed in-memory, never written to disk as base64
6. **Camera FileProvider**: Camera photos are captured via a `FileProvider`-backed URI, not written to public directories

## Dependencies

### External Dependencies

- **Coil** (image loading): For async image loading in previews and message bubbles. If not already in the project, add `io.coil-kt:coil-compose`.
- **Android Photo Picker**: System component (`ActivityResultContracts.PickVisualMedia`)
- **Android Camera**: System component (`ActivityResultContracts.TakePicture`)
- **Android SAF**: System component (`ActivityResultContracts.OpenDocument`)
- **MediaMetadataRetriever**: Android framework class for video metadata

### Internal Dependencies

- `Tool` system is NOT involved (this is user-initiated, not AI-tool-initiated)
- `Message` model and `MessageRepository`
- `ApiMessage` and all three adapter implementations
- `AppDatabase` (Room migration)
- `ChatViewModel` and `ChatScreen`

## Alternatives Considered

### 1. JSON Column Instead of Separate Table

**Approach**: Store attachments as a JSON string column in the `messages` table.
**Rejected because**: Harder to query (e.g., "find all images in session X"), no FK constraints, no index support. A separate table is cleaner and aligns with the existing Room patterns in the project.

### 2. In-Memory Only (No Database)

**Approach**: Keep attachment metadata only in memory, re-scan the file system on load.
**Rejected because**: Loses metadata (original filename, dimensions, duration). File system scanning is slower than a database query. Message-attachment association would be lost on app restart.

### 3. Coil for Thumbnails Instead of Pre-generating

**Approach**: Let Coil generate and cache thumbnails on-the-fly.
**Rejected because**: Video thumbnails require `MediaMetadataRetriever` which Coil doesn't handle out of the box. Pre-generated thumbnails are faster for initial display and work offline.

## Future Extensions

- Image compression/resizing options before sending
- Clipboard paste support for images
- Camera video recording
- Drag-and-drop from other apps
- Inline PDF preview
- AI-generated image display
- Attachment search across sessions
- Cloud storage integration (Google Drive, Dropbox)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
