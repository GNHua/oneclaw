# 搜索历史工具

## 功能信息
- **功能 ID**: FEAT-032
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft
- **优先级**: P1 (Should Have)
- **负责人**: TBD
- **关联 RFC**: RFC-032 (待定)

## 用户故事

**身为** 使用 OneClaw 的 AI 代理，
**我希望** 拥有一个能搜索过往对话历史、记忆文件和每日日志的工具，
**以便** 找到用户此前提及的信息——例如餐厅名称、曾去过的地点、讨论过的代码片段，或历史交互中的任何其他细节。

### 典型场景

1. 用户问"我上周提到的那家餐厅叫什么来着？"——代理搜索每日日志和消息历史以找到餐厅名称。
2. 用户问"我们关于数据库迁移讨论了什么？"——代理搜索会话标题、消息内容和记忆以找到相关上下文。
3. 用户问"上周二我去了哪里？"——代理在指定日期范围内搜索每日日志。
4. 用户问"帮我找那段解析 JSON 的代码片段"——代理搜索消息内容中涉及 JSON 解析的代码块。
5. 用户问"我配置了哪些 API 密钥？"——代理搜索记忆（MEMORY.md）中存储的配置备注。
6. 用户问"总结一下我们昨天聊了什么"——代理搜索所有数据源中前一天的交互记录。

## 功能描述

### 概述

FEAT-032 新增了一个 Kotlin 内置工具 `search_history`，可跨三个数据源搜索，以查找过往交互中的信息：

1. **记忆索引** — MEMORY.md 内容及存储在 `memory_index` 表中的每日日志片段，通过现有的 `HybridSearchEngine`（BM25 + 向量相似度 + 时间衰减）进行搜索。
2. **消息内容** — 存储在 `messages` 表中的原始消息文本，通过 SQL `LIKE` 查询进行搜索。
3. **会话元数据** — 存储在 `sessions` 表中的会话标题和最后一条消息预览，通过 SQL `LIKE` 查询进行搜索。

各数据源的结果分别独立评分，经过归一化处理后，以可配置的权重合并，并以排序列表的形式返回。该工具支持按范围（搜索哪些数据源）、日期区间和最大结果数量进行过滤。

### 架构概览

```
AI Model
    | tool call: search_history(query="restaurant", scope="all")
    v
 ToolExecutionEngine  (Kotlin, unchanged)
    |
    v
 ToolRegistry
    |
    v
 SearchHistoryTool  [NEW - Kotlin built-in tool]
    |
    v
 SearchHistoryUseCase  [NEW - business logic]
    |
    +-- HybridSearchEngine  (existing, unchanged)
    |       |
    |       +-- memory_index table (BM25 + vector + time decay)
    |
    +-- MessageDao.searchContent()  [NEW query]
    |       |
    |       +-- messages table (SQL LIKE)
    |
    +-- SessionDao.searchByTitleOrPreview()  [NEW query]
            |
            +-- sessions table (SQL LIKE)
    |
    v
 Result merging & ranking
    |
    v
 Formatted text output to AI model
```

### 工具定义

| 字段 | 值 |
|-------|-------|
| 名称 | `search_history` |
| 描述 | 搜索过往对话历史、记忆和每日日志，以查找用户此前提及的信息 |
| 参数 | `query` (string, required): 搜索关键词或短语 |
| | `scope` (string, optional): 要搜索的数据源。取值之一："all"（默认）、"memory"、"daily_log"、"sessions"。 |
| | `date_from` (string, optional): 起始日期过滤，格式为 YYYY-MM-DD |
| | `date_to` (string, optional): 结束日期过滤，格式为 YYYY-MM-DD |
| | `max_results` (integer, optional): 返回结果的最大数量，默认值：10 |
| 所需权限 | 无 |
| 超时时间 | 30 秒 |
| 返回值 | 包含来源、日期、分数和文本摘录的排序匹配结果列表 |

### scope 参数

| scope 值 | 搜索的数据源 |
|-------------|----------------------|
| `all` | 记忆索引 + 消息 + 会话 |
| `memory` | 仅记忆索引（MEMORY.md + 每日日志片段） |
| `daily_log` | 仅筛选 `source_type = "daily_log"` 的记忆索引 |
| `sessions` | 会话元数据（标题 + 预览）+ 消息内容 |

### 输出格式

工具返回结构化文本输出：

```
[Search Results for "restaurant" (scope: all, 3 results)]

--- Result 1 (score: 0.87, source: daily_log, date: 2026-02-25) ---
Discussed dinner plans. User mentioned wanting to try "Sakura Sushi" in Shibuya.
The user said they had been recommended this restaurant by a friend.

--- Result 2 (score: 0.64, source: message, date: 2026-02-25, session: "Dinner Planning") ---
User: Can you help me find a good sushi restaurant near Shibuya station?

--- Result 3 (score: 0.52, source: session, date: 2026-02-20) ---
Session: "Restaurant Recommendations" (12 messages, last active: 2026-02-20)
Preview: Looking for Italian restaurants in Roppongi...
```

无结果时：

```
[Search Results for "quantum physics" (scope: all, 0 results)]

No matching results found. Try broader keywords or a different scope.
```

### 用户交互流程

```
1. User: "What was that restaurant I mentioned last week?"
2. AI calls search_history(query="restaurant", date_from="2026-02-22", date_to="2026-02-28")
3. SearchHistoryTool:
   a. Delegates to SearchHistoryUseCase
   b. UseCase runs parallel searches across memory index, messages, sessions
   c. Each source returns independently scored results
   d. UseCase normalizes scores, applies source weights, merges, deduplicates
   e. Returns top-K results formatted as text
4. AI receives the search results, extracts the restaurant name, and tells the user
5. Chat shows the search_history tool call result
```

### 结果合并策略

各数据源的结果分别独立评分后再合并：

1. **逐数据源评分**：每个数据源按自身的评分范围生成带分数的结果
2. **归一化**：各数据源的分数归一化到 [0, 1] 区间（除以该数据源中的最高分）
3. **数据源权重**：归一化后的分数乘以数据源权重：
   - 记忆索引：1.0（最高——经过整理、汇总的内容）
   - 消息：0.6（原始对话文本，可能含有噪音）
   - 会话：0.5（仅标题/预览元数据）
4. **时间衰减**：所有分数乘以时间衰减因子（指数衰减，半衰期约 69 天）
5. **去重**：文本重叠度 > 80% 的结果进行去重（保留分数最高的）
6. **最终排序**：按最终分数降序排列，取前 `max_results` 条

## 验收标准

必须通过（全部必需）：

- [ ] `search_history` 工具在 `ToolRegistry` 中注册为 Kotlin 内置工具
- [ ] 工具接受 `query` 字符串参数并返回匹配结果
- [ ] `scope` 参数过滤要搜索的数据源（默认："all"）
- [ ] `date_from` 和 `date_to` 参数按日期区间过滤结果
- [ ] `max_results` 参数控制返回结果的数量（默认：10）
- [ ] 记忆索引搜索使用现有的 `HybridSearchEngine`
- [ ] 消息内容搜索通过 `MessageDao.searchContent()` 使用 SQL LIKE 查询
- [ ] 会话元数据搜索通过 `SessionDao.searchByTitleOrPreview()` 使用 SQL LIKE 查询
- [ ] 所有数据源的结果经过正确的归一化、加权和合并
- [ ] 输出格式化为带数据源归属的结构化文本
- [ ] 空查询返回验证错误
- [ ] 无结果时返回友好的"未找到结果"提示信息
- [ ] 日期区间过滤在 YYYY-MM-DD 格式下正常工作
- [ ] 无效日期格式返回验证错误
- [ ] 所有 Layer 1A 测试通过

可选（锦上添花）：

- [ ] 在结果摘录中高亮显示关键词匹配
- [ ] 按日期对结果分组

## UI/UX 要求

本功能无新增 UI。工具以透明方式运行：
- 在聊天中与其他工具使用相同的工具调用显示方式
- 输出显示在工具结果区域
- V1 不需要额外的设置页面

## 功能边界

### 包含范围

- Kotlin `SearchHistoryTool` 实现
- 用于业务逻辑编排的 `SearchHistoryUseCase`
- 用于合并结果的 `UnifiedSearchResult` 数据模型
- 新增 `MessageDao.searchContent()` LIKE 查询
- 新增 `SessionDao.searchByTitleOrPreview()` LIKE 查询
- 结果归一化、加权和合并
- 日期区间过滤
- 范围过滤
- 在 `ToolModule` 中注册

### 不包含范围（V1）

- 消息或会话的全文搜索（FTS）索引
- 正则表达式或高级查询语法
- 搜索结果缓存
- 搜索历史追踪（已搜索内容的记录）
- 在聊天外浏览搜索结果的 UI
- 对消息内容的语义搜索（仅记忆索引使用向量搜索）
- 跨会话上下文关联
- 导出或分享搜索结果

## 业务规则

1. `query` 参数必须非空且非空白字符串
2. 默认 `scope` 为 "all"，搜索所有三个数据源
3. 默认 `max_results` 为 10；允许的最大值为 50
4. 若 `max_results` 超过 50，则截断为 50
5. 日期参数必须为 YYYY-MM-DD 格式；无效格式返回验证错误
6. 若仅提供 `date_from`，则从该日期搜索至今
7. 若仅提供 `date_to`，则从最早时间搜索至该日期
8. 记忆索引搜索复用现有的 `HybridSearchEngine`（BM25 + 向量 + 时间衰减）
9. 消息和会话搜索使用 SQL `LIKE '%query%'`（大小写不敏感）
10. 结果文本摘录最多截断至 500 个字符
11. 不变更数据库 schema——仅在现有表上新增查询

## 非功能性要求

### 性能

- 记忆索引搜索：取决于 `HybridSearchEngine` 性能（通常 < 500ms）
- 消息 LIKE 搜索：在消息数量 < 100K 的数据库中 < 1s
- 会话 LIKE 搜索：< 100ms（通常会话数量 < 1000）
- 总搜索时间："all" 范围下 < 2s

### 内存

- 内存中持有的搜索结果数量以 `max_results` 为上限（最多 50 条）
- 不对搜索结果进行持久化缓存
- 结果中的大段消息内容被截断（每条摘录最多 500 字符）

### 兼容性

- 支持所有受支持的 Android 版本（API 26+）
- 无新增外部依赖
- 不需要数据库迁移

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**：工具接口、注册表、执行引擎
- **FEAT-013（代理记忆系统）**：`HybridSearchEngine`、`MemoryIndexDao`、记忆索引数据

### 被依赖于

- 暂无

### 外部依赖

- 无（仅使用现有内部组件）

## 错误处理

### 错误场景

1. **空查询**
   - 原因：`query` 参数为空或空白字符串
   - 处理方式：返回 `ToolResult.error("validation_error", "Parameter 'query' is required and cannot be empty")`

2. **无效日期格式**
   - 原因：`date_from` 或 `date_to` 不符合 YYYY-MM-DD 格式
   - 处理方式：返回 `ToolResult.error("validation_error", "Date must be in YYYY-MM-DD format: <value>")`

3. **无效 scope**
   - 原因：`scope` 不是已识别的值之一
   - 处理方式：返回 `ToolResult.error("validation_error", "Invalid scope '<value>'. Must be one of: all, memory, daily_log, sessions")`

4. **数据库错误**
   - 原因：Room 查询失败
   - 处理方式：返回 `ToolResult.error("search_error", "Search failed: <message>")`

5. **超时**
   - 原因：搜索耗时过长（例如数据库非常大）
   - 处理方式：工具级超时（30s）将终止搜索；如有可用的部分结果则返回

## 测试要点

### 功能测试

- 验证 `search_history` 执行简单查询并返回结果
- 验证 `scope=memory` 仅搜索记忆索引
- 验证 `scope=daily_log` 仅搜索每日日志条目
- 验证 `scope=sessions` 搜索会话元数据和消息内容
- 验证 `scope=all` 搜索所有三个数据源
- 验证 `date_from` 过滤掉指定日期之前的结果
- 验证 `date_to` 过滤掉指定日期之后的结果
- 验证 `date_from` 与 `date_to` 联合使用构成有效日期区间
- 验证 `max_results` 限制返回结果的数量
- 验证结果按分数降序排列
- 验证数据源权重正确应用（memory: 1.0, messages: 0.6, sessions: 0.5）
- 验证空查询返回验证错误
- 验证无效日期格式返回验证错误
- 验证无效 scope 返回验证错误
- 验证无结果时返回友好提示信息

### 边界情况

- 在所有三个数据源均有匹配的查询
- 仅在一个数据源有匹配的查询
- 包含特殊字符的查询（引号、反斜杠、SQL 注入尝试）
- 非常长的查询字符串（> 1000 个字符）
- `max_results` 设为 0 或负数（应默认为 10）
- `max_results` 设为超过 50（应截断为 50）
- `date_from` 晚于 `date_to` 的日期区间
- 未来日期的日期区间
- 空记忆索引（未索引每日日志或 MEMORY.md）
- 空消息数据库（全新安装）
- 包含工具调用 JSON 的消息内容（仍应可搜索）
- 跨数据源的重复结果（去重）

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
