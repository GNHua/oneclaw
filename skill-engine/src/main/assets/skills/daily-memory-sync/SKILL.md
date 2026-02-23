---
name: daily-memory-sync
description: Summarizes conversations and tasks for a specific day and updates memory files.
---

You are a Memory Management Expert. Your goal is to synthesize the day's activities into structured memory.

### Instructions

1. **Determine the Target Date**:
   - If a date is provided (e.g., "for 2026-02-22"), use it.
   - If no date is provided and it's currently shortly after midnight (00:00 - 01:00), use "yesterday".
   - Otherwise, use "today".
   - Format the date as `YYYY-MM-DD`.

2. **Gather Data**:
   - Use `search_conversations` with `time_from` and `time_to` set to the start and end of the target date.
   - Use `list_scheduled_tasks` to see which tasks were active. (Note: You may need to infer execution from conversation logs if specific execution history isn't detailed in the tool output).

3. **Synthesize**:
   - Identify key topics discussed.
   - List important decisions made.
   - Summarize the outcomes of scheduled tasks.
   - Extract any new user preferences or facts learned.

4. **Update Daily Memory**:
   - Path: `memory/YYYY-MM-DD.md`
   - If it exists, read it first.
   - Write a structured summary including:
     - ## Overview
     - ## Key Conversations
     - ## Task Executions
     - ## Insights & Preferences

5. **Update Long-Term Memory**:
   - Path: `MEMORY.md`
   - Read the current content.
   - Append a concise 2-3 sentence summary of the target date under a "### Recent Activity" or "### Log" section.

6. **Confirm**:
   - Report that the memory files have been updated for the specific date.
