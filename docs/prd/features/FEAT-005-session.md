# Session Management

## Feature Information
- **Feature ID**: FEAT-005
- **Created**: 2026-02-26
- **Last Updated**: 2026-02-27
- **Status**: Draft
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: [RFC-005 (Session Management)](../../rfc/features/RFC-005-session-management.md)
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md)

## User Story

**As** a user of OneClaw,
**I want to** manage multiple conversation sessions, each with its own history and context,
**so that** I can organize my interactions with the AI and resume past conversations at any time.

### Typical Scenarios
1. User opens the app and sees an empty chat screen (new conversation). They open the navigation drawer to see past sessions.
2. User taps "New conversation" in the drawer and starts a conversation -- the session gets an auto-generated title after the first exchange.
3. User renames a session to something more meaningful.
4. User swipes to delete a session in the drawer, then taps "Undo" on the Snackbar to recover it.
5. User selects multiple sessions in the drawer and deletes them in batch.
6. User opens the app offline and browses their past sessions and message history via the drawer.

## Feature Description

### Overview
Session Management provides the ability to create, view, resume, rename, and delete conversation sessions. A session is the container for a conversation -- it holds the message history, the current Agent, and metadata like title and timestamps. Sessions are displayed in a flat list sorted by last active time. Session titles are auto-generated but can be manually edited. The module supports single and batch deletion with a brief undo window.

### Session Data Model

| Field | Required | Description |
|-------|----------|-------------|
| ID | Yes | Unique identifier, auto-generated |
| Title | Yes | Display title for the session |

| Current Agent ID | Yes | The Agent currently associated with this session |
| Created At | Yes | Timestamp of session creation |
| Updated At | Yes | Timestamp of last activity (last message sent or received) |
| Message Count | Yes | Number of messages in the session |
| Is Active | Yes | Whether a request is currently in-flight in this session |

### Session Title Generation

Session titles are generated using a two-phase approach:

#### Phase 1: Immediate Truncated Title
- When the user sends the first message in a new session, the title is set to the first ~50 characters of that message, truncated at a word boundary with "..." appended if needed.
- This provides an instant title with zero latency and zero cost.

#### Phase 2: AI-Generated Title (Async)
- After the first AI response is received, an async background request is sent to generate a better title.
- The request asks a lightweight model to generate a short title (5-10 words) based on the first user message and AI response.
- Lightweight model selection:
  - For known providers: use a pre-set small model (e.g., gpt-4o-mini for OpenAI, claude-haiku for Anthropic, gemini-2.0-flash for Gemini)
  - For custom providers: use the same model as the current conversation
- Once the AI-generated title is received, it replaces the truncated title.
- If the AI title generation fails (network error, etc.), the truncated title is kept. No retry.
- AI title generation only happens once (after the first AI response). The title is never auto-updated again after that.

#### Manual Title Editing
- Users can manually edit a session title at any time.

### Session List

Sessions are displayed in a **Navigation Drawer** (slide-in panel from the left), not as a standalone home screen. The drawer is accessed via the hamburger menu icon in the top-left of the chat screen. See [UI Design Spec](../../design/ui-design-spec.md) for detailed layout.

- Sessions are displayed in a flat list (no folders, tags, or groups in V1)
- Sorted by last active time (most recent first)
- Each list item shows:
  - Session title
  - Current Agent name (small label/badge)
  - Last message preview (truncated, 1-2 lines)
  - Last active time (relative: "2 min ago", "Yesterday", "Feb 20")
  - Active indicator if a request is currently in-flight
- "New conversation" button at the top of the drawer
- Pull-to-refresh gesture (for when sync is implemented in FEAT-007)

### Session Operations

#### Create New Session
- "New conversation" button at the top of the navigation drawer
- Opens an empty chat view with the default Agent (General Assistant)
- **Lazy session creation**: No session record is created in the database until the user sends the first message. This prevents empty sessions from cluttering the session list.
- Title is empty/placeholder until the first message is sent

#### Resume Session
- Tap a session in the list to open it
- Chat view loads with full message history
- User can continue the conversation

#### Rename Session
- Long-press on a session or tap an edit icon
- Inline edit or dialog to change the title


#### Delete Single Session
- Swipe-to-delete gesture on a session item
- Session is removed from the list immediately
- A Snackbar appears at the bottom: "Session deleted" with an "Undo" button
- Undo window: ~5 seconds
- If user taps "Undo": session is restored to the list
- If undo window expires: session and all its messages are permanently deleted

#### Batch Delete
- User enters selection mode (long-press or toolbar action)
- Checkboxes appear on each session item
- "Select All" option available
- "Delete" button deletes all selected sessions
- Same Snackbar + Undo mechanism as single delete
- Undo restores all deleted sessions in the batch

### Offline Behavior
- All sessions and their message history are stored locally
- Users can browse the session list and read past conversations offline
- Users can create a new session offline (but cannot send messages until online)
- Last active times and all metadata are available offline

### User Interaction Flow

#### Starting a New Conversation
```
1. User opens the app (lands on empty chat screen with General Assistant)
   OR: User opens the drawer and taps "New conversation"
2. User types and sends first message
3. Session is created in the database at this point (lazy creation)
4. Title is set to truncated first message (Phase 1)
5. AI responds
6. Background: AI title generation request is sent (Phase 2)
7. Title updates to AI-generated title when ready
8. Session appears in drawer session list with the generated title
```

#### Deleting Sessions
```
Single:
1. User swipes left on a session
2. Session disappears from list
3. Snackbar appears: "Session deleted [Undo]"
4a. User taps Undo -> session reappears
4b. 5 seconds pass -> session permanently deleted

Batch:
1. User long-presses a session to enter selection mode
2. User taps additional sessions to select
3. User taps "Delete" in toolbar
4. All selected sessions disappear
5. Snackbar: "X sessions deleted [Undo]"
6a. Undo -> all restored
6b. Timeout -> all permanently deleted
```

## Acceptance Criteria

Must pass (all required):
- [ ] User can create a new session
- [ ] New sessions default to the General Assistant agent
- [ ] Session title is auto-generated: Phase 1 (truncated first message) is immediate
- [ ] Session title is auto-generated: Phase 2 (AI-generated) replaces the truncated title asynchronously
- [ ] AI title generation uses lightweight model for known providers, falls back to current model for custom providers
- [ ] If AI title generation fails, truncated title is kept (no error shown to user)
- [ ] User can manually rename a session title
- [ ] Title is only auto-generated once (after first AI response); never auto-updated again
- [ ] Session list is sorted by last active time (most recent first)
- [ ] Session list items show: title, agent name, last message preview, last active time
- [ ] User can tap a session to resume the conversation
- [ ] User can delete a single session by swiping
- [ ] After deletion, a Snackbar with "Undo" appears for ~5 seconds
- [ ] Tapping "Undo" restores the session
- [ ] After undo window expires, session is permanently deleted
- [ ] User can enter selection mode and batch delete multiple sessions
- [ ] Batch delete also supports Snackbar undo
- [ ] Sessions are accessible offline (browse list, read history)
- [ ] Session data is persisted locally

Optional (nice to have for V1):
- [ ] Session list shows an active indicator for sessions with in-flight requests
- [ ] Pull-to-refresh on session list
- [ ] Session creation animation

## UI/UX Requirements

### Session List (in Navigation Drawer)
- Session list lives inside a Navigation Drawer, not as a standalone screen
- See [UI Design Spec](../../design/ui-design-spec.md) for detailed visual layout
- "New conversation" button at the top of the drawer
- Flat list of sessions, each item:
  - Title (primary text)
  - Agent name badge (secondary)
  - Last message preview (tertiary, 1-2 lines, muted text)
  - Last active time (right-aligned or below title)
- Swipe-to-delete on individual items
- Long-press to enter selection mode for batch operations

### Session Item States
- **Normal**: default appearance
- **Active**: subtle indicator (e.g., pulsing dot) showing a request is in-flight
- **Selected**: checkbox visible, highlight background (in selection mode)

### Rename UI
- Tap edit icon on a session -> dialog with text field pre-filled with current title
- Or: long-press -> context menu with "Rename" option

### Deletion Feedback
- Swipe animation: session slides off screen
- Snackbar at bottom: "Session deleted" / "X sessions deleted" with "Undo" action button
- Undo animation: session slides back into position

### Empty State
- When app launches with no sessions: chat screen shows a centered greeting "How can I help you today?"
- Drawer session list shows empty state text when no sessions exist
- See [UI Design Spec](../../design/ui-design-spec.md) for empty state details

## Feature Boundary

### Included
- Session creation with default Agent
- Auto-generated titles (truncated + AI-generated)
- Manual title editing
- Session list sorted by last active time
- Session resume (tap to open)
- Single session delete with swipe + undo Snackbar
- Batch session delete with undo Snackbar
- Offline session browsing
- Local persistence of all session data

### Not Included (V1)
- Session folders, tags, or groups
- Session search
- Session pinning (pin to top)
- Session archiving (separate from delete)
- Session export (export conversation to text/PDF)
- Recycle bin / trash (undo Snackbar is the recovery mechanism)
- Session sharing (share a conversation link)
- Session templates (start a session with pre-filled messages)

## Business Rules

### Session Rules
1. Every session must have a current Agent at all times
2. New sessions default to the General Assistant
3. A session's title is never empty -- at minimum it shows a placeholder (e.g., "New Conversation") until the first message
4. Sessions are sorted by `updated_at` descending (most recent first), this is not user-configurable in V1
5. Deleting a session deletes all its messages permanently (after undo window expires)

### Title Generation Rules
1. Phase 1 triggers when the first user message is sent
2. Phase 2 triggers after the first AI response is received
3. Phase 2 uses a lightweight model for known providers (gpt-4o-mini, claude-haiku, gemini-2.0-flash)
4. Phase 2 uses the conversation's current model for custom providers
5. If Phase 2 fails, Phase 1 title is kept silently
6. Title auto-generation only happens once; after that, the title is never auto-updated (whether or not the user manually edited it)
7. Title generation does not block the conversation flow

### Deletion Rules
1. Deleted sessions are soft-deleted during the undo window (~5 seconds)
2. After undo window: hard delete (messages and session record removed from database)
3. Undo restores the session to its exact previous state
4. Batch undo is all-or-nothing (restores all or none)
5. If the app is killed during the undo window, soft-deleted sessions are cleaned up on next app launch

## Non-Functional Requirements

### Performance
- Session list loads in < 200ms (for up to 1000 sessions)
- Session creation is instant (< 50ms)
- AI title generation completes within 5 seconds (background, non-blocking)
- Scrolling through the session list maintains 60fps

### Data
- All session data stored locally (SQLite/Room)
- Session data included in Google Drive sync (FEAT-007)
- Message history lazy-loaded when a session is opened (not pre-loaded for all sessions)

### Reliability
- If AI title generation request is in-flight and the app is killed, the truncated title persists
- No data loss on unexpected app termination (messages are persisted as they arrive)

## Dependencies

### Depends On
- **FEAT-002 (Agent Management)**: Sessions reference an Agent
- **FEAT-003 (Model/Provider Management)**: Title generation needs access to a model/provider
- **FEAT-001 (Chat Interaction)**: Sessions contain conversations rendered by the chat module

### Depended On By
- **FEAT-006 (Token/Cost Tracking)**: Token usage is tracked per session
- **FEAT-007 (Data Storage & Sync)**: Session data is part of the sync payload
- **FEAT-008 (Notifications)**: Notification taps navigate to a specific session

## Error Handling

### Error Scenarios

1. **AI title generation fails**
   - Handling: Keep the truncated title silently, no error shown to user
   - No retry (to avoid unnecessary API costs)

2. **Session creation fails (storage error)**
   - Display: Error toast "Failed to create session. Please try again."
   - The chat view does not open

3. **Session deletion fails (storage error)**
   - Display: Error toast "Failed to delete session."
   - Session remains in the list

4. **Session resume fails (corrupted data)**
   - Display: Error dialog "This session could not be loaded."
   - Option to delete the corrupted session

5. **Offline title generation**
   - Phase 1 (truncated) works offline
   - Phase 2 (AI-generated) is skipped when offline -- no retry when back online

## Future Improvements

- [ ] **Session search**: Search sessions by title or message content
- [ ] **Session folders/tags/groups**: Organize sessions into categories
- [ ] **Session pinning**: Pin important sessions to the top of the list
- [ ] **Session archiving**: Archive old sessions instead of deleting
- [ ] **Session export**: Export a conversation to text, Markdown, or PDF
- [ ] **Session sharing**: Generate a shareable link or file for a conversation
- [ ] **Custom sort options**: Sort by creation time, title alphabetically, etc.
- [ ] **Recycle bin**: A trash folder for deleted sessions with configurable retention

## Test Points

### Functional Tests
- Create a new session and verify it appears in the list
- Verify new session defaults to General Assistant
- Send first message and verify truncated title appears immediately
- Verify AI-generated title replaces truncated title after first AI response
- Verify AI title uses lightweight model for known providers
- Verify AI title falls back to current model for custom providers
- Verify truncated title is kept when AI generation fails
- Rename a session manually and verify the title persists
- Verify manually renamed session is never auto-updated
- Verify session list is sorted by last active time
- Verify session list items show correct information
- Tap a session and verify it opens with full history
- Swipe to delete and verify Snackbar appears
- Tap Undo and verify session is restored
- Let undo expire and verify session is permanently deleted
- Enter selection mode and batch delete multiple sessions
- Verify batch undo restores all sessions
- Verify sessions are browsable offline

### Performance Tests
- Session list load time with 100, 500, 1000 sessions
- Scroll performance with large session list
- AI title generation latency

### Edge Cases
- Create session and close app before first message (session with placeholder title)
- Send first message and kill app before AI title generation completes
- Delete a session that has an in-flight request
- Batch delete all sessions
- Session with very long title (manual edit)
- Session with 10,000+ messages (resume performance)
- Rapid create-delete-undo cycles
- Delete session while offline

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-26 | 0.1 | Initial version | - |
| 2026-02-27 | 0.2 | Changed session list from standalone home screen to Navigation Drawer; added lazy session creation; updated UI section to reference UI Design Spec | - |
| 2026-02-27 | 0.3 | Added RFC-005 reference | - |
