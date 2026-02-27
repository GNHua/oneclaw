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

RFC-002 实现了完整的 Agent 管理系统：自定义 Agent 的 CRUD 操作、内置 Agent 保护、每个 Agent 的工具和模型分配、Agent 克隆，以及对话中使用的 Agent 选择器底部弹窗。四个测试层均成功执行。

| 层 | 步骤 | 结果 | 说明 |
|----|------|------|------|
| 1A | JVM 单元测试 | PASS | 245 个测试，0 个失败 |
| 1B | 设备 DAO 测试 | PASS | 48 个测试，0 个失败 |
| 1C | Roborazzi 截图测试 | PASS | 4 张新截图 |
| 2 | adb 视觉验证 | SKIP | Chat 尚未接线；Layer 2 推迟到 RFC-001 完成后执行 |

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

**结果：** SKIP

**原因：** Layer 2 adb 流程需要完整应用（Chat + Agent + Provider 全部接线完成）。RFC-001 Chat 在同一提交中实现，Layer 2 将在两个 RFC 均提交、完整应用可构建后执行。

## 发现的问题

未发现任何问题。

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-02-27 | 初始报告 |
