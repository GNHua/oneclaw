# Project Structure

Annotated directory layout of the OneClaw project.

## Top Level

```
oneclaw-1/
├── app/                           # Main Android application module
├── bridge/                        # Messaging bridge Android library module
├── docs/                          # All documentation (PRDs, RFCs, ADRs, wiki)
├── gradle/                        # Gradle wrapper and version catalog
│   └── libs.versions.toml         # Centralized dependency versions
├── build.gradle.kts               # Root build script
├── settings.gradle.kts            # Module declarations
├── gradle.properties              # Gradle configuration
├── signing.properties             # Release signing config (not committed)
├── CLAUDE.md                      # AI coding assistant instructions
└── README.md                      # Project overview
```

## App Module (`app/`)

```
app/
├── build.gradle.kts               # App dependencies and build config
├── proguard-rules.pro             # ProGuard/R8 rules for release builds
├── schemas/                       # Room database schema exports (for migrations)
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── assets/
    │   │   └── skills/            # Built-in skills (SKILL.md bundles)
    │   │       ├── create-skill/
    │   │       └── create-tool/
    │   ├── kotlin/com/oneclaw/shadow/
    │   │   ├── MainActivity.kt    # Single-activity entry point
    │   │   ├── OneclawApplication.kt  # Application class, Koin initialization
    │   │   ├── core/              # Domain layer
    │   │   ├── data/              # Data layer
    │   │   ├── di/                # Dependency injection
    │   │   ├── feature/           # Feature modules
    │   │   ├── navigation/        # Compose Navigation
    │   │   ├── tool/              # Tool system
    │   │   └── ui/                # Theme and shared UI components
    │   └── res/                   # Android resources (layouts, strings, etc.)
    ├── test/                      # JVM unit tests (Layer 1A)
    └── androidTest/               # Instrumented tests (Layer 1B)
```

## Core Layer (`core/`)

```
core/
├── lifecycle/
│   └── AppLifecycleObserver.kt        # App lifecycle events
├── model/                             # Domain models (20 files)
│   ├── Agent.kt                       # AI agent configuration
│   ├── AgentConstants.kt              # Built-in agent definitions
│   ├── AiModel.kt                     # Model definition (id, name, context window)
│   ├── Attachment.kt                  # File attachment metadata
│   ├── Citation.kt                    # Web search citation
│   ├── ConnectionTestResult.kt        # Provider connection test result
│   ├── FileContent.kt                 # File content wrapper
│   ├── FileInfo.kt                    # File metadata
│   ├── Message.kt                     # Chat message (role, content, tokens)
│   ├── Provider.kt                    # API provider configuration
│   ├── ProviderCapability.kt          # Provider feature flags
│   ├── ResolvedModel.kt              # Resolved provider + model pair
│   ├── ScheduledTask.kt              # Scheduled task definition
│   ├── Session.kt                     # Conversation session
│   ├── SkillDefinition.kt            # Skill metadata
│   ├── TaskExecutionRecord.kt        # Task run history
│   ├── ToolDefinition.kt             # Tool schema (name, params, description)
│   ├── ToolGroupDefinition.kt        # Tool group metadata
│   ├── ToolResult.kt                 # Tool execution result
│   └── ToolSourceInfo.kt             # Tool origin info (builtin/js/group)
├── notification/
│   └── NotificationHelper.kt         # Notification channel management
├── repository/                        # Repository interfaces (9 files)
│   ├── AgentRepository.kt
│   ├── AttachmentRepository.kt
│   ├── FileRepository.kt
│   ├── MessageRepository.kt
│   ├── ProviderRepository.kt
│   ├── ScheduledTaskRepository.kt
│   ├── SessionRepository.kt
│   ├── SettingsRepository.kt
│   └── TaskExecutionRecordRepository.kt
├── theme/
│   └── ThemeManager.kt                # Theme mode management (system/light/dark)
└── util/
    ├── AppResult.kt                   # Sealed class for fallible operations
    └── ErrorCode.kt                   # Error code enum
```

## Data Layer (`data/`)

```
data/
├── local/
│   ├── dao/                           # Room DAOs (10 files)
│   │   ├── AgentDao.kt
│   │   ├── AttachmentDao.kt
│   │   ├── MemoryIndexDao.kt
│   │   ├── MessageDao.kt
│   │   ├── ModelDao.kt
│   │   ├── ProviderDao.kt
│   │   ├── ScheduledTaskDao.kt
│   │   ├── SessionDao.kt
│   │   ├── SettingsDao.kt
│   │   └── TaskExecutionRecordDao.kt
│   ├── db/
│   │   ├── AppDatabase.kt            # Room database definition
│   │   ├── Converters.kt             # Type converters for Room
│   │   └── Migrations.kt             # Database migration scripts
│   ├── entity/                        # Room entities (10 files)
│   │   ├── AgentEntity.kt
│   │   ├── AttachmentEntity.kt
│   │   ├── MemoryIndexEntity.kt
│   │   ├── MessageEntity.kt
│   │   ├── ModelEntity.kt
│   │   ├── ProviderEntity.kt
│   │   ├── ScheduledTaskEntity.kt
│   │   ├── SessionEntity.kt
│   │   ├── SettingsEntity.kt
│   │   └── TaskExecutionRecordEntity.kt
│   └── mapper/                        # Entity <-> domain model mappers (7 files)
│       ├── AgentMapper.kt
│       ├── AttachmentMapper.kt
│       ├── MessageMapper.kt
│       ├── ProviderMapper.kt
│       ├── ScheduledTaskMapper.kt
│       ├── SessionMapper.kt
│       └── TaskExecutionRecordMapper.kt
├── remote/
│   ├── adapter/                       # API adapters (7 files)
│   │   ├── ModelApiAdapter.kt         # Adapter interface
│   │   ├── ModelApiAdapterFactory.kt  # Factory by ProviderType
│   │   ├── OpenAiAdapter.kt
│   │   ├── AnthropicAdapter.kt
│   │   ├── GeminiAdapter.kt
│   │   ├── ApiMessage.kt              # API message types
│   │   └── StreamEvent.kt            # Streaming event types
│   ├── dto/                           # Provider-specific DTOs
│   │   ├── openai/OpenAiModelListResponse.kt
│   │   ├── anthropic/AnthropicModelListResponse.kt
│   │   └── gemini/GeminiModelListResponse.kt
│   └── sse/
│       └── SseParser.kt              # Server-Sent Events parser
├── repository/                        # Repository implementations (9 files)
├── security/
│   ├── ApiKeyStorage.kt              # Encrypted API key storage
│   └── GoogleAuthManager.kt          # Google OAuth management
├── storage/
│   └── UserFileStorage.kt            # File I/O for user data
└── sync/
    ├── BackupManager.kt              # Google Drive backup
    ├── SyncManager.kt                # Sync orchestration
    └── SyncWorker.kt                 # Background sync via WorkManager
```

## Feature Layer (`feature/`)

Each feature follows a consistent structure:

```
feature/<name>/
├── <Name>Screen.kt                    # Composable screen
├── <Name>ViewModel.kt                 # ViewModel with StateFlow
├── <Name>UiState.kt                   # UI state data class
└── usecase/                           # Business logic use cases
    └── <Action>UseCase.kt
```

### Feature Directories

```
feature/
├── agent/                             # Agent CRUD + selection UI
├── bridge/                            # Bridge integration (app-side)
│   ├── BridgeConversationManagerImpl.kt
│   ├── BridgeAgentExecutorImpl.kt
│   ├── BridgeMessageObserverImpl.kt
│   └── BridgeSettingsScreen.kt
├── chat/                              # Main chat interface
│   ├── components/                    # Chat sub-components
│   │   ├── AttachmentDisplay.kt
│   │   ├── AttachmentPickerSheet.kt
│   │   ├── AttachmentPreviewRow.kt
│   │   └── ImageViewerDialog.kt
│   └── usecase/
│       ├── SendMessageUseCase.kt      # Core message flow
│       ├── AutoCompactUseCase.kt
│       ├── SaveAttachmentUseCase.kt
│       ├── CompactAwareMessageBuilder.kt
│       └── MessageToApiMapper.kt
├── file/                              # File browser and preview
├── memory/                            # Memory management
│   ├── compaction/                    # Auto-compaction logic
│   ├── embedding/                     # ONNX embedding engine
│   ├── injection/                     # System prompt injection
│   ├── log/                           # Daily log writer
│   ├── longterm/                      # Long-term memory management
│   ├── search/                        # Hybrid search engine
│   ├── storage/                       # Memory file storage
│   ├── trigger/                       # Memory write triggers
│   └── ui/                            # Memory screen
├── provider/                          # Provider/model config
├── schedule/                          # Scheduled tasks
│   ├── alarm/                         # Android alarm system
│   │   ├── AlarmScheduler.kt
│   │   ├── BootCompletedReceiver.kt
│   │   ├── ExactAlarmHelper.kt
│   │   ├── NextTriggerCalculator.kt
│   │   └── ScheduledTaskReceiver.kt
│   ├── usecase/                       # Task CRUD and execution
│   ├── util/                          # Schedule formatting
│   └── worker/                        # WorkManager integration
├── search/                            # Unified search
├── session/                           # Session lifecycle
├── settings/                          # App settings
│   ├── DataBackupScreen.kt
│   ├── GoogleAuthScreen.kt
│   ├── JsToolsSection.kt
│   └── EnvVarsSection.kt
├── skill/                             # Skill management
│   └── ui/
│       ├── SkillEditorScreen.kt
│       ├── SkillManagementScreen.kt
│       ├── SkillSelectionBottomSheet.kt
│       └── SlashCommandPopup.kt
├── tool/                              # Tool management UI
└── usage/                             # Token usage statistics
```

## Tool System (`tool/`)

```
tool/
├── engine/                            # Core tool infrastructure
│   ├── Tool.kt                        # Tool interface
│   ├── ToolRegistry.kt               # Tool registration and lookup
│   ├── ToolExecutionEngine.kt        # Execution with validation and timeout
│   ├── ToolEnabledStateStore.kt      # Enable/disable state persistence
│   ├── ToolCallModels.kt            # Tool call data models
│   ├── ToolSchemaSerializer.kt       # JSON schema serialization
│   └── PermissionChecker.kt          # Android permission checking
├── builtin/                           # Built-in Kotlin tools (23 files)
│   ├── BrowserTool.kt
│   ├── WebfetchTool.kt
│   ├── ExecTool.kt
│   ├── JsEvalTool.kt
│   ├── SaveMemoryTool.kt
│   ├── UpdateMemoryTool.kt
│   ├── SearchHistoryTool.kt
│   ├── CreateAgentTool.kt
│   ├── Create/Update/Delete/List ScheduledTaskTools
│   ├── Create/Update/Delete/List JsToolTools
│   ├── LoadSkillTool.kt
│   ├── LoadToolGroupTool.kt
│   ├── PdfExtractTextTool.kt
│   ├── PdfInfoTool.kt
│   ├── PdfRenderPageTool.kt
│   └── config/                        # Config management tools (17 files)
│       ├── Provider tools (Create/Update/Delete/List)
│       ├── Model tools (Add/Delete/List/Fetch/SetDefault)
│       ├── Agent tools (Delete/List/Update)
│       ├── GetConfigTool.kt / SetConfigTool.kt
│       ├── ManageEnvVarTool.kt
│       ├── ListToolStatesTool.kt
│       └── SetToolEnabledTool.kt
├── js/                                # JavaScript engine
│   ├── JsExecutionEngine.kt          # QuickJS runtime management
│   ├── JsTool.kt                     # JS tool wrapper
│   ├── JsToolLoader.kt              # Load JS tools from files
│   ├── UserToolManager.kt           # User tool lifecycle
│   ├── EnvironmentVariableStore.kt   # Encrypted env var storage
│   └── bridge/                        # JS <-> Kotlin bridges
│       ├── ConsoleBridge.kt           # console.log/warn/error
│       ├── FetchBridge.kt            # fetch() via OkHttp
│       ├── FsBridge.kt               # fs.readFile/writeFile/exists
│       ├── TimeBridge.kt             # _time() date formatting
│       ├── LibraryBridge.kt          # lib() reusable libraries
│       ├── GoogleAuthBridge.kt       # Google OAuth integration
│       └── FileTransferBridge.kt     # File upload/download
├── skill/
│   ├── SkillRegistry.kt             # Skill discovery and caching
│   └── SkillFileParser.kt           # SKILL.md YAML+MD parser
├── browser/
│   ├── WebViewManager.kt            # WebView lifecycle
│   ├── BrowserContentExtractor.kt   # Page content extraction
│   └── BrowserScreenshotCapture.kt  # Page screenshot capture
└── util/
    ├── HtmlToMarkdownConverter.kt    # HTML -> Markdown conversion
    └── PdfToolUtils.kt              # PDF utility functions
```

## Bridge Module (`bridge/`)

```
bridge/
├── build.gradle.kts
└── src/main/kotlin/com/oneclaw/shadow/bridge/
    ├── BridgeAgentExecutor.kt         # Agent execution interface
    ├── BridgeBroadcaster.kt           # Outbound broadcast manager
    ├── BridgeConversationManager.kt   # Session management interface
    ├── BridgeMessage.kt               # Message data model
    ├── BridgeMessageObserver.kt       # Message observation interface
    ├── BridgePreferences.kt           # SharedPreferences for config
    ├── BridgeStateTracker.kt          # In-process event bus (RFC-045)
    ├── channel/
    │   ├── ChannelMessage.kt          # Inbound message model
    │   ├── ChannelType.kt             # TELEGRAM, DISCORD, SLACK, LINE, MATRIX, WEBCHAT
    │   ├── ConversationMapper.kt      # External chat -> session mapping
    │   ├── MessagingChannel.kt        # Abstract base channel
    │   ├── telegram/
    │   │   ├── TelegramApi.kt         # Telegram Bot API client
    │   │   ├── TelegramChannel.kt     # Polling-based channel
    │   │   └── TelegramHtmlRenderer.kt # Markdown -> Telegram HTML
    │   ├── discord/
    │   │   ├── DiscordChannel.kt      # WebSocket gateway channel
    │   │   └── DiscordGateway.kt      # Discord gateway client
    │   ├── slack/
    │   │   ├── SlackChannel.kt        # Socket Mode channel
    │   │   ├── SlackMrkdwnRenderer.kt # Markdown -> Slack mrkdwn
    │   │   └── SlackSocketMode.kt     # Slack Socket Mode client
    │   ├── line/
    │   │   ├── LineApi.kt             # LINE Messaging API client
    │   │   ├── LineChannel.kt         # Webhook-based channel
    │   │   └── LineWebhookServer.kt   # Local HTTP server for webhooks
    │   ├── matrix/
    │   │   ├── MatrixApi.kt           # Matrix API client
    │   │   └── MatrixChannel.kt       # Matrix channel (placeholder)
    │   └── webchat/
    │       ├── WebChatChannel.kt      # HTTP server channel (placeholder)
    │       └── WebChatServer.kt       # Local web server
    ├── image/
    │   └── BridgeImageStorage.kt      # Image download and caching
    ├── receiver/
    │   └── BridgeBootReceiver.kt      # Auto-start on boot
    ├── service/
    │   ├── MessagingBridgeService.kt  # Foreground service
    │   ├── BridgeCredentialProvider.kt # Credential access
    │   └── BridgeWatchdogWorker.kt    # Service health monitoring
    └── util/
        └── MessageSplitter.kt         # Split messages per platform limits
```

## Documentation (`docs/`)

```
docs/
├── 00-project-design.md              # Overall project design
├── 01-workflow.md                     # Development workflow
├── prd/
│   ├── _template.md                   # PRD template
│   ├── 00-overview.md                 # Product overview
│   └── features/                      # FEAT-001 through FEAT-049 (EN + ZH)
├── rfc/
│   ├── _template.md                   # RFC template
│   ├── architecture/                  # RFC-000 architecture docs
│   └── features/                      # RFC-001 through RFC-049 (EN + ZH)
├── adr/                               # Architecture decision records
├── testing/
│   ├── strategy.md                    # Testing strategy
│   ├── manual-test-guide.md          # Manual test procedures
│   └── reports/                       # Per-RFC test reports
└── wiki/                              # This documentation
```

## Configuration Files

| File | Purpose |
|------|---------|
| `build.gradle.kts` (root) | Root build script with plugin declarations |
| `settings.gradle.kts` | Module includes (`:app`, `:bridge`) |
| `gradle/libs.versions.toml` | Centralized dependency version catalog |
| `gradle.properties` | Gradle JVM args and project settings |
| `signing.properties` | Release signing config (gitignored) |
| `app/proguard-rules.pro` | ProGuard/R8 rules for release |
| `CLAUDE.md` | AI assistant project instructions |
