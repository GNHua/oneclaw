# 配置管理工具

## 功能信息
- **功能 ID**: FEAT-036
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft
- **优先级**: P0（必须实现）
- **负责人**: TBD
- **关联 RFC**: RFC-036（待定）

## 用户故事

**作为** OneClawShadow 的用户，
**我希望** 通过与 AI Agent 对话来配置应用的每一个方面，
**以便** 我无需离开聊天界面即可调整 provider、模型、Agent、主题、工具状态或环境变量。

### 典型场景

1. 用户说"添加一个 base URL 为 https://api.openai.com/v1 的 OpenAI provider" -- Agent 调用 `create_provider` 并返回确认信息。用户随后在设置界面设置 API key（唯一需要通过界面操作的步骤）。
2. 用户说"我有哪些 provider？" -- Agent 调用 `list_providers`，返回包含 ID、名称、类型、base URL 和激活状态的格式化列表。
3. 用户说"切换到 Anthropic provider" -- Agent 调用 `update_provider`，将 Anthropic provider 的 `is_active` 设置为 true。
4. 用户说"我的 OpenAI provider 有哪些模型？" -- Agent 调用 `list_models`，传入 provider ID，返回模型列表。
5. 用户说"将 claude-sonnet-4-20250514 设为默认模型" -- Agent 调用 `set_default_model`，传入模型和 provider ID。
6. 用户说"显示我的 Agent 列表" -- Agent 调用 `list_agents`，返回所有 Agent 及其详细信息。
7. 用户说"将 Python Helper Agent 的系统提示词更新为包含错误处理指南" -- Agent 调用 `update_agent`，传入新的系统提示词。
8. 用户说"删除旧的测试 Agent" -- Agent 调用 `delete_agent`，传入该 Agent 的 ID。
9. 用户说"切换到暗色模式" -- Agent 调用 `set_config`，传入 `key=theme_mode, value=dark`。
10. 用户说"有哪些工具可用？哪些已启用？" -- Agent 调用 `list_tool_states`，返回完整列表。
11. 用户说"禁用浏览器工具" -- Agent 调用 `set_tool_enabled`，传入工具名称和 `enabled=false`。
12. 用户说"将 JS 工具的 OPENAI_API_KEY 环境变量设置为 sk-xxx" -- Agent 调用 `set_env_var`，传入 key 和 value。
13. 用户说"从我的 Gemini provider 刷新模型列表" -- Agent 调用 `fetch_models`，传入 provider ID。
14. 用户说"向我的 OpenAI provider 添加自定义模型 gpt-4-turbo" -- Agent 调用 `add_model`。

## 功能描述

### 概述

OneClawShadow 的目标是完全支持通过对话进行配置。目前，用户必须导航到各个设置界面来管理 provider、模型、Agent、主题、工具状态和环境变量。本功能新增一套完整的内置工具，使 AI Agent 能够读取和修改所有应用配置 -- 唯一例外是 API key / 身份验证凭据的管理，出于安全考虑仍须通过界面操作。

### 工具套件

本功能新增 17 个内置工具，按 6 个领域组织：

| 领域 | 工具 | 用途 |
|------|------|------|
| **Provider** | `list_providers` | 列出所有 provider 及详细信息 |
| | `create_provider` | 创建新的 provider |
| | `update_provider` | 更新 provider 字段（名称、URL、激活状态） |
| | `delete_provider` | 删除 provider |
| **模型** | `list_models` | 列出某个 provider 的模型 |
| | `fetch_models` | 从 provider API 刷新模型列表 |
| | `set_default_model` | 设置全局默认模型 |
| | `add_model` | 向 provider 手动添加模型 |
| | `delete_model` | 删除手动添加的模型 |
| **Agent** | `list_agents` | 列出所有 Agent |
| | `update_agent` | 更新 Agent 字段 |
| | `delete_agent` | 删除 Agent |
| **设置** | `get_config` | 读取应用配置项 |
| | `set_config` | 写入应用配置项 |
| **工具** | `list_tool_states` | 列出工具及其启用/禁用状态 |
| | `set_tool_enabled` | 启用或禁用某个工具或工具组 |
| **环境变量** | `manage_env_var` | 列出、设置或删除 JS 环境变量 |

### 架构概览

```
AI Agent（在聊天中）
    |  工具调用：create_provider / update_agent / set_config / ...
    v
ToolExecutionEngine（现有，不变）
    |
    v
ToolRegistry
    |
    +-- Provider 工具 -----> ProviderRepository
    +-- 模型工具 ----------> ProviderRepository
    +-- Agent 工具 --------> AgentRepository
    +-- 设置工具 ----------> SettingsRepository / ThemeManager
    +-- 工具状态工具 ------> ToolEnabledStateStore / ToolRegistry
    +-- 环境变量工具 ------> EnvironmentVariableStore
```

### 安全约束

**API key 和身份验证凭据被明确排除在外。** 这些内容必须通过界面设置（Provider 设置页面的 EncryptedSharedPreferences）。配置工具可以创建 provider 并配置其 URL，但用户必须前往「设置 > Provider」页面输入 API key。这是唯一需要离开聊天界面的操作。

### 用户交互流程

```
1. 用户："在 https://api.anthropic.com/v1 配置一个新的 Anthropic provider"
2. Agent 调用 create_provider(name="Anthropic", type="ANTHROPIC", api_base_url="https://api.anthropic.com/v1")
3. Agent："已创建 provider 'Anthropic'（ID：abc123）。请前往「设置 > Provider」设置 API key。"
4. 用户在界面设置 API key 后返回聊天
5. 用户："从 Anthropic 获取模型列表"
6. Agent 调用 fetch_models(provider_id="abc123")
7. Agent："找到 5 个模型：claude-sonnet-4-20250514、claude-haiku-4-5-20251001……"
8. 用户："将 claude-sonnet-4-20250514 设为默认"
9. Agent 调用 set_default_model(provider_id="abc123", model_id="claude-sonnet-4-20250514")
10. Agent："默认模型已设置为 claude-sonnet-4-20250514。"
```

## 验收标准

### Provider 工具

#### TEST-036-01：列出 Provider
- **前提** 存在一个或多个 provider
- **当** Agent 调用 `list_providers`
- **则** 返回所有 provider，包含 id、name、type、apiBaseUrl、isActive，以及是否已配置 API key

#### TEST-036-02：列出 Provider（空）
- **前提** 不存在任何 provider
- **当** Agent 调用 `list_providers`
- **则** 工具返回"No providers configured."

#### TEST-036-03：创建 Provider
- **前提** 提供有效的 name、type 和 api_base_url 参数
- **当** Agent 调用 `create_provider`
- **则** 创建新的 provider，工具返回其 ID，并提示用户设置 API key

#### TEST-036-04：创建 Provider 参数校验
- **前提** 参数缺失或无效（如 name 为空、type 无效）
- **当** Agent 调用 `create_provider`
- **则** 工具返回验证错误

#### TEST-036-05：更新 Provider
- **前提** 存在一个 provider
- **当** Agent 调用 `update_provider`，传入部分字段
- **则** 仅更新所提供的字段，其余字段保持不变

#### TEST-036-06：删除 Provider
- **前提** 存在一个 provider
- **当** Agent 调用 `delete_provider`
- **则** 该 provider、其关联的模型以及 API key 均被删除

#### TEST-036-07：删除预置 Provider
- **前提** 存在一个预置（内置）provider
- **当** Agent 调用 `delete_provider`
- **则** 工具返回错误：预置 provider 不可删除

### 模型工具

#### TEST-036-08：列出模型
- **前提** 某个 provider 下存在模型
- **当** Agent 调用 `list_models`，传入 provider ID
- **则** 返回所有模型，包含 id、displayName、source、isDefault 和 contextWindowSize

#### TEST-036-09：设置默认模型
- **前提** 存在有效的模型和 provider
- **当** Agent 调用 `set_default_model`
- **则** 全局默认模型被更新

#### TEST-036-10：手动添加模型
- **前提** 提供有效的 provider ID 和 model ID
- **当** Agent 调用 `add_model`
- **则** 向该 provider 添加一个新的 MANUAL 模型

#### TEST-036-11：删除手动模型
- **前提** 存在一个 MANUAL 模型
- **当** Agent 调用 `delete_model`
- **则** 该模型被删除

#### TEST-036-12：删除非手动模型
- **前提** 存在一个 DYNAMIC 或 PRESET 模型
- **当** Agent 调用 `delete_model`
- **则** 工具返回错误：仅 MANUAL 模型可被删除

#### TEST-036-13：从 API 获取模型
- **前提** 某个 provider 已配置 API key
- **当** Agent 调用 `fetch_models`
- **则** 从 provider API 获取模型，并刷新模型列表

### Agent 工具

#### TEST-036-14：列出 Agent
- **前提** 存在一个或多个 Agent
- **当** Agent 调用 `list_agents`
- **则** 返回所有 Agent，包含 id、name、description、isBuiltIn、preferredProvider 和 preferredModel

#### TEST-036-15：更新 Agent
- **前提** 存在一个 Agent
- **当** Agent 调用 `update_agent`，传入部分字段
- **则** 仅更新所提供的字段

#### TEST-036-16：更新内置 Agent
- **前提** 存在一个内置 Agent
- **当** Agent 调用 `update_agent`
- **则** 工具返回错误：内置 Agent 不可修改

#### TEST-036-17：删除 Agent
- **前提** 存在一个非内置 Agent
- **当** Agent 调用 `delete_agent`
- **则** 该 Agent 被删除

#### TEST-036-18：删除内置 Agent
- **前提** 存在一个内置 Agent
- **当** Agent 调用 `delete_agent`
- **则** 工具返回错误：内置 Agent 不可删除

### 设置工具

#### TEST-036-19：读取配置
- **前提** 存在已知的配置 key（如 `theme_mode`）
- **当** Agent 调用 `get_config`
- **则** 返回当前值

#### TEST-036-20：读取未知配置 Key
- **前提** 使用未知的配置 key
- **当** Agent 调用 `get_config`
- **则** 工具返回 null / 未设置，并附带已知 key 列表

#### TEST-036-21：设置主题配置
- **前提** 传入有效的主题值（system / light / dark）
- **当** Agent 调用 `set_config`，传入 `key=theme_mode`
- **则** 主题通过 ThemeManager 立即生效

#### TEST-036-22：设置无效配置值
- **前提** 对某个已知 key 传入无效值
- **当** Agent 调用 `set_config`
- **则** 工具返回验证错误，并附带允许值列表

### 工具状态工具

#### TEST-036-23：列出工具状态
- **当** Agent 调用 `list_tool_states`
- **则** 返回所有已注册的工具，按分类分组，并显示各工具的启用/禁用状态

#### TEST-036-24：启用/禁用工具
- **前提** 工具名称有效
- **当** Agent 调用 `set_tool_enabled`，传入 `enabled=false`
- **则** 该工具被禁用，后续 Agent 工具调用中不再包含该工具

#### TEST-036-25：启用/禁用工具组
- **前提** 工具组名称有效
- **当** Agent 调用 `set_tool_enabled`，传入 `type=group`
- **则** 该组内所有工具均被禁用

### 环境变量工具

#### TEST-036-26：列出环境变量
- **当** Agent 调用 `manage_env_var`，传入 `action=list`
- **则** 返回所有环境变量的 key（值被掩码隐藏）

#### TEST-036-27：设置环境变量
- **当** Agent 调用 `manage_env_var`，传入 `action=set`、key 和 value
- **则** 环境变量被存储到 EncryptedSharedPreferences

#### TEST-036-28：删除环境变量
- **当** Agent 调用 `manage_env_var`，传入 `action=delete` 和 key
- **则** 该环境变量被删除

## UI/UX 要求

本功能无新增 UI。所有工具均通过现有聊天界面和工具调用展示区运行，无需额外的设置页面。

## 功能边界

### 包含范围

- 17 个用于完整应用配置管理的内置 Kotlin 工具
- Provider 的增删改查（不含 API key）
- 模型的列出、设置默认、手动添加/删除、从 API 获取
- Agent 的列出、更新和删除（创建功能已由 FEAT-002 实现）
- 应用设置的读写（主题）
- 工具启用状态管理
- JS 环境变量管理
- 在 ToolModule 中注册所有工具

### 不包含（V1）

- API key 管理（出于安全考虑保留在界面操作）
- Skill 管理工具（由 FEAT-014 覆盖）
- 记忆管理工具（独立关注点）
- 定时任务工具（由 FEAT-027 覆盖）
- 会话管理工具（独立关注点）
- 用量统计工具（只读，优先级较低）
- 备份/同步工具（独立关注点）
- 批量操作（如删除所有 provider）
- 通过工具测试 provider 连接（使用界面操作）

## 业务规则

1. API key 和身份验证凭据**不得**通过工具读取、设置或修改 -- 这是硬性安全约束
2. 预置（内置）provider 不可删除，仅可停用
3. 内置 Agent 不可修改或删除
4. 只有 MANUAL 模型可被删除；DYNAMIC 和 PRESET 模型由系统管理
5. 通过 `set_config` 修改主题后立即生效（via ThemeManager.setThemeMode）
6. 环境变量值存储在 EncryptedSharedPreferences 中，列出时进行掩码处理（仅显示 key）
7. 通过 `set_tool_enabled` 禁用某工具后，该工具将不再被纳入 Agent 的工具调用范围
8. 删除某个 provider 时，其关联的模型和 API key 也一并删除
9. 已知配置 key 及其允许值会被校验；未知 key 以自由格式字符串存储

## 非功能性要求

### 性能

- 所有配置工具：响应时间 < 100ms（本地数据库/偏好设置操作）
- `fetch_models`：最长 30s（网络 API 调用）

### 内存

- 无显著内存影响 -- 工具为无状态设计，委托给现有 repository

### 兼容性

- 支持所有受支持的 Android 版本（API 26+）
- 无需额外依赖库

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**: Tool 接口、registry、执行引擎
- **FEAT-003（Provider）**: ProviderRepository、provider 管理
- **FEAT-002（Agent）**: AgentRepository、Agent 管理
- **FEAT-009（设置）**: SettingsRepository、ThemeManager

### 被依赖于

- 目前无

### 外部依赖

- 无（所有操作均使用现有内部组件）

## 错误处理

### 错误场景

1. **实体未找到**
   - 原因：Provider / Agent / Model ID 不存在
   - 处理：返回 `ToolResult.error("not_found", "Provider not found with ID 'xyz'.")`

2. **验证错误**
   - 原因：缺少必填参数、枚举值无效、违反约束条件
   - 处理：返回 `ToolResult.error("validation_error", "<具体信息>")`

3. **权限拒绝**
   - 原因：尝试修改内置 Agent 或删除预置 provider
   - 处理：返回 `ToolResult.error("permission_denied", "<具体信息>")`

4. **API key 未设置**
   - 原因：对未配置 API key 的 provider 调用 `fetch_models`
   - 处理：返回 `ToolResult.error("api_key_required", "API key not configured for provider 'X'. Please set it in Settings > Providers.")`

5. **网络错误**
   - 原因：`fetch_models` 因网络问题失败
   - 处理：返回 `ToolResult.error("network_error", "<错误信息>")`

## 测试要点

### 功能测试

- 验证每个工具在参数有效时的成功路径
- 验证每个工具在参数无效/缺失时的错误路径
- 验证更新类工具的部分更新语义
- 验证预置 provider 的保护机制
- 验证内置 Agent 的保护机制
- 验证只有 MANUAL 模型可被删除
- 验证主题变更立即生效
- 验证列出环境变量时值被掩码处理
- 验证启用/禁用工具影响工具可用性

### 边界情况

- 创建与已有 provider 同名的 provider
- 更新 provider 时修改其类型（不应允许）
- 删除作为全局默认模型所属 provider 的 provider
- 将来自非激活 provider 的模型设为默认模型
- 以空系统提示词更新 Agent
- 以空值调用 set_config
- 启用/禁用不存在的工具
- 设置 key 或 value 极长的环境变量
- 无已注册工具时列出工具列表
- 并发配置更新

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
