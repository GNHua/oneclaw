# Memory Quality Improvement

## Feature Information
- **Feature ID**: FEAT-049
- **Created**: 2026-03-02
- **Last Updated**: 2026-03-02
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-049 (Memory Quality Improvement)](../../rfc/features/RFC-049-memory-quality.md)
- **Extends**: [FEAT-023 (Memory System Enhancement)](FEAT-023-memory-enhancement.md)

## User Story

**As** a user of OneClaw,
**I want** the AI to save only meaningful, non-redundant information to long-term memory,
**so that** MEMORY.md remains a concise, high-signal knowledge base rather than accumulating noise like repeated model-switch records, duplicate entries, and trivial observations.

### Pain Points (Current State)

The `save_memory` tool (FEAT-023) works mechanically -- it appends whatever the AI decides to save. However, the tool description is too vague about what constitutes "important information", leading to:

1. **Transient state pollution** -- The AI saves every model configuration change (e.g., "User switched from Opus to Haiku"), producing 6+ near-identical entries in a single week
2. **Duplicate accumulation** -- The same preference (e.g., "User wants to clean promotional emails") gets saved 3 times in different phrasings because the AI never checks existing content
3. **Low-value observations** -- Random visual details from screenshots (e.g., "User has a colorful patterned area rug") are persisted permanently
4. **No update mechanism** -- All writes are append-only; outdated facts (e.g., old model preference) can never be corrected, only piled upon

### Typical Scenarios

1. User switches their default model from Sonnet to Haiku. The AI should NOT save this -- it is transient configuration, not a long-term preference.
2. User says "Remember that I prefer dark mode in all apps." The AI saves it. Later the user says "Actually, I prefer light mode now." The AI should UPDATE the existing entry, not append a contradictory one.
3. User has a conversation about cleaning Gmail inbox. The AI identifies a pattern after multiple conversations: "User wants email automation tools." It saves a concise summary once, not three times.
4. User shares a screenshot. The AI should NOT save observations about furniture or room decor unless explicitly asked.

## Feature Description

### Overview

This feature improves memory quality through three progressive phases:

1. **Phase 1 -- Prompt Guardrails**: Rewrite the `save_memory` tool description with explicit save/skip criteria, negative examples, and a deduplication check requirement
2. **Phase 2 -- Read-Before-Write**: Add a mechanism for the AI to see existing MEMORY.md content before saving, plus an `update_memory` tool for modifying existing entries
3. **Phase 3 -- Structured Memory with Compaction**: Organize MEMORY.md into semantic sections, add periodic compaction that merges duplicates and removes stale entries

### Detailed Specification

#### Phase 1: Prompt Guardrails (save_memory tool description rewrite)

Replace the current vague tool description with a precise one that includes:

**Positive triggers** (WHEN to save):
- User explicitly says "remember this" / "save this"
- Stable user preferences confirmed across 2+ conversations
- Important personal context (profession, key projects, domain expertise)
- Recurring workflow patterns observed multiple times

**Negative triggers** (WHEN NOT to save):
- Transient state: current model selection, temporary settings, session-specific config
- One-time observations: screenshot contents, single-use requests, visual environment details
- Information that changes frequently (e.g., "currently working on X")
- Information already present in MEMORY.md (must check before saving)
- Inferred personality traits from a single interaction

**Pre-save checklist** (embedded in the tool description):
1. Would this still be useful 30 days from now?
2. Is this already in the existing memory? If so, skip or update.
3. Is this a confirmed pattern or just a one-time event?

#### Phase 2: Read-Before-Write + Update Mechanism

Two changes:

1. **Inject existing memory into save_memory response**: When `save_memory` is called, the tool returns the current MEMORY.md content alongside the success message, so the AI can see what already exists. Alternatively, the tool description instructs the AI to use `read_memory` (or the injected system prompt memory) before calling save.

2. **New `update_memory` tool**: Inspired by Mem0's ADD/UPDATE/DELETE/NOOP classification and Letta's `memory_replace`/`memory_rethink` tools. This tool allows the AI to:
   - Replace a specific existing entry with updated content
   - Delete an outdated entry
   - The AI provides the old text (or a unique substring) and the new text

#### Phase 3: Structured Memory with Compaction

1. **Sectioned MEMORY.md**: Organize memory into semantic categories:
   ```markdown
   # Long-term Memory

   ## User Profile
   - Software engineer, maker/builder personality

   ## Preferences
   - Prefers dark mode in all apps
   - Creative writing: prefers longer-form content

   ## Interests
   - Card game: Sushi Go
   - Stock price data retrieval

   ## Workflow
   - Gmail: wants promotional email cleanup automation
   - Screenshots: prefers 900x2048, adequate JS render wait time
   ```

2. **Memory compaction with backup**: A background process (triggered on app background or manually) that:
   - Creates a timestamped backup of MEMORY.md before any destructive operation (e.g., `MEMORY_backup_2026-03-02_14-30-00.md`)
   - Reads all MEMORY.md content
   - Uses LLM to merge duplicates, remove contradictions (keeping the latest), and reorganize into sections
   - Overwrites MEMORY.md with the compacted version
   - Prunes old backups (keeps the most recent 5)
   - Reindexes for search
   - If compaction goes wrong, the user (or developer) can restore from the backup

3. **save_memory with category**: Add an optional `category` parameter so the AI places new memories in the correct section.

## Acceptance Criteria

### Phase 1
- [ ] `save_memory` tool description includes explicit positive/negative trigger lists
- [ ] `save_memory` tool description includes a pre-save checklist
- [ ] AI demonstrably skips saving model switch events (manual test)
- [ ] AI demonstrably avoids saving duplicate information (manual test)
- [ ] AI does not save random visual observations from screenshots (manual test)
- [ ] All existing `SaveMemoryTool` unit tests still pass

### Phase 2
- [ ] `update_memory` tool is registered and available to all agents
- [ ] AI can replace an existing memory entry via `update_memory`
- [ ] AI can delete an outdated memory entry via `update_memory`
- [ ] `update_memory` validates that the target text exists in MEMORY.md
- [ ] `update_memory` returns error if target text is not found
- [ ] AI uses existing memory context before saving new entries (manual test)

### Phase 3
- [ ] MEMORY.md uses section headers for organization
- [ ] `save_memory` accepts an optional `category` parameter
- [ ] A timestamped backup is created before every compaction
- [ ] Backup retention policy keeps at most 5 most recent backups
- [ ] Memory compaction reduces duplicate entries (manual test with seeded duplicates)
- [ ] Memory compaction preserves the latest version of contradictory facts
- [ ] Memory compaction reindexes content for search
- [ ] Compaction can be triggered manually from memory settings screen
- [ ] Compaction runs automatically on day change (at most once per day, if memory size exceeds threshold)
- [ ] Backup can be restored programmatically (future: from memory settings screen)

## Feature Boundary

### Included
- Rewritten `save_memory` tool description (Phase 1)
- New `update_memory` built-in tool (Phase 2)
- Structured MEMORY.md with section headers (Phase 3)
- Memory compaction logic (Phase 3)
- Optional `category` parameter on `save_memory` (Phase 3)

### Not Included
- Knowledge graph or entity-relationship extraction (Zep/Graphiti style) -- too complex for current needs
- Automatic embedding-based deduplication at write time -- deferred to future optimization
- Full memory versioning or undo history UI (but pre-compaction backups are included for safety)
- Cross-device memory sync
- Memory import/export

## Business Rules

### Memory Save Rules (Phase 1)
1. The tool description is the primary quality control mechanism -- the AI must self-regulate based on the prompt
2. No programmatic filtering is applied; the LLM is trusted to follow the description
3. The 5,000 character limit and non-empty validation remain unchanged from FEAT-023

### Memory Update Rules (Phase 2)
1. `update_memory` requires exact match of the `old_text` parameter in MEMORY.md
2. If `old_text` is not found, the tool returns an error (no partial matching)
3. If `new_text` is empty/null, the entry is deleted (old_text is removed)
4. Whitespace around old_text/new_text is trimmed before matching
5. Only one replacement per tool call (no global replace)

### Memory Compaction Rules (Phase 3)
1. A timestamped backup of MEMORY.md is created BEFORE every compaction (no exceptions)
2. Backup retention: keep the 5 most recent backups; older ones are auto-pruned after each compaction
3. Compaction uses the same LLM model as the active chat session
4. Compaction has a token budget limit to prevent excessive API costs
5. Compaction preserves the most recent version of contradictory facts
6. Compaction never removes entries the user explicitly asked to remember (entries with "User requested:" prefix)
7. Auto-compaction triggers only on day change (`onDayChangeForActiveSession`), at most once per day
8. Size threshold for auto-compaction: MEMORY.md exceeds 3,000 characters
8. If the LLM returns an empty or suspiciously short result, compaction is aborted and the original is kept (backup still exists as additional safety)

## Dependencies

### Depends On
- **FEAT-023 (Memory System Enhancement)**: This feature extends `save_memory` and the memory system
- **FEAT-013 (Memory System)**: Base memory infrastructure
- **FEAT-004 (Tool System)**: Tool registration for `update_memory`

### Depended On By
- None currently

## Error Handling

### Error Scenarios

1. **`update_memory` target text not found**
   - Tool returns: `ToolResult.error("not_found", "The specified text was not found in MEMORY.md. Use read_memory to check current content.")`
   - AI reports the error and may retry with corrected text

2. **`update_memory` target text matches multiple locations**
   - Tool returns: `ToolResult.error("ambiguous_match", "The specified text matches multiple locations. Provide more surrounding context to make the match unique.")`
   - AI provides more context and retries

3. **Memory compaction LLM call fails**
   - Handling: Original MEMORY.md is preserved (backup was already created before the LLM call)
   - User impact: None; compaction retried on next trigger

4. **Memory compaction produces empty result**
   - Handling: Reject the result and keep the original content; backup is still available
   - Log a warning for debugging

5. **Memory compaction produces bad result (data loss)**
   - Handling: Pre-compaction backup allows restore via `MemoryFileStorage.restoreFromBackup()`
   - Future: Memory settings screen will expose a "Restore from backup" option

5. **MEMORY.md is locked during compaction**
   - Handling: Use a Mutex to prevent concurrent writes during compaction
   - `save_memory` and `update_memory` calls during compaction wait for completion

## Test Points

### Phase 1 Tests
- Manual: Ask AI to switch models 3 times, verify no model-switch entries in MEMORY.md
- Manual: Tell AI the same preference twice, verify only one entry
- Manual: Share a screenshot, verify no furniture/decor observations saved
- Unit: All existing SaveMemoryTool tests pass without modification

### Phase 2 Tests
- Unit: `update_memory` replaces target text successfully
- Unit: `update_memory` with empty new_text deletes the entry
- Unit: `update_memory` returns error when target not found
- Unit: `update_memory` returns error for ambiguous matches
- Manual: Tell AI "I now prefer light mode" after it saved "prefers dark mode" -- verify update

### Phase 3 Tests
- Unit: `save_memory` with `category` places entry in correct section
- Unit: Backup file created before compaction with correct timestamp format
- Unit: Old backups pruned when exceeding retention limit (5)
- Unit: Restore from backup overwrites MEMORY.md correctly
- Unit: Compaction merges duplicate entries
- Unit: Compaction preserves most recent contradictory fact
- Unit: Compaction reindexes after overwrite
- Manual: Seed MEMORY.md with known duplicates, trigger compaction, verify backup exists and cleanup performed
- Manual: Restore from backup after simulated bad compaction
- Manual: Verify auto-compaction triggers when MEMORY.md exceeds 3,000 chars

## Industry Context

This design is informed by research into existing AI memory systems:

- **Mem0**: Two-stage extract-then-update pipeline with ADD/UPDATE/DELETE/NOOP classification (Phase 2 draws from this)
- **Letta (MemGPT)**: Agent self-manages memory via `memory_replace`/`memory_rethink` tools (Phase 2's `update_memory` is inspired by this)
- **LangChain/LangMem**: Distinguishes semantic/episodic/procedural memory; uses Profile objects that merge-on-update (Phase 3's structured sections borrow this concept)
- **Anthropic Claude Memory Tool**: File-based approach with `str_replace` for updates; system prompt instructs agent to check memory before acting (Phase 1 and 2 align with this)
- **Zep/Graphiti**: Temporal knowledge graph with edge invalidation for contradictions (too complex for our needs, but the "invalidate-don't-delete" principle informs Phase 3's compaction)

The key insight across all systems: **the quality of memory is determined at write time, not read time**. Filtering and structuring memories before they are persisted is far more effective than trying to clean up a noisy memory store at retrieval time.

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-02 | 0.1 | Initial version | - |
| 2026-03-02 | 0.2 | Add pre-compaction backup mechanism with retention policy | - |
| 2026-03-02 | 0.3 | Change compaction trigger from onAppBackground to onDayChange | - |
