# File Browsing

## Feature Information
- **Feature ID**: FEAT-025
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-025 (File Browsing)](../../rfc/features/RFC-025-file-browsing.md)

## User Story

**As** a user of OneClaw,
**I want to** browse, preview, and manage files that the AI agent has saved within the app,
**so that** I can review generated content, share files with other apps, and keep my file storage organized.

### Typical Scenarios

1. User asks the AI to "write a Python script that sorts a list" -- the AI uses `write_file` to save the script. The user opens the file browser to review, copy, or share the saved script.
2. User asks the AI to "save a summary of our conversation" -- the file is saved in the app. The user later opens the file browser to find and read the summary.
3. User notices the app's file storage is getting large. They open the file browser, identify unnecessary files, and delete them.
4. User wants to send an AI-generated file to a colleague via messaging app. They open the file browser, find the file, and use the share action.
5. User navigates through directories in the file browser to find files organized by category or date.

## Feature Description

### Overview

The File Browsing feature provides a built-in file manager for the app's internal storage. When the AI agent saves files using the `write_file` tool, those files are stored in a dedicated directory within the app's private storage (`filesDir/user_files/`). The file browser allows users to navigate this directory structure, preview file contents, delete unwanted files, and share files with other apps via the Android share sheet.

### Page Sections

#### 1. File List View

The main screen displays files and directories in the current path:
- **Breadcrumb navigation**: Shows the current path with tappable segments to navigate up
- **Directory entries**: Displayed with folder icons, tappable to navigate into
- **File entries**: Displayed with type-appropriate icons, showing name, size, and last modified date
- **Sorting**: Files sorted alphabetically by default, with directories listed first
- **Empty state**: "No files yet" message when the directory is empty

#### 2. File Preview

Tapping a file opens a preview screen:
- **Text files** (.txt, .md, .json, .py, .kt, .xml, .csv, .log, .yaml, .toml, .html, .css, .js, etc.): Displayed as scrollable text with monospace font
- **Image files** (.png, .jpg, .jpeg, .gif, .webp, .bmp): Displayed as a zoomable image
- **Other files**: Show file metadata (name, size, type, path, last modified) with a "Share" action but no inline preview

#### 3. File Actions

Available actions for each file:
- **Share**: Send the file to other apps via Android share sheet (available from both the list item and the preview screen)
- **Delete**: Remove the file with a confirmation dialog
- **Copy path**: Copy the file's internal path to clipboard (useful for referencing in prompts)

Available actions for directories:
- **Delete**: Remove the directory and all its contents (with confirmation dialog stating the item count)

### Navigation Entry Points

- **Settings/Tools section**: A "Files" menu item in the app settings or main navigation
- **Chat integration**: When the AI saves a file, the chat message includes a tappable file reference that opens the file preview directly

### User Interaction Flow

```
1. User opens the file browser from navigation menu
2. File browser shows the root directory (user_files/)
3. User taps a directory to navigate into it
4. User taps a file to open the preview
5. User can share, delete, or copy path from the preview screen
6. User navigates back via breadcrumbs or back button
```

## Acceptance Criteria

### TEST-025-01: Open File Browser
- **Given** the user is in the app
- **When** they navigate to the file browser
- **Then** the file browser opens showing the root directory contents

### TEST-025-02: Display Files and Directories
- **Given** there are files and directories in the user_files directory
- **When** the file browser loads
- **Then** directories are listed first with folder icons, followed by files with type icons, each showing name, size, and last modified date

### TEST-025-03: Navigate Into Directory
- **Given** the user is viewing a directory with subdirectories
- **When** they tap a subdirectory
- **Then** the browser navigates into that directory and the breadcrumb updates

### TEST-025-04: Breadcrumb Navigation
- **Given** the user has navigated into nested directories (e.g., root > scripts > python)
- **When** they tap "scripts" in the breadcrumb
- **Then** the browser navigates back to the "scripts" directory

### TEST-025-05: Preview Text File
- **Given** there is a .txt or .py file in the file browser
- **When** the user taps the file
- **Then** a preview screen opens showing the file content in monospace font

### TEST-025-06: Preview Image File
- **Given** there is a .png or .jpg file in the file browser
- **When** the user taps the file
- **Then** a preview screen opens showing the image with zoom/pan support

### TEST-025-07: Share File
- **Given** the user is viewing a file preview
- **When** they tap the Share action
- **Then** the Android share sheet opens with the file attached

### TEST-025-08: Delete File
- **Given** the user is viewing a file in the browser
- **When** they tap Delete and confirm
- **Then** the file is removed from storage and disappears from the list

### TEST-025-09: Delete Directory
- **Given** the user selects a directory for deletion
- **When** they confirm the deletion dialog (which shows the number of contained items)
- **Then** the directory and all its contents are removed

### TEST-025-10: Empty State
- **Given** there are no files in the user_files directory
- **When** the file browser opens
- **Then** an empty state message is displayed: "No files yet"

### TEST-025-11: Copy Path
- **Given** the user is viewing a file preview
- **When** they tap "Copy Path"
- **Then** the file's internal path is copied to the clipboard and a toast confirms

### TEST-025-12: Chat File Reference
- **Given** the AI agent saves a file using write_file during a chat
- **When** the save succeeds
- **Then** the chat message includes a tappable file chip that opens the file preview

## Non-Functional Requirements

- File list should load within 200ms for directories with up to 100 entries
- File preview should load within 500ms for text files up to 1MB
- Image preview should support pinch-to-zoom and pan gestures
- The file browser should handle gracefully if a file is deleted externally (e.g., by the system)
- Maximum supported file preview size: 1MB for text, 10MB for images

## Out of Scope

- Browsing files outside the app's private storage (device file system)
- Creating or editing files from the file browser (files are created by the AI only)
- File search or filtering
- File rename or move operations
- Multi-file selection for batch operations
- Cloud backup or sync of files
- File encryption

## Dependencies

### Depends On
- **FEAT-004 (Tool System)**: The write_file tool saves files to the browsable directory

### Depended On By
- None currently

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
