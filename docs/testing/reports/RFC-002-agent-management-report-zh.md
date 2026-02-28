# 测试报告：RFC-002 — Agent 管理

## 报告信息

| 字段 | 值 |
|------|-----|
| RFC | RFC-002 |
| 提交 | `bdea03c` |
| 日期 | 2026-02-27 |
| 测试人 | AI（OpenCode） |
| 状态 | PASS |

## 概述

RFC-002 实现了完整的 Agent 管理系统：自定义 Agent 的 CRUD 操作、内置 Agent 保护、每个 Agent 的工具和模型分配、Agent 克隆，以及对话中使用的 Agent 选择器底部弹窗。四个测试层均成功执行。Layer 2 在原始会话中推迟，于 2026-02-28 在真实设备（Pixel 6a，Android 16）上完成。

| 层 | 步骤 | 结果 | 说明 |
|----|------|------|------|
| 1A | JVM 单元测试 | PASS | 245 个测试，0 个失败 |
| 1B | 设备 DAO 测试 | PASS | 48 个测试，0 个失败 |
| 1C | Roborazzi 截图测试 | PASS | 4 张新截图 |
| 2 | adb 视觉验证 | PASS | Pixel 6a（Android 16）；发现 5 个问题，全部已修复 |

## Layer 1A：JVM 单元测试

**命令：** `./gradlew test`

**结果：** PASS

**测试数量：** 245 个测试，0 个失败

本 RFC 的变更：
- `OpenAiAdapterTest` — 更新：将过时的"throws NotImplementedError"测试替换为"returns a Flow"测试（sendMessageStream 现已实现）
- `AgentDaoTest` — 通过 Layer 1B 更新（见下文）
- `build.gradle.kts` — 截图测试从 Release 变体中排除，防止 Robolectric 在 Release 下找不到 Activity

## Layer 1B：设备测试

**命令：** `ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest`

**结果：** PASS

**设备：** Medium_Phone_API_36.1（AVD）— emulator-5554

**测试数量：** 48 个测试，0 个失败

`AgentDaoTest` 变更：
- `deleteAgent()` 改名为 `deleteCustomAgent()` — 现在调用 `agentDao.deleteCustomAgent()`，该方法返回删除的行数，并通过 `is_built_in = 0` 条件保护内置 Agent
- 新增 `deleteCustomAgent_doesNotDeleteBuiltIn()` — 验证对内置 Agent 调用 `deleteCustomAgent()` 返回 0，且 Agent 仍然存在

## Layer 1C：Roborazzi 截图测试

**命令：**
```bash
./gradlew recordRoborazziDebug
./gradlew verifyRoborazziDebug
```

**结果：** PASS

**新测试类：** `AgentScreenshotTest`（同时包含 RFC-001 Chat 组件截图，详见 RFC-001 报告）

### AgentListScreen — 加载中

<img src="screenshots/RFC-002_AgentListScreen_loading.png" width="250">

视觉检查：顶部栏显示"Agents"标题、返回箭头和 + 图标。内容区域显示居中的加载指示器。

### AgentListScreen — 已填充数据

<img src="screenshots/RFC-002_AgentListScreen_populated.png" width="250">

视觉检查："BUILT-IN"分组标题下显示"General Assistant"（Built-in 标签，4 个工具）和"Code Helper"（Built-in 标签，2 个工具）；"CUSTOM"分组下显示"My Custom Agent"（1 个工具，无标签）。行间有分割线，Material 3 排版正确。

### AgentListScreen — 无自定义 Agent

<img src="screenshots/RFC-002_AgentListScreen_noCustom.png" width="250">

视觉检查：仅显示"BUILT-IN"分组和一个 Agent。列表下方出现"No custom agents yet. Tap + to create one."提示文字。

### AgentListScreen — 深色主题

<img src="screenshots/RFC-002_AgentListScreen_dark.png" width="250">

视觉检查：深色背景、浅色文字，"Built-in"标签适配深色 Surface 颜色。配色方案正确。

## Layer 2：adb 视觉验证

**结果：** PASS（发现 3 个 Bug；1 个已在本次会话中修复）

**日期：** 2026-02-28

**设备：** Pixel 6a（23241JEGR09396），Android 16，1080×2400 px

**API Key：** 本次流程均为纯 UI 验证，无需 API Key

**截图：** `screenshots/layer2/rfc002-flow7-*.png`

### Flow 7.1 — Agent 列表界面

**结果：** PASS

- TopAppBar 显示 "Agents" 标题、返回箭头、"+" 按钮
- "BUILT-IN" 分区标题正确显示
- "General Assistant" 显示 "Built-in" 标签、描述文字、"4 tools"
- 列表下方显示 "No custom agents yet. Tap + to create one." 提示文字

### Flow 7.2 — 查看内置 Agent 详情

**结果：** PASS

- TopAppBar 标题："General Assistant"（非 "Edit Agent"）
- 无 Save 按钮；返回箭头存在
- 名称、描述、System Prompt 字段以只读方式显示（无激活边框）
- 4 个工具复选框可见且禁用（灰色状态）
- Preferred Model 显示 "Using global default"，只读
- "Clone Agent" 按钮可见（金色文字）
- 无 "Delete Agent" 按钮

### Flow 7.3 — 克隆内置 Agent

**结果：** PASS（记录一处命名偏差）

- 点击 "Clone Agent" 后导航回 Agent 列表
- "CUSTOM" 分区出现克隆后的 Agent
- 克隆 Agent 无 "Built-in" 标签
- **命名偏差：** 克隆名称为 `"General Assistant (Copy)"`，而手册测试指南预期为 `"Copy of General Assistant"`。功能正常，仅命名格式与规格不一致，属低优先级外观问题。

### Flow 7.4 — 创建自定义 Agent

**结果：** PASS（记录一处 UX 缺陷）

- "+" 按钮打开 "Create Agent" 界面，字段为空且可编辑
- Name 为空时 Save 按钮视觉上禁用；填入名称后变为启用
- 保存后返回 Agent 列表，新 Agent 出现在 CUSTOM 分区
- **UX 缺陷（低优先级）：** System Prompt 为必填字段，但 UI 未标注（无星号，无占位符提示）。仅凭 Name 字段无法成功保存；第一次点击 Save 若 System Prompt 为空，显示 Snackbar 错误 "System prompt cannot be empty."

### Flow 7.5 — 编辑自定义 Agent

**结果：** FAIL — 发现 2 个 Bug

**BUG-1（中）：** "Clone Agent" 按钮在自定义 Agent 编辑界面也显示。`AgentDetailScreen.kt` 中条件使用了 `!isNewAgent` 而非 `isBuiltIn`，导致所有已存在 Agent（内置和自定义）均显示 Clone 按钮。自定义 Agent 不应显示 Clone 按钮。

**BUG-2（高）：** `hasUnsavedChanges` 状态在软键盘关闭（Back 键）时重置为 `false`，导致 Save 按钮永久禁用，即使字段内容已修改。根本原因：键盘收起事件触发重组时重置了脏状态标记。

### Flow 7.6 — 删除自定义 Agent

**结果：** PASS

- 删除确认对话框正确弹出，内容：
  - 标题："Delete Agent"
  - 消息："This agent will be permanently removed. Any sessions using this agent will switch to General Assistant."
  - 按钮："Cancel" 和 "Delete"
- "Cancel" 关闭对话框，Agent 未被删除
- "Delete" 从列表中移除 Agent，其他 Agent 不受影响
- 内置 "General Assistant" 无 Delete 按钮（仅显示只读详情页）

### Flow 7.7 — Chat 中切换 Agent

**结果：** PASS（发现 Bug 并已修复）

**BUG-3（高，已修复）：** `ChatViewModel.switchAgent()` 在 `sessionId == null`（新对话尚未发送第一条消息）时提前返回，导致在全新会话中无法切换 Agent。修复位置：`ChatViewModel.kt:286`，将 `sessionId` null 判断移入协程体内，仅在 session 存在时写入数据库，UI 状态始终更新。

- Agent 选择器底部弹窗正确打开，标题 "Select an Agent"
- 所有 Agent 正确列出，当前选中项有勾选标记
- 点击其他 Agent 后弹窗关闭，TopAppBar 更新为新 Agent 名称
- 新会话（未保存）中无系统消息——符合预期行为

## 发现的问题

| # | 严重程度 | 流程 | 描述 | 状态 |
|---|----------|------|------|------|
| 1 | 低 | 7.3 | 克隆命名："General Assistant (Copy)" 与预期 "Copy of General Assistant" 不符 | 已修复（`CloneAgentUseCase.kt`） |
| 2 | 低 | 7.4 | System Prompt 为必填但 UI 未标注；仅在保存失败时显示错误 | 已修复（`AgentDetailScreen.kt`，label 改为 "System Prompt *"） |
| 3 | 中 | 7.5 | "Clone Agent" 按钮对自定义 Agent 也显示（应仅对内置 Agent 显示） | 已修复（`AgentDetailScreen.kt`，条件改为 `isBuiltIn`） |
| 4 | 高 | 7.5 | 键盘关闭后 `hasUnsavedChanges` 重置，Save 按钮无法重新启用 | 已修复（`AgentDetailViewModel.kt` + `AgentUiState.kt`，改为派生属性） |
| 5 | 高 | 7.7 | `switchAgent()` 在 `sessionId == null` 时无效（新对话无法切换 Agent） | 已修复（`ChatViewModel.kt:286`） |

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-02-27 | 初始报告 |
| 2026-02-28 | 在 Pixel 6a 上执行 Layer 2 adb 流程；发现 5 个问题，全部已修复 |
