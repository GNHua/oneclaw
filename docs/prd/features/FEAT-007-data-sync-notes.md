# FEAT-007: Data Storage & Sync - Discussion Notes

## Status: Not yet discussed in detail. Waiting for detailed PRD writing.

## What we know from overview discussions:
- All data stored locally first (local-first architecture)
- Google Drive sync for cross-device backup/restore
- Sync is user-initiated or configurable auto-sync
- User provides their own Google account
- No backend server operated by us
- API keys are NOT synced by default (opt-in only, discussed in FEAT-003)

## Data to sync:
- Sessions and message history
- Agent configurations (custom agents)
- Provider configurations (excluding API keys by default)
- App settings/preferences
- Token usage statistics

## Open questions to discuss:
1. **Sync granularity**: Sync everything or let user choose what to sync (e.g., sync agents but not sessions)?
2. **Conflict resolution**: If the same session is modified on two devices, how do we resolve conflicts? Last-write-wins? Merge?
3. **Sync frequency**: Manual only? On app open? Periodic (every N minutes)?
4. **Initial sync / migration**: When user sets up a new device, how does the restore flow work?
5. **Storage format on Google Drive**: Single file? Multiple files? Database dump? JSON export?
6. **Sync progress UI**: Show sync progress/status? Last sync time?
7. **Data size limits**: Google Drive has storage limits. What happens when sync data exceeds available space?
8. **Encryption**: Should synced data be encrypted on Google Drive? (API keys definitely, but what about conversations?)
9. **Selective restore**: Can user restore only certain sessions or agents from a backup?

## Future exploration (from overview):
- Additional cloud sync providers (Dropbox, OneDrive, etc.)
- Local backup export (zip packaging to a local folder)
