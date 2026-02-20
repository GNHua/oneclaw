---
layout: default
title: OneClaw
---

[Home](./)

# Memory System

How OneClaw stores and retrieves context across conversations.

---

## Overview

OneClaw uses a file-based memory model with `MEMORY.md` plus daily files. Memory is eagerly loaded into the system prompt at session start, and a `search_memory` tool provides on-demand lookups mid-conversation.

---

## File Structure

- **Long-term memory**: `MEMORY.md` at workspace root (curated facts, decisions, preferences)
- **Daily memory**: `memory/YYYY-MM-DD.md` (append-only daily notes)
- Plain markdown is the source of truth.

---

## Memory Loading

`MemoryBootstrap.loadMemoryContext()` runs at session start:

1. Reads `MEMORY.md` (long-term) -- max 4,000 characters
2. Reads today's daily memory (`memory/YYYY-MM-DD.md`) -- max 4,000 characters
3. Reads yesterday's daily memory -- max 2,000 characters
4. Returns a formatted context block wrapped in `--- Your Memory ---` markers
5. If no memory files exist, returns empty string
6. Overflow marked with `[...truncated]`

### Character Limits

| File | Max Characters |
|------|---------------|
| `MEMORY.md` | 4,000 |
| Today's daily memory | 4,000 |
| Yesterday's daily memory | 2,000 |

---

## System Prompt Injection

`SystemPromptBuilder.buildFullSystemPrompt()` appends the memory context to the system prompt if present.

Memory is loaded identically in all three execution paths:

1. **Interactive chat** -- `ChatExecutionService.buildSystemPrompt()`
2. **Delegate/sub-agent** -- `DelegateAgentPlugin.buildSubAgentSystemPrompt()`
3. **Scheduled tasks** -- `ScheduledAgentExecutor.executeTask()`

All converge on `MemoryBootstrap.loadMemoryContext()` + `SystemPromptBuilder.buildFullSystemPrompt()`.

---

## search_memory Tool

| Field | Value |
|---|---|
| Plugin | `MemoryPlugin` (`lib-workspace`) |
| Category | `core` (always visible to LLM) |
| Tool name | `search_memory` |

Takes a `query` parameter (required, 2+ characters).

### Search Algorithm

1. Searches both `MEMORY.md` and all daily files in `memory/` directory
2. Daily files sorted descending by name (newest first)
3. Returns max 20 results with context (1 line before/after match)
4. Each result shows file path, line number, and snippet with matched line prefixed with `>`

### Search Implementation

- Case-insensitive substring matching
- Line-by-line scanning
- Context extraction (1 line before/after)
- Formats output as markdown with file path, line number, and code block

---

## Memory Write

Memory is written via **WorkspacePlugin** tools (`write_file`, `edit_file`). The LLM is instructed to:

- Save memories proactively to daily files at `memory/YYYY-MM-DD.md`
- Use `search_memory` to find past memories when relevant context might exist
- Update long-term curated memory by reading and writing `MEMORY.md` directly

---

## Pre-Summarization Memory Flush

When conversation context grows too large, OneClaw flushes important information to memory before summarizing.

### Trigger

Context tokens exceed 80% of the context window.

### Process

1. Only runs if conversation history has more than 2 messages
2. Prompts the LLM to review the conversation and save important information (decisions, preferences, facts, pending tasks) to memory files
3. Runs a separate short ReAct loop (max 5 iterations, temperature 0.3)
4. Has access to `write_file` and `edit_file` tools during flush

Also triggered explicitly via `/summarize` command or `summarize_conversation` tool.

---

## Prompt Strategy

**Passive**: Memory content is injected directly into the system prompt. The agent reads it passively at the start of each session and can call `search_memory` for additional lookups.

---

## Key Files

| Component | File |
|-----------|------|
| Memory loading & instructions | `app/.../service/MemoryBootstrap.kt` |
| System prompt builder | `skill-engine/.../skill/SystemPromptBuilder.kt` |
| search_memory tool | `lib-workspace/.../workspace/MemoryPlugin.kt` |
| search_memory metadata | `lib-workspace/.../workspace/MemoryPluginMetadata.kt` |
| Tool registration | `app/.../plugin/PluginCoordinator.kt` |
| write_file tool | `lib-workspace/.../workspace/WorkspacePlugin.kt` |
| Memory flush | `app/.../service/ChatExecutionService.kt` |
| Summarization trigger | `core-agent/.../agent/AgentCoordinator.kt` |
