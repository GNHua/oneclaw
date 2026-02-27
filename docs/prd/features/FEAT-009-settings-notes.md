# FEAT-009: Settings - Discussion Notes

## Status: Not yet discussed in detail. Waiting for detailed PRD writing.

## What we know from overview discussions:
- UI preferences (theme, font size, etc.)
- Default model/provider selection (covered in FEAT-003)
- Notification preferences
- Data management (clear cache, manage storage)

## Open questions to discuss:
1. **Theme**: Dark mode / light mode / system default?
2. **Font size**: Follow system, or app-level override?
3. **Language**: App language setting? Or follow system language?
4. **Chat display settings**: Message bubble style, compact vs spacious layout?
5. **Tool call default display mode**: Default to compact or detailed? (Referenced in FEAT-001)
6. **Data management**: What can user clear? Cache only? All sessions? Reset app?
7. **About/version info**: Show app version, open source licenses, etc.?
8. **Settings scope**: Some settings are already in other modules (provider in FEAT-003, agent in FEAT-002, sync in FEAT-007). What lives in the global Settings screen vs in each module's own screen?
9. **Backup reminder**: Prompt user to set up sync if they haven't?
