# Feature Overview

OneClaw has 51 features, each documented with a PRD and RFC. Features are organized by category below.

## Core (P0)

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-001 | Chat Interaction | Real-time streaming chat with tool call display and markdown rendering | [PRD](../prd/features/FEAT-001-chat.md) | [RFC](../rfc/features/RFC-001-chat-interaction.md) |
| FEAT-002 | Agent Management | Create and configure AI personas with custom system prompts and tool sets | [PRD](../prd/features/FEAT-002-agent.md) | [RFC](../rfc/features/RFC-002-agent-management.md) |
| FEAT-003 | Provider Management | Configure AI providers (OpenAI, Anthropic, Gemini) with API keys and models | [PRD](../prd/features/FEAT-003-provider.md) | [RFC](../rfc/features/RFC-003-provider-management.md) |
| FEAT-004 | Tool System | Extensible tool framework for AI to interact with the device and services | [PRD](../prd/features/FEAT-004-tool-system.md) | [RFC](../rfc/features/RFC-004-tool-system.md) |
| FEAT-005 | Session Management | Multiple conversation sessions with rename, delete, and auto-title | [PRD](../prd/features/FEAT-005-session.md) | [RFC](../rfc/features/RFC-005-session-management.md) |

## Data and Infrastructure

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-006 | Token Tracking | Track and display token usage per message, session, and model | [PRD](../prd/features/FEAT-006-token-tracking.md) | [RFC](../rfc/features/RFC-006-token-tracking.md) |
| FEAT-007 | Data Sync | Google Drive backup and local JSON export/import | [PRD](../prd/features/FEAT-007-data-sync.md) | [RFC](../rfc/features/RFC-007-data-sync.md) |
| FEAT-008 | Notifications | Background task completion and failure notifications | [PRD](../prd/features/FEAT-008-notifications.md) | [RFC](../rfc/features/RFC-008-notifications.md) |
| FEAT-009 | Settings | App configuration: theme, providers, agents, data backup | [PRD](../prd/features/FEAT-009-settings.md) | [RFC](../rfc/features/RFC-009-settings.md) |

## Chat Enhancements

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-010 | Concurrent Input | Send multiple messages while AI is still streaming | [PRD](../prd/features/FEAT-010-concurrent-input.md) | [RFC](../rfc/features/RFC-010-concurrent-user-input.md) |
| FEAT-011 | Auto Compact | Automatically compress message history when approaching context limits | [PRD](../prd/features/FEAT-011-auto-compact.md) | [RFC](../rfc/features/RFC-011-auto-compact.md) |
| FEAT-016 | Chat Input Redesign | Improved chat input bar with attachment support and skill invocation | [PRD](../prd/features/FEAT-016-chat-input-redesign.md) | [RFC](../rfc/features/RFC-016-chat-input-redesign.md) |
| FEAT-026 | File Attachments | Attach images and files to chat messages | [PRD](../prd/features/FEAT-026-file-attachments.md) | [RFC](../rfc/features/RFC-026-file-attachments.md) |

## Tool System

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-012 | JS Tool Engine | Execute JavaScript tools via QuickJS sandboxed runtime | [PRD](../prd/features/FEAT-012-js-tool-engine.md) | [RFC](../rfc/features/RFC-012-js-tool-engine.md) |
| FEAT-015 | JS Tool Migration | Migrate built-in tools from JS to Kotlin for performance | [PRD](../prd/features/FEAT-015-js-tool-migration.md) | [RFC](../rfc/features/RFC-015-js-tool-migration.md) |
| FEAT-017 | Tool Management | UI for enabling/disabling tools and viewing tool status | [PRD](../prd/features/FEAT-017-tool-management.md) | [RFC](../rfc/features/RFC-017-tool-management.md) |
| FEAT-018 | JS Tool Group | Group JavaScript tools for organized loading | [PRD](../prd/features/FEAT-018-js-tool-group.md) | [RFC](../rfc/features/RFC-018-js-tool-group.md) |
| FEAT-034 | JS Eval Tool | In-chat JavaScript evaluation for computation tasks | [PRD](../prd/features/FEAT-034-js-eval-tool.md) | [RFC](../rfc/features/RFC-034-js-eval-tool.md) |
| FEAT-035 | JS Tool Creator | AI-assisted creation of custom JavaScript tools | [PRD](../prd/features/FEAT-035-js-tool-creator.md) | [RFC](../rfc/features/RFC-035-js-tool-creator.md) |
| FEAT-036 | Config Tools | Tools for managing providers, models, agents, and settings via chat | [PRD](../prd/features/FEAT-036-config-tools.md) | [RFC](../rfc/features/RFC-036-config-tools.md) |
| FEAT-040 | Tool Group Routing | Route tool groups to specific agents automatically | [PRD](../prd/features/FEAT-040-tool-group-routing.md) | [RFC](../rfc/features/RFC-040-tool-group-routing.md) |
| FEAT-044 | Tool Group Persistence | Persist tool group enabled state across sessions | [PRD](../prd/features/FEAT-044-tool-group-persistence.md) | [RFC](../rfc/features/RFC-044-tool-group-persistence.md) |

## Agent and Skill System

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-014 | Agent Skill | Reusable prompt templates invoked via slash commands | [PRD](../prd/features/FEAT-014-agent-skill.md) | [RFC](../rfc/features/RFC-014-agent-skill.md) |
| FEAT-020 | Agent Enhancement | Agent parameters, preferred model, and cloning | [PRD](../prd/features/FEAT-020-agent-enhancement.md) | [RFC](../rfc/features/RFC-020-agent-enhancement.md) |
| FEAT-038 | Agent Parameters | Temperature and model override per agent | [PRD](../prd/features/FEAT-038-agent-parameters.md) | [RFC](../rfc/features/RFC-038-agent-parameters.md) |

## Memory and Search

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-013 | Memory System | Persistent long-term memory with daily logs and system prompt injection | [PRD](../prd/features/FEAT-013-memory.md) | [RFC](../rfc/features/RFC-013-memory-system.md) |
| FEAT-023 | Memory Enhancement | Hybrid search with BM25 + vector embeddings and time decay | [PRD](../prd/features/FEAT-023-memory-enhancement.md) | [RFC](../rfc/features/RFC-023-memory-enhancement.md) |
| FEAT-032 | Search History | Unified search across sessions, memory, and daily logs | [PRD](../prd/features/FEAT-032-search-history.md) | [RFC](../rfc/features/RFC-032-search-history.md) |
| FEAT-049 | Memory Quality | Memory quality scoring and automated cleanup | [PRD](../prd/features/FEAT-049-memory-quality.md) | [RFC](../rfc/features/RFC-049-memory-quality.md) |
| FEAT-050 | Git Versioning | Git-based versioning of all text files in app storage with auto-commit | [PRD](../prd/features/FEAT-050-git-versioning.md) | [RFC](../rfc/features/RFC-050-git-versioning.md) |
| FEAT-051 | Git History UI | In-app browser for git commit history with filter and diff view | [PRD](../prd/features/FEAT-051-git-history-ui.md) | [RFC](../rfc/features/RFC-051-git-history-ui.md) |

## Web and Content

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-021 | Webfetch Tool | Fetch web pages and convert to Markdown | [PRD](../prd/features/FEAT-021-kotlin-webfetch.md) | [RFC](../rfc/features/RFC-021-kotlin-webfetch.md) |
| FEAT-022 | WebView Browser | Full browser rendering with screenshot and content extraction | [PRD](../prd/features/FEAT-022-webview-browser.md) | [RFC](../rfc/features/RFC-022-webview-browser.md) |
| FEAT-025 | File Browsing | Browse and preview files in app storage | [PRD](../prd/features/FEAT-025-file-browsing.md) | [RFC](../rfc/features/RFC-025-file-browsing.md) |
| FEAT-029 | Shell Exec Tool | Execute shell commands on the device | [PRD](../prd/features/FEAT-029-exec-tool.md) | [RFC](../rfc/features/RFC-029-exec-tool.md) |
| FEAT-031 | Web Search | Provider-native web search (grounding) | [PRD](../prd/features/FEAT-031-provider-web-search.md) | [RFC](../rfc/features/RFC-031-provider-web-search.md) |
| FEAT-033 | PDF Tools | Extract text, metadata, and render pages from PDF files | [PRD](../prd/features/FEAT-033-pdf-tools.md) | [RFC](../rfc/features/RFC-033-pdf-tools.md) |

## Scheduled Tasks

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-019 | Scheduled Tasks | Time-based task scheduling with daily and weekly repeats | [PRD](../prd/features/FEAT-019-scheduled-tasks.md) | [RFC](../rfc/features/RFC-019-scheduled-tasks.md) |
| FEAT-027 | Scheduled Task Tools | Create and manage scheduled tasks via chat | [PRD](../prd/features/FEAT-027-scheduled-task-tools.md) | [RFC](../rfc/features/RFC-027-scheduled-task-tools.md) |
| FEAT-028 | Scheduled Task Detail | Detailed view with execution history | [PRD](../prd/features/FEAT-028-scheduled-task-detail.md) | [RFC](../rfc/features/RFC-028-scheduled-task-detail.md) |
| FEAT-037 | Exact Alarm Permission | Handle Android 12+ exact alarm permission requirements | [PRD](../prd/features/FEAT-037-exact-alarm-permission.md) | [RFC](../rfc/features/RFC-037-exact-alarm-permission.md) |

## Google Integration

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-030 | Google Workspace | Google Drive file access tools | [PRD](../prd/features/FEAT-030-google-workspace.md) | [RFC](../rfc/features/RFC-030-google-workspace.md) |
| FEAT-043 | Google OAuth Bugfix | Fix Google OAuth flow reliability | [PRD](../prd/features/FEAT-043-google-oauth-bugfix.md) | [RFC](../rfc/features/RFC-043-google-oauth-bugfix.md) |
| FEAT-047 | Google Tools Expansion | Additional Google Drive tools (create, update, share) | [PRD](../prd/features/FEAT-047-google-tools-expansion.md) | [RFC](../rfc/features/RFC-047-google-tools-expansion.md) |

## Messaging Bridge

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-024 | Messaging Bridge | Multi-channel messaging bridge as foreground service | [PRD](../prd/features/FEAT-024-messaging-bridge.md) | [RFC](../rfc/features/RFC-024-messaging-bridge.md) |
| FEAT-041 | Bridge Improvements | Enhanced bridge reliability and UX | [PRD](../prd/features/FEAT-041-bridge-improvements.md) | [RFC](../rfc/features/RFC-041-bridge-improvements.md) |
| FEAT-042 | Telegram Image Delivery | Image sending and receiving for Telegram channel | [PRD](../prd/features/FEAT-042-telegram-image-delivery.md) | [RFC](../rfc/features/RFC-042-telegram-image-delivery.md) |
| FEAT-045 | Bridge Session Sync | Sync bridge sessions with main app UI | [PRD](../prd/features/FEAT-045-bridge-session-sync.md) | [RFC](../rfc/features/RFC-045-bridge-session-sync.md) |
| FEAT-046 | Bridge Response Fix | Fix response delivery reliability | [PRD](../prd/features/FEAT-046-bridge-response-delivery-fix.md) | [RFC](../rfc/features/RFC-046-bridge-response-delivery-fix.md) |
| FEAT-048 | Bridge Channel Parity | Feature parity across all messaging channels | [PRD](../prd/features/FEAT-048-bridge-channel-parity.md) | [RFC](../rfc/features/RFC-048-bridge-channel-parity.md) |

## Maintenance

| ID | Feature | Description | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-039 | Bugfix and Polish | Cross-cutting bug fixes and UI polish | [PRD](../prd/features/FEAT-039-bugfix-polish.md) | [RFC](../rfc/features/RFC-039-bugfix-polish.md) |
