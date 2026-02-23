---
layout: default
title: OneClaw
---

[Home](./)

# Plugin & Skill Reference

Complete reference for all plugins and skills in OneClaw. Each entry documents what it does, every tool it exposes, parameters, credentials, and permissions.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Kotlin Native Plugins](#kotlin-native-plugins)
  - [WorkspacePlugin](#1-workspaceplugin)
  - [MemoryPlugin](#2-memoryplugin)
  - [SchedulerPlugin](#3-schedulerplugin)
  - [ConfigPlugin](#4-configplugin)
  - [SearchPlugin](#5-searchplugin)
  - [DelegateAgentPlugin](#6-delegateagentplugin)
  - [DeviceControlPlugin](#7-devicecontrolplugin)
  - [ActivateToolsPlugin](#8-activatetoolsplugin)
  - [SummarizationPlugin](#9-summarizationplugin)
  - [PluginManagementTool](#10-pluginmanagementtool)
  - [WebPlugin](#11-webplugin)
  - [LocationPlugin](#12-locationplugin)
  - [QrCodePlugin](#13-qrcodeplugin)
  - [SmsPhonePlugin](#14-smsphoneplugin)
  - [PdfToolsPlugin](#15-pdftoolsplugin)
  - [NotificationPlugin](#16-notificationplugin)
  - [MediaControlPlugin](#17-mediacontrolplugin)
  - [CameraPlugin](#18-cameraplugin)
  - [VoiceMemoPlugin](#19-voicememoplugin)
- [JavaScript Plugins](#javascript-plugins)
  - [Google Calendar](#20-google-calendar)
  - [Google Contacts](#21-google-contacts)
  - [Google Docs](#22-google-docs)
  - [Google Drive](#23-google-drive)
  - [Google Forms](#24-google-forms)
  - [Google Gmail](#25-google-gmail)
  - [Google Gmail Settings](#26-google-gmail-settings)
  - [Google Places](#27-google-places)
  - [Google Sheets](#28-google-sheets)
  - [Google Slides](#29-google-slides)
  - [Google Tasks](#30-google-tasks)
  - [Image Generation](#31-image-generation)
  - [Notion](#32-notion)
  - [Smart Home](#33-smart-home)
  - [Web Fetch (HTTP)](#34-web-fetch)
  - [Time](#35-time)
- [Skills](#skills)
  - [Weather](#skill-weather)
  - [Summarize URL](#skill-summarize-url)
  - [Morning Briefing](#skill-morning-briefing)
  - [Translate](#skill-translate)
  - [Code Review](#skill-code-review)
  - [Explain](#skill-explain)
  - [About OneClaw](#skill-about-oneclaw)
  - [Create Plugin](#skill-create-plugin)
- [Tool Count Summary](#tool-count-summary)

---

## Architecture Overview

### Plugin Types

OneClaw has two plugin types:

**Kotlin native plugins** are registered in `PluginCoordinator.registerBuiltInPlugins()`. Each implements the `Plugin` interface with `onLoad()`, `execute()`, `onUnload()` methods. They have full access to Android APIs and are compiled into the app. Metadata (plugin ID, name, version, tools, category) is defined in companion `PluginMetadata` objects.

**JavaScript plugins** are loaded from `plugin-manager/src/main/assets/plugins/` (built-in) or `workspace/plugins/` (user-created). Each is a directory containing `plugin.json` (metadata, tools, credentials) and `plugin.js` (implementation). They run inside a QuickJS sandbox with host bindings exposed as the `oneclaw.*` namespace: `oneclaw.http`, `oneclaw.fs`, `oneclaw.credentials`, `oneclaw.google`, `oneclaw.notifications`, `oneclaw.env`, `oneclaw.log`.

### Two-Tier Tool Activation

Core tools (category `"core"`) are always visible to the LLM. On-demand categories (gmail, calendar, web, location, phone, notifications, media_control, pdf, camera, voice_memo, image_gen, notion, smart_home, etc.) are hidden until the LLM calls the `activate_tools` meta-tool mid-conversation. Once activated, a category stays active for the rest of the conversation. Agent profiles can further restrict tools via `allowedTools`.

### Tool Execution Pipeline

1. LLM returns a tool call with name + JSON arguments
2. `ReActLoop` passes to `ToolExecutor`
3. `ToolExecutor` looks up the tool in `ToolRegistry`
4. `ToolRegistry.getTool()` returns the `LoadedPlugin` instance
5. `Plugin.execute(toolName, arguments)` is called
6. Returns `ToolResult.Success(output)` or `ToolResult.Failure(error)`
7. Result sent back to LLM as tool result message

### Credential Flow

- **Kotlin plugins**: Use `PluginContext.getProviderCredential()` which reads from `CredentialVault` (EncryptedSharedPreferences)
- **JS plugins**: Use `oneclaw.credentials.get("key_name")` or `oneclaw.credentials.getProviderKey("Provider")` which reads from `CredentialVault`
- **Google plugins**: Use `oneclaw.google.getAccessToken()` which goes through `GoogleAuthManager` for OAuth token refresh

---

## Kotlin Native Plugins

### 1. WorkspacePlugin

| Field | Value |
|---|---|
| Plugin ID | `workspace` |
| Module | `lib-workspace` |
| Category | `core` (always visible) |
| Permissions | None (uses app-internal storage) |

Workspace root: `context.filesDir/workspace/` (Android internal storage). All paths are relative to this root. Absolute paths and path traversal (`../`) are rejected by `resolveSafePath()`.

#### Tools

**`read_file`** -- Read file contents with line-based pagination.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `path` | string | yes | -- | Relative path within workspace |
| `offset` | integer | no | 1 | 1-indexed starting line |
| `limit` | integer | no | 500 | Max lines to return (max 500) |

Returns file contents. Truncates if file exceeds 20KB per read. Reports total line count.

**`write_file`** -- Write or overwrite file contents.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `path` | string | yes | -- | Relative path |
| `content` | string | yes | -- | File contents |

Creates parent directories automatically.

**`edit_file`** -- Replace text with fuzzy matching fallback.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `path` | string | yes | -- | Relative path |
| `old_text` | string | yes | -- | Text to find |
| `new_text` | string | yes | -- | Replacement text |

Attempts exact match first, falls back to fuzzy matching. Returns unified diff snippet showing the change.

**`list_files`** -- List directory contents.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `path` | string | no | root | Relative directory path |
| `limit` | integer | no | 200 | Max entries (1-500) |

Shows directory indicator `/` suffix and file sizes.

**`exec`** -- Execute shell commands.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `command` | string | yes | -- | Shell command |
| `timeout` | integer | no | 30 | Timeout in seconds (1-120) |
| `cwd` | string | no | workspace root | Working directory |

Output tail-truncated to 16KB. Available utilities: ls, cat, cp, mv, rm, mkdir, chmod, grep, sed, awk, sort, uniq, wc, head, tail, cut, tr, diff, find, xargs, tee, date, env, sleep, tar, gzip.

**`javascript_eval`** -- Evaluate JavaScript with QuickJS engine.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `code` | string | yes | -- | JavaScript code to evaluate |

Returns last expression value. Auto-JSON-stringifies objects. No filesystem, network, or system access within the eval sandbox.

---

### 2. MemoryPlugin

| Field | Value |
|---|---|
| Plugin ID | `memory` |
| Module | `lib-workspace` |
| Category | `core` |
| Permissions | None |

#### Tools

**`search_memory`** -- Full-text search across workspace memory files.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `query` | string | yes (2+ chars) | -- | Search query |

Searches `MEMORY.md` (long-term) and `memory/*.md` (daily/topic files). Returns up to 20 results with file paths, line numbers, and context snippets. Case-insensitive substring matching.

---

### 3. SchedulerPlugin

| Field | Value |
|---|---|
| Plugin ID | `scheduler` |
| Module | `lib-scheduler` |
| Category | `core` (always visible) |
| Permissions | SCHEDULE_EXACT_ALARM, FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED, WAKE_LOCK |

#### Tools

**`schedule_task`** -- Schedule one-time or recurring agent tasks.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `title` | string | no | -- | Human-readable title |
| `instruction` | string | yes | -- | Natural language task instruction |
| `schedule_type` | enum | yes | -- | `one_time` or `recurring` |
| `execute_at` | string | conditional | -- | ISO 8601 datetime for one-time (e.g., `2026-02-12T18:00:00`) |
| `interval_minutes` | integer | conditional | -- | Interval for recurring (min 15) |
| `cron_expression` | string | conditional | -- | Unix cron for recurring (e.g., `0 9 * * MON`) |
| `max_executions` | integer | no | -- | Limit total runs |
| `require_network` | boolean | no | false | Only run with network |
| `require_charging` | boolean | no | false | Only run while charging |

Uses AlarmManager for exact timing and WorkManager for recurring tasks. Minimum 15-minute interval due to Android battery optimization.

**`list_scheduled_tasks`** -- List all scheduled tasks.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `include_disabled` | boolean | no | false | Include disabled tasks |

Returns: ID, title, instruction, schedule, status, execution count.

**`run_scheduled_task`** -- Run a scheduled task immediately in the background.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `task_id` | string | yes | -- | Task ID to run |

**`cancel_scheduled_task`** -- Cancel (disable) a scheduled task. The task remains in the list but will no longer execute.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `task_id` | string | yes | -- | Task ID to cancel |

**`update_scheduled_task`** -- Update an existing scheduled task. Only provided fields are changed; omitted fields keep their current values.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `task_id` | string | yes | -- | Task ID to update |
| `title` | string | no | -- | New title |
| `instruction` | string | no | -- | New instruction |
| `schedule_type` | enum | no | -- | `one_time` or `recurring` |
| `execute_at` | string | no | -- | ISO 8601 datetime for one-time |
| `interval_minutes` | integer | no | -- | Interval for recurring (min 15) |
| `cron_expression` | string | no | -- | Unix cron expression |
| `max_executions` | integer | no | -- | Limit total runs |
| `enabled` | boolean | no | -- | Enable or disable the task |

**`delete_scheduled_task`** -- Permanently delete a scheduled task and all its execution history.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `task_id` | string | yes | -- | Task ID to delete |

---

### 4. ConfigPlugin

| Field | Value |
|---|---|
| Plugin ID | `config` |
| Module | `app` |
| Category | `core` |
| Permissions | None |

#### Tools

**`get_app_config`** -- Retrieve all current app settings.

No parameters. Returns all settings with current values, descriptions, and type constraints. Uses `ConfigRegistry` with `ConfigContributor` pattern -- plugins and skills can register their own settings.

**`set_app_config`** -- Update one or more settings.

Parameters are arbitrary key-value pairs matching registered config keys. Validates type (string, integer with min/max, boolean, enum).

---

### 5. SearchPlugin

| Field | Value |
|---|---|
| Plugin ID | `search` |
| Module | `app` |
| Category | `core` |
| Permissions | None |

#### Tools

**`search_conversations`** -- Search conversation history.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `query` | string | yes (2+ chars) | -- | Search query |
| `limit` | integer | no | 20 | Max results (1-100) |
| `time_from` | string | no | -- | ISO 8601 start time |
| `time_to` | string | no | -- | ISO 8601 end time |

Returns up to 20 conversations with 5 messages each (200 char snippets). Searches both titles and message content via Room database queries.

---

### 6. DelegateAgentPlugin

| Field | Value |
|---|---|
| Plugin ID | `delegate_agent` |
| Module | `app` |
| Category | `core` |
| Permissions | None |

#### Tools

**`delegate_to_agent`** -- Delegate task to a specialized agent profile.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `agent` | enum | yes | -- | Agent profile name (non-main profiles only) |
| `task` | string | yes | -- | Full task description |

Creates an isolated sub-conversation with a temporary conversation ID. Builds a filtered tool registry excluding `delegate_to_agent` itself (prevents recursion). Uses the target profile's `allowedTools` whitelist. Builds system prompt with skills and memory context. Timeout: 10 minutes. Max iterations capped at 50. Returns the sub-agent's final response text.

---

### 7. DeviceControlPlugin

| Field | Value |
|---|---|
| Plugin ID | `device_control` |
| Module | `lib-device-control` |
| Category | `device_control` (on-demand) |
| Permissions | Requires Accessibility Service enabled |

#### Tools

**`observe_screen`** -- Get structured text representation of the current UI.

No parameters. Returns a tree of UI elements with indices, class names, text content, and flags (clickable, scrollable, editable). Uses Android Accessibility API to traverse the window's node tree.

**`tap`** -- Tap a screen element.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `text` | string | no | -- | Match by visible text |
| `content_description` | string | no | -- | Match by content description |
| `resource_id` | string | no | -- | Match by resource ID |
| `x` | integer | no | -- | Tap at x coordinate |
| `y` | integer | no | -- | Tap at y coordinate |

At least one targeting parameter required. Uses accessibility `performAction(ACTION_CLICK)`.

**`type_text`** -- Type text into an input field.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `text` | string | yes | -- | Text to type |
| `target_hint` | string | no | -- | Hint text of target field |

Finds editable fields, optionally matching by hint text.

**`swipe`** -- Swipe/scroll the screen.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `direction` | enum | yes | -- | `up`, `down`, `left`, `right` |

Uses accessibility scroll action if available, falls back to gesture dispatch.

**`press_back`** -- Press system back button. No parameters.

**`press_home`** -- Press system home button. No parameters.

**`launch_app`** -- Launch an app by package name.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `package_name` | string | yes | -- | Android package name |

Common packages: `com.android.settings`, `com.android.chrome`, `com.google.android.gm`.

---

### 8. ActivateToolsPlugin

| Field | Value |
|---|---|
| Plugin ID | `activate_tools` |
| Module | `core-agent` |
| Category | `core` |
| Permissions | None |

#### Tools

**`activate_tools`** -- Activate on-demand tool categories for the current conversation.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `categories` | array[string] | yes | -- | Categories to activate (enum generated from registry) |

The `categories` parameter has a dynamically generated enum from `toolRegistry.getOnDemandCategories()`. Once activated, tools remain available for the rest of the conversation. Returns list of newly available tools. Invalid categories are ignored with a warning.

---

### 9. SummarizationPlugin

| Field | Value |
|---|---|
| Plugin ID | `summarization` |
| Module | `core-agent` |
| Category | `core` |
| Permissions | None |

Note: This plugin is dynamically created per conversation by `AgentCoordinator`, not permanently registered like other plugins.

#### Tools

**`summarize_conversation`** -- Summarize and compact the conversation context.

No parameters. Triggers the summarization pipeline: splits history into old (summarized) and recent (kept) messages. Summary stored as a meta message prepended to the system prompt on subsequent turns. Also triggers `onBeforeSummarize` callback to flush important context to workspace memory.

Auto-triggers when prompt tokens exceed 80% of the context window. Can also be invoked manually via `/summarize` slash command.

---

### 10. PluginManagementTool

| Field | Value |
|---|---|
| Plugin ID | `plugin_management` |
| Module | `plugin-manager` |
| Category | `core` (always visible) |
| Permissions | None |

#### Tools

**`install_plugin`** -- Install or update a custom JavaScript plugin at runtime.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `source` | string | yes | -- | Complete plugin source (JSON metadata + JS code) |

Parses the source, validates plugin.json schema, writes files to `workspace/plugins/`, and registers the plugin in the tool registry.

**`remove_plugin`** -- Remove a user-installed plugin.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `plugin_id` | string | yes | -- | ID of the plugin to remove |

Only user-installed plugins can be removed, not built-in ones.

**`list_user_plugins`** -- List all user-installed plugins.

No parameters. Returns plugin IDs, names, versions, and tool lists for all plugins installed in `workspace/plugins/`.

---

### 11. WebPlugin

| Field | Value |
|---|---|
| Plugin ID | `web` |
| Module | `lib-web` |
| Category | `web` (on-demand) |
| Permissions | None (uses INTERNET implicitly) |
| Credentials | `search_provider` (tavily or brave), `api_key` |

#### Tools

**`web_search`** -- Search the internet.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `query` | string | yes | -- | Search query |
| `max_results` | integer | no | 5 | Max results (1-10) |

Uses `WebSearchProviderFactory` to select tavily or brave backend. Returns results with titles, URLs, snippets.

**`web_fetch`** -- Extract clean text from a web page.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `url` | string | yes | -- | URL to fetch |
| `selector` | string | no | -- | CSS selector to narrow extraction |

Returns page title + stripped HTML text. Auto-detects main content area. Truncated at 15,000 characters.

---

### 12. LocationPlugin

| Field | Value |
|---|---|
| Plugin ID | `location` |
| Module | `lib-location` |
| Category | `location` (on-demand) |
| Permissions | ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION |
| Credentials | Google Maps API key (for `search_nearby`) |

#### Tools

**`get_location`** -- Get device GPS location.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `accuracy` | enum | no | `balanced` | `high` (GPS), `balanced` (WiFi/cell), `low` (cell only) |

Returns latitude, longitude, accuracy (meters), altitude (if available), reverse-geocoded address. Uses Google Play Services `FusedLocationProviderClient`.

**`search_nearby`** -- Search nearby places via Google Places API.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `query` | string | yes | -- | Search query (e.g., "coffee shops") |
| `latitude` | number | no | current | Latitude (auto-detected if omitted) |
| `longitude` | number | no | current | Longitude (auto-detected if omitted) |
| `radius_meters` | integer | no | 5000 | Search radius (100-50000) |
| `max_results` | integer | no | 10 | Max results (1-20) |

Returns: place name, address, rating, review count, phone, website, coordinates. Requires Google Maps API key.

**`get_directions_url`** -- Generate a Google Maps directions URL.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `destination` | string | yes | -- | Destination address or place |
| `origin` | string | no | current location | Starting point |
| `travel_mode` | enum | no | `driving` | `driving`, `walking`, `bicycling`, `transit` |

Returns a Google Maps URL. No API key or permissions needed.

---

### 13. QrCodePlugin

| Field | Value |
|---|---|
| Plugin ID | `qrcode` |
| Module | `lib-qrcode` |
| Category | `core` |
| Permissions | None |

#### Tools

**`qr_scan`** -- Scan and decode QR codes/barcodes from an image.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `image_path` | string | yes | -- | Relative path in workspace |

Returns decoded values and format for each barcode found. Supports: QR Code, EAN-13, UPC-A, Code 128, Data Matrix, PDF417, Aztec, Codabar, Code 39, Code 93, EAN-8, ITF, UPC-E. Auto-detects barcode type (URL, Email, Phone, SMS, WiFi, Geo, Contact, Calendar Event, ISBN, Product).

Uses ML Kit Barcode Scanner API.

**`qr_generate`** -- Generate a QR code image.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `content` | string | yes | -- | Content to encode |
| `size` | integer | no | 512 | Image size in pixels (64-2048) |
| `output_path` | string | no | `images/qr-{timestamp}.png` | Output path |

Saves PNG to workspace. Uses ZXing `QRCodeWriter`.

---

### 14. SmsPhonePlugin

| Field | Value |
|---|---|
| Plugin ID | `sms-phone` |
| Module | `app` |
| Category | `phone` (on-demand) |
| Permissions | SEND_SMS, READ_SMS, READ_CALL_LOG |

#### Tools

**`sms_send`** -- Send an SMS message.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `to` | string | yes | -- | Phone number |
| `message` | string | yes | -- | Message text |

Auto-splits long messages into multipart SMS. Uses `SmsManager` (API 31+).

**`sms_list`** -- List SMS messages from a folder.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `folder` | enum | no | `inbox` | `inbox`, `sent`, `all` |
| `limit` | integer | no | 20 | Max messages (1-50) |

Returns recent messages sorted by date descending.

**`sms_search`** -- Search SMS by text content.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `query` | string | yes | -- | Search query |
| `limit` | integer | no | 20 | Max results (1-50) |

Case-insensitive LIKE search on message body.

**`sms_get_thread`** -- Get conversation thread with a specific contact.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `address` | string | yes | -- | Phone number or contact |
| `limit` | integer | no | 20 | Max messages (1-50) |

**`phone_dial`** -- Open dialer with a number pre-filled.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `number` | string | yes | -- | Phone number |

Opens the Android dialer but does NOT initiate the call -- user must press the call button. Uses `Intent.ACTION_DIAL`.

**`phone_call_log`** -- View call history.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `limit` | integer | no | 20 | Max entries (1-50) |
| `type` | enum | no | `all` | `all`, `incoming`, `outgoing`, `missed` |

Returns: number, contact name, call type, date, duration.

---

### 15. PdfToolsPlugin

| Field | Value |
|---|---|
| Plugin ID | `pdf-tools` |
| Module | `lib-pdf` |
| Category | `pdf` (on-demand) |
| Permissions | None |

#### Tools

**`pdf_info`** -- Get PDF metadata.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `path` | string | yes | -- | Relative path in workspace |

Returns: page count, file size, title, author, subject, creator, producer, creation date. Uses PDFBox.

**`pdf_extract_text`** -- Extract text with optional page range.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `path` | string | yes | -- | Relative path |
| `pages` | string | no | all | Page range (e.g., `1-5`, `3`, `1,3,5-7`) |
| `max_chars` | integer | no | 50000 | Character limit |

Handles complex page range specs (commas, dashes). Notes when PDFs are scanned (no extractable text).

**`pdf_render_page`** -- Render a PDF page to PNG.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `path` | string | yes | -- | Relative path |
| `page` | integer | yes | -- | 1-based page number |
| `dpi` | integer | no | 150 | Resolution (72-300) |

Saves to `workspace/pdf-renders/{name}-page{N}.png`. Uses Android `PdfRenderer` (hardware-accelerated). Useful for scanned PDFs or complex layouts where text extraction fails.

---

### 16. NotificationPlugin

| Field | Value |
|---|---|
| Plugin ID | `notifications` |
| Module | `lib-notification-media` |
| Category | `notifications` (on-demand) |
| Permissions | Requires NotificationListenerService enabled |

#### Tools

**`list_notifications`** -- List active notifications.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `package_filter` | string | no | -- | Substring filter on package name |
| `limit` | integer | no | 50 | Max notifications |

Returns: notification key, package, title, text, timestamp, ongoing status.

**`get_notification_details`** -- Get full notification info.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `key` | string | yes | -- | Notification key (from `list_notifications`) |

Returns all extras, actions, category, and text fields (title, text, bigText, subText, infoText, summaryText).

**`dismiss_notification`** -- Dismiss a notification.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `key` | string | yes | -- | Notification key |

Some notifications (ongoing/foreground service) cannot be dismissed.

---

### 17. MediaControlPlugin

| Field | Value |
|---|---|
| Plugin ID | `media_control` |
| Module | `lib-notification-media` |
| Category | `media_control` (on-demand) |
| Permissions | Requires NotificationListenerService enabled |

#### Tools

**`get_media_info`** -- Get current playback info.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `session_id` | string | no | -- | Package name of media app (targets first session if omitted) |

Returns: package, title, artist, album, duration, state (playing/paused/stopped/buffering/connecting/error), position.

**`media_play_pause`** -- Toggle play/pause.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `session_id` | string | no | -- | Target media session |

**`media_skip_next`** -- Skip to next track. Same parameters.

**`media_skip_previous`** -- Skip to previous track. Same parameters.

**`media_stop`** -- Stop playback. Same parameters.

Uses Android `MediaController` and `MediaSession` APIs to control any media app (Spotify, YouTube Music, podcasts, etc.).

---

### 18. CameraPlugin

| Field | Value |
|---|---|
| Plugin ID | `camera` |
| Module | `app` |
| Category | `camera` (on-demand) |
| Permissions | CAMERA |

#### Tools

**`take_photo`** -- Capture a photo and save to workspace.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `camera` | enum | no | `back` | `back` or `front` |
| `filename` | string | no | timestamp | Custom filename (no extension) |

Saves to `workspace/captures/{filename}.jpg`. Uses `HeadlessCameraCapture` -- a custom Camera2 API wrapper that captures without showing a preview UI.

**`list_cameras`** -- List available cameras.

No parameters. Returns camera facing direction and description.

---

### 19. VoiceMemoPlugin

| Field | Value |
|---|---|
| Plugin ID | `voice_memo` |
| Module | `app` |
| Category | `voice_memo` (on-demand) |
| Permissions | RECORD_AUDIO |
| Credentials | OpenAI API key (for transcription) |

#### Tools

**`record_audio`** -- Record audio from microphone.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `duration_seconds` | integer | no | 10 | Recording duration (1-300) |
| `filename` | string | no | timestamp | Custom filename (no extension) |

Saves to `workspace/recordings/{filename}.m4a`. Format: M4A/AAC, 44.1kHz, 128kbps. Uses Android `MediaRecorder`.

**`transcribe_audio`** -- Transcribe audio via OpenAI Whisper API.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `path` | string | yes | -- | Relative path in workspace |
| `language` | string | no | auto-detect | ISO 639-1 code |

Supported formats: m4a, mp3, wav, webm, mp4, mpeg, mpga, oga, ogg. Uses OpenAI Whisper-1 model via HTTP. Requires OpenAI API key.

**`list_recordings`** -- List audio files in workspace.

No parameters. Returns: filename, size (KB), modification date.

---

## JavaScript Plugins

All JS plugins run in QuickJS sandbox. Google Workspace plugins authenticate via `oneclaw.google.getAccessToken()` (OAuth). Third-party plugins use `oneclaw.credentials.get()` or `oneclaw.credentials.getProviderKey()`.

### 20. Google Calendar

| Field | Value |
|---|---|
| Plugin ID | `google-calendar` |
| Version | 2.0.0 |
| Category | `calendar` |
| API | Google Calendar API v3 |
| Auth | Google OAuth (Calendar scope) |

#### Tools (11)

| Tool | Description | Key Parameters |
|---|---|---|
| `calendar_list_events` | List events in time range | `time_min`, `time_max`, `calendar_id`, `query`, `max_results` |
| `calendar_get_event` | Get single event details | `event_id`, `calendar_id` |
| `calendar_create_event` | Create new event | `summary`, `start`, `end`, `description`, `location`, `attendees` |
| `calendar_update_event` | Update existing event | `event_id`, fields to update |
| `calendar_delete_event` | Delete event | `event_id`, `calendar_id` |
| `calendar_list_calendars` | List all calendars | -- |
| `calendar_freebusy` | Check free/busy status | `time_min`, `time_max`, `calendars` |
| `calendar_respond` | Set attendance response | `event_id`, `status` (accepted/declined/tentative) |
| `calendar_list_colors` | List available event colors | -- |
| `calendar_quick_add` | Natural language event creation | `text` (e.g., "Lunch tomorrow at noon") |
| `calendar_instances` | List instances of recurring event | `event_id`, `time_min`, `time_max` |

---

### 21. Google Contacts

| Field | Value |
|---|---|
| Plugin ID | `google-contacts` |
| Version | 1.0.0 |
| Category | `contacts` |
| API | Google People API v1 |
| Auth | Google OAuth (Contacts scope) |

#### Tools (7)

| Tool | Description |
|---|---|
| `contacts_search` | Full-text search by name, email, phone |
| `contacts_list` | Paginated list with nextPageToken |
| `contacts_get` | Detailed contact info (addresses, birthday, URLs) |
| `contacts_create` | Create new contact |
| `contacts_update` | Update contact (fetches etag first for optimistic locking) |
| `contacts_delete` | Delete contact |
| `contacts_directory` | Google Workspace domain directory (403 for consumer accounts) |

---

### 22. Google Docs

| Field | Value |
|---|---|
| Plugin ID | `google-docs` |
| Version | 1.0.0 |
| Category | `drive` |
| API | Google Docs API v1 |
| Auth | Google OAuth (Docs scope) |

#### Tools (6)

| Tool | Description |
|---|---|
| `docs_get` | Full document JSON structure |
| `docs_create` | Create new empty document |
| `docs_get_text` | Extract plain text content (walks paragraph structure) |
| `docs_insert` | Insert text at index via batchUpdate |
| `docs_delete_range` | Remove content between indices |
| `docs_find_replace` | Replace all occurrences (optional case-insensitive) |

---

### 23. Google Drive

| Field | Value |
|---|---|
| Plugin ID | `google-drive` |
| Version | 1.0.0 |
| Category | `drive` |
| API | Google Drive API v3 |
| Auth | Google OAuth (Drive scope) |

#### Tools (13)

| Tool | Description |
|---|---|
| `drive_list` | List files with query syntax, pagination |
| `drive_search` | Full-text search |
| `drive_get` | File metadata |
| `drive_mkdir` | Create folder |
| `drive_copy` | Copy file with optional rename |
| `drive_rename` | Rename file |
| `drive_move` | Move file to different folder |
| `drive_delete` | Move to trash (soft delete) |
| `drive_share` | Create permissions (user, group, domain) |
| `drive_permissions` | List current permissions |
| `drive_download` | Download file to workspace |
| `drive_upload` | Multipart upload |
| `drive_export` | Export Google format to standard format (PDF, DOCX, CSV, etc.) |

---

### 24. Google Forms

| Field | Value |
|---|---|
| Plugin ID | `google-forms` |
| Version | 1.0.0 |
| Category | `drive` |
| API | Google Forms API v1 |
| Auth | Google OAuth (Forms scope) |

#### Tools (3)

| Tool | Description |
|---|---|
| `forms_get` | Form structure with all questions (parses choice, text, scale, date, time) |
| `forms_list_responses` | Response summaries with counts |
| `forms_get_response` | Full response with all answers |

---

### 25. Google Gmail

| Field | Value |
|---|---|
| Plugin ID | `google-gmail` |
| Version | 2.0.0 |
| Category | `gmail` |
| API | Gmail API v1 |
| Auth | Google OAuth (Gmail scope) |

#### Tools (18)

| Tool | Description |
|---|---|
| `gmail_search` | Query-based search with Gmail query syntax |
| `gmail_get_message` | Full message with headers, body, attachments |
| `gmail_send` | Compose and send email (RFC 2822 format, Base64-URL encoded) |
| `gmail_reply` | Reply maintaining thread |
| `gmail_delete` | Move to trash (batch support) |
| `gmail_list_labels` | All labels with types |
| `gmail_get_thread` | Full thread with all messages |
| `gmail_modify_labels` | Add/remove labels from message or thread |
| `gmail_create_label` | Create nested labels with visibility options |
| `gmail_delete_label` | Delete label |
| `gmail_list_drafts` | List all drafts |
| `gmail_get_draft` | Get draft content |
| `gmail_create_draft` | Create draft |
| `gmail_send_draft` | Send existing draft |
| `gmail_delete_draft` | Delete draft |
| `gmail_get_attachment` | Download attachment to workspace |
| `gmail_history` | State changes since history ID |
| `gmail_batch_modify` | Batch label operations (up to 1000 messages) |

---

### 26. Google Gmail Settings

| Field | Value |
|---|---|
| Plugin ID | `google-gmail-settings` |
| Version | 1.0.0 |
| Category | `gmail` |
| API | Gmail API v1 (Settings endpoints) |
| Auth | Google OAuth (Gmail scope) |

#### Tools (11)

| Tool | Description |
|---|---|
| `gmail_list_filters` | List filter criteria and actions |
| `gmail_create_filter` | Create filter with AND-ed criteria |
| `gmail_delete_filter` | Remove filter by ID |
| `gmail_get_vacation` | Get out-of-office settings |
| `gmail_set_vacation` | Enable/disable auto-reply with restrictions |
| `gmail_list_forwarding` | Forwarding addresses and verification status |
| `gmail_add_forwarding` | Add forwarding address (triggers verification email) |
| `gmail_get_auto_forward` | Auto-forward settings |
| `gmail_set_auto_forward` | Enable/disable with disposition options |
| `gmail_list_send_as` | Send-as aliases with signatures |
| `gmail_list_delegates` | Granted delegates |

---

### 27. Google Places

| Field | Value |
|---|---|
| Plugin ID | `google-places` |
| Version | 1.0.0 |
| Category | `location` |
| API | Google Places API (New) |
| Auth | Google OAuth |

#### Tools (3)

| Tool | Description | Key Parameters |
|---|---|---|
| `places_search` | Search for places with location biasing and filters | `query`, `latitude`, `longitude`, `radius_meters`, `type`, `open_now`, `min_rating`, `price_levels`, `max_results` |
| `places_details` | Get detailed info about a place by ID | `place_id` |
| `places_resolve` | Resolve a location name/address to place IDs and coordinates | `location_text`, `limit` |

---

### 28. Google Sheets

| Field | Value |
|---|---|
| Plugin ID | `google-sheets` |
| Version | 1.0.0 |
| Category | `drive` |
| API | Google Sheets API v4 |
| Auth | Google OAuth (Sheets scope) |

#### Tools (7)

| Tool | Description |
|---|---|
| `sheets_get_values` | Read 2D array from A1 range |
| `sheets_update_values` | Overwrite values (USER_ENTERED for formula parsing) |
| `sheets_append` | Add rows after last data row |
| `sheets_clear` | Remove values (preserves formatting) |
| `sheets_metadata` | Spreadsheet title and sheet properties |
| `sheets_create` | Create new spreadsheet |
| `sheets_batch_update` | Structural changes (add/delete sheets, formatting, sorting) |

---

### 29. Google Slides

| Field | Value |
|---|---|
| Plugin ID | `google-slides` |
| Version | 1.0.0 |
| Category | `drive` |
| API | Google Slides API v1 |
| Auth | Google OAuth (Slides scope) |

#### Tools (6)

| Tool | Description |
|---|---|
| `slides_get` | Presentation with slide objectIds |
| `slides_create` | Create new presentation |
| `slides_list_slides` | List slides with indices and types |
| `slides_get_slide_text` | Extract text from slide by index |
| `slides_add_slide` | Add slide with layout (BLANK, TITLE, TITLE_AND_BODY, etc.) |
| `slides_delete_slide` | Remove slide by objectId |

---

### 30. Google Tasks

| Field | Value |
|---|---|
| Plugin ID | `google-tasks` |
| Version | 1.0.0 |
| Category | `tasks` |
| API | Google Tasks API v1 |
| Auth | Google OAuth (Tasks scope) |

#### Tools (7)

| Tool | Description |
|---|---|
| `tasks_list_tasklists` | List all task lists |
| `tasks_list_tasks` | List tasks with show_completed/show_hidden filters |
| `tasks_get_task` | Full task details with links and timestamps |
| `tasks_create` | Create task with optional notes, due date, parent (subtask) |
| `tasks_update` | Partial update (fetches existing data first) |
| `tasks_complete` | Mark task as completed |
| `tasks_delete` | Delete task |

---

### 31. Image Generation

| Field | Value |
|---|---|
| Plugin ID | `image-gen` |
| Version | 1.0.0 |
| Category | `image_gen` |
| API | OpenAI DALL-E API |
| Auth | OpenAI API key |

#### Tools (1)

**`generate_image`** -- Generate an image from a text prompt.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `prompt` | string | yes | -- | Image description |
| `size` | enum | no | `1024x1024` | Image dimensions |
| `quality` | enum | no | `standard` | `standard` or `hd` |
| `model` | enum | no | `dall-e-3` | `dall-e-2` or `dall-e-3` |
| `style` | enum | no | `vivid` | `vivid` or `natural` |

Saves to `workspace/images/{timestamp}-{sanitized-prompt}.png`. Returns file path, model used, and revised prompt (if the API modified it).

---

### 32. Notion

| Field | Value |
|---|---|
| Plugin ID | `notion` |
| Version | 1.0.0 |
| Category | `notion` |
| API | Notion API v1 (Version: 2022-06-28) |
| Auth | Integration Token |

#### Tools (7)

| Tool | Description | Key Parameters |
|---|---|---|
| `notion_search` | Full-text search with type filtering | `query`, `filter_type` (page/database) |
| `notion_get_page` | Page properties and metadata | `page_id` |
| `notion_get_page_content` | Recursively fetch blocks with pagination | `page_id`, `max_blocks` |
| `notion_create_page` | Create page under database or parent page | `parent_id`, `title`, `content`, `properties` |
| `notion_update_page` | Update properties and archived status | `page_id`, `properties`, `archived` |
| `notion_query_database` | Query with filter and sort | `database_id`, `filter`, `sorts` |
| `notion_add_blocks` | Append blocks to page (markdown syntax) | `page_id`, `content` |

Supports 30+ Notion block types and rich property types (title, rich_text, number, select, multi_select, date, checkbox, url, email, phone, status, people, relation, formula, rollup). Content uses markdown syntax: `# heading`, `- bullets`, `1. numbered`, `> quotes`, etc.

Requires creating an integration at [notion.so/my-integrations](https://notion.so/my-integrations) and sharing pages/databases with it.

---

### 33. Smart Home

| Field | Value |
|---|---|
| Plugin ID | `smart-home` |
| Version | 1.0.0 |
| Category | `smart_home` |
| API | Home Assistant REST API |
| Auth | Long-Lived Access Token |
| Setup | `base_url`: Home Assistant instance URL |

#### Tools (4)

| Tool | Description | Key Parameters |
|---|---|---|
| `ha_list_entities` | List entities, filtered by domain | `domain` (light, switch, sensor, climate, cover, media_player, fan, lock) |
| `ha_get_state` | Full state with attributes and timestamps | `entity_id` |
| `ha_call_service` | Call service on entity | `domain`, `service`, `entity_id`, `service_data` |
| `ha_get_history` | State changes over time (max 7 days) | `entity_id`, `hours` (max 168) |

Common services: `turn_on`, `turn_off`, `toggle`, `set_temperature`. Service data supports brightness, color names, temperature values, etc.

---

### 34. Web Fetch

| Field | Value |
|---|---|
| Plugin ID | `web-fetch` |
| Version | 1.0.0 |
| Category | none (always available) |
| Auth | None (per-request auth via headers) |

#### Tools (3)

| Tool | Description | Key Parameters |
|---|---|---|
| `http_get` | Simple GET request | `url` |
| `http_post` | POST with JSON body | `url`, `body` |
| `http_request` | Full HTTP control | `url`, `method`, `headers`, `body`, `content_type` |

Supports all HTTP methods (GET, POST, PUT, PATCH, DELETE). Returns status code and body. This plugin provides the HTTP primitives that skills (weather, summarize-url) build upon.

---

### 35. Time

| Field | Value |
|---|---|
| Plugin ID | `time` |
| Version | 1.0.0 |
| Category | none (always available) |

#### Tools (2)

| Tool | Description | Returns |
|---|---|---|
| `get_current_datetime` | Current date and time | ISO 8601 datetime |
| `get_timestamp` | Full timestamp | ISO 8601 + Unix milliseconds |

---

## Skills

Skills are markdown files with YAML frontmatter in `skill-engine/src/main/assets/skills/`. Only metadata appears in the system prompt as an `<available_skills>` XML block. The full skill body is injected when the user invokes the slash command (e.g., `/skill:weather`).

Skills do not introduce new tools. They instruct the LLM to use existing tools (like `http_get` from web-fetch, or calendar/gmail tools) in specific ways.

### Skill: Weather

| Field | Value |
|---|---|
| Name | weather |
| Slash command | `/skill:weather` |
| Description | Get current weather and forecast for any location |
| Depends on | `http_get` (from web-fetch plugin) |
| API key | None (uses free Open-Meteo API) |

Instructs the LLM to:
1. Geocode the location using Open-Meteo's geocoding API
2. Fetch current weather + 3-day forecast from Open-Meteo
3. Interpret WMO weather codes (0=clear, 1-3=partly cloudy, 45-48=fog, 51-67=rain, 71-77=snow, 80-82=showers, 95-99=thunderstorm)
4. Format output with temperature, humidity, wind speed, and daily forecast

---

### Skill: Summarize URL

| Field | Value |
|---|---|
| Name | summarize-url |
| Slash command | `/skill:summarize-url` |
| Description | Summarize the content of a web page or article |
| Depends on | `http_get` (from web-fetch plugin) |

Instructs the LLM to:
1. Fetch the URL content via `http_get`
2. Extract the main article content, ignoring navigation/ads/boilerplate
3. Produce a summary with: title, key points (3-7 bullets), and one-line takeaway

---

### Skill: Morning Briefing

| Field | Value |
|---|---|
| Name | morning-briefing |
| Slash command | `/skill:morning-briefing` |
| Description | Daily briefing combining calendar, email, tasks, and weather |
| Depends on | calendar tools, gmail tools, tasks tools, `http_get` |

Instructs the LLM to:
1. Gather data from multiple sources: calendar events for today, unread emails, pending tasks, current weather
2. Compile a concise briefing with sections for weather, schedule, email count with highlights, tasks, and notable items
3. Present it as a single unified summary

Can be triggered via the scheduler for automated daily delivery.

---

### Skill: Translate

| Field | Value |
|---|---|
| Name | translate |
| Slash command | `/skill:translate` |
| Description | Translate text between languages |
| Depends on | None (uses LLM's built-in translation ability) |

Instructs the LLM to:
1. Auto-detect the source language
2. Default to English if no target language specified
3. Preserve original tone and register
4. Handle ambiguous phrases carefully
5. Include pronunciation hints for non-Latin scripts

---

### Skill: About OneClaw

| Field | Value |
|---|---|
| Name | about-oneclaw |
| Slash command | `/skill:about-oneclaw` |
| Description | Comprehensive knowledge about OneClaw's architecture, features, tools, and source code |
| Depends on | `http_get` (to fetch source files from GitHub when needed) |

Injects detailed knowledge about OneClaw's module structure, plugin system, two-tier tool activation, agent profiles, workspace/memory architecture, and key data flows. Enables the LLM to answer questions about how the app works and reference actual source code via GitHub.

---

### Skill: Create Plugin

| Field | Value |
|---|---|
| Name | create-plugin |
| Slash command | `/skill:create-plugin` |
| Description | Create or update a custom JavaScript plugin for OneClaw |
| Depends on | `install_plugin` (from plugin_management plugin) |

Instructs the LLM to act as an expert plugin author. Provides the full plugin.json schema, plugin.js execution model, available host bindings (`oneclaw.http`, `oneclaw.fs`, `oneclaw.credentials`, etc.), and the `install_plugin` tool interface. The LLM generates both metadata JSON and JavaScript source, then calls `install_plugin` to install or update the plugin at runtime.

---

### Skill: Create Skill

| Field | Value |
|---|---|
| Name | create-skill |
| Slash command | `/skill:create-skill` |
| Description | Create a new OneClaw skill with correct format and best practices |
| Depends on | `write_file` (from WorkspacePlugin) |

Instructs the LLM to act as an expert skill author. Provides the SKILL.md file format (YAML frontmatter + markdown body), required and optional frontmatter fields, naming conventions, and best practices for writing effective skill instructions.

---

## Tool Count Summary

| Category | Plugin Count | Tool Count |
|---|---|---|
| Kotlin core (workspace, memory, scheduler, config, search, delegate, activate, summarization, plugin_management) | 9 | 22 |
| Kotlin device (device_control, location, qrcode, sms-phone, camera, voice_memo, notifications, media_control) | 8 | 27 |
| Kotlin utility (web, pdf-tools) | 2 | 5 |
| Google Workspace (calendar, contacts, docs, drive, forms, gmail, gmail-settings, places, sheets, slides, tasks) | 11 | 99 |
| Third-party JS (image-gen, notion, smart-home) | 3 | 12 |
| Utility JS (web-fetch, time) | 2 | 5 |
| Skills | 7 | -- |
| **Total** | **35 plugins + 7 skills** | **170 tools** |
