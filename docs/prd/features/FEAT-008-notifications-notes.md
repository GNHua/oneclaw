# FEAT-008: Notifications - Discussion Notes

## Status: Not yet discussed in detail. Waiting for detailed PRD writing.

## What we know from overview discussions:
- Android push notifications
- Notify when a long-running agent task completes
- Notification tap navigates back to the relevant session

## Open questions to discuss:
1. **What counts as "long-running"?**: Is it time-based (task takes > N seconds)? Or is it only when the app is in the background?
2. **Notification content**: Just "Task completed" or include a preview of the result?
3. **Notification channels**: Android supports notification channels with different priorities. Do we need multiple channels (e.g., task completion, errors, sync status)?
4. **In-app notifications**: When the app is in the foreground, should we still show a notification? Or just update the UI?
5. **Error notifications**: Notify on errors too? (e.g., "Task failed: API error")
6. **Do Not Disturb**: Respect system DND settings (this is automatic on Android, but worth noting).
7. **Notification grouping**: If multiple tasks complete, group them or show individually?
8. **Sound/vibration**: Custom notification sound? Or just use system default?
