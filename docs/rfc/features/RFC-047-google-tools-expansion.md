# RFC-047: Google Workspace Tools Expansion

## Document Information
- **RFC ID**: RFC-047
- **Related PRD**: [FEAT-047 (Google Tools Expansion)](../../prd/features/FEAT-047-google-tools-expansion.md)
- **Related RFC**: [RFC-040 (Tool Group Routing)](RFC-040-tool-group-routing.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClawShadow currently supports Google Workspace tools across 8 tool groups (Gmail, Gmail Settings, Calendar, Docs, Drive, Sheets, Slides, Tasks). The reference implementation (oneclaw-1) has 24 additional tools that are not yet present in shadow. This RFC adds 20 of those tools and enhances 1 existing tool to achieve near feature parity.

Three Google Places tools are excluded from this RFC because they require an API key bridge mechanism that does not exist yet in shadow (deferred to FEAT-048).

### Goals

1. Add 20 new tools across 8 existing tool groups
2. Enhance `gmail_trash` with batch support (single ID or array of IDs)
3. Add `resolveLabels()` helper to convert label names to IDs for batch operations
4. All changes in JS/JSON asset files only -- zero Kotlin modifications

### Non-Goals

- Google Places tools (needs API key bridge)
- New tool groups
- Kotlin code changes
- OAuth scope additions (all required scopes already present)

## Technical Design

### Architecture

All 20 new tools follow the existing pattern established in RFC-040:
- **JSON manifest** (`.json`): tool name, description, function reference, parameter schema, timeout
- **JavaScript implementation** (`.js`): async function using the group's `*Fetch()` helper with OAuth token from `google.getAccessToken()`

No new infrastructure is needed. Each new tool is a function added to an existing JS file with a corresponding entry in the JSON manifest.

### Gmail Enhancements (6 new + 1 enhanced)

#### Enhanced: `gmail_trash` -- batch support

The existing `gmail_trash` takes a single `message_id` string. The enhanced version accepts either:
- `message_id` (string) -- single message, uses `POST /messages/{id}/trash` (backward compatible)
- `message_ids` (array) -- multiple messages, uses `POST /messages/batchModify` with `addLabelIds: ["TRASH"], removeLabelIds: ["INBOX"]`

An `ensureArray()` helper normalizes the input.

#### Helper: `resolveLabels(labels)`

Converts an array of label names or IDs to Gmail label IDs:
1. System labels (INBOX, SENT, TRASH, etc.) are returned as-is
2. Labels matching `Label_*` pattern are treated as IDs
3. All other strings are looked up by name via `GET /labels` (with lazy cache)

Used by `gmailModifyLabels` and `gmailBatchModify`.

#### New tools

| Tool | API Call | Notes |
|------|----------|-------|
| `gmail_modify_labels` | `POST /messages/{id}/modify` or `POST /threads/{id}/modify` | Supports both message and thread targets |
| `gmail_delete_label` | `DELETE /labels/{id}` | Handles 204 No Content |
| `gmail_get_draft` | `GET /drafts/{id}?format=full` | Extracts headers and body |
| `gmail_delete_draft` | `DELETE /drafts/{id}` | Handles 204 No Content |
| `gmail_history` | `GET /history?startHistoryId=...` | Returns change records |
| `gmail_batch_modify` | `POST /messages/batchModify` | Uses `resolveLabels` for name-to-ID |

### Gmail Settings (3 new)

| Tool | API Call |
|------|----------|
| `gmail_settings_add_forwarding` | `POST /settings/forwardingAddresses` |
| `gmail_settings_set_auto_forward` | `PUT /settings/autoForwarding` |
| `gmail_settings_list_delegates` | `GET /settings/delegates` |

### Calendar (3 new)

| Tool | API Call | Notes |
|------|----------|-------|
| `calendar_respond` | GET event, modify attendee, PUT back | Uses `google.getAccountEmail()` to find/add user attendee |
| `calendar_list_colors` | `GET /colors` | Returns event and calendar color definitions |
| `calendar_instances` | `GET /calendars/{id}/events/{id}/instances` | Lists instances of recurring events |

### Docs (2 new)

| Tool | API Call |
|------|----------|
| `docs_delete_range` | `POST /documents/{id}:batchUpdate` with `deleteContentRange` |
| `docs_find_replace` | `POST /documents/{id}:batchUpdate` with `replaceAllText` |

### Drive (2 new)

| Tool | API Call |
|------|----------|
| `drive_copy` | `POST /files/{id}/copy` |
| `drive_permissions` | `GET /files/{id}/permissions` |

### Sheets (2 new)

| Tool | API Call |
|------|----------|
| `sheets_metadata` | `GET /spreadsheets/{id}?fields=...` |
| `sheets_create` | `POST /spreadsheets` |

### Slides (1 new)

| Tool | API Call |
|------|----------|
| `slides_list_slides` | `GET /presentations/{id}`, extract slides array with objectId, index, layout |

### Tasks (1 new)

| Tool | API Call |
|------|----------|
| `tasks_get_task` | `GET /lists/{listId}/tasks/{taskId}` |

## File Changes

| File | Action |
|------|--------|
| `app/src/main/assets/js/tools/google_gmail.json` | Modify: enhance `gmail_trash`, add 6 tool entries |
| `app/src/main/assets/js/tools/google_gmail.js` | Modify: add `ensureArray`, `resolveLabels`, enhance `gmailTrash`, add 6 functions |
| `app/src/main/assets/js/tools/google_gmail_settings.json` | Modify: add 3 tool entries |
| `app/src/main/assets/js/tools/google_gmail_settings.js` | Modify: add 3 functions |
| `app/src/main/assets/js/tools/google_calendar.json` | Modify: add 3 tool entries |
| `app/src/main/assets/js/tools/google_calendar.js` | Modify: add 3 functions |
| `app/src/main/assets/js/tools/google_docs.json` | Modify: add 2 tool entries |
| `app/src/main/assets/js/tools/google_docs.js` | Modify: add 2 functions |
| `app/src/main/assets/js/tools/google_drive.json` | Modify: add 2 tool entries |
| `app/src/main/assets/js/tools/google_drive.js` | Modify: add 2 functions |
| `app/src/main/assets/js/tools/google_sheets.json` | Modify: add 2 tool entries |
| `app/src/main/assets/js/tools/google_sheets.js` | Modify: add 2 functions |
| `app/src/main/assets/js/tools/google_slides.json` | Modify: add 1 tool entry |
| `app/src/main/assets/js/tools/google_slides.js` | Modify: add 1 function |
| `app/src/main/assets/js/tools/google_tasks.json` | Modify: add 1 tool entry |
| `app/src/main/assets/js/tools/google_tasks.js` | Modify: add 1 function |

**Zero Kotlin changes.** All new tools use existing OAuth, fetch, and auth bridge infrastructure.

## Testing

### Automated
- `./gradlew assembleDebug` -- build succeeds with all modified assets
- `./gradlew test` -- all existing JVM tests pass (no new tests needed for JS-only changes)

### Manual Verification
For each tool group, load it via `load_tool_group` and invoke representative tools:
1. Gmail: `gmail_batch_modify` on 3 messages, `gmail_history`, `gmail_get_draft`
2. Gmail Settings: `gmail_settings_list_delegates`
3. Calendar: `calendar_respond` to accept an event, `calendar_list_colors`
4. Docs: `docs_find_replace` on a test document
5. Drive: `drive_copy` a file, `drive_permissions` on it
6. Sheets: `sheets_create` a new spreadsheet, `sheets_metadata`
7. Slides: `slides_list_slides`
8. Tasks: `tasks_get_task`

## Rollback Plan

All changes are in asset files. Reverting the git commit restores the previous tool set. No database migrations or Kotlin changes to roll back.
