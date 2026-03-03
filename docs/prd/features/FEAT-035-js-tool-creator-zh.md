# JS 工具创建器

## 功能信息
- **功能 ID**: FEAT-035
- **创建时间**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **优先级**: P1（应有）
- **负责人**: TBD
- **关联 RFC**: RFC-035

## 用户故事

**作为** OneClaw 的用户，
**我希望** 在聊天界面用自然语言描述一个自定义工具，并让 AI 为我生成、注册和管理它，
**以便于** 我无需手动编写 manifest 和源文件，即可用新的 JavaScript 工具扩展 Agent 的能力。

### 典型场景

1. 用户需要一个解析 CSV 数据的工具。他们调用 `/create-tool` 或直接在聊天中描述需求。AI 提出澄清性问题，生成 JSON manifest 和 JS 代码，展示供审查，用户确认后立即创建并注册该工具。
2. 用户发现自定义工具需要增加一个参数。他们请 AI 更新，AI 生成更新后的代码并调用 `update_js_tool` 应用修改。
3. 用户想了解自己拥有哪些自定义工具。AI 调用 `list_user_tools` 并呈现摘要。
4. 用户不再需要某个自定义工具。他们请 AI 删除它，AI 调用 `delete_js_tool` 注销并删除工具文件。
5. 用户请 AI "制作一个从 API 获取天气的工具"。AI 使用 `/create-tool` 技能引导对话，设计带有适当参数（城市、单位）的工具，使用 `fetch()` 桥接生成 JS 代码，并完成注册。

## 功能描述

### 概述

FEAT-035 新增了直接从聊天界面创建、列表、更新和删除自定义 JavaScript 工具的能力。该功能由两部分组成：

1. **一个内置技能**（`create-tool`），引导 AI 完成工具创建流程，包括收集需求、设计参数、生成代码，以及在保存前请用户确认。
2. **四个内置 Kotlin 工具**，执行对用户 JS 工具的实际 CRUD 操作：
   - `create_js_tool` -- 创建新的 JS 工具（写入文件 + 在 ToolRegistry 中注册）
   - `list_user_tools` -- 列出所有用户创建的 JS 工具
   - `update_js_tool` -- 更新已有的用户 JS 工具
   - `delete_js_tool` -- 删除用户 JS 工具

创建的工具会持久化到文件系统，并在应用重启后继续可用。它们与手动创建的用户 JS 工具在功能上完全相同，可访问相同的桥接 API（`fetch`、`fs`、`console`、`_time`、`lib`）。

### 架构概览

```
用户: "我需要一个解析 CSV 的工具"
    |
    v
AI 加载 /create-tool 技能
    |
    v
AI 设计工具，展示代码供审查
    |
    v（用户确认）
AI 调用 create_js_tool(name, description, parameters_schema, js_code)
    |
    v
CreateJsToolTool
    |-- 验证输入（名称格式、JSON schema、JS 语法）
    |-- 将 tool-name.json（manifest）写入 {filesDir}/tools/
    |-- 将 tool-name.js（源码）写入 {filesDir}/tools/
    |-- 创建 JsTool 实例
    |-- 在 ToolRegistry 中注册
    |
    v
工具在当前会话中立即可用
（并在下次应用重启时由 JsToolLoader 自动加载）
```

### 工具定义

#### create_js_tool

| 字段 | 值 |
|-------|-------|
| 名称 | `create_js_tool` |
| 描述 | 创建一个新的 JavaScript 工具并注册以供使用 |
| 参数 | `name`（string，必填）：工具名称（小写字母、数字、下划线；2-50 个字符） |
| | `description`（string，必填）：工具的用途说明（向 AI 展示以辅助工具选择） |
| | `parameters_schema`（string，必填）：定义参数 schema 的 JSON 字符串 |
| | `js_code`（string，必填）：包含 `execute(params)` 函数的 JavaScript 源代码 |
| | `required_permissions`（string，可选）：逗号分隔的 Android 权限名称 |
| | `timeout_seconds`（integer，可选，默认值：30）：执行超时时间 |
| 所需权限 | 无（工具本身无需权限；被创建的工具可声明自己的权限） |
| 超时 | 10 秒 |
| 返回值 | 包含工具名称和注册状态的成功消息 |

#### list_user_tools

| 字段 | 值 |
|-------|-------|
| 名称 | `list_user_tools` |
| 描述 | 列出所有用户创建的 JavaScript 工具 |
| 参数 | （无） |
| 所需权限 | 无 |
| 超时 | 5 秒 |
| 返回值 | 包含名称、描述和源文件路径的用户工具格式化列表 |

#### update_js_tool

| 字段 | 值 |
|-------|-------|
| 名称 | `update_js_tool` |
| 描述 | 更新已有的用户创建 JavaScript 工具 |
| 参数 | `name`（string，必填）：要更新的工具名称 |
| | `description`（string，可选）：新的描述 |
| | `parameters_schema`（string，可选）：新的参数 schema JSON |
| | `js_code`（string，可选）：新的 JavaScript 源代码 |
| | `required_permissions`（string，可选）：新的逗号分隔权限 |
| | `timeout_seconds`（integer，可选）：新的超时时间 |
| 所需权限 | 无 |
| 超时 | 10 秒 |
| 返回值 | 确认更新成功的消息 |

#### delete_js_tool

| 字段 | 值 |
|-------|-------|
| 名称 | `delete_js_tool` |
| 描述 | 删除用户创建的 JavaScript 工具 |
| 参数 | `name`（string，必填）：要删除的工具名称 |
| 所需权限 | 无 |
| 超时 | 5 秒 |
| 返回值 | 确认删除成功的消息 |

### 技能定义

#### create-tool

| 字段 | 值 |
|-------|-------|
| 名称 | `create-tool` |
| 显示名称 | Create Tool |
| 描述 | 引导用户创建自定义 JavaScript 工具 |
| 所需工具 | `create_js_tool` |
| 参数 | `idea`（string，可选）：对工具用途的简要描述 |

技能提示词指示 AI 执行以下步骤：
1. 澄清用户需求（工具的用途、输入、输出）
2. 设计工具的参数和行为
3. 生成 JSON manifest 和 JS 代码
4. 将生成的代码呈现给用户审查
5. 仅在用户确认后才调用 `create_js_tool`
6. 在可能的情况下测试新创建的工具

### 用户交互流程

```
1. 用户："/create-tool" 或"我需要一个获取股票价格的工具"
2. AI 加载 create-tool 技能
3. AI 提出澄清性问题：
   "您希望使用哪个股票 API？工具应接受哪些参数？"
4. 用户提供详细信息
5. AI 生成工具代码并展示：
   "以下是我设计的工具：
    名称：fetch_stock_price
    参数：symbol（必填），market（可选）
    [展示 JS 代码]
    是否创建此工具？"
6. 用户："是"
7. AI 调用 create_js_tool(name="fetch_stock_price", ...)
8. 工具返回："工具 'fetch_stock_price' 已成功创建并注册。"
9. AI："工具已就绪。现在可以让我获取一只股票的价格试试！"
```

## 验收标准

必须通过（全部必填）：

- [ ] TEST-035-01：`create_js_tool` 已作为 Kotlin 内置工具注册到 `ToolRegistry`
- [ ] TEST-035-02：`list_user_tools` 已作为 Kotlin 内置工具注册到 `ToolRegistry`
- [ ] TEST-035-03：`update_js_tool` 已作为 Kotlin 内置工具注册到 `ToolRegistry`
- [ ] TEST-035-04：`delete_js_tool` 已作为 Kotlin 内置工具注册到 `ToolRegistry`
- [ ] TEST-035-05：`create_js_tool` 验证工具名称格式（小写、字母/数字/下划线、2-50 个字符）
- [ ] TEST-035-06：`create_js_tool` 验证 parameters_schema 是具有正确结构的有效 JSON
- [ ] TEST-035-07：`create_js_tool` 将 .json manifest 和 .js 源文件写入用户工具目录
- [ ] TEST-035-08：`create_js_tool` 立即在 ToolRegistry 中注册新工具（无需重启即可使用）
- [ ] TEST-035-09：创建的工具在应用重启后仍然存在（由 JsToolLoader 在下次启动时加载）
- [ ] TEST-035-10：`create_js_tool` 拒绝重复的工具名称（已在注册表中的工具）
- [ ] TEST-035-11：`list_user_tools` 返回所有用户创建的工具及其名称和描述
- [ ] TEST-035-12：`update_js_tool` 仅更新指定字段，保留其他字段
- [ ] TEST-035-13：`update_js_tool` 以更新后的定义重新注册工具
- [ ] TEST-035-14：`update_js_tool` 拒绝更新内置工具
- [ ] TEST-035-15：`delete_js_tool` 从 ToolRegistry 中移除工具并删除文件
- [ ] TEST-035-16：`delete_js_tool` 拒绝删除内置工具
- [ ] TEST-035-17：`create-tool` 技能已加载并在技能注册表中可用
- [ ] TEST-035-18：所有 Layer 1A 测试通过

可选（锦上添花）：

- [ ] `create_js_tool` 在保存前执行基本的 JS 语法验证
- [ ] `create_js_tool` 在注册前进行演习执行以验证工具可用性
- [ ] 支持创建工具组（来自单个 JS 文件的多个工具，RFC-018 格式）

## UI/UX 需求

本功能无新增 UI。工具和技能集成到现有系统中：
- 工具名称显示在工具管理界面（FEAT-017）
- 工具调用结果显示在聊天视图中（FEAT-001）
- `/create-tool` 技能出现在技能列表中供用户调用
- 创建的工具与其他用户工具一同显示在工具管理界面

## 功能边界

### 包含内容

- 四个 Kotlin 内置工具：`CreateJsToolTool`、`ListUserToolsTool`、`UpdateJsToolTool`、`DeleteJsToolTool`
- 一个内置技能：`create-tool`（assets 中的 SKILL.md）
- 将 .json manifest 和 .js 源文件写入用户工具目录
- 在 ToolRegistry 中立即进行内存注册
- 通过现有 JsToolLoader 实现跨应用重启的持久化
- 工具名称验证和重复检测
- 防止修改/删除内置工具的保护机制

### 不包含内容（V1）

- JS 工具的可视化代码编辑器
- 工具测试/调试 UI
- 设备间的工具共享或导出/导入
- 工具组创建（每个 JS 文件包含多个工具）
- 工具版本控制或回滚
- 工具市场或社区工具
- 基于用户行为的自动工具发现

## 业务规则

1. 工具名称必须匹配 `^[a-z][a-z0-9_]{0,48}[a-z0-9]$`（小写，2-50 个字符，以字母开头）
2. 工具名称不得与已注册的工具冲突（内置工具或用户工具）
3. 只有用户创建的工具可以被更新或删除（内置工具受保护）
4. `parameters_schema` 必须是包含 `properties` 对象的有效 JSON
5. `js_code` 必须定义一个 `execute(params)` 函数（或其异步变体）
6. 创建的工具保存到应用的内部工具目录
7. 创建的工具与手动创建的 JS 工具拥有相同的桥接 API 访问权限
8. 技能提示词必须指示 AI 在创建前向用户展示代码供审查

## 非功能性需求

### 性能

- `create_js_tool`：< 500ms（文件写入 + 注册表操作）
- `list_user_tools`：< 100ms（内存注册表查询）
- `update_js_tool`：< 500ms（文件写入 + 重新注册）
- `delete_js_tool`：< 200ms（文件删除 + 注销）

### 安全性

- 工具名称经过验证，防止路径穿越攻击（不含 `/`、`..` 等）
- 创建的 JS 代码在与其他 JS 工具相同的 QuickJS 沙箱中运行（内存/超时限制）
- 桥接 API（fs、fetch）有自己的安全限制（受限路径、响应大小限制）
- 技能指示 AI 在保存前向用户展示代码供审查
- 内置工具无法通过这些工具被覆盖、更新或删除

### 兼容性

- 使用现有 JS 工具基础设施（JsTool、JsExecutionEngine、QuickJS）
- 创建的工具与现有工具管理功能兼容（FEAT-017）
- 与工具启用/禁用切换兼容（ToolEnabledStateStore）

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**：Tool 接口、注册表、执行引擎
- **FEAT-014（Agent 技能）**：create-tool 技能的技能系统
- **RFC-004（工具系统）**：JsTool、JsExecutionEngine、JsToolLoader 基础设施

### 被依赖于

- 目前没有其他功能依赖 FEAT-035

### 外部依赖

- 无新增外部依赖。使用现有的 QuickJS 引擎和 JS 工具基础设施。

## 错误处理

### 错误场景

1. **工具名称无效**
   - 原因：名称不符合要求的格式
   - 处理：返回 `ToolResult.error("validation_error", "Invalid tool name: ...")`

2. **工具名称重复**
   - 原因：注册表中已存在同名工具
   - 处理：返回 `ToolResult.error("duplicate_name", "Tool 'X' already exists")`

3. **参数 schema 无效**
   - 原因：parameters_schema 字符串不是有效 JSON 或缺少必要结构
   - 处理：返回 `ToolResult.error("validation_error", "Invalid parameters schema: ...")`

4. **工具未找到（更新/删除）**
   - 原因：指定的工具名称不存在
   - 处理：返回 `ToolResult.error("not_found", "Tool 'X' not found")`

5. **受保护的工具（更新/删除）**
   - 原因：尝试修改或删除内置工具
   - 处理：返回 `ToolResult.error("protected_tool", "Cannot modify built-in tool 'X'")`

6. **文件写入失败**
   - 原因：磁盘空间不足或保存工具文件时发生 I/O 错误
   - 处理：返回 `ToolResult.error("io_error", "Failed to save tool files: ...")`

## 测试要点

### 功能测试

- 验证 `create_js_tool` 在正确目录创建 .json 和 .js 文件
- 验证 `create_js_tool` 以正确定义在 ToolRegistry 中注册工具
- 验证创建的工具可通过 ToolExecutionEngine 执行
- 验证创建的工具可访问桥接 API（fetch、fs、console、_time）
- 验证 `create_js_tool` 拒绝无效名称、重复名称、无效 schema
- 验证 `list_user_tools` 仅返回用户工具，而非内置工具
- 验证 `update_js_tool` 更新指定字段并保留其他字段
- 验证 `update_js_tool` 重新注册工具（旧定义被替换）
- 验证 `delete_js_tool` 从注册表中移除工具并删除文件
- 验证 `delete_js_tool` 无法删除内置工具
- 验证 `create-tool` 技能正确加载并提供创建指引

### 边界情况

- 使用最少参数（仅必填字段）创建工具
- 使用所有可选字段创建工具
- 创建使用异步 execute 函数的工具
- 创建需要用户批准权限的工具
- 仅更改一个字段的工具更新
- 删除最后一个用户工具（注册表应仍可正常工作）
- 创建名称为 2 个字符（最小值）的工具
- 创建名称为 50 个字符（最大值）的工具
- 创建 JS 代码为空的工具（应验证失败）
- 创建 JS 代码存在语法错误的工具
- 创建两个名称相似的工具（如 "my_tool" 和 "my_tool2"）
- 在没有用户工具时列出工具

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
