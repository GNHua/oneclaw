# Agent Memory System

## Feature Information
- **Feature ID**: FEAT-013
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-013 (Memory System)](../../rfc/features/RFC-013-memory-system.md)

## User Story

**As** a user of OneClawShadow,
**I want to** have my AI agent remember important context, preferences, and past decisions across sessions,
**so that** I don't have to repeat myself and the agent becomes more helpful over time.

### Typical Scenarios
1. User tells the agent "I prefer concise answers" in one session. In a later session, the agent remembers this preference and keeps responses brief without being reminded.
2. User has a long conversation about a project. The next day, the agent can recall the key decisions made and continue where they left off.
3. User asks "What did we discuss about the API design last week?" and the agent retrieves the relevant daily log entries to answer.
4. User manually edits the long-term memory file to correct or add facts the agent should always know.

## Feature Description

### Overview
The Agent Memory System gives OneClawShadow persistent, cross-session memory. Currently, the app stores full message history in Room DB and sends everything to the model each session -- there is no mechanism for the agent to recall information from prior sessions, and no context management beyond raw message history.

This feature introduces two layers of memory (Daily Logs and Long-term Memory), stored as Markdown files on device, with a hybrid search engine (BM25 keyword + vector semantic) that retrieves relevant memories and injects them into the system prompt. A small local embedding model runs on-device for vector search, requiring no API calls.

### Detailed Description

#### Daily Logs
- At the end of each session (and at other trigger points), the AI summarizes the conversation highlights into a Markdown file organized by date.
- Each daily log file is named by date (e.g., `2026-02-28.md`) and stored under `getFilesDir()/memory/daily/`.
- The summarization is performed by calling the active AI model with a dedicated summarization prompt.
- A `lastLoggedMessageId` pointer per session prevents duplicate summarization of already-processed messages.
- Daily logs capture: key topics discussed, decisions made, tasks completed, user preferences expressed, and notable facts.

#### Long-term Memory (MEMORY.md)
- A single persistent file (`getFilesDir()/memory/MEMORY.md`) containing stable facts, user preferences, and key knowledge the agent should always have access to.
- Populated two ways:
  - **AI auto-extraction**: During daily log writing, the AI identifies facts/preferences that should be promoted to long-term memory and appends them.
  - **User manual editing**: Users can directly view and edit MEMORY.md through a settings screen.
- MEMORY.md is always included in the system prompt (or the first N lines if it grows large), providing baseline context for every conversation.
- Organized semantically by topic with Markdown headers.

#### Hybrid Search
- When the user sends a message, the memory system searches for relevant past context.
- **BM25 keyword search (30% weight)**: Full-text search over daily log entries and memory chunks, good for exact matches and specific terms.
- **Vector semantic search (70% weight)**: Embedding-based similarity search using cosine distance, good for meaning-based retrieval.
- **Time decay**: More recent memories are weighted higher. Score is multiplied by a decay factor based on age.
- Results are merged, ranked, and the top-K most relevant memory chunks are selected for injection.

#### Local Embedding Model
- A small transformer model (~22MB, e.g., MiniLM-L6-v2) runs on-device via ONNX Runtime.
- Generates 384-dimensional embeddings for memory chunks and queries.
- No API calls required -- all embedding computation is local.
- Model asset is bundled with the app (or downloaded on first launch).

#### Memory Injection
- Retrieved memories are formatted and injected into the system prompt, below the agent's custom system prompt.
- Format: a `## Relevant Memories` section with the top-K results, each with source attribution (daily log date or MEMORY.md).
- MEMORY.md content (or first 200 lines) is always injected regardless of search results.

#### Trigger Mechanisms
Daily log writing is triggered by:
1. **Session end**: When the user explicitly ends or switches away from a session.
2. **App background**: When the app goes to the background (via `ProcessLifecycleOwner`).
3. **Session switch**: When the user switches from one session to another.
4. **Day change**: When the date changes during an active session.
5. **Pre-compaction flush**: Before FEAT-011 Auto Compact compresses the message history, important context is extracted and saved to daily logs first.

#### Message Tracking
- Each session tracks a `lastLoggedMessageId` indicating the last message that has been processed for daily log extraction.
- When a daily log trigger fires, only messages after `lastLoggedMessageId` are sent to the summarization model.
- This prevents duplicate entries and ensures efficient incremental logging.

### User Interaction Flow
```
Daily Log (automatic):
1. User has a conversation in a session
2. Session ends / app goes to background / day changes
3. System extracts new messages since lastLoggedMessageId
4. System calls AI model to summarize the conversation segment
5. Summary is appended to the daily log file (e.g., 2026-02-28.md)
6. AI identifies any long-term facts/preferences and appends to MEMORY.md
7. lastLoggedMessageId is updated

Memory Retrieval (automatic):
1. User sends a new message in any session
2. System embeds the user's message using local model
3. System runs hybrid search (BM25 + vector) over memory index
4. Top-K relevant memories are retrieved
5. Memories + MEMORY.md content are injected into system prompt
6. Message is sent to AI model with enriched context

Manual Memory Editing:
1. User navigates to Settings > Memory
2. User views MEMORY.md content in an editor
3. User adds, edits, or removes entries
4. Changes are saved and the search index is updated
```

## Acceptance Criteria

Must pass (all required):
- [ ] Daily logs are automatically created as Markdown files when any trigger fires (session end, app background, session switch, day change)
- [ ] Daily logs contain AI-summarized highlights of the conversation, not raw message dumps
- [ ] Daily logs use the `lastLoggedMessageId` tracking mechanism to avoid duplicate entries
- [ ] MEMORY.md file is created and maintained at `getFilesDir()/memory/MEMORY.md`
- [ ] AI automatically extracts stable facts/preferences to MEMORY.md during daily log writing
- [ ] User can manually view and edit MEMORY.md from a settings screen
- [ ] Local embedding model loads and produces embeddings on-device without API calls
- [ ] Hybrid search returns relevant results combining BM25 keyword and vector semantic scores
- [ ] Time decay is applied so recent memories rank higher than old ones
- [ ] Retrieved memories are injected into the system prompt in a readable format
- [ ] MEMORY.md content is always included in the system prompt (first 200 lines)
- [ ] Memory system does not block the main UI thread
- [ ] Memory files survive app updates and are not cleared by cache clearing
- [ ] Pre-compaction flush integration: daily log extraction runs before FEAT-011 compaction (when FEAT-011 is implemented)

Optional (nice to have):
- [ ] Daily log files can be viewed in the app (read-only)
- [ ] Memory search can be invoked manually by the user (e.g., "search my memory for X")
- [ ] Memory usage statistics (total files, total size, embedding count)
- [ ] Memory export/import functionality

## UI/UX Requirements

### Settings Screen Integration
- A "Memory" entry in the Settings screen
- Tapping opens a Memory management screen with:
  - MEMORY.md editor (full-text edit with save/cancel)
  - Daily log browser (list of dates, tap to view read-only)
  - Memory statistics (file count, total size)
  - "Rebuild Index" button to regenerate the search index

### Memory Indicator (Optional)
- A small icon or badge in the chat screen indicating when memories were injected into the current context
- Not required for V1

### No Visible UI During Logging
- Daily log writing and memory extraction happen silently in the background
- No progress indicator or toast is shown to the user during automatic memory operations

## Feature Boundary

### Included
- Daily conversation log summarization to Markdown files
- MEMORY.md persistent long-term memory file
- Hybrid search (BM25 + vector) with time decay
- Local on-device embedding model
- Memory injection into system prompt
- Message tracking with lastLoggedMessageId
- User-editable MEMORY.md via settings
- Pre-compaction flush integration point (for FEAT-011)
- Trigger mechanisms (session end, app background, session switch, day change)

### Not Included
- Cloud sync of memory files (deferred to FEAT-007 integration)
- Memory sharing between devices (deferred)
- Per-agent isolated memory (all agents share one memory pool)
- Voice or image-based memory
- Automated memory cleanup or expiry policies
- Memory encryption at rest (uses standard Android file security)
- Conversation branching awareness in memory
- Real-time memory updates during streaming (memory is written post-conversation)

## Business Rules

### Memory Rules
1. Daily logs are append-only within a day -- new summaries are appended, old entries are never modified automatically.
2. MEMORY.md can be modified by both the AI (append) and the user (full edit).
3. If the AI model is unavailable when a trigger fires, the daily log write is skipped silently (no crash, no error shown).
4. Memory injection must not exceed a configurable token budget (default: 2000 tokens) to avoid crowding the context window.
5. The local embedding model is loaded lazily on first use, not at app startup.

### Data Rules
- Daily log files: one file per day, Markdown format, named `YYYY-MM-DD.md`
- MEMORY.md: single file, Markdown format, organized by topic headers
- Search index: Room DB table mapping chunk IDs to embeddings and metadata
- Embeddings: 384-dimensional float vectors, stored as BLOB in Room

### Storage Rules
- Memory files are stored at `getFilesDir()/memory/` (internal app storage, survives updates)
- Daily logs: `getFilesDir()/memory/daily/`
- Long-term memory: `getFilesDir()/memory/MEMORY.md`
- Embedding model asset: `assets/models/` or downloaded to `getFilesDir()/models/`

## Non-Functional Requirements

### Performance
- Embedding a single query: < 200ms on mid-range device
- Hybrid search (query + rank): < 500ms total
- Daily log summarization: runs in background, no UI impact
- Memory injection adds < 100ms to message send flow
- Local embedding model memory footprint: < 100MB during inference

### Storage
- Embedding model size: ~22MB (ONNX format)
- Daily log files: ~1-5KB per day
- Search index: grows with content, estimated < 10MB for 1 year of daily use
- MEMORY.md: expected < 50KB for typical use

### Reliability
- If the embedding model fails to load, fall back to BM25-only search
- If daily log writing fails, log the error and continue -- do not crash the app
- If memory injection fails, send the message without memory context rather than blocking

## Dependencies

### Depends On
- **FEAT-001 (Chat Interaction)**: Needs message data and the send-message flow for memory injection
- **FEAT-005 (Session Management)**: Session lifecycle events trigger daily log writing

### Future Integration
- **FEAT-011 (Auto Compact)**: Pre-compaction flush will call memory extraction before compressing message history. The integration point is defined but FEAT-011 is not yet implemented.
- **FEAT-007 (Data Storage & Sync)**: Memory files could be included in Google Drive sync in the future.

### External Dependencies
- ONNX Runtime for Android (local model inference)
- MiniLM-L6-v2 or similar small embedding model (ONNX format)

## Error Handling

### Error Scenarios

1. **Embedding model fails to load**
   - Fallback: Use BM25-only search (no vector component)
   - Log warning, do not show error to user
   - Retry loading on next app launch

2. **AI model unavailable during daily log trigger**
   - Skip the daily log write for this trigger
   - The unprocessed messages will be picked up on the next trigger (lastLoggedMessageId is not updated)
   - No error shown to user

3. **Memory file write fails (disk full, permission error)**
   - Log error
   - Show a subtle notification if persistent (e.g., disk full warning)
   - Do not crash

4. **Search index corrupted**
   - Provide "Rebuild Index" button in settings
   - On detection, fall back to file-only search until rebuilt

5. **MEMORY.md becomes very large (> 200 lines)**
   - Only first 200 lines are injected into system prompt
   - User is advised to curate/trim via the settings editor

## Test Points

### Functional Tests
- Verify daily log file is created on session end
- Verify daily log file is created on app background
- Verify daily log file is created on session switch
- Verify daily log file is created on day change
- Verify lastLoggedMessageId prevents duplicate entries
- Verify MEMORY.md is created and can be edited
- Verify AI auto-extracts preferences to MEMORY.md
- Verify local embedding model produces valid embeddings
- Verify BM25 search returns relevant keyword matches
- Verify vector search returns semantically similar results
- Verify hybrid search combines scores correctly (30/70 split)
- Verify time decay reduces scores for older memories
- Verify memory injection appears in system prompt
- Verify MEMORY.md is always included in system prompt
- Verify memory injection respects token budget

### Performance Tests
- Embedding latency on various device tiers
- Search latency with 100, 500, 1000 daily log entries
- Memory footprint of loaded embedding model
- Battery impact of background embedding operations

### Edge Cases
- First launch with no memory files
- Empty conversation (no messages to summarize)
- Very long conversation (1000+ messages in one session)
- Multiple triggers firing in rapid succession
- Concurrent session switch and app background
- MEMORY.md edited while daily log is being written
- Embedding model asset missing or corrupted

## Data Requirements

### Data Entities
| Data Item | Type | Required | Description |
|-----------|------|----------|-------------|
| Daily log file | Markdown file | Yes | AI-summarized conversation highlights per day |
| MEMORY.md | Markdown file | Yes | Long-term persistent memory |
| MemoryIndex | Room entity | Yes | Search index with chunk text, embeddings, metadata |
| lastLoggedMessageId | String (per session) | Yes | Tracks last processed message for daily log |
| Embedding model | ONNX model file | Yes | Local embedding model asset |

### Data Storage
- **Markdown files**: `getFilesDir()/memory/` -- internal app storage, not user-accessible via file manager
- **Search index**: Room DB `memory_index` table
- **Embedding model**: `assets/models/` (bundled) or `getFilesDir()/models/` (downloaded)
- **Retention**: Memory files are retained indefinitely; user can manually delete via settings

## Future Improvements

- [ ] Per-agent memory isolation (each agent has its own memory pool)
- [ ] Memory sharing/export across devices
- [ ] Automated memory cleanup policies (archive old daily logs)
- [ ] Richer memory types (images, structured data)
- [ ] Memory-aware conversation suggestions
- [ ] Integration with FEAT-007 for cloud backup of memory files
- [ ] User-facing memory search command ("search memory for X")

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
