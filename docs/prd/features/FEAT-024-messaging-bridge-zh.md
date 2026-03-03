# 消息桥接

## 功能标识
- **功能 ID**: FEAT-024
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **优先级**: P1（应该有）
- **负责人**: 待定
- **关联 RFC**: [RFC-024（消息桥接）](../../rfc/features/RFC-024-messaging-bridge.md)

## 用户故事

**作为** OneClaw 的用户，
**我希望** 通过外部消息平台（Telegram、Discord、Slack、Matrix、LINE、WebChat）与我的 AI Agent 进行交互，
**以便** 无需直接打开应用，就能向 Agent 发送消息并接收回复，使用我日常已在使用的任意聊天平台。

### 典型场景
1. 用户在 Bridge 设置中填写 Telegram 机器人 Token，启用 Telegram 频道并启动桥接服务。此后，用户可以在任意设备上向该 Telegram 机器人发送消息，与 AI Agent 进行对话。
2. 用户运营一个 Discord 服务器，并将 OneClaw 机器人添加进去。团队成员可以在指定的 Discord 频道中与 AI Agent 交互。
3. 用户启用 WebChat 频道，手机上随即启动一个本地 WebSocket 服务器。同一网络下的浏览器可以连接该服务器，通过简洁的网页界面与 Agent 聊天。
4. 用户同时启用多个频道（Telegram + Slack）。来自两个平台的消息均路由至同一 Agent，Agent 的回复则发回各自的来源平台。
5. 用户在 Telegram 中向机器人发送一张图片。图片被下载并存储到本地，连同文本消息一起传递给 Agent 进行多模态处理。
6. 用户在任意频道中发送 `/clear` 指令，开启与 Agent 的全新对话。

## 功能描述

### 概述
消息桥接是一个后台服务，负责将外部消息平台连接到 OneClaw AI Agent 运行时。它接收来自支持平台的消息，将其路由至 Agent 进行处理，并将 Agent 的回复发回原始平台。用户无需直接打开 Android 应用，即可在任意设备或平台上与 AI Agent 交互。

桥接以带有持久通知的 Android 前台服务方式运行，确保可靠的后台操作。每个消息平台以独立频道的形式实现，各自采用不同的连接机制（轮询、WebSocket 或 Webhook 服务器）。

### 详细规格

#### 支持的频道

| 频道 | 连接方式 | 协议 | 主要特性 |
|------|----------|------|----------|
| **Telegram** | 长轮询 | HTTP API | Bot API、通过 `getFile()` 下载图片、HTML 消息格式化、4096 字符消息分割 |
| **Discord** | WebSocket Gateway | WebSocket + REST | Gateway 事件、心跳、消息意图、附件图片下载 |
| **Slack** | Socket Mode | WebSocket + REST | App Token + Bot Token、信封确认、事件过滤 |
| **Matrix** | 长轮询同步 | HTTP API | Homeserver URL 配置、`/sync` 端点、基于房间的消息传递 |
| **LINE** | Webhook 服务器 | HTTP（NanoHTTPD） | HMAC-SHA256 签名验证、Push API 回复、可配置端口 |
| **WebChat** | WebSocket 服务器 | WebSocket（NanoWSD） | 局域网访问、可选 JWT 认证、可配置端口 |

#### 消息流程

```
入站：
  外部平台 --> Channel.processInboundMessage()
    --> 去重处理（500 条 LRU 缓存）
    --> 访问控制（白名单校验）
    --> 对话解析（复用已有对话或创建新对话）
    --> 将用户消息插入对话
    --> 携带消息执行 Agent
    --> 等待 Assistant 回复（同时发送正在输入指示）
    --> 格式化回复并发回平台

出站（广播）：
  BridgeBroadcaster.broadcast(message)
    --> 所有已注册频道
    --> 每个频道向其最后已知的聊天 ID 发送消息
```

#### 指令
- `/clear` -- 创建新对话并向用户确认。旧对话被保留，但桥接开始路由至全新对话。

#### 图片支持
- 支持图片的频道（Telegram、Discord）可接收图片消息
- 图片以 UUID 文件名下载至本地 `bridge_images/` 目录
- 图片路径连同文本一起传递给 Agent 进行多模态处理
- 自动格式检测（PNG、GIF、WebP、BMP、JPG）

#### 服务管理
- 带有持久通知的前台服务，通知中显示桥接状态
- 可选唤醒锁，防止设备在桥接运行期间进入休眠
- 看门狗 Worker（WorkManager），在服务意外停止时重新启动
- 通过 `ACTION_START` / `ACTION_STOP` Intent 实现干净的启停生命周期

### 用户交互流程
```
1. 用户打开设置并导航至 Bridge 区域
2. 用户为所需频道配置凭据（如 Telegram 机器人 Token）
3. 用户通过开关启用特定频道
4. 用户点击主"启动 Bridge"按钮
5. 前台服务启动，并显示持久通知
6. Bridge 设置页面实时显示各频道状态（已连接、消息数量、错误信息）
7. 用户从外部平台（如 Telegram）发送消息
8. Agent 处理消息并在同一平台上回复
9. 用户可从设置页面或通知中停止桥接
```

## 验收标准

必须通过（全部必需）：
- [ ] 桥接以带有持久通知的 Android 前台服务方式运行
- [ ] Telegram 频道通过长轮询连接并能收发消息
- [ ] Discord 频道通过 WebSocket Gateway 连接并能收发消息
- [ ] Slack 频道通过 Socket Mode 连接并能收发消息
- [ ] Matrix 频道通过长轮询同步连接并能收发消息
- [ ] LINE 频道运行 Webhook 服务器并能收发消息
- [ ] WebChat 频道运行 WebSocket 服务器并能收发消息
- [ ] 消息去重（同一消息不被处理两次）
- [ ] 通过每个频道独立的用户 ID 白名单进行访问控制，功能正常
- [ ] `/clear` 指令在任意频道中均可创建新对话
- [ ] 来自 Telegram 的图片消息被下载并传递给 Agent
- [ ] 来自 Discord 的图片消息被下载并传递给 Agent
- [ ] 长消息在发送前按段落/句子边界进行分割
- [ ] 频道凭据使用 EncryptedSharedPreferences 存储
- [ ] Bridge 设置页面显示每个频道的启用/禁用开关
- [ ] Bridge 设置页面显示每个频道的凭据输入字段
- [ ] Bridge 设置页面实时显示每个频道的状态（已连接、消息数量、错误信息）
- [ ] Bridge 服务在应用进入后台后仍能持续处理消息
- [ ] 等待 Agent 回复时，向支持的频道（Telegram、Matrix、WebChat）发送正在输入指示
- [ ] Bridge 服务可从设置页面启动和停止
- [ ] 看门狗 Worker 在服务意外停止时重启服务
- [ ] Bridge 模块为独立的 Gradle 模块（`:bridge`）

可选（锦上添花）：
- [ ] 广播：向所有活跃频道的最后已知聊天 ID 发送消息
- [ ] 唤醒锁选项，防止设备进入休眠
- [ ] 主应用通知中显示 Bridge 状态
- [ ] 连接错误时的指数退避（初始 3 秒，最大 60 秒）

## UI/UX 要求

### Bridge 设置页面

#### 布局
- 从主设置页面进入，作为独立的「消息桥接」入口
- 顶部区域：主开关 + 服务启动/停止按钮
- 全局设置区域：唤醒锁开关、桥接对话选择器
- 每频道区域：6 个频道各自的可展开卡片，每张卡片包含：
  - 启用/禁用开关
  - 凭据输入字段（密码掩码）
  - 允许的用户 ID 字段（逗号分隔）
  - 频道特定设置（端口号、Homeserver URL）
  - 状态指示器（已连接/已断开/错误、连接时间、消息数量）

#### 视觉设计
- Material 3 设计，与现有设置页面保持一致
- 活跃/启用状态使用金色/琥珀色 `#6D5E0F` 作为强调色
- 状态指示：绿点表示已连接，红点表示错误，灰点表示已禁用
- 各频道图标用于视觉识别（Telegram、Discord、Slack、Matrix、LINE、WebChat）

#### 交互反馈
- 启动/停止桥接时显示加载指示器
- 频道连接状态通过 StateFlow 观察实时更新
- 错误信息内联显示在受影响频道卡片下方
- 凭据字段在输入时进行校验（必填字段不得为空）
- 桥接启动/停止时显示 Toast 或 Snackbar 确认提示

## 功能边界

### 包含
- 包含所有桥接逻辑的独立 `:bridge` Gradle 模块
- 带有通知和生命周期管理的前台服务
- 全部 6 个频道实现（Telegram、Discord、Slack、Matrix、LINE、WebChat）
- 频道抽象层（MessagingChannel 基类）
- 消息去重、访问控制、对话映射
- Telegram 和 Discord 的图片下载与存储
- 支持频道的正在输入指示
- 通过 EncryptedSharedPreferences 存储凭据
- 频道配置和状态的 Preferences 存储
- 通过 StateFlow 进行状态跟踪以供 UI 观察
- 包含每频道配置的完整 Bridge 设置页面
- 保障服务可靠性的看门狗 Worker
- 向所有活跃频道广播的能力
- 针对平台字符限制的消息分割
- 用于重置对话的 `/clear` 指令
- 桥接依赖的 Koin DI 模块
- 在 `:app` 模块中实现的桥接专用接口（BridgeAgentExecutor、BridgeMessageObserver、BridgeConversationManager）

### 不包含
- WebChat 的端到端加密（明文 WebSocket）
- 富媒体回复（仅支持文本回复；图片仅支持入站）
- 语音消息支持
- 文件附件支持（图片除外）
- 多 Agent 路由（所有消息路由至单一 Agent/对话）
- 远程桥接管理（桥接仅在设备本地控制）
- 用于桥接配置的 Web 管理面板
- 频道的推送通知集成（仅支持实时消息）
- 入站消息的速率限制或流量控制

## 业务规则

### 服务规则
1. 桥接服务要求至少启用一个频道；若启动时没有任何频道被启用，服务立即停止
2. 服务以前台服务方式运行，返回值为 `START_STICKY`，确保系统在其被杀死后重新启动
3. 频道的启动/停止操作通过 Mutex 串行化，以防止竞态条件
4. 看门狗 Worker 定期运行，在服务意外停止时重新启动它

### 频道规则
1. 每个频道独立运行——一个频道失败不影响其他频道
2. 频道使用 SupervisorJob 作用域，协程失败被隔离
3. 连接错误触发指数退避（初始 3 秒，每次翻倍，最大 60 秒）
4. 每个频道维护自己的连接生命周期（启动/停止/重连）

### 访问控制规则
1. 每频道允许的用户 ID 作为白名单
2. 若白名单为空，则允许所有用户访问（开放访问）
3. 若白名单非空，则仅处理列表中用户 ID 的消息
4. 未授权用户的消息被静默丢弃（不回复错误）

### 消息规则
1. 消息使用每频道 500 条的 LRU 缓存进行去重
2. `/clear` 指令创建新对话；旧对话被保留
3. 长消息按自然边界分割（段落 > 句子 > 单词 > 字符）
4. 遵守平台特定的字符限制（如 Telegram 4096 字符）
5. 空消息或仅含空白字符的消息被忽略

### 凭据规则
1. 所有 API Token 和密钥存储在 EncryptedSharedPreferences 中
2. 凭据不得记录日志，也不得在错误消息中暴露
3. Slack 需要两个 Token：Bot Token 和 App Token（Socket Mode）
4. LINE 需要两个凭据：Channel Access Token 和 Channel Secret

### 对话规则
1. 所有桥接消息路由至单一桥接对话
2. 桥接对话与 UI 创建的对话相互独立
3. `/clear` 创建新的桥接对话并更新映射关系
4. 对话 ID 持久化存储在 BridgePreferences 中，以便服务重启后恢复

## 非功能性需求

### 性能
- 消息处理延迟（桥接开销，不含 Agent 执行时间）：< 500ms
- 频道连接建立时间：轮询/WebSocket 频道 < 10 秒
- 正在输入指示间隔：等待回复期间每 4 秒发送一次
- Agent 响应超时：300 秒（5 分钟）
- 每个频道的内存开销：< 5MB

### 安全性
- API Token 存储在 EncryptedSharedPreferences 中（AES256_SIV + AES256_GCM）
- LINE Webhook 服务器对所有入站请求进行 HMAC-SHA256 签名验证
- WebChat 服务器支持可选的基于 Token 的认证
- 任何日志级别均不记录凭据
- 仅在加密硬件不可用时回退至普通 SharedPreferences（已记录的边缘情况）

### 兼容性
- Android API 26+（minSdk）
- 后台执行：前台服务 + 可选唤醒锁
- 网络：支持 WiFi 和移动数据
- WebChat/LINE Webhook 服务器仅在局域网内可访问（不内置端口转发）

## 依赖关系

### 依赖于
- **FEAT-001（聊天交互）**：Agent 执行和消息系统
- **FEAT-004（工具系统）**：Agent 处理过程中的工具执行
- **FEAT-008（会话管理）**：对话/会话生命周期

### 被依赖于
- 目前无

### 外部依赖
- OkHttp：HTTP 客户端和 WebSocket 支持
- NanoHTTPD：用于 LINE Webhook 和 WebChat 的嵌入式 HTTP/WebSocket 服务器
- Commonmark：将 Markdown 渲染为 HTML 以用于 Telegram 消息格式化
- AndroidX Security Crypto：用于凭据存储的 EncryptedSharedPreferences
- AndroidX WorkManager：看门狗 Worker 调度

## 测试要点

### 功能测试
- Telegram：发送文本消息、接收回复、发送图片、`/clear` 指令
- Discord：发送文本消息、接收回复、发送图片附件
- Slack：发送文本消息、接收回复、信封确认
- Matrix：发送文本消息、接收回复、房间消息路由
- LINE：Webhook 接收消息、签名验证、Push 回复
- WebChat：WebSocket 连接、认证、发送消息、接收回复
- 服务：携带已启用频道启动、停止所有频道、通过看门狗重启
- 访问控制：允许的用户通过，被屏蔽的用户被静默丢弃
- 去重：相同 messageId 不被处理两次
- 消息分割：超过 4096 字符的消息在 Telegram 中被正确分割
- `/clear`：创建新对话，后续消息路由至新对话

### 边缘情况
- 在没有任何频道启用的情况下启动桥接（应立即停止）
- 使用无效凭据启动桥接（该频道报告错误，其他频道不受影响）
- 网络断开与重连（指数退避，自动重连）
- 同一用户快速发送多条消息（并发处理，去重）
- Agent 回复非常长（跨所有平台进行消息分割）
- 图片下载失败（记录错误，仅处理文本消息）
- 服务被系统杀死（看门狗重启，从 Preferences 恢复状态）
- 应用强制停止（服务和看门狗均停止，下次启动时状态干净）

### 性能测试
- 桥接开销测量（从接收消息到调用 Agent 的时间）
- 所有 6 个频道同时活跃时的内存使用情况
- 持续运行 24 小时以上（无内存泄漏，无连接性能下降）

## 数据需求

### 频道凭据
| 数据项 | 类型 | 是否必需 | 描述 |
|--------|------|----------|------|
| Telegram Bot Token | String | 是（若启用 Telegram） | 从 @BotFather 获取的 Bot API Token |
| Discord Bot Token | String | 是（若启用 Discord） | 从 Discord 开发者门户获取的 Bot Token |
| Slack Bot Token | String | 是（若启用 Slack） | Bot OAuth Token（xoxb-） |
| Slack App Token | String | 是（若启用 Slack） | Socket Mode 所需的应用级 Token（xapp-） |
| Matrix Access Token | String | 是（若启用 Matrix） | Matrix Homeserver 的访问 Token |
| Matrix Homeserver URL | String | 是（若启用 Matrix） | Matrix Homeserver 的基础 URL |
| LINE Channel Access Token | String | 是（若启用 LINE） | 从 LINE Developers 获取的 Channel Access Token |
| LINE Channel Secret | String | 是（若启用 LINE） | 用于 Webhook 签名验证的 Channel Secret |
| WebChat Access Token | String | 否 | WebChat 认证的可选 Token |

### 配置数据
| 数据项 | 类型 | 是否必需 | 描述 |
|--------|------|----------|------|
| Bridge Enabled | Boolean | 是 | 主开关，默认 false |
| Per-channel Enabled | Boolean | 是 | 每频道开关，默认 false |
| Allowed User IDs | Set<String> | 否 | 每频道白名单，为空表示允许所有人 |
| WebChat Port | Int | 否 | WebSocket 服务器端口，默认 8080 |
| LINE Webhook Port | Int | 否 | Webhook HTTP 服务器端口，默认 8081 |
| Wake Lock Enabled | Boolean | 否 | 防止设备休眠，默认 false |
| Bridge Conversation ID | String | 否 | 当前活跃的桥接对话 |

### 数据存储
- 凭据：EncryptedSharedPreferences（`bridge_credentials`）
- 配置：SharedPreferences（`messaging_bridge`）
- 已下载图片：内部存储（`bridge_images/` 目录）
- 状态跟踪：内存中的 StateFlow（不持久化）

## 错误处理

### 错误场景

1. **凭据无效**
   - 频道连接失败，并在频道状态中报告错误
   - 错误显示在设置页面频道卡片下方
   - 其他频道不受影响

2. **网络断开**
   - 频道检测到断开并进入指数退避
   - 网络可用时自动重连
   - 断开期间状态跟踪器显示错误状态

3. **Agent 执行超时（> 5 分钟）**
   - 消息观察者返回超时错误
   - 频道向外部用户发送错误消息
   - 频道继续运行以处理后续消息

4. **图片下载失败**
   - 记录错误，消息作为纯文本处理
   - 不向用户通知图片下载失败

5. **服务被系统杀死**
   - 看门狗 Worker 检测到服务停止
   - 以之前已启用的频道重启服务
   - 从 BridgePreferences 恢复对话状态

6. **端口冲突（WebChat/LINE）**
   - 频道启动失败，错误在频道状态中报告
   - 用户需在设置中更改端口

7. **LINE Webhook 签名验证失败**
   - 请求被拒绝，返回 401 状态
   - 记录为安全警告

8. **EncryptedSharedPreferences 初始化失败**
   - 回退至普通 SharedPreferences
   - 记录为警告（安全性降级）

## 待解决问题

- [ ] 桥接是否应支持多个并发对话（每个外部聊天 ID 对应一个）还是单一共享对话？
- [ ] 桥接对话是否应使用特定 Agent，还是使用默认的通用助手？
- [ ] 是否应设置最大消息速率限制以防止滥用？

## 参考资料

- 参考实现：`oneclaw-1/lib-messaging-bridge`
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Discord Gateway API](https://discord.com/developers/docs/topics/gateway)
- [Slack Socket Mode](https://api.slack.com/apis/connections/socket)
- [Matrix Client-Server API](https://spec.matrix.org/latest/client-server-api/)
- [LINE Messaging API](https://developers.line.biz/en/docs/messaging-api/)
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
