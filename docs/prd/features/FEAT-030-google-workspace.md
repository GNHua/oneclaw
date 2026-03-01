# Google Workspace Tools

## Feature Information
- **Feature ID**: FEAT-030
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Must Have)
- **Owner**: TBD
- **Related RFC**: RFC-030 (pending)

## User Story

**As** an AI agent using OneClawShadow,
**I want** to access Google Workspace services (Gmail, Calendar, Tasks, Contacts, Drive, Docs, Sheets, Slides, Forms) through authenticated API tools,
**so that** I can manage the user's email, calendar events, tasks, contacts, files, documents, spreadsheets, presentations, and forms on their behalf.

### Typical Scenarios

1. The user asks the agent to check their unread emails. The agent calls `gmail_search` with query `is:unread` and returns a summary of recent unread messages.
2. The user says "Schedule a meeting with Alice tomorrow at 2pm." The agent calls `calendar_create_event` with the appropriate date, time, and attendee.
3. The user asks "What tasks do I have due this week?" The agent calls `tasks_list_tasks` and filters for tasks with due dates in the current week.
4. The user says "Find John Smith's phone number." The agent calls `contacts_search` with the name and returns matching contact details.
5. The user asks to upload a local file to Google Drive. The agent calls `drive_upload` with the file path and target folder.
6. The user says "Create a new spreadsheet with a budget template." The agent calls `sheets_create` and then `sheets_update_values` to populate the template.
7. The user asks "What are the responses to my feedback form?" The agent calls `forms_list_responses` and summarizes the results.
8. The user says "Draft a reply to the latest email from my boss." The agent calls `gmail_search` to find the email, reads it with `gmail_get_message`, then calls `gmail_create_draft` with the reply.
9. The user asks "Share the Q1 report with the marketing team." The agent calls `drive_search` to find the file, then `drive_share` to add permissions.
10. The user says "Add a slide about revenue to my presentation." The agent calls `slides_add_slide` and uses the Google Slides API to add content.

## Feature Description

### Overview

FEAT-030 adds Google Workspace integration to OneClawShadow, providing ~89 tools across 10 Google services. This is a port from the proven oneclaw-1 plugin system, adapted to shadow-4's architecture (JS tool groups with QuickJS execution engine).

The feature includes:
1. **BYOK OAuth Authentication** -- Users bring their own GCP OAuth Client ID and Secret for secure, self-managed authentication
2. **10 Google Service Tool Groups** -- Each service is a JSON+JS asset pair registered as a JS tool group
3. **Settings UI** -- Google Account configuration and sign-in management

### Architecture Overview

```
User
    | Configure OAuth credentials in Settings
    | Sign in via browser OAuth flow
    v
GoogleAuthManager  [NEW - Kotlin, handles OAuth flow]
    |
    +-- EncryptedSharedPreferences (stores tokens)
    |
    v
AI Model
    | tool call: gmail_search(query="is:unread") ...
    v
ToolExecutionEngine (unchanged)
    |
    v
ToolRegistry
    |  JS Tool Group: google_gmail
    |  JS Tool Group: google_calendar
    |  JS Tool Group: google_tasks
    |  ... (10 groups total)
    v
JsExecutionEngine (MODIFIED)
    |
    +-- GoogleAuthBridge [NEW] -- google.getAccessToken()
    +-- FileTransferBridge [NEW] -- downloadToFile(), uploadMultipart()
    +-- FetchBridge (existing) -- fetch()
    +-- FsBridge (existing) -- fs.*
    +-- ConsoleBridge (existing) -- console.*
    |
    v
QuickJS Runtime
    |  Executes google_{service}.js
    |  Calls Google Workspace REST APIs
    v
Google Workspace APIs
    |
    v
Results returned to AI Model
```

### 10 Google Services

#### 1. Google Gmail (18 tools)

| Tool | Description |
|------|-------------|
| `gmail_search` | Search messages using Gmail query syntax |
| `gmail_get_message` | Get full content of a specific message |
| `gmail_send` | Send a new email (plain text and HTML) |
| `gmail_reply` | Reply to an existing email thread |
| `gmail_delete` | Move messages to Trash (batch, max 1000) |
| `gmail_list_labels` | List all labels/folders |
| `gmail_get_thread` | Get all messages in a thread |
| `gmail_modify_labels` | Add/remove labels on messages |
| `gmail_batch_modify` | Batch modify labels on multiple messages |
| `gmail_list_drafts` | List drafts |
| `gmail_get_draft` | Get a specific draft |
| `gmail_create_draft` | Create a new draft |
| `gmail_send_draft` | Send an existing draft |
| `gmail_delete_draft` | Delete a draft |
| `gmail_create_label` | Create a new label |
| `gmail_delete_label` | Delete a label |
| `gmail_get_attachment` | Download an attachment |
| `gmail_history` | Get mailbox change history |

#### 2. Google Gmail Settings (11 tools)

| Tool | Description |
|------|-------------|
| `gmail_list_filters` | List all Gmail filters |
| `gmail_create_filter` | Create a filter (criteria + action) |
| `gmail_delete_filter` | Delete a filter by ID |
| `gmail_get_vacation` | Get vacation responder settings |
| `gmail_set_vacation` | Set/update vacation responder |
| `gmail_list_forwarding` | List forwarding addresses |
| `gmail_add_forwarding` | Add a forwarding address |
| `gmail_get_auto_forward` | Get auto-forwarding settings |
| `gmail_set_auto_forward` | Enable/disable auto-forwarding |
| `gmail_list_send_as` | List send-as aliases |
| `gmail_list_delegates` | List delegates |

#### 3. Google Calendar (11 tools)

| Tool | Description |
|------|-------------|
| `calendar_list_events` | List upcoming events (default: 7 days, primary calendar) |
| `calendar_get_event` | Get event details by ID |
| `calendar_create_event` | Create a new event |
| `calendar_update_event` | Update an existing event |
| `calendar_delete_event` | Delete an event |
| `calendar_quick_add` | Quick-add via natural language text |
| `calendar_list_calendars` | List all calendars |
| `calendar_freebusy` | Check free/busy availability |
| `calendar_instances` | List instances of a recurring event |
| `calendar_respond` | Respond to an event (accept/decline/tentative) |
| `calendar_list_colors` | List available calendar colors |

#### 4. Google Tasks (7 tools)

| Tool | Description |
|------|-------------|
| `tasks_list_tasklists` | List all task lists |
| `tasks_list_tasks` | List tasks in a specific list |
| `tasks_get_task` | Get task details |
| `tasks_create` | Create a task (supports subtasks via parent) |
| `tasks_update` | Update a task |
| `tasks_complete` | Mark task as completed |
| `tasks_delete` | Delete a task |

#### 5. Google Contacts (7 tools)

| Tool | Description |
|------|-------------|
| `contacts_search` | Search by name/email/phone (max 30) |
| `contacts_list` | List contacts with pagination |
| `contacts_get` | Get full contact details |
| `contacts_create` | Create a contact |
| `contacts_update` | Update a contact (auto-fetches etag) |
| `contacts_delete` | Delete a contact |
| `contacts_directory` | List Workspace domain directory |

#### 6. Google Drive (13 tools)

| Tool | Description |
|------|-------------|
| `drive_list` | List files (with query, ordering, pagination) |
| `drive_search` | Full-text search files |
| `drive_get` | Get file metadata |
| `drive_mkdir` | Create a folder |
| `drive_copy` | Copy a file |
| `drive_rename` | Rename a file |
| `drive_move` | Move a file between folders |
| `drive_delete` | Delete a file (trash) |
| `drive_share` | Share a file (set permissions) |
| `drive_permissions` | List file permissions |
| `drive_download` | Download a file to local storage |
| `drive_upload` | Upload a file to Drive |
| `drive_export` | Export Google Docs/Sheets/Slides to PDF/DOCX/etc. |

#### 7. Google Docs (6 tools)

| Tool | Description |
|------|-------------|
| `docs_get` | Get full document structure |
| `docs_create` | Create a new empty document |
| `docs_get_text` | Extract plain text from document |
| `docs_insert` | Insert text at a specific index position |
| `docs_delete_range` | Delete a content range |
| `docs_find_replace` | Find and replace all occurrences |

#### 8. Google Sheets (7 tools)

| Tool | Description |
|------|-------------|
| `sheets_get_values` | Read cell values from a range |
| `sheets_update_values` | Update cells |
| `sheets_append` | Append rows after last data row |
| `sheets_clear` | Clear values in range (preserves formatting) |
| `sheets_metadata` | Get spreadsheet title and sheet properties |
| `sheets_create` | Create a new spreadsheet |
| `sheets_batch_update` | Batch structural changes (add/delete sheets, formatting, merge, sort) |

#### 9. Google Slides (6 tools)

| Tool | Description |
|------|-------------|
| `slides_get` | Get presentation details |
| `slides_create` | Create a new presentation |
| `slides_list_slides` | List all slides |
| `slides_get_slide_text` | Extract text from a specific slide |
| `slides_add_slide` | Add a slide (supports layout types) |
| `slides_delete_slide` | Delete a slide |

#### 10. Google Forms (3 tools)

| Tool | Description |
|------|-------------|
| `forms_get` | Get form structure (items, question types, options) |
| `forms_list_responses` | List submitted responses with summary |
| `forms_get_response` | Get a specific response with all answers |

### BYOK OAuth Authentication

OneClawShadow uses a BYOK (Bring Your Own Key) OAuth flow. The user provides their own GCP Desktop OAuth Client ID and Client Secret, giving them full control over their credentials and API access.

#### OAuth Flow

```
1. User opens Settings > Google Account
2. User enters their GCP OAuth Client ID and Client Secret
3. User taps "Save Credentials"
4. User taps "Sign In with Google"
5. App starts a loopback HTTP server on a random port (127.0.0.1:{port})
6. App opens browser with Google OAuth consent URL:
   - client_id, redirect_uri=http://127.0.0.1:{port}
   - scope = all 11 Workspace scopes
   - access_type=offline, prompt=consent
7. User grants permissions in the browser
8. Browser redirects to http://127.0.0.1:{port}?code=...
9. Loopback server captures the authorization code
10. App exchanges the code for tokens (POST https://oauth2.googleapis.com/token)
11. App fetches user email (GET https://www.googleapis.com/oauth2/v2/userinfo)
12. App stores refresh token + access token + expiry + email in EncryptedSharedPreferences
13. Settings UI updates to show signed-in state with user email
```

#### Required Google Workspace Scopes

| Scope | Service |
|-------|---------|
| `https://www.googleapis.com/auth/gmail.modify` | Gmail (read + send + modify) |
| `https://www.googleapis.com/auth/gmail.settings.basic` | Gmail Settings |
| `https://www.googleapis.com/auth/calendar` | Google Calendar |
| `https://www.googleapis.com/auth/tasks` | Google Tasks |
| `https://www.googleapis.com/auth/contacts` | Google Contacts |
| `https://www.googleapis.com/auth/drive` | Google Drive |
| `https://www.googleapis.com/auth/documents` | Google Docs |
| `https://www.googleapis.com/auth/spreadsheets` | Google Sheets |
| `https://www.googleapis.com/auth/presentations` | Google Slides |
| `https://www.googleapis.com/auth/forms.body.readonly` | Google Forms (structure) |
| `https://www.googleapis.com/auth/forms.responses.readonly` | Google Forms (responses) |

### Settings UI

A new "Google Account" settings item is added to the Settings screen, navigating to a dedicated Google Account configuration screen.

#### Google Account Settings Screen

- **Client ID field** -- Text input for the user's GCP OAuth Client ID
- **Client Secret field** -- Password-masked text input for the Client Secret
- **Save Credentials button** -- Stores credentials in EncryptedSharedPreferences
- **Sign In with Google button** -- Initiates the OAuth flow (enabled only after credentials are saved)
- **Signed-in state** -- Shows the connected Google account email and a "Sign Out" button
- **Status indicators** -- Shows configuration status (not configured / credentials saved / signed in)

### User Interaction Flows

#### Initial Setup Flow

```
1. User: Opens Settings > Google Account
2. UI: Shows empty Client ID and Client Secret fields
3. User: Enters their GCP OAuth credentials and taps "Save"
4. UI: Shows "Credentials saved" confirmation, enables "Sign In" button
5. User: Taps "Sign In with Google"
6. System: Opens browser for Google OAuth consent
7. User: Grants permissions in browser
8. UI: Shows signed-in state with email (e.g., "user@gmail.com")
```

#### Using Google Tools Flow

```
1. User: "Check my unread emails"
2. AI: Calls gmail_search(query="is:unread", max_results=10)
3. JS Engine: Executes google_gmail.js::gmailSearch()
   a. Gets access token via google.getAccessToken()
   b. Calls Gmail API: GET /gmail/v1/users/me/messages?q=is:unread
   c. Returns formatted message list
4. AI: Summarizes unread emails for the user
```

#### Token Refresh Flow (Transparent)

```
1. AI calls any Google tool
2. google.getAccessToken() is called in JS
3. GoogleAuthManager checks token expiry
4. If expired (within 60s margin): refreshes using refresh_token
5. Returns valid access token
6. Tool proceeds with API call
```

## Acceptance Criteria

Must pass (all required):

- [ ] BYOK OAuth flow: user can enter GCP Client ID + Secret, sign in, and get tokens
- [ ] Access tokens are automatically refreshed when expired
- [ ] All 89 tools across 10 services are registered in ToolRegistry as JS tool groups
- [ ] Each tool group has a valid JSON definition file and JS implementation file
- [ ] Gmail tools: search, read, send, reply, draft, label management work correctly
- [ ] Gmail Settings tools: filter, vacation, forwarding management work correctly
- [ ] Calendar tools: list, create, update, delete events work correctly
- [ ] Tasks tools: list, create, update, complete, delete tasks work correctly
- [ ] Contacts tools: search, list, create, update, delete contacts work correctly
- [ ] Drive tools: list, search, upload, download, share files work correctly
- [ ] Docs tools: get, create, insert, delete, find-replace work correctly
- [ ] Sheets tools: read, write, append, clear values work correctly
- [ ] Slides tools: get, create, add/delete slides work correctly
- [ ] Forms tools: get form structure, list and get responses work correctly
- [ ] Google Account settings screen allows credential configuration and sign-in/out
- [ ] Tokens and credentials are stored in EncryptedSharedPreferences (never in Room)
- [ ] Sign-out revokes tokens server-side (best-effort) and clears local storage
- [ ] All Google tools return clear error messages when not signed in
- [ ] All Layer 1A tests pass

Optional (nice to have for V1):

- [ ] Retry logic for transient API errors (429, 5xx)
- [ ] Batch operations for multi-item requests
- [ ] OAuth scope granularity (allow user to select which services to authorize)

## UI/UX Requirements

### Settings Screen Changes

- Add "Google Account" item to the Settings screen list
- Position: between "Backup & Sync" and "Theme"
- Icon: account/person icon
- Subtitle: shows connected email or "Not connected"
- Tapping navigates to Google Account configuration screen

### Google Account Screen

- Top section: Client ID and Client Secret input fields
- Middle section: Save Credentials button
- Bottom section: Sign In / Sign Out button with status
- Signed-in state: shows email address prominently
- Error states: inline error messages for failed operations

## Feature Boundary

### Included

- BYOK OAuth authentication with loopback redirect
- Token management (store, refresh, revoke)
- 10 Google Workspace service tool groups (89 tools)
- Google Account settings UI (credentials + sign-in/out)
- GoogleAuthBridge for JS tools to access OAuth tokens
- FileTransferBridge for Drive upload/download operations
- Error handling for auth failures, API errors, and rate limits

### Not Included (V1)

- Google service account authentication (only OAuth user flow)
- OAuth scope selection (all scopes are requested together)
- Google Workspace Admin SDK tools
- Google Maps / YouTube / other Google API integrations
- Multi-account support (one Google account at a time)
- Offline caching of Google data
- Real-time sync or push notifications from Google services
- Google Workspace Marketplace integration

## Business Rules

1. OAuth credentials (Client ID, Client Secret) are stored in EncryptedSharedPreferences
2. Refresh tokens, access tokens, and token expiry are stored in EncryptedSharedPreferences
3. Access tokens are refreshed 60 seconds before expiry to avoid race conditions
4. Token refresh is mutex-protected to prevent concurrent refresh storms
5. Sign-out revokes the token server-side (best-effort) before clearing local storage
6. All Google API calls use Bearer token authentication
7. Google tools return descriptive errors when the user is not signed in
8. The user must provide their own GCP OAuth credentials (BYOK model)
9. All 11 Google Workspace scopes are requested together during OAuth consent

## Non-Functional Requirements

### Performance

- OAuth flow completion: 5-15 seconds (includes browser interaction)
- Token refresh: < 1 second (single HTTPS call)
- Individual tool API call: 0.5-3 seconds (depends on Google API and payload)
- Tool group JS loading: < 100ms (from assets)
- QuickJS execution overhead: < 50ms per tool call

### Security

- OAuth credentials stored in EncryptedSharedPreferences with Android KeyStore
- Access tokens never logged or exposed in UI
- Loopback OAuth redirect (127.0.0.1) prevents external interception
- HTTPS-only communication with Google APIs
- Token revocation on sign-out (best-effort)
- No credential persistence in Room database

### Reliability

- Automatic token refresh with 60-second margin
- Mutex-protected refresh prevents token race conditions
- Clear error messages for all failure modes (not signed in, expired, network, API errors)

### Compatibility

- Requires Android API 24+
- Requires internet connectivity for all Google API operations
- Works with both personal Gmail and Google Workspace accounts

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: Tool interface, ToolRegistry, ToolExecutionEngine
- **FEAT-012 (JS Tool Engine)**: JsExecutionEngine, bridge injection system
- **FEAT-018 (JS Tool Group)**: JS tool group registration pattern (JSON+JS pairs)
- **FEAT-009 (Settings)**: Settings screen navigation and layout

### Depended On By

- No other features currently depend on FEAT-030

### External Dependencies

- **Google Workspace REST APIs**: Gmail, Calendar, Tasks, Contacts, Drive, Docs, Sheets, Slides, Forms
- **Google OAuth 2.0**: Authorization endpoint, token endpoint, userinfo endpoint
- **OkHttpClient**: Already available via DI for HTTP requests
- **EncryptedSharedPreferences**: Already used for API key storage
- **QuickJS (com.dokar.quickjs)**: Already used as JS runtime

## Error Handling

### Error Scenarios

1. **Not signed in**
   - Cause: User attempts to use a Google tool without signing in
   - Handling: Return `{error: "Not signed in to Google. Connect your Google account in Settings."}`

2. **OAuth credentials not configured**
   - Cause: User hasn't entered Client ID/Secret
   - Handling: Settings UI shows "Not configured" status, Sign In button is disabled

3. **OAuth consent denied**
   - Cause: User denies permissions in the Google consent screen
   - Handling: Show error in Settings UI, no tokens stored

4. **Token refresh failure**
   - Cause: Refresh token revoked or network error
   - Handling: Clear stored tokens, return auth error requiring re-sign-in

5. **Google API error (4xx)**
   - Cause: Invalid request, insufficient permissions, resource not found
   - Handling: Return Google API error message to the AI model

6. **Google API rate limit (429)**
   - Cause: Too many requests to Google API
   - Handling: Return rate limit error to the AI model

7. **Network error**
   - Cause: No internet connectivity
   - Handling: Return network error message

8. **Invalid parameters**
   - Cause: Missing or malformed tool parameters
   - Handling: Return parameter validation error

## Future Improvements

- [ ] Multi-account support (multiple Google accounts simultaneously)
- [ ] Selective scope authorization (choose which services to enable)
- [ ] Google service account support (for server-to-server auth)
- [ ] Retry logic with exponential backoff for transient errors
- [ ] Batch API requests for multi-item operations
- [ ] Offline caching for recently accessed data
- [ ] Push notifications for new emails, calendar events
- [ ] Google Workspace Admin SDK integration
- [ ] Google Maps, YouTube API tool groups

## Test Points

### Functional Tests

- Verify OAuth credentials can be saved and loaded from EncryptedSharedPreferences
- Verify OAuth flow completes and stores tokens
- Verify access token refresh works when token is expired
- Verify sign-out revokes tokens and clears storage
- Verify all 10 tool groups are registered in ToolRegistry
- Verify each tool group's JSON definition parses correctly
- Verify JS execution with GoogleAuthBridge provides valid tokens
- Verify FileTransferBridge download and upload operations
- Verify Gmail search returns formatted results
- Verify Calendar event creation with date/time handling
- Verify Drive file upload and download
- Verify Contacts search and CRUD operations
- Verify Docs text extraction and insertion
- Verify Sheets read/write operations
- Verify Slides list and add operations
- Verify Forms response listing
- Verify Google Account settings screen renders correctly
- Verify navigation to/from Google Account settings

### Edge Cases

- Token expires during a tool call execution
- Concurrent Google tool calls triggering simultaneous token refresh
- Google API returns paginated results
- User revokes app access from Google Account settings (external)
- Very large email attachments or Drive files
- Special characters in email subjects, document titles
- Network disconnection during OAuth flow
- Loopback server port conflict
- User signs out while a tool call is in progress
- Google Workspace account with restricted API access

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
