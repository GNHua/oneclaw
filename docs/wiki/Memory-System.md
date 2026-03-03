# Memory System

OneClaw provides a persistent memory system that allows the AI to remember information across conversations. Memory is automatically injected into system prompts, giving the AI context about the user's preferences, projects, and past interactions.

## Memory Types

### Long-Term Memory (MEMORY.md)

A structured Markdown file stored at `filesDir/memory/MEMORY.md`. Its content is injected into the system prompt of every conversation.

Organized by category:
- **profile** -- User identity and background
- **preferences** -- Communication and workflow preferences
- **interests** -- Topics and hobbies
- **workflow** -- How the user works
- **projects** -- Active projects and goals
- **notes** -- General information

Managed via the `save_memory` and `update_memory` tools.

### Daily Logs

Automatic daily summaries stored at `filesDir/memory/daily/{YYYY-MM-DD}.md`. Written by `DailyLogWriter` at specific trigger points:

1. **Session end** -- When a conversation session ends
2. **App background** -- When the app goes to the background
3. **Session switch** -- When the user switches to a different session
4. **Day change** -- When the date changes during use
5. **Pre-compaction flush** -- Before auto-compact runs

Daily logs capture conversation summaries and are searchable via the `search_history` tool.

## Hybrid Search Engine

The search system combines two approaches for best results:

### BM25 Keyword Search (30% weight)

Full-text keyword scoring using the BM25 algorithm. Finds exact term matches across memory chunks.

### Vector Semantic Search (70% weight)

Embedding-based similarity search using cosine distance. Finds semantically related content even without exact keyword matches.

**Embedding Engine:**
- Uses ONNX Runtime with MiniLM-L6-v2 model (~22MB)
- Generates 384-dimensional vectors
- Falls back gracefully to BM25-only search when the model is unavailable
- Embeddings stored in Room via `MemoryIndexDao`

### Time Decay

A time decay multiplier weights recent memories higher than older ones, ensuring that recent context is prioritized in search results.

### Search Flow

```
Query
  |
  +---> BM25Scorer -> keyword matches with scores
  |
  +---> VectorSearcher -> semantic matches with cosine similarity
  |
  v
HybridSearchEngine
  |
  +---> Merge results (0.3 * BM25 + 0.7 * vector)
  +---> Apply time decay multiplier
  +---> Return top-K ranked results
```

## Memory Injection

`MemoryInjector` retrieves relevant memories and prepends them to the system prompt before each AI request. This provides the AI with context about the user without the user needing to repeat information.

## Memory Quality (FEAT-049)

The memory quality system scores and manages memory entries to prevent bloat:
- Quality scoring for memory entries
- Automated cleanup of low-quality or duplicate entries
- Ensures MEMORY.md stays concise and relevant

## Git Versioning (FEAT-050)

All text files under `filesDir` are automatically versioned in a git repository managed by JGit. Every write to `MEMORY.md`, daily logs, or AI-generated markdown triggers a commit. Binary files (images, videos, database files) are excluded via `.gitignore`.

**Auto-commit triggers:**

| Event | Commit message |
|---|---|
| First app launch | `init: initialize memory repository` |
| MEMORY.md updated | `memory: update MEMORY.md` |
| Daily log appended | `log: add daily log YYYY-MM-DD` |
| AI writes a file | `file: write <path>` |
| File deleted | `file: delete <path>` |
| Monthly gc | `gc: repository maintenance` |

The legacy rotating-backup mechanism has been replaced entirely by git history.

## Memory UI

The Memory screen (`feature/memory/ui/MemoryScreen.kt`) provides:
- View and browse daily logs by date
- View and edit long-term memory
- Memory statistics display
- **Version History** — tap the history icon in the top bar to open the git commit browser (FEAT-051)

### Version History Screen

The Git History screen shows all commits in reverse chronological order with filter chips:

| Filter | Shows |
|---|---|
| All | Every commit |
| Memory | Changes to `MEMORY.md` only |
| Daily Logs | Changes to `memory/daily/` files |
| Files | AI-generated file changes |

Tapping a commit opens a diff view in a bottom sheet showing exactly what text was added or removed.

## Related Tools

| Tool | Purpose |
|------|---------|
| `save_memory` | Add new entries to MEMORY.md |
| `update_memory` | Edit or delete existing entries |
| `search_history` | Search across memory, daily logs, and past sessions |
| `git_log` | List commit history, optionally filtered by file path |
| `git_show` | Show the diff for a specific commit |
| `git_diff` | Compare two commits |
| `git_restore` | Restore a file to a previous version |
| `git_bundle` | Export the repository as a bundle file for backup |

## File Layout

```
filesDir/
├── .git/               # Git repository (hidden from Files UI)
├── .gitignore          # Excludes binaries and DB files
├── memory/
│   ├── MEMORY.md       # Long-term memory (injected into system prompt)
│   └── daily/
│       ├── 2026-02-28.md
│       ├── 2026-03-01.md
│       └── ...
└── attachments/        # Chat attachments (not versioned)
```
