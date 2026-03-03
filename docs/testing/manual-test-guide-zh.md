# 人工测试手册 — OneClaw

本文档是 OneClaw 应用的累积性、始终保持最新的人工测试指南。它描述了当前实现中所有用户可见的流程，以及如何在真实设备或模拟器上进行验证。

**使用方式：**
- 每次发布前，从全新安装状态开始，完整执行本手册中的所有流程。
- 每当某个 RFC 新增、修改或移除用户可见的行为时，更新本手册。
- 本手册反映 App 当前实际状态，不包含计划中的未来功能。

## 文档信息

| 字段 | 内容 |
|------|------|
| 最后更新 | 2026-02-27 |
| App 版本 | 0.1.0 |
| 最后实现的 RFC | RFC-001（对话交互）+ RFC-002（Agent 管理） |
| 当前状态 | 完整应用已实现：Setup、Provider 管理、Tool 系统、Session 管理、Agent 管理和流式 Chat |

---

## 准备工作

### 前置条件

- Android 设备或模拟器（API 26+）
- 已安装 App：`./gradlew installDebug` 或 `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 如需测试 Provider 连接：至少一个 Provider 的有效 API key（Anthropic、OpenAI 或 Gemini）

### 全新安装

```bash
adb shell pm clear com.oneclaw.shadow
adb shell am start -n com.oneclaw.shadow/.MainActivity
```

---

## 当前 App 状态

截至最后实现的 RFC（RFC-001 + RFC-002），App 支持以下功能：
- 首次启动引导流程（选择 Provider、输入 API key、选择默认模型）
- Provider 管理（列表、详情、API key、连接测试、模型列表）
- 设置界面（含"Manage Agents"入口）
- Tool 系统（后端 + AgentDetailScreen 中的每个 Agent 工具配置）
- Session 管理（抽屉 UI，支持新建/切换/删除/重命名 Session）
- Agent 管理（列表、创建、编辑、克隆、删除自定义 Agent；查看内置 Agent）
- 流式 Chat：来自 OpenAI/Anthropic/Gemini 的 SSE 流式传输、工具调用循环、thinking blocks、消息历史

**所有计划的 RFC 均已实现。**

---

## 流程 1：首次启动 — 设置引导

**触发时机：** 全新安装，`has_completed_setup` 标志为 false。

**预期行为：** App 启动后直接进入设置引导界面（3 步向导）。

### 步骤 1.1：全新启动 App

```bash
adb shell pm clear com.oneclaw.shadow
adb shell am start -n com.oneclaw.shadow/.MainActivity
```

**验证：**
- 显示设置引导界面（不是 Chat 界面）
- 显示第 1 步"选择 Provider"
- 可见三个 Provider 选项：OpenAI、Anthropic、Google Gemini
- 可见"跳过"按钮

### 步骤 1.2：跳过设置

在设置界面点击"Skip for now"（暂时跳过）。

**验证：**
- App 导航至 Chat 界面
- Chat 界面显示占位符/欢迎语
- `has_completed_setup` 现在为 true（重新启动 App 应直接进入 Chat，不再显示 Setup）

### 步骤 1.3：完成设置 — 选择 Provider

全新安装后，点击"Anthropic"（或其他 Provider）。

**验证：**
- 显示第 2 步"输入 API Key"
- 可见 API key 输入框
- 可见"Test Connection"（测试连接）按钮
- "Skip for now"按钮仍然可见

### 步骤 1.4：输入 API key 并测试连接

在输入框中输入有效的 API key，点击"Test Connection"。

**验证：**
- 测试过程中显示加载指示器
- 成功时：显示绿色成功提示或"Connected"文字
- 失败时：显示错误信息（如"Invalid API key"或"Network error"）

### 步骤 1.5：进入模型选择

连接测试成功后，进入第 3 步。

**验证：**
- 显示第 3 步"Select Default Model"（选择默认模型）
- 显示该 Provider 的模型列表
- 每个模型可选择
- 选中模型后"Get Started"按钮变为可点击状态

### 步骤 1.6：完成设置

选择模型后点击"Get Started"。

**验证：**
- App 导航至 Chat 界面
- `has_completed_setup` 为 true（下次启动直接进入 Chat）

---

## 流程 2：设置界面

**入口：** 从 Chat 界面点击右上角设置齿轮图标。

### 步骤 2.1：打开设置

**验证：**
- 打开设置界面
- 可见"Manage Providers"列表项，副标题"Add API keys, configure models"
- 可见"Manage Agents"列表项，副标题"Create and configure agents"
- 返回导航有效（左上角返回箭头）

### 步骤 2.2：进入 Provider 列表

点击"Manage Providers"。

**验证：**
- 打开 Provider 列表界面
- 显示三个内置 Provider：OpenAI、Anthropic、Google Gemini
- 每个 Provider 行显示：名称、模型数量、状态标签
- 状态标签为以下之一："Connected"（绿色/紫色）、"Not configured"（灰色）、"Disconnected"（红色/粉色）
- 右上角可见"+"按钮（为未来自定义 Provider 预留）

### 步骤 2.3：进入 Agent 列表

返回设置界面，点击"Manage Agents"。

**验证：**
- 打开 Agent 列表界面（详细的 Agent 管理流程见流程 7）

---

## 流程 3：Provider 详情 — API Key 管理

**入口：** 设置 → Manage Providers → 点击某个 Provider（如 Anthropic）。

### 步骤 3.1：打开 Provider 详情

**验证：**
- 打开 Provider 详情界面
- 顶部显示 Provider 名称
- API key 区域：遮蔽输入框（已有 key 时显示"••••"，无 key 时为空）
- 眼睛图标（切换显示/隐藏）
- 保存按钮
- "Test Connection"按钮
- 模型列表区域，显示可用模型
- 每个模型显示：名称、来源标签（PRESET 或 MANUAL）、默认模型星标图标

### 步骤 3.2：输入并保存 API key

点击 API key 输入框，输入 key，点击保存。

**验证：**
- Key 已保存（遮蔽显示更新）
- 保存后 UI 不显示明文 key
- 出现成功提示

### 步骤 3.3：切换 API key 显示

点击 API key 旁边的眼睛图标。

**验证：**
- Key 以明文形式显示
- 眼睛图标切换为"隐藏"样式
- 再次点击：key 重新遮蔽

### 步骤 3.4：测试连接

输入有效 API key 后，点击"Test Connection"。

**验证：**
- 显示加载指示器
- 成功时：显示"Connected"结果（绿色指示器或成功卡片）
- 失败时：显示错误类型（认证错误、网络错误、超时）

### 步骤 3.5：查看模型列表

**验证：**
- "Available Models"区域列出模型
- 每个模型显示：ID、来源（PRESET 或 MANUAL）、若为全局默认则显示星标

### 步骤 3.6：设置默认模型

点击非默认模型旁边的星标图标。

**验证：**
- 该模型的星标变为实心
- 之前默认模型的星标变为空心
- 出现成功提示

### 步骤 3.7：刷新模型列表

点击"Refresh Models"按钮。

**验证：**
- 显示加载指示器
- 模型列表更新（从 Provider API 重新获取）
- 显示成功或错误提示

---

## 流程 4：Provider 详情 — 手动添加模型

**入口：** 设置 → Manage Providers → [任意 Provider] → Add Manual Model。

### 步骤 4.1：添加手动模型

点击"Add Manual Model"按钮，输入模型 ID（如 `gpt-4-turbo`），确认。

**验证：**
- 模型出现在列表中，来源标签为"MANUAL"
- 可以将其设置为默认模型（星标可用）

### 步骤 4.2：删除手动模型

点击 MANUAL 模型旁边的删除图标，确认删除。

**验证：**
- 模型从列表中移除
- PRESET 模型不能删除（不显示删除图标）

---

## 流程 5：重新启动 — 首次启动检测

**验证 App 是否记住设置状态。**

### 步骤 5.1：完成设置后关闭并重新启动

```bash
adb shell am force-stop com.oneclaw.shadow
adb shell am start -n com.oneclaw.shadow/.MainActivity
```

**验证：**
- App 直接启动至 Chat 界面（不显示 Setup）
- 之前输入的 API key 仍然生效（检查：设置 → Providers）

---

## 流程 6：Chat — 发送消息和流式响应

**前置条件：** 至少一个 Provider 已配置有效 API key（见流程 3）。

### 步骤 6.1：进入 Chat 界面

从任意界面导航到 Chat 界面（设置完成后的主界面）。

**验证：**
- Chat 界面可见
- 无消息时显示空状态占位符
- 底部可见文字输入框
- 顶部栏显示 Agent 名称（默认："General Assistant"）
- 右上角有设置齿轮图标
- 左上角有抽屉/汉堡菜单图标

### 步骤 6.2：键盘弹出不推动顶部栏

点击消息输入框，使软键盘弹出。

**验证：**
- 软键盘出现在输入框上方
- **顶部应用栏保持完整可见**——左上角汉堡图标和右上角设置齿轮不得被隐藏或推出屏幕
- 消息列表在顶部栏和输入框之间仍然可见
- 如果键盘弹出后顶部栏被推出屏幕，这是一个 bug

### 步骤 6.3：发送消息

在输入框中输入消息（如"你好，你能做什么？"），点击发送按钮。

**验证：**
- 用户消息气泡出现在右侧（金/琥珀色背景）
- 流式传输期间发送按钮变为停止按钮
- AI 回复出现在左侧，文字逐步流入
- 流式传输完成后，AI 消息下方显示模型 ID 标签
- AI 消息下方显示复制和重新生成图标

### 步骤 6.4：中途停止生成

AI 流式传输期间，点击停止按钮。

**验证：**
- 流式传输立即停止
- 已生成的部分内容保持可见
- **停止按钮切换回发送按钮**——如果点击停止后停止按钮仍然可见，这是一个 bug（UI 卡在流式传输状态）
- 输入框变为可用，可以发送新消息

### 步骤 6.5：复制 AI 消息

点击已完成的 AI 消息下方的复制图标。

**验证：**
- 消息内容已复制到剪贴板（可通过粘贴到其他地方验证）

### 步骤 6.6：重新生成回复

点击 AI 消息下方的重新生成图标。

**验证：**
- AI 对同一对话重新生成一个新回复
- 新回复以流式方式呈现

### 步骤 6.7：Session 抽屉 — 导航

点击左上角汉堡菜单图标。

**验证：**
- 抽屉打开
- 顶部显示"New Conversation"按钮
- 已保存的 Session 列表显示：标题、消息预览、相对时间戳
- 点击某个 Session 可切换至该对话
- 长按可进入选择模式（显示复选框和批量删除工具栏）
- 向左滑动某个 Session 可删除；删除后显示撤销 Snackbar

### 步骤 6.8：开始新对话

在抽屉中点击"New Conversation"。

**验证：**
- 抽屉关闭
- Chat 界面重置为空状态
- 顶部栏显示当前 Agent 名称

### 步骤 6.9：Tool 调用可视化（如适用）

发送一条会触发工具的消息，例如"现在几点了？"。

**验证：**
- 出现工具调用卡片，显示：工具名称、状态（PENDING → SUCCESS/FAILED）、输入参数
- 工具完成后出现工具结果卡片，显示输出和耗时
- AI 最终回复引用工具结果

---

## 流程 7：Agent 管理

**入口：** 设置 → Manage Agents。

### 步骤 7.1：查看 Agent 列表

**验证：**
- 打开 Agent 列表界面
- "BUILT-IN"分组显示内置 Agent（如"General Assistant"、"Code Helper"）
- 内置 Agent 带"Built-in"标签
- 每个 Agent 名称下方显示工具数量
- 若有自定义 Agent，则显示"CUSTOM"分组
- 无自定义 Agent 时显示"No custom agents yet. Tap + to create one."提示

### 步骤 7.2：查看内置 Agent

点击某个内置 Agent（如"General Assistant"）。

**验证：**
- Agent 详情界面打开，标题为 Agent 名称（不是"Edit Agent"）
- 名称、描述、系统提示字段为只读
- 工具复选框显示但禁用（无法修改内置工具）
- "Preferred Model"区域显示（只读）
- "Clone Agent"按钮可见
- 无"Save"按钮；无"Delete Agent"按钮（内置 Agent 不能删除）

### 步骤 7.3：克隆内置 Agent

在内置 Agent 详情界面点击"Clone Agent"。

**验证：**
- 创建一个新的自定义 Agent（内置 Agent 的副本，名称前缀"Copy of"）
- 导航返回 Agent 列表
- 克隆的 Agent 出现在"CUSTOM"分组中

### 步骤 7.4：创建自定义 Agent

在 Agent 列表中点击"+"按钮。

**验证：**
- Agent 详情界面打开，标题为"Create Agent"
- 名称字段为空且可编辑
- 描述和系统提示字段可编辑
- 可通过复选框切换工具
- 顶部栏显示"Save"按钮（填写名称前禁用）

输入名称（如"My Test Agent"），可选择编辑其他字段，点击"Save"。

**验证：**
- 导航返回 Agent 列表
- 新 Agent 出现在"CUSTOM"分组中，名称和工具数量正确

### 步骤 7.5：编辑自定义 Agent

从 Agent 列表中点击某个自定义 Agent。

**验证：**
- Agent 详情界面打开，标题为"Edit Agent"
- 所有字段可编辑
- 未修改时"Save"按钮禁用
- 修改某个字段（如更新描述），点击"Save"
- 出现成功 Snackbar 或返回列表后值已更新

### 步骤 7.6：删除自定义 Agent

在自定义 Agent 详情界面点击"Delete Agent"。

**验证：**
- 弹出确认对话框："Delete Agent? This agent will be permanently removed. Any sessions using this agent will switch to General Assistant."
- 点击"Cancel"——对话框关闭，Agent 未被删除
- 再次点击"Delete Agent"并确认——Agent 被删除
- 导航返回 Agent 列表，该 Agent 不再显示

### 步骤 7.7：对话中切换 Agent

在 Chat 界面点击顶部栏的 Agent 名称。

**验证：**
- Agent 选择器底部弹窗打开
- 显示所有可用 Agent（内置和自定义）
- 当前 Agent 被高亮/勾选
- 点击其他 Agent——弹窗关闭，顶部栏更新为新 Agent 名称
- 新消息将使用所选 Agent

---

## 当前已知限制

所有计划的 RFC 均已实现，暂无已知功能限制。

---

## 变更历史

| 日期 | RFC | 变更内容 |
|------|-----|---------|
| 2026-02-27 | RFC-003、RFC-004 | 初始版本，涵盖 Setup、Settings、Provider 管理流程 |
| 2026-02-27 | RFC-005 | 更新当前状态，补充步骤 6.2 Session 抽屉细节，更新已知限制表 |
| 2026-02-27 | RFC-001、RFC-002 | 完整重写流程 6（含流式 Chat），新增流程 7（Agent 管理），更新 App 状态和设置步骤 2.3，移除所有"未实现"限制说明 |
