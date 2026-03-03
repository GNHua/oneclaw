# Google Workspace Tools Expansion

## Feature Information
- **Feature ID**: FEAT-047
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-047 (Google Tools Expansion)](../../rfc/features/RFC-047-google-tools-expansion.md)
- **Related Feature**: [FEAT-040 (Tool Group Routing)](FEAT-040-tool-group-routing.md)

## User Story

**As** a user of OneClaw,
**I want** a comprehensive set of Google Workspace tools comparable to other AI agent platforms,
**so that** I can perform advanced operations like batch email management, calendar RSVP, document editing, and file copying without switching to a browser.

### Typical Scenarios
1. User says "mark all these 15 promotional emails as read and move them to the Promotions label" -- the AI uses `gmail_batch_modify` to apply label changes across all messages in one call.
2. User says "accept the team meeting invitation for tomorrow" -- the AI uses `calendar_respond` to RSVP as accepted.
3. User says "find and replace 'Q3' with 'Q4' in my quarterly report doc" -- the AI uses `docs_find_replace` to do a global text replacement.
4. User says "make a copy of this spreadsheet and put it in the Archive folder" -- the AI uses `drive_copy` with a target parent folder.
5. User says "what are the forwarding addresses on my Gmail?" -- the AI uses `gmail_settings_list_forwarding_addresses` (existing) or adds a new one via `gmail_settings_add_forwarding`.

## Feature Description

### Overview
This feature expands the Google Workspace tool coverage from the existing set to include 20 new tools and 1 enhanced tool across 8 existing tool groups. The focus is on batch operations (Gmail batch modify, batch trash), calendar event responses, document editing operations, and file management utilities.

### New Tools Summary

| Group | Tools | Count |
|-------|-------|-------|
| Google Gmail | `gmail_modify_labels`, `gmail_delete_label`, `gmail_get_draft`, `gmail_delete_draft`, `gmail_history`, `gmail_batch_modify` + enhanced `gmail_trash` | 6 new + 1 enhanced |
| Google Gmail Settings | `gmail_settings_add_forwarding`, `gmail_settings_set_auto_forward`, `gmail_settings_list_delegates` | 3 new |
| Google Calendar | `calendar_respond`, `calendar_list_colors`, `calendar_instances` | 3 new |
| Google Docs | `docs_delete_range`, `docs_find_replace` | 2 new |
| Google Drive | `drive_copy`, `drive_permissions` | 2 new |
| Google Sheets | `sheets_metadata`, `sheets_create` | 2 new |
| Google Slides | `slides_list_slides` | 1 new |
| Google Tasks | `tasks_get_task` | 1 new |

### Out of Scope
- **Google Places** (3 tools: `places_search`, `places_details`, `places_nearby`) -- deferred to FEAT-048 because it requires an API key bridge mechanism that does not exist in the current architecture.

## Success Criteria
1. All 20 new tools and 1 enhanced tool load and execute correctly via the tool group routing system.
2. Existing tools continue to function without regression.
3. Zero Kotlin code changes required -- all changes are in JS/JSON asset files.
4. Build and existing tests pass without modification.

## Risks and Mitigations
| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| OAuth scope insufficient for new operations | Low | Medium | All required scopes are already declared in GoogleAuthManager.SCOPES |
| Batch operations hitting API rate limits | Low | Low | Gmail API batch endpoints have generous quotas |
| 204 No Content responses breaking gmailFetch | Medium | Medium | New delete tools use direct fetch with explicit 204 handling |
