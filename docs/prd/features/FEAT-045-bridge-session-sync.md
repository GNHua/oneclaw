# Bridge-App Session Synchronization

## Feature Information
- **Feature ID**: FEAT-045
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Completed
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-045 (Bridge Session Sync)](../../rfc/features/RFC-045-bridge-session-sync.md)
- **Related Feature**: [FEAT-041 (Bridge Improvements)](FEAT-041-bridge-improvements.md)

## User Story

**As** a user who controls the AI agent through both the Telegram bridge and the OneClaw app,
**I want** the ChatScreen to automatically follow whichever session the bridge is currently using,
**so that** there is always a single shared active session and I never lose track of the ongoing conversation.

### Typical Scenarios

1. User sends `/clear` via Telegram. The bridge creates a new empty session. Within 3 seconds, the app's ChatScreen switches to that new session without any manual action by the user.
2. Normal bridge message arrives. The ChatScreen is already displaying the bridge's current session (established by FEAT-041). Messages appear in real time in both Telegram and the app.
3. User manually switches to a different session inside the app. Subsequent bridge messages route to that session (not the most-recently-updated session in the database); ChatScreen remains on that session.
4. User taps "New Conversation" in the app. A new empty session is immediately created in the database and displayed. Subsequent bridge messages route to that new session.
5. Bridge receives the first message in a new session (after `/clear`). The session title updates from "Bridge Conversation" to a meaningful truncated title immediately, and later to an AI-generated title.

## Feature Description

### Background

FEAT-041 established that bridge messages go to the app's most recently updated session (`getMostRecentSessionId()`). This means the bridge and the app naturally share the same active session as long as the user does not issue a `/clear` command.

The remaining gap is the `/clear` command: when the bridge creates a brand-new session, the app's ChatScreen has no way to know about it and stays on the previous session. The user sees an empty conversation in Telegram but a stale conversation in the app.

### Overview

FEAT-045 closes this gap by adding a lightweight event notification path from the bridge layer to the UI layer. When the bridge creates a new session (due to `/clear`), it broadcasts the new session ID through a shared in-process event bus. The ChatScreen subscribes to this bus and reinitializes itself with the new session ID.

No new database tables, no new network calls, and no changes to navigation are required.

### Acceptance Criteria

1. After the user sends `/clear` via Telegram, the ChatScreen switches to display the newly created empty session within 3 seconds.
2. The session list in the drawer updates automatically (it already does so via existing Room Flow; no additional work required).
3. If the ChatScreen is not currently visible (app is backgrounded), the switch takes effect the next time the screen is foregrounded.
4. Normal bridge messages (non-`/clear`) do not trigger any session switch in the ChatScreen.
5. After the user manually switches to a session in the app, subsequent bridge messages route to that session (not the DB-most-recent session).
6. Tapping "New Conversation" in the app immediately creates a DB session and registers it as the active session for bridge routing.
7. If the current session has no messages when "New Conversation" is tapped (or `/clear` is sent), the empty session is soft-deleted before the new one is created.
8. Bridge sessions receive a meaningful title: a truncated user-message title on the first message, and an AI-generated title after the first AI response.
9. All existing Layer 1A unit tests continue to pass.

### Out of Scope

- Multi-session mapping per channel (each external chat maps to a different app session).
- Push notification when `/clear` is invoked while the app is not running.
- Support for channels other than Telegram (the mechanism is channel-agnostic, but the only `/clear` command currently implemented is in Telegram).
