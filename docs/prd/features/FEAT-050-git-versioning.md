# FEAT-050: Git-based File Versioning

## Document Information
- **Feature ID**: FEAT-050
- **Status**: Draft
- **Created**: 2026-03-02
- **Last Updated**: 2026-03-02

## Background

OneClaw stores user-facing files — memory logs, daily conversation summaries, AI-generated reports, and markdown documents — in the app's internal `filesDir`. Currently, the only versioning mechanism is a simple rotating backup of `MEMORY.md` (up to 5 timestamped copies). This approach:

- Covers only `MEMORY.md`, not daily logs or AI-generated files
- Retains at most 5 snapshots with no granular change history
- Provides no ability to see what changed between versions
- Cannot be used by the AI agent to inspect or restore past content

## Goals

1. Replace the existing backup mechanism with a proper git repository tracking all text files under `filesDir`
2. Auto-commit every write to memory files and AI-generated markdown, creating a continuous change history
3. Expose a set of git tools so the AI agent can query history, inspect diffs, and restore files
4. Schedule periodic `git gc` to keep repository size under control
5. Allow users to create a portable git bundle for manual archival (e.g., upload to Google Drive via existing Google tools)

## Non-Goals

- No remote sync to GitHub or any git hosting service
- No branch management; all commits go to the default branch (`main`)
- No in-app UI for browsing git history (deferred to a future feature)
- No versioning of binary files (images, videos, PDFs, database files)

## User Stories

**US-1: Automatic versioning of memory**
As a user, I want every change to my `MEMORY.md` and daily logs to be automatically saved as a git commit, so I never lose a previous version of my memories.

**US-2: AI-assisted history inspection**
As a user, I want to ask the AI to "show me what changed in my memory last week" or "restore yesterday's daily log", and have the AI execute git commands to fulfill the request.

**US-3: Manual backup via bundle**
As a user, I want to be able to schedule a task that bundles the entire git repository into a single file and uploads it to Google Drive, so I have an off-device backup without exposing my data to any third-party git hosting.

**US-4: AI-generated files versioned**
As a user, I want AI-generated markdown reports and notes to be automatically committed, so I can recover any past version of an AI-produced document.

## Functional Requirements

### FR-1: Git Repository Initialization
- On first launch (or upgrade from a version without this feature), initialize a git repository at `context.filesDir`
- Create an initial commit containing all existing eligible files
- Write a `.gitignore` file to exclude binary and internal files (see FR-2)

### FR-2: Gitignore Configuration
The `.gitignore` at the repo root must exclude:
```
attachments/
bridge_images/
*.jpg
*.jpeg
*.png
*.gif
*.webp
*.bmp
*.mp4
*.onnx
*.pdf
*.db
*.db-shm
*.db-wal
*.bundle
*.zip
```

### FR-3: Auto-commit Triggers
A commit is created automatically after each of the following operations:
- Write to `MEMORY.md` (via `LongTermMemoryManager`)
- Append to a daily log file (via `MemoryFileStorage.appendToDailyLog`)
- Write any file via the AI `write_file` tool
- Delete a file via the AI `delete_file` tool or the Files UI

Commit messages follow this format:
| Trigger | Commit message |
|---|---|
| First init | `init: initialize memory repository` |
| MEMORY.md update | `memory: update MEMORY.md` |
| Daily log append | `log: add daily log YYYY-MM-DD` |
| AI write_file | `file: write <relative-path>` |
| File deletion | `file: delete <relative-path>` |
| Periodic gc | `gc: repository maintenance` |

### FR-4: Remove Legacy Backup Mechanism
Remove `createBackup()`, `pruneOldBackups()`, `restoreFromBackup()`, and `listBackups()` from `MemoryFileStorage`. The `MAX_BACKUPS` constant and the `BACKUP_PREFIX`/`BACKUP_SUFFIX`/`BACKUP_TIMESTAMP_FORMAT` companions are also removed.

### FR-5: AI Git Tools
Expose the following tools to the AI agent:

| Tool | Description |
|---|---|
| `git_log` | List commits, optionally filtered by file path and limited by count |
| `git_show` | Show the content and diff of a specific commit by SHA |
| `git_diff` | Show the diff between two commits, or between a commit and the working tree |
| `git_restore` | Restore a specific file to its state at a given commit SHA |
| `git_bundle` | Create a git bundle file at a specified output path for manual archival |

### FR-6: Periodic Repository Maintenance
- Schedule a monthly WorkManager task that runs `git gc`
- The task creates a commit with message `gc: repository maintenance` after compaction
- Run on the IO dispatcher; skip if the repo does not exist

## Non-Functional Requirements

- All git operations run on the IO dispatcher (never on the main thread)
- JGit is used as the git implementation (pure Java, no native binary required)
- Git operations must not block or delay the UI; they are fire-and-forget after each write
- The `.git/` directory is excluded from any Room database queries or file browser listing

## Out of Scope

- Multi-device sync
- Conflict resolution
- Tag or release management
