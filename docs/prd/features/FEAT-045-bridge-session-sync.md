# Bridge-App Session Synchronization

## Feature Information
- **Feature ID**: FEAT-045
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-045 (Bridge Session Sync)](../../rfc/features/RFC-045-bridge-session-sync.md)
- **Related Feature**: [FEAT-041 (Bridge Improvements)](FEAT-041-bridge-improvements.md)

## User Story

**As** a user who controls the AI agent through both the Telegram bridge and the OneClawShadow app,
**I want** the ChatScreen to automatically follow whichever session the bridge is currently using,
**so that** there is always a single shared active session and I never lose track of the ongoing conversation.

### Typical Scenarios

1. User sends `/clear` via Telegram. The bridge creates a new empty session. Within 3 seconds, the app's ChatScreen switches to that new session without any manual action by the user.
2. Normal bridge message arrives. The ChatScreen is already displaying the bridge's current session (established by FEAT-041). Messages appear in real time in both Telegram and the app.
3. User manually switches to a different session inside the app. Subsequent bridge messages route to that session (existing behavior from FEAT-041); ChatScreen remains on that session.

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
5. Manual session switching by the user in the app is unaffected.
6. All existing Layer 1A unit tests continue to pass.

### Out of Scope

- Reverse synchronization: notifying the bridge when the user switches sessions in the app (already handled by `getMostRecentSessionId()` in FEAT-041).
- Multi-session mapping per channel (each external chat maps to a different app session).
- Push notification when `/clear` is invoked while the app is not running.
- Support for channels other than Telegram (the mechanism is channel-agnostic, but the only `/clear` command currently implemented is in Telegram).
