# 消息桥接模块

消息桥接模块是一个独立的 Android 库（`:bridge` 模块），用于将 OneClaw 与外部消息平台连接。它作为 foreground service 运行，接收来自外部渠道的消息并将其路由至 AI 智能体处理。

## 支持的渠道

| 渠道 | 协议 | 状态 |
|------|------|------|
| Telegram | 通过 Bot API 进行 HTTP 轮询 | 完整支持（文本 + 图片） |
| Discord | WebSocket gateway | 完整支持（文本 + 附件） |
| Slack | Socket Mode | 完整支持（文本） |
| LINE | Webhook（HTTP 服务器） | 完整支持（文本） |
| Matrix | HTTP API | 占位符 |
| WebChat | HTTP 服务器 | 占位符 |

## 架构

```
外部平台
    |
    v
MessagingChannel（各平台实现）
    |
    v
processInboundMessage()
    |
    +---> 消息去重（LRU 缓存，500 条记录）
    +---> 访问控制（各渠道独立白名单）
    +---> ConversationMapper.resolveConversationId()
    +---> 正在输入指示循环（每 4 秒一次）
    +---> BridgeAgentExecutor.executeMessage()
    |         |
    |         v
    |     SendMessageUseCase（与应用内聊天相同）
    |         |
    |         v
    |     AI 回复
    |
    +---> Channel.sendResponse()（平台特定格式化）
    v
外部平台
```

## 核心组件

### MessagingBridgeService

管理所有渠道生命周期的 foreground service，通过静态 intent 方法进行控制：

- `MessagingBridgeService.start(context)` -- 启动所有已启用的渠道
- `MessagingBridgeService.stop(context)` -- 停止所有渠道并关闭服务
- `MessagingBridgeService.restart(context)` -- 使用最新配置重新初始化
- `MessagingBridgeService.broadcast(context, text)` -- 向所有渠道广播文本

该服务会创建 foreground 通知，并可选择性地获取唤醒锁以增强 Doze 模式下的可靠性。

### MessagingChannel（抽象基类）

所有渠道实现均继承自该基类，提供以下功能：

- **消息处理流水线：** 消息去重、白名单检查、会话映射、智能体执行
- **正在输入指示：** 处理消息期间每 4 秒发送一次正在输入状态
- **广播：** 向各渠道最后已知的聊天 ID 发送消息
- **超时：** 智能体响应的超时时间为 5 分钟

子类负责实现传输层：
- `start()` / `stop()` -- 渠道生命周期
- `sendResponse(externalChatId, message)` -- 平台特定的响应投递
- `sendTypingIndicator(externalChatId)` -- 平台特定的正在输入状态

### BridgeConversationManager

会话管理接口，由应用模块中的 `BridgeConversationManagerImpl` 实现：

- `getActiveConversationId()` -- 返回当前桥接会话
- `createNewConversation()` -- 为桥接创建新会话
- `insertUserMessage(conversationId, content, imagePaths)` -- 持久化用户消息
- `conversationExists(conversationId)` -- 检查会话有效性

### BridgeStateTracker

进程内事件总线单例（RFC-045），使用 SharedFlow 和 StateFlow：

- `serviceRunning: StateFlow<Boolean>` -- 服务是否正在运行
- `channelStates: StateFlow<Map<ChannelType, ChannelState>>` -- 各渠道状态
- `newSessionFromBridge: SharedFlow<String>` -- 桥接创建新会话时触发
- `activeAppSessionId: StateFlow<String?>` -- 当前活跃的应用会话

这使得应用界面能够观察桥接活动并同步会话状态，无需依赖数据库。

### BridgeImageStorage

将来自外部平台的图片缓存至本地存储：

- 支持携带可选鉴权请求头下载图片
- 通过魔术字节检测图片格式（JPEG、PNG、GIF、WebP）
- 存储于 `context.filesDir/bridge_images/`
- 返回供智能体使用的绝对文件路径

### ConversationMapper

将外部聊天 ID 映射至内部会话 ID：

- 若有可用的活跃会话则直接返回
- 按需创建新会话
- 防止短时间内重复创建会话

### BridgeBroadcaster

用于向所有活跃渠道发送出站消息的单例：

- 渠道自行注册和注销
- `broadcast(content)` 向各渠道最后已知的聊天 ID 发送消息

## 渠道详情

### Telegram

- **协议：** 通过 `getUpdates(offset)` 进行长轮询，支持指数退避（初始 3 秒，最大 60 秒）
- **图片：** 通过 `getFile()` API 下载，经 `BridgeImageStorage` 存储
- **响应渲染：** Markdown 通过 `TelegramHtmlRenderer`（AST 访问者模式）转换为 Telegram HTML
- **消息长度限制：** 每条消息最多 4096 个字符，超出时自动分割
- **正在输入：** `sendChatAction(action="typing")`

### Discord

- **协议：** 通过 `DiscordGateway` 实现带事件回调的 WebSocket gateway
- **附件：** 从事件 URL 下载
- **响应：** 使用 Bot token 鉴权，通过 REST API `POST /channels/{channelId}/messages` 发送
- **消息长度限制：** 每条消息最多 2000 个字符
- **正在输入：** `POST /channels/{channelId}/typing`

### Slack

- **协议：** Socket Mode，带信封 ACK 回调
- **过滤：** 忽略机器人消息及子类型（编辑、删除）
- **响应：** 使用 mrkdwn 格式通过 `POST chat.postMessage` 发送
- **智能分割：** 使用 MessageSplitter 处理长内容

### LINE

- **协议：** 通过可配置端口（默认：8081）上的本地 HTTP 服务器接收 Webhook
- **事件：** 处理类型为 `text` 的 `message` 事件
- **响应：** `LineApi.pushMessage(userId, text)`
- **消息长度限制：** 每条消息最多 5000 个字符

## 访问控制

每个渠道均有独立的允许用户 ID 白名单，存储于 `BridgePreferences`。来自不在白名单中的用户的消息将被静默忽略。

## 会话同步（RFC-045）

1. 桥接通过 `BridgeConversationManager` 创建新会话
2. `BridgeStateTracker.emitNewSessionFromBridge(sessionId)` 通知应用
3. 应用观察 SharedFlow 并可切换至桥接会话
4. `BridgeStateTracker.setActiveAppSession(sessionId)` 保持双端同步

## 配置

桥接配置存储于 `SharedPreferences("oneclaw_messaging_bridge")`：

- 主启用/禁用开关
- 各渠道启用标志
- 各渠道用户白名单
- WebChat 端口（默认：8080）
- LINE Webhook 端口（默认：8081）
- Matrix 主服务器 URL
- 唤醒锁开关

可通过应用内的桥接设置界面进行配置。
