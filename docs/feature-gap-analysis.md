# Feature Gap Analysis: oneclaw-1 vs oneclaw-2

Date: 2026-03-02

Reference project: `../oneclaw-1`
Current project: `oneclaw-2`

---

## 1. Hardware / Device Capability Modules

These are standalone library modules in oneclaw-1 that have no equivalent in oneclaw-2.

| Feature | oneclaw-1 Module | Tools / Description |
|---------|-----------------|---------------------|
| **Device Control** | `lib-device-control` | Accessibility Service for screen observation and interaction: `get_screen_content`, `tap`, `type`, `swipe` |
| **Location** | `lib-location` | GPS location, nearby places search, directions: `get_current_location`, `search_nearby_places`, `get_directions_url` |
| **Notification / Media Control** | `lib-notification-media` | Read system notifications, control media playback: `list_notifications`, `inspect_notification`, `dismiss_notification`, `play_pause`, `next_track`, `stop_media` |
| **Camera** | `lib-camera` | CameraX headless photo capture: `take_photo` |
| **QR Code** | `lib-qrcode` | QR code scanning and generation: `generate_qr_code`, `scan_qr_code` |
| **SMS / Phone** | `lib-sms-phone` | Send SMS, phone dial, call log: `send_sms`, `list_sms`, `search_sms`, `dial_phone`, `get_call_log` |
| **Voice Memo** | `lib-voice-memo` | Audio recording + OpenAI Whisper speech-to-text: `record_audio` |

---

## 2. Agent System

| Feature | Description |
|---------|-------------|
| **Delegate Agent** | `DelegateAgentPlugin` -- switch to a different Agent Profile mid-conversation to execute sub-tasks |
| **Two-Tier Tool Activation** | `ActivateToolsPlugin` -- tools split into "always-on" and "on-demand" categories; LLM dynamically activates categories (web, location, phone, etc.) via `activate_tools` |
| **Runtime Plugin Install** | `InstallPluginTool` -- LLM can install new JS plugins at runtime |

---

## 3. Google Workspace JS Plugins (16 built-in)

oneclaw-1 ships 16 QuickJS-based plugins authenticated via Google OAuth. oneclaw-2 has `GoogleAuthBridge` but lacks these concrete plugin implementations.

| Plugin | Capabilities |
|--------|-------------|
| Gmail | Email send / read |
| Gmail Settings | Mail rules and configuration |
| Calendar | Event management |
| Contacts | Contact CRUD |
| Tasks | Task lists |
| Drive | File storage operations |
| Docs | Document editing |
| Sheets | Spreadsheet operations |
| Slides | Presentation editing |
| Forms | Form creation / responses |
| Places | Google Places API integration |
| Time | Timezone and time utilities |
| Image-Gen | Image generation (via OpenAI) |
| Notion | Notion integration |
| Smart Home | Home automation (protocol-agnostic) |
| Web-Fetch (raw) | Unfiltered HTTP requests |

---

## 4. Web Search

| Feature | Description |
|---------|-------------|
| **Web Search** | `lib-web` provides `web_search` via Tavily / Brave API. shadow-2 has `WebfetchTool` / `BrowserTool` for page fetching but **no standalone search tool**. |

---

## 5. UI / UX Features

| Feature | Description |
|---------|-------------|
| **Appearance / Theme Settings** | `AppearanceScreen` -- dedicated theme customization screen |
| **Conversation History Search** | `ConversationHistoryScreen` -- standalone screen for searching and loading past conversations |
| **Plugin Detail Sheet** | `PluginDetailSheet` -- bottom sheet showing detailed plugin information |
| **Audio Player Bar** | `AudioPlayerBar` -- inline audio message playback in chat |
| **Voice Input** | `AudioInputController` + `SttProvider` -- microphone voice input with speech-to-text |

---

## 6. Other

| Feature | Description |
|---------|-------------|
| **Antigravity Provider** | Google Cloud Code Assist (Gemini backend), additional LLM provider with PKCE OAuth |
| **Conversation Summarization** | Auto-triggered at 80% context window; splits history into summarized + recent. shadow-2 has `AutoCompactUseCase` which may partially cover this -- needs verification. |

---

## Summary

The three largest gap areas:

1. **Hardware capability plugins** -- 7 library modules (device control, camera, location, SMS, voice, etc.)
2. **Google Workspace integration** -- 16 JS plugins covering Gmail, Calendar, Drive, Docs, Sheets, etc.
3. **Two-tier tool activation and delegate agent** -- architectural patterns for dynamic tool management and sub-agent delegation
