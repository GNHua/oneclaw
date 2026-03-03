# Data Storage & Sync

## Feature Information
- **Feature ID**: FEAT-007
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: TBD
- **Related Feature**: [FEAT-005 (Session Management)](FEAT-005-session.md)

## User Story

**As** a user of OneClaw,
**I want to** back up my data to Google Drive and export it locally,
**so that** I can restore my conversations and settings on a new device or recover from data loss.

### Typical Scenarios

1. User sets up Google Drive sync. Every hour, the app automatically uploads changed data to their Google Drive. If they get a new phone, they can restore everything.
2. User wants a local backup before a major change. They tap "Export Backup" and get a file they can save or share.
3. User sets up the app on a second device, signs into Google Drive, and restores their sessions, agents, and settings.

## Feature Description

### Overview

Data Storage & Sync provides two backup mechanisms: automatic Google Drive sync (incremental, hourly) and manual local export (full backup file). All data is local-first -- sync is an add-on for backup and cross-device use. API keys are never synced.

### Google Drive Sync

#### Setup
- User signs in with their Google account from the Settings screen.
- After sign-in, sync is enabled and begins automatically.
- The sync status is visible in Settings (last sync time, sync status).

#### What Gets Synced
All app data except API keys:
- Sessions and message history
- Agent configurations (built-in and custom)
- Provider configurations (provider name, API base URL, models -- but NOT API keys)
- App settings/preferences
- Token usage data (stored in messages)

#### What Does NOT Get Synced
- API keys (never leave the device unless user explicitly re-enters them on the new device)
- Roborazzi screenshots or local temp files
- App cache

#### Sync Behavior
- **Frequency**: Every 1 hour, automatic. Only runs when the app is in the foreground or has recently been used.
- **Incremental**: Only uploads records that have changed since the last sync (based on `updatedAt` timestamps or a change log).
- **Conflict resolution**: Last-write-wins. If the same record was modified on two devices, the most recently modified version overwrites the other.
- **Direction**: Bidirectional. Changes from the device are pushed up; changes from Drive are pulled down.
- **Manual trigger**: User can tap "Sync Now" in Settings to force an immediate sync.
- **Background**: Sync runs on a background coroutine. UI is never blocked.

#### Restore Flow
- On a new device, user opens Settings, signs into Google Drive.
- App detects existing backup data on Drive.
- User confirms "Restore from backup".
- All data is downloaded and merged into the local database.
- API keys are empty -- user must re-enter them per provider.

### Local Export

- User taps "Export Backup" in Settings.
- App generates a single file (JSON or ZIP) containing all app data (same scope as Google Drive sync, excluding API keys).
- System share sheet opens so the user can save the file, send it via email, etc.
- Import: User taps "Import Backup" and selects a previously exported file. Data is restored (overwrites local data with a confirmation prompt).

## Acceptance Criteria

Must pass (all required):
- [ ] User can sign in to Google Drive from Settings
- [ ] After sign-in, sync begins automatically every hour
- [ ] Sync is incremental (only changed data is uploaded)
- [ ] Last sync time is displayed in Settings
- [ ] "Sync Now" button triggers immediate sync
- [ ] API keys are never included in synced or exported data
- [ ] New device can restore from Google Drive backup
- [ ] After restore, API keys are empty (user must re-enter)
- [ ] User can export a local backup file
- [ ] User can import a local backup file (with confirmation prompt before overwriting)
- [ ] Sync does not block the UI
- [ ] Conflict resolution uses last-write-wins

Optional (nice to have):
- [ ] Sync progress indicator during large syncs
- [ ] Sync error notification if sync fails repeatedly

## UI/UX Requirements

### Settings Entries

In the Settings screen, add a "Data & Backup" section:

```
Data & Backup
  Google Drive Sync         Connected (user@gmail.com)
                            Last synced: 5 minutes ago
                            [Sync Now]

  Export Backup             Save all data to a file
  Import Backup             Restore from a backup file
```

If not signed in:

```
Data & Backup
  Google Drive Sync         Not connected
                            [Sign in with Google]

  Export Backup             Save all data to a file
  Import Backup             Restore from a backup file
```

### Restore Confirmation Dialog

When restoring from Google Drive or importing a local backup:

```
Restore from Backup?

This will replace all current data with the backup.
API keys will not be restored -- you will need to
re-enter them in provider settings.

[Cancel]  [Restore]
```

### Sync Status Indicators

- Syncing in progress: small `CircularProgressIndicator` next to the sync status text
- Sync error: red error text with retry option
- Sync complete: green checkmark or "Last synced: X minutes ago"

## Feature Boundary

### Included
- Google Drive automatic sync (hourly, incremental)
- Manual "Sync Now"
- Local export (full backup file)
- Local import (restore from file)
- Restore from Google Drive on new device
- Sync status display in Settings

### Not Included
- Selective sync (choose what to sync)
- Additional cloud providers (Dropbox, OneDrive)
- API key sync (explicitly excluded)
- Sync scheduling configuration (fixed at 1 hour)
- End-to-end encryption of synced data (relies on Google Drive's built-in encryption)
- Merge conflict UI (always last-write-wins, no user prompt)

## Business Rules

1. API keys are NEVER included in any sync or export operation.
2. Sync is bidirectional: local changes push to Drive, Drive changes pull to local.
3. Conflict resolution is last-write-wins based on the record's `updatedAt` timestamp.
4. Sync only runs when the app has been recently active (not a persistent background service).
5. Local export includes all data in the same scope as Google Drive sync.
6. Import overwrites local data entirely (not a merge). User must confirm before proceeding.
7. After restore from backup, the app behaves normally except API keys are empty.

## Dependencies

### Depends On
- **FEAT-005 (Session Management)**: Sessions and messages are the primary data to sync.
- **FEAT-002 (Agent Management)**: Agent configurations are synced.
- **FEAT-003 (Provider Management)**: Provider configurations (minus API keys) are synced.
- **FEAT-009 (Settings)**: Sync settings live in the Settings screen.

### Depended On By
- None

## Test Points

### Functional Tests -- User Operating the App

#### Test 1: Google Drive sign-in and initial sync

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Go to Settings > Data & Backup | "Google Drive Sync" shows "Not connected". |
| 2 | Tap "Sign in with Google" | Google sign-in flow opens. Select an account. |
| 3 | Complete sign-in | Status changes to "Connected (user@gmail.com)". Sync begins. |
| 4 | Wait for sync to complete | "Last synced: just now" appears. |

#### Test 2: Incremental sync

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | With sync enabled, send a few messages in a chat | Messages are saved locally. |
| 2 | Tap "Sync Now" | Sync runs. Only the new messages (not all history) are uploaded. |
| 3 | Observe sync status | "Last synced: just now" updates. |

#### Test 3: Restore on new device

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | On a new device, install the app | Fresh install, setup screen. |
| 2 | Complete setup, go to Settings > Data & Backup | "Google Drive Sync" shows "Not connected". |
| 3 | Sign in with the same Google account | App detects existing backup on Drive. |
| 4 | Tap "Restore" when prompted | Confirmation dialog appears. Tap "Restore". |
| 5 | Wait for restore to complete | All sessions, agents, settings are restored. |
| 6 | Check provider settings | API keys are empty -- user must re-enter them. |
| 7 | Re-enter an API key and send a message | Chat works normally with restored history. |

#### Test 4: Local export and import

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Go to Settings > Data & Backup | "Export Backup" option visible. |
| 2 | Tap "Export Backup" | File is generated. System share sheet opens. |
| 3 | Save the file (e.g., to Downloads) | File is saved successfully. |
| 4 | Clear app data: `adb shell pm clear com.oneclaw.shadow` | App is reset to fresh state. |
| 5 | Go through setup, then Settings > Data & Backup | "Import Backup" option visible. |
| 6 | Tap "Import Backup", select the saved file | Confirmation dialog: "This will replace all current data." |
| 7 | Tap "Restore" | Data is restored. Sessions, agents, settings are back. API keys are empty. |

#### Test 5: API keys are not synced

| Step | User Action | Expected Result |
|------|-------------|-----------------|
| 1 | Configure a provider with an API key | Provider shows "Connected". |
| 2 | Export a local backup | File is generated. |
| 3 | Inspect the exported file (open in text editor) | No API key values present in the file. |

### Edge Cases

- Sync with no internet: sync fails gracefully, retries next hour
- Sync with empty app (no sessions, no custom agents): sync succeeds with minimal data
- Very large history (1000+ sessions): sync completes without crashing, may take longer
- Sign out of Google Drive: sync stops, existing local data is not affected
- Import a backup from an older app version: graceful handling (skip unknown fields, don't crash)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | TBD |
