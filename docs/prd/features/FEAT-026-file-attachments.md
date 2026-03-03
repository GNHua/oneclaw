# File Attachments

## Feature Information
- **Feature ID**: FEAT-026
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-026 (pending)

## User Story

**As** a user of OneClaw,
**I want** to attach images, videos, and files to my chat messages,
**so that** I can send visual and document context to AI models for analysis, and view attached media inline within the conversation.

### Typical Scenarios

1. The user takes a photo of a document with their camera and sends it to the AI for OCR or summarization.
2. The user selects an image from their gallery and asks the AI to describe or analyze it.
3. The user attaches a video from their gallery and asks the AI to analyze it (Gemini) or receives a notification that video is not supported by the current provider.
4. The user attaches a PDF or text file and asks the AI to summarize its contents.
5. The user taps on an image in a message bubble to view it full-screen with zoom and pan.
6. The user taps on a video thumbnail in a message bubble to play it with the system video player.

## Feature Description

### Overview

FEAT-026 adds multimodal input support to the chat interface. Users can attach images, videos, and files to their messages via an attachment button in the chat input area. Attached media is displayed as previews before sending and rendered inline in message bubbles after sending. Users can tap on media to view images full-screen or play videos with the system player.

This feature also extends the API adapter layer to support multimodal content for all three providers (OpenAI, Anthropic, Gemini), with graceful handling when a provider does not support a particular media type.

### Detailed Description

#### Attachment Button and Picker

- An attachment button ("+") is added to the chat input action row, to the right of the Skill button
- Tapping the button opens a bottom sheet with four options:
  - **Photo**: Select images from the device gallery (Photo Picker API)
  - **Video**: Select videos from the device gallery (Photo Picker API)
  - **Camera**: Take a photo with the device camera (no video recording)
  - **File**: Select any file from the device (SAF document picker)
- Multiple images/videos can be selected at once from the gallery
- Selected files are copied to app-internal storage at `files/attachments/{sessionId}/`

#### Attachment Preview (Pre-send)

- After selecting files, a horizontally scrollable preview row appears above the text input field
- Each preview item shows:
  - **Image**: Thumbnail with a remove (X) button
  - **Video**: Thumbnail with duration overlay and remove button
  - **File**: File name, size, and remove button
- Users can remove individual attachments before sending

#### Message Bubble Display (Post-send)

- User message bubbles display attached media inline:
  - **Images**: Rendered as rounded thumbnails (max width ~240dp), displayed above the text content
  - **Videos**: Thumbnail with a play button overlay
  - **Files**: Card-style display with file icon, name, and size
- Multiple attachments are displayed in a vertical list within the bubble

#### Media Viewer (Tap Interaction)

- **Image**: Opens a full-screen image viewer with pinch-to-zoom and pan gestures
- **Video**: Opens with the system video player via Intent
- **File**: Opens with the appropriate system app via Intent (ACTION_VIEW)

#### Provider Compatibility

Before sending a message with attachments, the system checks whether the current provider supports each attachment type:

| Attachment Type | OpenAI | Anthropic | Gemini |
|----------------|--------|-----------|--------|
| Image (JPEG, PNG, GIF, WebP) | Yes | Yes | Yes |
| Video | No | No | Yes |
| File (PDF, etc.) | No (text extraction only) | Yes (PDF via base64) | Yes |

If an attachment type is not supported by the current provider, a Snackbar notification warns the user that the unsupported attachment will be skipped. The message is still sent with the text and any supported attachments.

### User Interaction Flows

#### Attaching and Sending an Image

```
1. User taps the "+" button in the chat input area
2. Bottom sheet appears with Photo / Video / Camera / File options
3. User taps "Photo"
4. System Photo Picker opens
5. User selects one or more images
6. Images are copied to internal storage
7. Preview thumbnails appear above the text input
8. User types a message (optional) and taps Send
9. Message is sent with text + image attachments to the AI provider
10. Message bubble shows the image(s) inline above the text
11. AI response arrives (may reference the image content)
```

#### Viewing an Attached Image

```
1. User taps on an image in a message bubble
2. Full-screen image viewer opens
3. User can pinch to zoom and pan
4. User taps back or swipes down to dismiss
```

#### Unsupported Attachment Warning

```
1. User has OpenAI provider selected
2. User attaches a video
3. User taps Send
4. Snackbar appears: "Video attachments are not supported by this provider and will be skipped"
5. Message is sent with text only (video is excluded)
6. The video attachment is still stored locally and shown in the message bubble
```

## Acceptance Criteria

Must pass (all required):

- [ ] Attachment button ("+") is visible in the chat input action row
- [ ] Tapping the attachment button opens a bottom sheet with Photo, Video, Camera, File options
- [ ] Photo option opens the system Photo Picker and allows selecting images
- [ ] Video option opens the system Photo Picker and allows selecting videos
- [ ] Camera option opens the device camera for taking a photo
- [ ] File option opens the system document picker for selecting files
- [ ] Selected files are copied to `files/attachments/{sessionId}/` in app-internal storage
- [ ] Attachment previews appear above the text input with remove buttons
- [ ] Images display as thumbnails in the preview row
- [ ] Videos display as thumbnails with duration overlay in the preview row
- [ ] Files display as name + size cards in the preview row
- [ ] Removing an attachment from the preview removes it from the pending list
- [ ] Sending a message with attachments stores attachment metadata in the database
- [ ] User message bubbles display attached images inline
- [ ] User message bubbles display video thumbnails with play overlay
- [ ] User message bubbles display file cards for non-media attachments
- [ ] Tapping an image opens a full-screen viewer with zoom/pan
- [ ] Tapping a video opens the system video player
- [ ] Tapping a file opens the appropriate system app
- [ ] Attachments are encoded as base64 and sent to the API in the correct provider format
- [ ] Unsupported attachment types show a Snackbar warning before sending
- [ ] Unsupported attachments are excluded from the API request but stored locally
- [ ] Deleting a session deletes the associated attachment files from storage
- [ ] All Layer 1A tests pass

Optional (nice to have):

- [ ] Image compression/resizing before sending to reduce API payload
- [ ] Progress indicator while copying/encoding large files
- [ ] Attachment file size validation with user-friendly error messages

## UI/UX Requirements

### Attachment Button

- Position: In the action row (Layer 2), between the Skill button and the Spacer
- Style: Same as Skill button -- 36dp circle with `secondaryContainer` background
- Icon: `Icons.Default.Add` or `Icons.Default.AttachFile`, 20dp, `onSecondaryContainer` tint

### Bottom Sheet Picker

- Material 3 ModalBottomSheet
- Four options displayed as a 2x2 grid or vertical list, each with:
  - Icon (48dp)
  - Label text below the icon
- Options: Photo (image icon), Video (videocam icon), Camera (camera icon), File (folder icon)

### Attachment Preview Row

- Position: Above the text field, inside the ChatInput Surface
- Layout: Horizontal scrollable row (LazyRow)
- Item size: 72dp x 72dp for images/videos, flexible width for files
- Remove button: 20dp "X" badge in the top-right corner of each item
- Images: `RoundedCornerShape(8.dp)`, `ContentScale.Crop`
- Videos: Same as images, with semi-transparent play icon overlay and duration label
- Files: Compact card with file type icon + truncated filename

### Message Bubble Attachments

- Position: Above the text content in the message bubble
- Images: Max width 240dp, aspect ratio preserved, `RoundedCornerShape(12.dp)`
- Videos: Same layout as images, with centered play button overlay
- Files: Full-width card with icon, filename, and file size
- Multiple attachments: Vertical stack with 4dp spacing

### Full-Screen Image Viewer

- Dark background (scrim)
- Image centered, fills available space
- Pinch-to-zoom and drag-to-pan gestures
- Dismiss via back button or swipe down
- No additional controls needed for V1

## Feature Boundary

### Included

- Attachment button in chat input with bottom sheet picker
- Photo selection from gallery (Photo Picker)
- Video selection from gallery (Photo Picker)
- Camera photo capture
- File selection (SAF document picker)
- Attachment preview in chat input with remove functionality
- File storage in app-internal `files/attachments/` directory
- Room database table for attachment metadata
- Inline display of images, videos, and files in message bubbles
- Full-screen image viewer with zoom/pan
- Video playback via system Intent
- File opening via system Intent
- Base64 encoding for API transmission
- Provider-specific multimodal message formatting (OpenAI, Anthropic, Gemini)
- Unsupported attachment type warning (Snackbar)
- Cascade deletion of attachments when session is deleted

### Not Included (V1)

- Video recording from camera (photo only)
- Image editing or cropping before sending
- Drag-and-drop attachment
- Clipboard paste for images
- Audio recording or voice messages
- File preview within the app (e.g., inline PDF viewer)
- Cloud storage integration (Google Drive, etc.)
- Attachment forwarding or sharing
- AI-generated image display (text-to-image)
- Attachment search across sessions
- Compression settings UI

## Business Rules

### Functional Rules

1. Attachments are always copied to app-internal storage; the app never references external URIs after initial copy
2. Each attachment gets a UUID filename to avoid collisions: `{uuid}.{extension}`
3. Thumbnails for images and videos are generated at copy time and stored alongside the original
4. A message can have 0 or more attachments; there is no hard limit on count in V1
5. When a provider does not support an attachment type, the attachment is skipped with a Snackbar warning, and the message is still sent with supported attachments and text
6. If all attachments are unsupported but text is present, the message is sent as text-only
7. If all attachments are unsupported and no text is present, sending is blocked with an error message
8. Attachment files are stored per-session: `files/attachments/{sessionId}/`
9. Deleting a session cascades to delete the `files/attachments/{sessionId}/` directory

### Data Rules

- Maximum individual file size: 20MB (enforced at selection time)
- Supported image MIME types: `image/jpeg`, `image/png`, `image/gif`, `image/webp`
- Supported video MIME types: `video/mp4`, `video/webm`, `video/quicktime`
- File type: any MIME type (generic file handling)
- Thumbnails: JPEG format, max 256x256 pixels

### Permission Rules

- Camera: Requires `android.permission.CAMERA` (requested at runtime)
- Photo/Video picker: No permission required (Photo Picker API on Android 13+, READ_MEDIA_IMAGES/READ_MEDIA_VIDEO on older)
- File picker: No permission required (SAF handles access)

## Non-Functional Requirements

### Performance

- File copy to internal storage: < 2 seconds for files up to 20MB
- Thumbnail generation: < 500ms per image/video
- Base64 encoding: < 1 second for files up to 20MB
- Attachment preview rendering: < 100ms (use pre-generated thumbnails)
- Message bubble with attachments: Smooth scrolling at 60fps (use Coil for lazy image loading)

### Memory

- Thumbnails: ~256KB per image at 256x256 JPEG
- Base64 encoded file in memory: ~1.3x original file size (temporary, during API call)
- Image viewer: Single full-resolution image loaded at a time

### Compatibility

- Photo Picker: Android 13+ (native), 11-12 (backport via ActivityResultContracts)
- Camera capture: All supported Android versions
- SAF document picker: All supported Android versions

## Dependencies

### Depends On

- **FEAT-001 (Chat)**: Chat screen, message model, message repository
- **FEAT-016 (Chat Input Redesign)**: Current ChatInput layout that will be extended

### Depended On By

- None currently

### External Dependencies

- **Coil**: Image loading library (likely already in project, or add dependency)
- **Android Photo Picker API**: System component
- **Android Camera Intent**: System component
- **Android SAF (Storage Access Framework)**: System component

## Error Handling

### Error Scenarios

1. **File too large**
   - Cause: Selected file exceeds 20MB limit
   - Handling: Show Snackbar "File is too large (max 20MB)" and do not add to attachments

2. **File copy failure**
   - Cause: Insufficient storage space or I/O error
   - Handling: Show Snackbar "Failed to save attachment" and do not add to attachments

3. **Camera not available**
   - Cause: Device has no camera or camera permission denied
   - Handling: Show Snackbar "Camera not available" or launch permission request

4. **Thumbnail generation failure**
   - Cause: Corrupt image/video file
   - Handling: Use a generic placeholder icon; file is still attachable

5. **Base64 encoding failure**
   - Cause: File deleted between selection and send
   - Handling: Skip the attachment, warn user, send remaining content

6. **Provider API rejection**
   - Cause: File too large for provider limits, unsupported format
   - Handling: Report error in AI response (provider error message)

## Data Needs

### Attachment Data Model

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | String (UUID) | Yes | Unique attachment identifier |
| messageId | String | Yes | FK to messages table |
| type | Enum | Yes | IMAGE, VIDEO, FILE |
| fileName | String | Yes | Original file name |
| mimeType | String | Yes | MIME type (e.g., image/jpeg) |
| fileSize | Long | Yes | File size in bytes |
| filePath | String | Yes | Path to file in internal storage |
| thumbnailPath | String | No | Path to thumbnail (images/videos) |
| width | Int | No | Image/video width in pixels |
| height | Int | No | Image/video height in pixels |
| durationMs | Long | No | Video duration in milliseconds |
| createdAt | Long | Yes | Timestamp |

### Data Storage

- **Metadata**: Room database `attachments` table with FK to `messages`
- **Files**: App-internal storage at `files/attachments/{sessionId}/{uuid}.{ext}`
- **Thumbnails**: App-internal storage at `files/attachments/{sessionId}/thumbs/{uuid}_thumb.jpg`
- **Retention**: Files deleted when parent session is deleted

## Test Points

### Functional Tests

- Attachment button opens bottom sheet with 4 options
- Photo picker returns selected images
- Video picker returns selected videos
- Camera capture returns a photo
- File picker returns selected file
- Files are copied to correct internal storage path
- Thumbnails are generated for images and videos
- Attachment previews display correctly in chat input
- Remove button removes attachment from pending list
- Sending message stores attachment records in database
- Message bubbles display images inline
- Message bubbles display video thumbnails with play overlay
- Message bubbles display file cards
- Tapping image opens full-screen viewer
- Full-screen viewer supports zoom and pan
- Tapping video opens system player
- Tapping file opens system app
- Unsupported attachment type shows warning Snackbar
- Session deletion removes attachment files from storage
- API message includes correct multimodal format per provider

### Edge Cases

- Selecting a file that exceeds 20MB size limit
- Selecting a corrupt image that cannot generate a thumbnail
- Sending attachments with no text content
- Sending to a provider that supports no attachment types
- Low storage space on device
- Attachment file deleted from storage before viewing
- Very long filename display in preview and bubble
- Multiple large attachments in a single message
- Rapid attach-remove-attach cycles
- Camera permission denied

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
