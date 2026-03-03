# Agent 模型参数（Temperature 与最大迭代次数）

## 功能信息
- **功能 ID**: FEAT-038
- **创建时间**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **优先级**: P1（应该有）
- **负责人**: 待定
- **关联 RFC**: RFC-038（待定）

## 用户故事

**作为** OneClaw 的用户，
**我希望** 能够为每个 agent 分别配置模型 temperature 和最大迭代次数，
**以便** 我可以精细调节每个 agent 的创意程度，并控制其在停止前执行的工具调用轮次。

### 典型场景

1. "创意写作"agent 应使用较高的 temperature（例如 1.2）以获得多样且富有想象力的输出，而"代码助手"agent 应使用较低的 temperature（例如 0.2）以获得确定性、精确的响应。
2. 执行大量顺序工具调用的数据分析 agent 应允许最多 50 次迭代，而简单的问答 agent 应在 5 次迭代后停止，以避免无限循环。
3. 内置的通用助手使用系统默认值（temperature = null / 最大迭代次数 = null），即使用服务商默认 temperature，并沿用全局上限 100 轮。
4. 用户克隆通用助手后，仅调整 temperature，将最大迭代次数保持为默认值。
5. 用户通过提示词生成创建新 agent；生成的 agent 继承默认参数值，用户可在之后进行调整。

## 功能描述

### 概述

FEAT-038 为每个 agent 新增两个可配置参数：

| 参数 | 类型 | 范围 | 默认值 | 用途 |
|------|------|------|--------|------|
| **Temperature** | `Float?` | 0.0 -- 2.0 | `null`（服务商默认） | 控制模型输出的随机性 / 创意程度 |
| **Max Iterations** | `Int?` | 1 -- 100 | `null`（全局默认 = 100） | 限制每个对话轮次中工具调用的轮数 |

这些参数存储在 agent 的数据模型中，通过 Room 持久化，并在执行时传入聊天管道。

### 架构概览

```
AgentDetailScreen  (UI: 两个新输入字段)
       |
AgentDetailViewModel  (状态 + 校验)
       |
AgentRepository  (CRUD，接口不变)
       |
   AgentEntity / Agent  (新字段: temperature, maxIterations)
       |
   AgentMapper  (映射新字段)
       |
  Room Migration  (v7 -> v8: ALTER TABLE ADD COLUMN x2)
       |
SendMessageUseCase  (读取 agent.maxIterations 作为循环上限,
       |              将 agent.temperature 传给 adapter)
       |
ModelApiAdapter.sendMessageStream()  (新增 `temperature` 参数)
       |
OpenAiAdapter / AnthropicAdapter / GeminiAdapter  (在 API 请求 JSON 中包含 temperature)
```

### 用户交互流程

#### Temperature 设置
```
1. 用户进入 Agent Detail 界面（新建或编辑）
2. 在"首选模型"下拉框下方显示"Temperature"滑块或文本输入框
3. 用户调整数值（0.0 到 2.0，步长 0.1）
4. 点击"Clear"选项可重置为 null（服务商默认）
5. 用户保存 agent
6. 下次发送聊天消息时，temperature 将传递给 API
```

#### Max Iterations 设置
```
1. 用户进入 Agent Detail 界面（新建或编辑）
2. 在 Temperature 字段下方显示"Max Iterations"文本输入框
3. 用户输入数值（1 到 100）
4. 点击"Clear"选项可重置为 null（全局默认 = 100）
5. 用户保存 agent
6. 下次发送聊天消息时，工具循环将遵守该限制
```

## 验收标准

必须通过（全部必需）：

- [ ] Agent 领域模型包含 `temperature: Float?` 和 `maxIterations: Int?`
- [ ] AgentEntity 包含对应的 Room 列，默认值为 NULL
- [ ] Room 迁移 v7 -> v8 在 `agents` 表中新增两列
- [ ] AgentMapper 在 entity 与领域模型之间映射两个新字段
- [ ] AgentDetailScreen 显示 temperature 输入控件（滑块或文本框，范围 0.0--2.0）
- [ ] AgentDetailScreen 显示 max iterations 输入控件（文本框，范围 1--100）
- [ ] 两个字段均支持"清除/重置为默认值"操作
- [ ] `hasUnsavedChanges` 检测包含两个新字段
- [ ] `ModelApiAdapter.sendMessageStream()` 接受可选的 `temperature` 参数
- [ ] 三个 adapter（OpenAI、Anthropic、Gemini）在非 null 时将 `temperature` 包含在 API 请求中
- [ ] `SendMessageUseCase` 使用 `agent.maxIterations ?: MAX_TOOL_ROUNDS` 作为循环上限
- [ ] `SendMessageUseCase` 将 `agent.temperature` 传递给 adapter
- [ ] 内置通用助手 agent 的两个字段默认为 null（行为不变）
- [ ] 校验拒绝 temperature 超出 0.0--2.0 范围及 iterations 超出 1--100 范围的输入
- [ ] 所有 Layer 1A 测试通过

可选（锦上添花）：

- [ ] Temperature 预设值（例如"精确 0.2"、"均衡 0.7"、"创意 1.2"）作为快速选择芯片
- [ ] 在 agent 列表中以视觉标识显示非默认参数值

## UI/UX 要求

### Agent Detail 界面（新增内容）

```
┌──────────────────────────────────┐
│ <- Edit Agent              [Save]│
├──────────────────────────────────┤
│ ... existing fields ...          │
├──────────────────────────────────┤
│ PREFERRED MODEL (optional)       │
│ ┌──────────────────────────────┐ │
│ │ Using global default       v │ │
│ └──────────────────────────────┘ │
├──────────────────────────────────┤
│ TEMPERATURE (optional)           │
│ ┌──────────────────────────────┐ │
│ │ [0.0 ====|======== 2.0] 0.7 │ │
│ └──────────────────────────────┘ │
│ Provider default when not set    │
│                        [Clear]   │
├──────────────────────────────────┤
│ MAX ITERATIONS (optional)        │
│ ┌──────────────────────────────┐ │
│ │ 10                           │ │
│ └──────────────────────────────┘ │
│ Global default (100) when not set│
│                        [Clear]   │
├──────────────────────────────────┤
│ [      Clone Agent             ] │
│ [      Delete Agent            ] │
└──────────────────────────────────┘
```

- Temperature：带数字显示的滑块（步长 0.1），或带数字键盘的 OutlinedTextField
- Max Iterations：带数字键盘的 OutlinedTextField，校验范围 1--100
- 两者均显示辅助文字，说明值为 null 时的默认行为
- 仅在已设置值时显示"Clear"按钮

## 功能边界

### 包含范围

- Agent 模型与 entity 上的 `temperature` 和 `maxIterations` 字段
- Room 迁移（v7 -> v8）
- AgentDetailScreen 上的 UI 控件
- `ModelApiAdapter.sendMessageStream()` 中的 temperature 透传
- 三个 adapter 实现均已更新
- `SendMessageUseCase` 中的最大迭代次数限制
- 校验逻辑
- 未保存变更的检测

### 不包含范围（V1）

- Top-P、频率惩罚、存在惩罚或其他采样参数
- 逐条消息的 temperature 覆盖（始终使用 agent 级别设置）
- 最大 token 数 / 输出长度控制（独立功能）
- Temperature 预设值 UI
- 聊天头部的 agent 参数展示

## 业务规则

1. `temperature = null` 表示"使用服务商默认值"——该字段将从 API 请求中省略
2. `maxIterations = null` 表示"使用全局默认值"——沿用现有的 `MAX_TOOL_ROUNDS = 100`
3. Temperature 范围为 0.0 到 2.0（含边界），与 OpenAI、Anthropic 和 Gemini API 的通用范围一致
4. 最大迭代次数范围为 1 到 100（含边界）
5. 两个字段均可为 null——agent 无需强制设置
6. 内置 agent 的两个字段默认为 null
7. 克隆 agent 时，temperature 和 maxIterations 的值会一并复制
8. `create_agent` 工具（RFC-020）不设置这些参数——它们只能通过 UI 配置

## 非功能性要求

### 性能

- 无可测量的性能影响——Room 中额外两列可空字段，API 请求 JSON 中额外两个字段

### 兼容性

- Room 迁移为增量操作（ALTER TABLE ADD COLUMN WITH DEFAULT NULL）——完全向后兼容
- API 兼容性：三个服务商（OpenAI、Anthropic、Gemini）均支持 `temperature` 参数

## 依赖关系

### 依赖于

- **FEAT-002（Agent 管理）**：Agent 模型、entity、repository、CRUD
- **FEAT-001（聊天交互）**：SendMessageUseCase、流式传输管道
- **RFC-020（Agent 增强）**：Agent detail UI、模型选择器

### 被依赖于

- 暂无

## 错误处理

### 错误场景

1. **Temperature 超出范围**：用户输入 0.0--2.0 范围外的值——显示内联校验错误，阻止保存
2. **最大迭代次数超出范围**：用户输入 1--100 范围外的值——显示内联校验错误，阻止保存
3. **非数字输入**：用户在数字字段中输入文本——忽略非数字字符
4. **迁移失败**：Room 迁移 v7->v8 失败——沿用标准 Room 迁移错误处理（应用不会崩溃；如已配置则执行回退迁移）

## 测试要点

### 功能测试

- 验证 Agent 领域模型包含 temperature 和 maxIterations，且默认值为 null
- 验证 AgentEntity 包含两列
- 验证 Room 迁移 v7->v8 成功新增两列
- 验证保存 temperature = 0.7 的 agent 后能正确持久化并重新加载
- 验证保存 maxIterations = 10 的 agent 后能正确持久化并重新加载
- 验证 null 值能正确持久化（两个字段均为可选）
- 验证 AgentDetailScreen 显示 temperature 控件
- 验证 AgentDetailScreen 显示最大迭代次数控件
- 验证清除 temperature 后重置为 null
- 验证清除 maxIterations 后重置为 null
- 验证 hasUnsavedChanges 能检测到 temperature 变更
- 验证 hasUnsavedChanges 能检测到 maxIterations 变更
- 验证 SendMessageUseCase 遵守 agent.maxIterations
- 验证 SendMessageUseCase 将 agent.temperature 传递给 adapter
- 验证 OpenAI adapter 在非 null 时将 temperature 包含在请求中
- 验证 Anthropic adapter 在非 null 时将 temperature 包含在请求中
- 验证 Gemini adapter 在非 null 时将 temperature 包含在请求中
- 验证 adapter 在 temperature 为 null 时将其省略

### 边界情况

- Temperature 设置为 0.0（最小值）——应正常工作，产生确定性输出
- Temperature 设置为 2.0（最大值）——应正常工作，产生高度多样化的输出
- 最大迭代次数设置为 1——agent 恰好执行一轮（无工具循环）
- 最大迭代次数设置为 100——与当前默认行为相同
- 克隆含非 null temperature 的 agent——克隆后的 agent 应具有相同的 temperature
- 迁移后内置 agent 的 temperature 仍为 null

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
