# 项目结构

OneClaw 项目的目录结构注释说明。

## 顶层目录

```
oneclaw-1/
├── app/                           # 主 Android 应用模块
├── bridge/                        # 消息桥接 Android 库模块
├── docs/                          # 所有文档（PRD、RFC、ADR、wiki）
├── gradle/                        # Gradle wrapper 及版本目录
│   └── libs.versions.toml         # 集中管理的依赖版本
├── build.gradle.kts               # 根构建脚本
├── settings.gradle.kts            # 模块声明
├── gradle.properties              # Gradle 配置
├── signing.properties             # 发布签名配置（不提交到版本控制）
├── CLAUDE.md                      # AI 编码助手指令
└── README.md                      # 项目概览
```

## App 模块（`app/`）

```
app/
├── build.gradle.kts               # 应用依赖和构建配置
├── proguard-rules.pro             # 发布构建的 ProGuard/R8 规则
├── schemas/                       # Room 数据库 schema 导出（用于迁移）
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── assets/
    │   │   └── skills/            # 内置技能（SKILL.md 包）
    │   │       ├── create-skill/
    │   │       └── create-tool/
    │   ├── kotlin/com/oneclaw/shadow/
    │   │   ├── MainActivity.kt    # 单 Activity 入口
    │   │   ├── OneclawApplication.kt  # Application 类，Koin 初始化
    │   │   ├── core/              # 领域层
    │   │   ├── data/              # 数据层
    │   │   ├── di/                # 依赖注入
    │   │   ├── feature/           # 功能模块
    │   │   ├── navigation/        # Compose Navigation
    │   │   ├── tool/              # 工具系统
    │   │   └── ui/                # 主题和共享 UI 组件
    │   └── res/                   # Android 资源（布局、字符串等）
    ├── test/                      # JVM 单元测试（第 1A 层）
    └── androidTest/               # 仪器测试（第 1B 层）
```

## 核心层（`core/`）

```
core/
├── lifecycle/
│   └── AppLifecycleObserver.kt        # 应用生命周期事件
├── model/                             # 领域模型（20 个文件）
│   ├── Agent.kt                       # AI 代理配置
│   ├── AgentConstants.kt              # 内置代理定义
│   ├── AiModel.kt                     # 模型定义（id、name、上下文窗口）
│   ├── Attachment.kt                  # 文件附件元数据
│   ├── Citation.kt                    # 网络搜索引用
│   ├── ConnectionTestResult.kt        # 提供商连接测试结果
│   ├── FileContent.kt                 # 文件内容包装器
│   ├── FileInfo.kt                    # 文件元数据
│   ├── Message.kt                     # 聊天消息（角色、内容、Token 数）
│   ├── Provider.kt                    # API 提供商配置
│   ├── ProviderCapability.kt          # 提供商功能标志
│   ├── ResolvedModel.kt              # 已解析的提供商与模型组合
│   ├── ScheduledTask.kt              # 定时任务定义
│   ├── Session.kt                     # 对话会话
│   ├── SkillDefinition.kt            # 技能元数据
│   ├── TaskExecutionRecord.kt        # 任务运行历史
│   ├── ToolDefinition.kt             # 工具 schema（名称、参数、描述）
│   ├── ToolGroupDefinition.kt        # 工具组元数据
│   ├── ToolResult.kt                 # 工具执行结果
│   └── ToolSourceInfo.kt             # 工具来源信息（内置/js/组）
├── notification/
│   └── NotificationHelper.kt         # 通知渠道管理
├── repository/                        # 仓库接口（9 个文件）
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
│   └── ThemeManager.kt                # 主题模式管理（系统/浅色/深色）
└── util/
    ├── AppResult.kt                   # 用于可失败操作的密封类
    └── ErrorCode.kt                   # 错误码枚举
```

## 数据层（`data/`）

```
data/
├── local/
│   ├── dao/                           # Room DAO（10 个文件）
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
│   │   ├── AppDatabase.kt            # Room 数据库定义
│   │   ├── Converters.kt             # Room 类型转换器
│   │   └── Migrations.kt             # 数据库迁移脚本
│   ├── entity/                        # Room 实体（10 个文件）
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
│   └── mapper/                        # 实体 <-> 领域模型映射器（7 个文件）
│       ├── AgentMapper.kt
│       ├── AttachmentMapper.kt
│       ├── MessageMapper.kt
│       ├── ProviderMapper.kt
│       ├── ScheduledTaskMapper.kt
│       ├── SessionMapper.kt
│       └── TaskExecutionRecordMapper.kt
├── remote/
│   ├── adapter/                       # API 适配器（7 个文件）
│   │   ├── ModelApiAdapter.kt         # 适配器接口
│   │   ├── ModelApiAdapterFactory.kt  # 按 ProviderType 创建的工厂
│   │   ├── OpenAiAdapter.kt
│   │   ├── AnthropicAdapter.kt
│   │   ├── GeminiAdapter.kt
│   │   ├── ApiMessage.kt              # API 消息类型
│   │   └── StreamEvent.kt            # 流式事件类型
│   ├── dto/                           # 提供商特定的 DTO
│   │   ├── openai/OpenAiModelListResponse.kt
│   │   ├── anthropic/AnthropicModelListResponse.kt
│   │   └── gemini/GeminiModelListResponse.kt
│   └── sse/
│       └── SseParser.kt              # Server-Sent Events 解析器
├── repository/                        # 仓库实现（9 个文件）
├── security/
│   ├── ApiKeyStorage.kt              # 加密的 API 密钥存储
│   └── GoogleAuthManager.kt          # Google OAuth 管理
├── storage/
│   └── UserFileStorage.kt            # 用户数据的文件 I/O
└── sync/
    ├── BackupManager.kt              # Google Drive 备份
    ├── SyncManager.kt                # 同步编排
    └── SyncWorker.kt                 # 通过 WorkManager 实现的后台同步
```

## 功能层（`feature/`）

每个功能遵循统一结构：

```
feature/<name>/
├── <Name>Screen.kt                    # Composable 屏幕
├── <Name>ViewModel.kt                 # 带有 StateFlow 的 ViewModel
├── <Name>UiState.kt                   # UI 状态数据类
└── usecase/                           # 业务逻辑用例
    └── <Action>UseCase.kt
```

### 功能目录

```
feature/
├── agent/                             # 代理 CRUD 和选择 UI
├── bridge/                            # 桥接集成（应用侧）
│   ├── BridgeConversationManagerImpl.kt
│   ├── BridgeAgentExecutorImpl.kt
│   ├── BridgeMessageObserverImpl.kt
│   └── BridgeSettingsScreen.kt
├── chat/                              # 主聊天界面
│   ├── components/                    # 聊天子组件
│   │   ├── AttachmentDisplay.kt
│   │   ├── AttachmentPickerSheet.kt
│   │   ├── AttachmentPreviewRow.kt
│   │   └── ImageViewerDialog.kt
│   └── usecase/
│       ├── SendMessageUseCase.kt      # 核心消息流
│       ├── AutoCompactUseCase.kt
│       ├── SaveAttachmentUseCase.kt
│       ├── CompactAwareMessageBuilder.kt
│       └── MessageToApiMapper.kt
├── file/                              # 文件浏览器和预览
├── memory/                            # 记忆管理
│   ├── compaction/                    # 自动压缩逻辑
│   ├── embedding/                     # ONNX 嵌入引擎
│   ├── injection/                     # 系统提示注入
│   ├── log/                           # 每日日志写入
│   ├── longterm/                      # 长期记忆管理
│   ├── search/                        # 混合搜索引擎
│   ├── storage/                       # 记忆文件存储
│   ├── trigger/                       # 记忆写入触发器
│   └── ui/                            # 记忆屏幕
├── provider/                          # 提供商/模型配置
├── schedule/                          # 定时任务
│   ├── alarm/                         # Android 闹钟系统
│   │   ├── AlarmScheduler.kt
│   │   ├── BootCompletedReceiver.kt
│   │   ├── ExactAlarmHelper.kt
│   │   ├── NextTriggerCalculator.kt
│   │   └── ScheduledTaskReceiver.kt
│   ├── usecase/                       # 任务 CRUD 和执行
│   ├── util/                          # 调度格式化
│   └── worker/                        # WorkManager 集成
├── search/                            # 统一搜索
├── session/                           # 会话生命周期
├── settings/                          # 应用设置
│   ├── DataBackupScreen.kt
│   ├── GoogleAuthScreen.kt
│   ├── JsToolsSection.kt
│   └── EnvVarsSection.kt
├── skill/                             # 技能管理
│   └── ui/
│       ├── SkillEditorScreen.kt
│       ├── SkillManagementScreen.kt
│       ├── SkillSelectionBottomSheet.kt
│       └── SlashCommandPopup.kt
├── tool/                              # 工具管理 UI
└── usage/                             # Token 用量统计
```

## 工具系统（`tool/`）

```
tool/
├── engine/                            # 核心工具基础设施
│   ├── Tool.kt                        # 工具接口
│   ├── ToolRegistry.kt               # 工具注册与查找
│   ├── ToolExecutionEngine.kt        # 带验证和超时的执行引擎
│   ├── ToolEnabledStateStore.kt      # 启用/禁用状态持久化
│   ├── ToolCallModels.kt            # 工具调用数据模型
│   ├── ToolSchemaSerializer.kt       # JSON schema 序列化
│   └── PermissionChecker.kt          # Android 权限检查
├── builtin/                           # 内置 Kotlin 工具（23 个文件）
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
│   └── config/                        # 配置管理工具（17 个文件）
│       ├── Provider tools (Create/Update/Delete/List)
│       ├── Model tools (Add/Delete/List/Fetch/SetDefault)
│       ├── Agent tools (Delete/List/Update)
│       ├── GetConfigTool.kt / SetConfigTool.kt
│       ├── ManageEnvVarTool.kt
│       ├── ListToolStatesTool.kt
│       └── SetToolEnabledTool.kt
├── js/                                # JavaScript 引擎
│   ├── JsExecutionEngine.kt          # QuickJS 运行时管理
│   ├── JsTool.kt                     # JS 工具包装器
│   ├── JsToolLoader.kt              # 从文件加载 JS 工具
│   ├── UserToolManager.kt           # 用户工具生命周期
│   ├── EnvironmentVariableStore.kt   # 加密的环境变量存储
│   └── bridge/                        # JS <-> Kotlin 桥接
│       ├── ConsoleBridge.kt           # console.log/warn/error
│       ├── FetchBridge.kt            # 通过 OkHttp 实现的 fetch()
│       ├── FsBridge.kt               # fs.readFile/writeFile/exists
│       ├── TimeBridge.kt             # _time() 日期格式化
│       ├── LibraryBridge.kt          # lib() 可复用库
│       ├── GoogleAuthBridge.kt       # Google OAuth 集成
│       └── FileTransferBridge.kt     # 文件上传/下载
├── skill/
│   ├── SkillRegistry.kt             # 技能发现与缓存
│   └── SkillFileParser.kt           # SKILL.md YAML+MD 解析器
├── browser/
│   ├── WebViewManager.kt            # WebView 生命周期
│   ├── BrowserContentExtractor.kt   # 页面内容提取
│   └── BrowserScreenshotCapture.kt  # 页面截图捕获
└── util/
    ├── HtmlToMarkdownConverter.kt    # HTML -> Markdown 转换
    └── PdfToolUtils.kt              # PDF 工具函数
```

## Bridge 模块（`bridge/`）

```
bridge/
├── build.gradle.kts
└── src/main/kotlin/com/oneclaw/shadow/bridge/
    ├── BridgeAgentExecutor.kt         # 代理执行接口
    ├── BridgeBroadcaster.kt           # 出站广播管理器
    ├── BridgeConversationManager.kt   # 会话管理接口
    ├── BridgeMessage.kt               # 消息数据模型
    ├── BridgeMessageObserver.kt       # 消息观察接口
    ├── BridgePreferences.kt           # 配置用 SharedPreferences
    ├── BridgeStateTracker.kt          # 进程内事件总线（RFC-045）
    ├── channel/
    │   ├── ChannelMessage.kt          # 入站消息模型
    │   ├── ChannelType.kt             # TELEGRAM、DISCORD、SLACK、LINE、MATRIX、WEBCHAT
    │   ├── ConversationMapper.kt      # 外部聊天 -> 会话映射
    │   ├── MessagingChannel.kt        # 抽象基础渠道
    │   ├── telegram/
    │   │   ├── TelegramApi.kt         # Telegram Bot API 客户端
    │   │   ├── TelegramChannel.kt     # 基于轮询的渠道
    │   │   └── TelegramHtmlRenderer.kt # Markdown -> Telegram HTML
    │   ├── discord/
    │   │   ├── DiscordChannel.kt      # WebSocket 网关渠道
    │   │   └── DiscordGateway.kt      # Discord 网关客户端
    │   ├── slack/
    │   │   ├── SlackChannel.kt        # Socket Mode 渠道
    │   │   ├── SlackMrkdwnRenderer.kt # Markdown -> Slack mrkdwn
    │   │   └── SlackSocketMode.kt     # Slack Socket Mode 客户端
    │   ├── line/
    │   │   ├── LineApi.kt             # LINE Messaging API 客户端
    │   │   ├── LineChannel.kt         # 基于 Webhook 的渠道
    │   │   └── LineWebhookServer.kt   # 用于 Webhook 的本地 HTTP 服务器
    │   ├── matrix/
    │   │   ├── MatrixApi.kt           # Matrix API 客户端
    │   │   └── MatrixChannel.kt       # Matrix 渠道（占位符）
    │   └── webchat/
    │       ├── WebChatChannel.kt      # HTTP 服务器渠道（占位符）
    │       └── WebChatServer.kt       # 本地 Web 服务器
    ├── image/
    │   └── BridgeImageStorage.kt      # 图片下载与缓存
    ├── receiver/
    │   └── BridgeBootReceiver.kt      # 开机自启
    ├── service/
    │   ├── MessagingBridgeService.kt  # 前台服务
    │   ├── BridgeCredentialProvider.kt # 凭据访问
    │   └── BridgeWatchdogWorker.kt    # 服务健康监控
    └── util/
        └── MessageSplitter.kt         # 按平台限制拆分消息
```

## 文档（`docs/`）

```
docs/
├── 00-project-design.md              # 总体项目设计
├── 01-workflow.md                     # 开发工作流
├── prd/
│   ├── _template.md                   # PRD 模板
│   ├── 00-overview.md                 # 产品概览
│   └── features/                      # FEAT-001 至 FEAT-049（英文 + 中文）
├── rfc/
│   ├── _template.md                   # RFC 模板
│   ├── architecture/                  # RFC-000 架构文档
│   └── features/                      # RFC-001 至 RFC-049（英文 + 中文）
├── adr/                               # 架构决策记录
├── testing/
│   ├── strategy.md                    # 测试策略
│   ├── manual-test-guide.md          # 手动测试流程
│   └── reports/                       # 每个 RFC 的测试报告
└── wiki/                              # 本文档
```

## 配置文件

| 文件 | 用途 |
|------|------|
| `build.gradle.kts`（根） | 包含插件声明的根构建脚本 |
| `settings.gradle.kts` | 模块引入（`:app`、`:bridge`） |
| `gradle/libs.versions.toml` | 集中管理的依赖版本目录 |
| `gradle.properties` | Gradle JVM 参数和项目设置 |
| `signing.properties` | 发布签名配置（已加入 .gitignore） |
| `app/proguard-rules.pro` | 发布版本的 ProGuard/R8 规则 |
| `CLAUDE.md` | AI 助手项目指令 |
