# RFC-014: Agent Skill 系统

## 文档信息
- **RFC编号**: RFC-014
- **关联PRD**: FEAT-014 (Agent Skill)
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
OneClaw 目前有工具系统(RFC-004)提供原子操作(读文件、HTTP 请求等),以及 Agent 系统(RFC-002)配置带有系统提示词和工具集的 AI 角色。但缺少一个可复用的结构化工作流层 -- 即引导 AI 通过特定任务模式的多步骤 prompt 模板。

Claude Code 通过其 "Skill" 系统有效地展示了这种模式:轻量级的 prompt 模板将现有工具编排为可重复的工作流,通过 `/` 命令或 AI 自主调用触发,并在系统提示词中注册以供发现。

### 目标
1. 实现 Skill 框架:基于 Markdown 的 prompt 模板,存储在 `<skill-name>/SKILL.md` 目录中
2. 添加 `load_skill` 作为新的内置工具,按需加载技能 prompt 内容
3. 将轻量级技能注册表注入系统提示词(通过分隔符追加到 agent 系统提示词之后)
4. 支持三条触发路径:聊天输入框 `/` 命令、UI 技能按钮、AI 自主调用
5. 支持用户自定义技能:通过应用内编辑器创建、编辑、删除
6. 支持技能分享:通过 `SKILL.md` 文件导出和导入

### 非目标
- 技能市场或基于云的技能分发
- 技能链式调用(一个技能调用另一个)
- 条件逻辑或模板引擎(Handlebars、Mustache 等) -- V1 仅使用简单的 `{{param}}` 替换
- Room 数据库存储技能 -- 技能完全基于文件
- 技能版本控制或变更历史追踪
- 超出现有工具权限的技能专用权限

## 技术方案

### 整体设计

```
┌─────────────────────────────────────────────────────────────────┐
│                          UI 层                                  │
│                                                                 │
│  SkillManagementScreen    SkillEditorScreen    ChatScreen       │
│  (列表/查看/删除)         (创建/编辑)          (/ 命令 + 按钮)  │
│         │                       │                    │          │
│  SkillListViewModel     SkillEditorViewModel   ChatViewModel   │
└────────┬───────────────────────┬────────────────────┬──────────┘
         │                       │                    │
┌────────┴───────────────────────┴────────────────────┴──────────┐
│                        领域层                                    │
│                                                                 │
│  SkillRegistry          LoadSkillTool         SkillFileParser   │
│  (扫描、索引、           (Tool 接口,          (解析 YAML        │
│   查找、CRUD)            加载技能)             frontmatter)     │
│         │                       │                    │          │
│  GetAllSkillsUseCase   ExportSkillUseCase  ImportSkillUseCase  │
│  CreateSkillUseCase    DeleteSkillUseCase                      │
└────────┬───────────────────────┬────────────────────┬──────────┘
         │                       │                    │
┌────────┴───────────────────────┴────────────────────┴──────────┐
│                         数据层                                   │
│                                                                 │
│  assets/skills/              files/skills/                      │
│    summarize-file/             my-custom-skill/                 │
│      SKILL.md                    SKILL.md                      │
│    translate-file/                                              │
│      SKILL.md                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 核心组件

#### 1. SkillDefinition (领域模型)

技能元数据的内存表示,从 SKILL.md frontmatter 解析而来。

```kotlin
data class SkillDefinition(
    val name: String,                    // 唯一标识符, 如 "summarize-file"
    val displayName: String,             // 人类可读名称, 如 "Summarize File"
    val description: String,             // 一句话描述
    val version: String,                 // 版本字符串, 如 "1.0"
    val toolsRequired: List<String>,     // 此技能需要的工具名称
    val parameters: List<SkillParameter>, // 参数定义
    val isBuiltIn: Boolean,              // 是否为内置技能
    val directoryPath: String            // 技能目录的绝对路径
)

data class SkillParameter(
    val name: String,                    // 参数名, snake_case
    val type: String,                    // "string" (V1 仅此类型)
    val required: Boolean,               // 是否必填
    val description: String              // 人类可读描述
)
```

**位置**: `app/src/main/kotlin/com/oneclaw/shadow/core/model/SkillDefinition.kt`

#### 2. SkillFileParser

将 SKILL.md 文件解析为 `SkillDefinition` 元数据和原始 prompt 内容。

```kotlin
class SkillFileParser {

    data class ParseResult(
        val definition: SkillDefinition,
        val promptContent: String         // frontmatter 之后的所有内容
    )

    /**
     * 解析 SKILL.md 文件为元数据 + prompt 内容。
     * 文件无效时返回 AppResult.Error。
     */
    fun parse(
        filePath: String,
        isBuiltIn: Boolean
    ): AppResult<ParseResult>

    /**
     * 从原始内容字符串解析(用于导入验证)。
     */
    fun parseContent(
        content: String,
        isBuiltIn: Boolean,
        directoryPath: String
    ): AppResult<ParseResult>

    /**
     * 将 SkillDefinition + prompt 内容序列化回 SKILL.md 格式。
     */
    fun serialize(
        definition: SkillDefinition,
        promptContent: String
    ): String

    /**
     * 替换 prompt 内容中的参数值。
     * 将 {{param_name}} 替换为实际值。
     */
    fun substituteParameters(
        promptContent: String,
        parameterValues: Map<String, String>
    ): String
}
```

**YAML Frontmatter 解析**: 使用轻量级 YAML 解析器。由于 frontmatter 结构固定且简单(除 `parameters` 列表外无嵌套对象),可以使用 `org.yaml.snakeyaml:snakeyaml` 或为已知 schema 实现最小化解析器。

**格式**:
```markdown
---
name: summarize-file
display_name: "Summarize File"
description: "Read a local file and produce a structured summary"
version: "1.0"
tools_required:
  - read_file
parameters:
  - name: file_path
    type: string
    required: true
    description: "Absolute path to the file to summarize"
---

# 此处开始 Prompt 内容(--- 之后的所有内容)
# 这是 load_skill 工具返回的内容。

## Instructions

1. Use `read_file` to read the file at {{file_path}}
...
```

**位置**: `app/src/main/kotlin/com/oneclaw/shadow/tool/skill/SkillFileParser.kt`

#### 3. SkillRegistry

管理所有技能的生命周期和查找。初始化时扫描目录,维护内存索引。

```kotlin
class SkillRegistry(
    private val context: Context,
    private val parser: SkillFileParser
) {
    private val skills = mutableMapOf<String, SkillDefinition>()
    private val promptCache = mutableMapOf<String, String>()

    /** 初始化:扫描内置和用户自定义技能目录。应用启动时调用一次。 */
    fun initialize()

    /** 获取所有技能定义(用于系统提示词注册和 UI)。 */
    fun getAllSkills(): List<SkillDefinition>

    /** 仅获取内置技能。 */
    fun getBuiltInSkills(): List<SkillDefinition>

    /** 仅获取用户自定义技能。 */
    fun getUserSkills(): List<SkillDefinition>

    /** 按名称获取技能定义。 */
    fun getSkill(name: String): SkillDefinition?

    /** 加载技能的完整 prompt 内容(读取文件,缓存结果)。可选替换参数值。 */
    fun loadSkillContent(
        name: String,
        parameterValues: Map<String, String> = emptyMap()
    ): AppResult<String>

    /** 创建新的用户自定义技能。创建目录 + SKILL.md 文件。名称冲突时返回错误。 */
    fun createSkill(definition: SkillDefinition, promptContent: String): AppResult<SkillDefinition>

    /** 更新现有用户自定义技能。覆写 SKILL.md。内置或未找到时返回错误。 */
    fun updateSkill(name: String, definition: SkillDefinition, promptContent: String): AppResult<SkillDefinition>

    /** 删除用户自定义技能(移除目录)。内置或未找到时返回错误。 */
    fun deleteSkill(name: String): AppResult<Unit>

    /** 从 SKILL.md 内容字符串导入技能。创建新目录。名称冲突时返回错误。 */
    fun importSkill(content: String): AppResult<SkillDefinition>

    /** 将技能导出为 SKILL.md 内容字符串。 */
    fun exportSkill(name: String): AppResult<String>

    /** 检查技能名称是否存在。 */
    fun hasSkill(name: String): Boolean

    /** 生成用于系统提示词注入的技能注册表文本。 */
    fun generateRegistryPrompt(): String

    /** 刷新注册表(重新扫描目录)。在创建/更新/删除/导入操作后调用。 */
    fun refresh()
}
```

**目录扫描逻辑**:
1. 扫描 `assets/skills/` 中的内置技能(列出 asset 目录,读取每个 `SKILL.md`)
2. 扫描 `{filesDir}/skills/` 中的用户自定义技能(列出子目录,读取每个 `SKILL.md`)
3. 解析每个 SKILL.md 的 frontmatter 以构建索引
4. 解析错误时:记录警告,跳过该技能(不崩溃)

**缓存**: Prompt 内容在首次加载后缓存到内存。更新/删除时使缓存失效。

**位置**: `app/src/main/kotlin/com/oneclaw/shadow/tool/skill/SkillRegistry.kt`

#### 4. LoadSkillTool

实现 `Tool` 接口的新内置工具,使 AI 能够加载技能内容。

```kotlin
class LoadSkillTool(
    private val skillRegistry: SkillRegistry
) : Tool {

    override val definition = ToolDefinition(
        name = "load_skill",
        description = "Load the full prompt instructions for a skill. " +
            "Use this when the user requests a skill or when you recognize " +
            "that a task matches an available skill.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "name" to ToolParameter(
                    type = "string",
                    description = "The skill name to load (e.g., 'summarize-file')"
                )
            ),
            required = listOf("name")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val name = parameters["name"] as? String
            ?: return ToolResult.error(
                "validation_error",
                "Parameter 'name' is required and must be a string"
            )

        val skill = skillRegistry.getSkill(name)
            ?: return ToolResult.error(
                "skill_not_found",
                "Skill '$name' not found. Available skills: " +
                    skillRegistry.getAllSkills().joinToString(", ") { it.name }
            )

        return when (val result = skillRegistry.loadSkillContent(name)) {
            is AppResult.Success -> {
                val header = buildString {
                    appendLine("# Skill: ${skill.displayName}")
                    appendLine("Description: ${skill.description}")
                    if (skill.toolsRequired.isNotEmpty()) {
                        appendLine("Required tools: ${skill.toolsRequired.joinToString(", ")}")
                    }
                    if (skill.parameters.isNotEmpty()) {
                        appendLine("Parameters:")
                        skill.parameters.forEach { param ->
                            val req = if (param.required) "(required)" else "(optional)"
                            appendLine("  - ${param.name} $req: ${param.description}")
                        }
                    }
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
                ToolResult.success(header + result.data)
            }
            is AppResult.Error -> {
                ToolResult.error("load_error", "Failed to load skill '$name': ${result.message}")
            }
        }
    }
}
```

**位置**: `app/src/main/kotlin/com/oneclaw/shadow/tool/builtin/LoadSkillTool.kt`

#### 5. 系统提示词集成

修改 `SendMessageUseCase`,将技能注册表追加到 agent 的系统提示词中。

```kotlin
// 在 SendMessageUseCase 中构建系统提示词时:

private fun buildSystemPrompt(agent: Agent, skillRegistry: SkillRegistry): String {
    val skillRegistryPrompt = skillRegistry.generateRegistryPrompt()

    return if (skillRegistryPrompt.isBlank()) {
        agent.systemPrompt
    } else {
        """
${agent.systemPrompt}

---

$skillRegistryPrompt
        """.trimIndent()
    }
}
```

**SkillRegistry.generateRegistryPrompt()** 输出:

```
## Available Skills

The following skills are available. Use the `load_skill` tool to load full instructions when needed. You can proactively load a skill when you recognize the user's request matches one.

- summarize-file: Read a local file and produce a structured summary
- translate-file: Translate a file's content to a target language
- fetch-webpage: Fetch and summarize a webpage's content
- rewrite-text: Rewrite text in a specified style or tone
- device-info: Gather and report device information
```

**关键细节**: `load_skill` 工具必须对所有 Agent 可用,无论其工具集配置如何,因为技能是全局资源。这意味着 `load_skill` 始终包含在发送给模型的工具定义中,即使 agent 有受限的工具集。

**实现**: 在 `SendMessageUseCase` 中解析 agent 工具时:
```kotlin
val agentToolDefs = buildList {
    // 始终包含 load_skill
    toolRegistry.getTool("load_skill")?.let { add(it.definition) }
    // 添加 agent 配置的工具
    if (agent.toolIds.isNotEmpty()) {
        addAll(toolRegistry.getToolDefinitionsByNames(agent.toolIds))
    }
}
```

#### 6. 斜杠命令系统(聊天输入集成)

聊天输入框中的 `/` 命令系统检测用户输入的第一个字符是否为 `/`,并显示自动完成弹窗。

```kotlin
/**
 * 斜杠命令自动完成弹窗的状态。
 */
data class SlashCommandState(
    val isActive: Boolean = false,
    val query: String = "",                     // "/" 之后的文本
    val matchingSkills: List<SkillDefinition> = emptyList(),
    val selectedIndex: Int = -1
)

/**
 * ChatViewModel 中的斜杠命令处理逻辑。
 */
fun onInputTextChanged(text: String) {
    if (text.startsWith("/") && text.length >= 1) {
        val query = text.removePrefix("/").lowercase()
        val matches = skillRegistry.getAllSkills().filter { skill ->
            skill.name.contains(query) || skill.displayName.lowercase().contains(query)
        }
        _slashCommandState.update {
            it.copy(isActive = true, query = query, matchingSkills = matches)
        }
    } else {
        _slashCommandState.update { it.copy(isActive = false) }
    }
}
```

**技能选择流程**:
1. 用户从自动完成列表中选择技能
2. 如果技能有必填参数且无值,显示参数输入对话框
3. 构造用户消息: `"Use the ${skill.displayName} skill"` (如适用则附带参数上下文)
4. 作为普通消息发送 -- AI 在系统提示词中看到技能注册表并调用 `load_skill`

#### 7. UI 技能按钮

聊天输入区域附近的按钮,打开分类技能列表的底部弹出表。

```kotlin
/**
 * 显示按类别分组的可用技能列表的底部弹出表。
 */
@Composable
fun SkillSelectionBottomSheet(
    skills: List<SkillDefinition>,
    onSkillSelected: (SkillDefinition) -> Unit,
    onDismiss: () -> Unit
)
```

### 数据模型

不需要 Room 实体。技能完全基于文件。

#### 文件结构

```
# 内置(只读,与 APK 打包)
assets/
  skills/
    summarize-file/
      SKILL.md
    translate-file/
      SKILL.md
    fetch-webpage/
      SKILL.md
    rewrite-text/
      SKILL.md
    extract-key-points/
      SKILL.md
    device-info/
      SKILL.md
    storage-check/
      SKILL.md
    check-api/
      SKILL.md

# 用户自定义(app 内部存储,可读写)
{context.filesDir}/
  skills/
    my-custom-skill/
      SKILL.md
    daily-report/
      SKILL.md
```

#### SKILL.md 格式规范

```yaml
---
# 必填字段
name: summarize-file              # 唯一 ID, 小写 + 连字符, 2-50 字符
display_name: "Summarize File"    # 人类可读名称
description: "Read a local file and produce a structured summary"

# 可选字段
version: "1.0"                    # 默认: "1.0"
tools_required:                   # 默认: 空列表
  - read_file
parameters:                       # 默认: 空列表
  - name: file_path
    type: string                  # V1: 仅支持 "string"
    required: true
    description: "Absolute path to the file to summarize"
---

# Prompt 内容从此开始(结束 --- 之后的所有内容)
# 这是 load_skill 工具返回的内容。

## Instructions

1. Use `read_file` to read the file at {{file_path}}
2. Analyze the content...
```

**Frontmatter 验证规则**:
- `name`: 必填,必须匹配 `^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$`
- `display_name`: 必填,非空
- `description`: 必填,非空
- `version`: 可选,默认 "1.0"
- `tools_required`: 可选,每个条目必须是有效的工具名称字符串
- `parameters`: 可选,每个条目必须有 `name`、`type`、`required`、`description`
- Prompt 内容(`---` 之后): 必须非空

### API 设计

#### 内部 API (Use Cases)

```kotlin
/** 获取所有技能(用于管理界面、自动完成等) */
class GetAllSkillsUseCase(private val skillRegistry: SkillRegistry) {
    operator fun invoke(): List<SkillDefinition> = skillRegistry.getAllSkills()
}

/** 创建新的用户自定义技能 */
class CreateSkillUseCase(private val skillRegistry: SkillRegistry) {
    operator fun invoke(definition: SkillDefinition, promptContent: String): AppResult<SkillDefinition> =
        skillRegistry.createSkill(definition, promptContent)
}

/** 更新现有用户自定义技能 */
class UpdateSkillUseCase(private val skillRegistry: SkillRegistry) {
    operator fun invoke(name: String, definition: SkillDefinition, promptContent: String): AppResult<SkillDefinition> =
        skillRegistry.updateSkill(name, definition, promptContent)
}

/** 删除用户自定义技能 */
class DeleteSkillUseCase(private val skillRegistry: SkillRegistry) {
    operator fun invoke(name: String): AppResult<Unit> = skillRegistry.deleteSkill(name)
}

/** 将技能导出为 SKILL.md 内容 */
class ExportSkillUseCase(private val skillRegistry: SkillRegistry) {
    operator fun invoke(name: String): AppResult<String> = skillRegistry.exportSkill(name)
}

/** 从 SKILL.md 内容导入技能 */
class ImportSkillUseCase(private val skillRegistry: SkillRegistry) {
    operator fun invoke(content: String): AppResult<SkillDefinition> = skillRegistry.importSkill(content)
}

/** 加载技能 prompt 内容 */
class LoadSkillContentUseCase(private val skillRegistry: SkillRegistry) {
    operator fun invoke(name: String): AppResult<String> = skillRegistry.loadSkillContent(name)
}
```

### UI 层设计

#### 页面/Screen 定义

##### 1. SkillManagementScreen
- **路由**: `skills`
- **ViewModel**: `SkillListViewModel`
- **状态**: `SkillListUiState`
- **区域**: 内置技能(只读)、用户自定义技能(可操作)
- **操作**: 创建(FAB)、点击查看/编辑、滑动删除/导出

##### 2. SkillEditorScreen
- **路由**: `skills/create` 或 `skills/edit/{skillName}`
- **ViewModel**: `SkillEditorViewModel`
- **状态**: `SkillEditorUiState`
- **模式**: 创建(空表单)或编辑(预填充)
- **字段**: name, display_name, description, version, parameters, tools_required, prompt content
- **内置技能**: 只读模式,带克隆和导出按钮

##### 3. SlashCommandPopup (在 ChatScreen 中)
- **组件**: `SlashCommandPopup` composable
- **状态**: 由 `ChatViewModel.slashCommandState` 管理
- **触发**: 用户输入 `/` 作为第一个字符
- **显示**: 输入框上方的浮动弹窗,显示过滤后的技能列表

##### 4. SkillSelectionBottomSheet (在 ChatScreen 中)
- **组件**: `SkillSelectionBottomSheet` composable
- **触发**: 用户点击输入区域附近的技能按钮
- **显示**: 底部弹出表,显示分类的技能列表

##### 5. ParameterInputDialog
- **组件**: `SkillParameterDialog` composable
- **触发**: 选择有必填参数的技能时
- **显示**: 对话框,每个必填参数一个文本输入框

#### 状态管理

```kotlin
// 技能列表界面
data class SkillListUiState(
    val builtInSkills: List<SkillDefinition> = emptyList(),
    val userSkills: List<SkillDefinition> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class SkillListViewModel(
    private val getAllSkillsUseCase: GetAllSkillsUseCase,
    private val deleteSkillUseCase: DeleteSkillUseCase,
    private val exportSkillUseCase: ExportSkillUseCase,
    private val importSkillUseCase: ImportSkillUseCase
) : ViewModel() {
    private val _uiState = MutableStateFlow(SkillListUiState())
    val uiState: StateFlow<SkillListUiState> = _uiState.asStateFlow()

    fun loadSkills() { /* 扫描并分类 */ }
    fun deleteSkill(name: String) { /* 删除 + 刷新 */ }
    fun exportSkill(name: String) { /* 导出到分享 intent */ }
    fun importSkill(content: String) { /* 导入 + 刷新 */ }
}

// 技能编辑器界面
data class SkillEditorUiState(
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val version: String = "1.0",
    val parameters: List<SkillParameter> = emptyList(),
    val toolsRequired: List<String> = emptyList(),
    val promptContent: String = "",
    val isBuiltIn: Boolean = false,
    val isEditMode: Boolean = false,
    val isSaving: Boolean = false,
    val validationErrors: Map<String, String> = emptyMap(),
    val saveResult: AppResult<Unit>? = null
)

// 斜杠命令状态(在 ChatViewModel 中)
data class SlashCommandState(
    val isActive: Boolean = false,
    val query: String = "",
    val matchingSkills: List<SkillDefinition> = emptyList()
)
```

### 技术选型

| 技术/库 | 用途 | 选择原因 |
|---------|------|----------|
| SnakeYAML (`org.yaml.snakeyaml`) | 解析 YAML frontmatter | 轻量、经过充分测试、纯 Java |
| Kotlin `File` API | 读写 SKILL.md 文件 | 标准库,无额外依赖 |
| Android `AssetManager` | 读取 assets 中的内置技能 | 标准 Android API |
| Jetpack Compose | UI 界面 | 与现有应用一致 |

### 目录结构

```
app/src/main/
├── assets/
│   └── skills/                          # 内置技能
│       ├── summarize-file/
│       │   └── SKILL.md
│       ├── translate-file/
│       │   └── SKILL.md
│       └── ...
│
├── kotlin/com/oneclaw/shadow/
│   ├── core/model/
│   │   └── SkillDefinition.kt          # SkillDefinition, SkillParameter
│   │
│   ├── tool/
│   │   ├── skill/
│   │   │   ├── SkillFileParser.kt      # 解析/序列化 SKILL.md
│   │   │   └── SkillRegistry.kt        # 技能生命周期管理
│   │   └── builtin/
│   │       └── LoadSkillTool.kt        # load_skill Tool 实现
│   │
│   ├── feature/skill/
│   │   ├── SkillListViewModel.kt
│   │   ├── SkillListUiState.kt
│   │   ├── SkillEditorViewModel.kt
│   │   ├── SkillEditorUiState.kt
│   │   └── usecase/
│   │       ├── GetAllSkillsUseCase.kt
│   │       ├── CreateSkillUseCase.kt
│   │       ├── UpdateSkillUseCase.kt
│   │       ├── DeleteSkillUseCase.kt
│   │       ├── ExportSkillUseCase.kt
│   │       ├── ImportSkillUseCase.kt
│   │       └── LoadSkillContentUseCase.kt
│   │
│   ├── ui/features/skill/
│   │   ├── SkillManagementScreen.kt    # 列表 + 操作
│   │   ├── SkillEditorScreen.kt        # 创建/编辑表单
│   │   ├── SlashCommandPopup.kt        # / 命令自动完成
│   │   ├── SkillSelectionBottomSheet.kt # 技能按钮弹窗
│   │   └── SkillParameterDialog.kt     # 参数输入对话框
│   │
│   └── di/
│       └── SkillModule.kt              # Koin DI 模块
```

### Koin DI 模块

```kotlin
val skillModule = module {
    // 核心
    single { SkillFileParser() }
    single { SkillRegistry(androidContext(), get()).apply { initialize() } }

    // Tool 注册: 将 LoadSkillTool 添加到现有 ToolRegistry
    single { LoadSkillTool(get()) }

    // Use cases
    factory { GetAllSkillsUseCase(get()) }
    factory { CreateSkillUseCase(get()) }
    factory { UpdateSkillUseCase(get()) }
    factory { DeleteSkillUseCase(get()) }
    factory { ExportSkillUseCase(get()) }
    factory { ImportSkillUseCase(get()) }
    factory { LoadSkillContentUseCase(get()) }

    // ViewModels
    viewModel { SkillListViewModel(get(), get(), get(), get()) }
    viewModel { params -> SkillEditorViewModel(get(), get(), get(), get(), get()) }
}
```

**ToolModule 集成**: 在现有 `ToolModule` 中注册 `LoadSkillTool`:

```kotlin
val toolModule = module {
    single {
        ToolRegistry().apply {
            register(GetCurrentTimeTool())
            register(ReadFileTool())
            register(WriteFileTool())
            register(HttpRequestTool(get()))
            register(LoadSkillTool(get()))  // 新增: 添加 load_skill 工具
        }
    }
    // ... 其余不变
}
```

## 实现步骤

### Phase 1: 核心基础设施
1. [ ] 添加 SnakeYAML 依赖(或实现最小化 YAML 解析器)
2. [ ] 创建 `SkillDefinition` 和 `SkillParameter` 领域模型
3. [ ] 实现 `SkillFileParser`(解析、序列化、参数替换)
4. [ ] 实现 `SkillRegistry`(目录扫描、索引、CRUD)
5. [ ] 编写解析器和注册表的单元测试

### Phase 2: 工具集成
6. [ ] 实现 `LoadSkillTool`(Tool 接口实现)
7. [ ] 在 `ToolModule` 中注册 `LoadSkillTool`
8. [ ] 修改 `SendMessageUseCase` 将技能注册表注入系统提示词
9. [ ] 确保 `load_skill` 始终包含在发送给模型的工具定义中
10. [ ] 编写 LoadSkillTool 和系统提示词集成的单元测试

### Phase 3: 内置技能
11. [ ] 编写内置技能 SKILL.md 文件(4 个类别共 8 个技能)
12. [ ] 将技能文件添加到 `assets/skills/` 目录
13. [ ] 验证技能从 assets 正确加载

### Phase 4: 技能管理 UI
14. [ ] 实现 `SkillListViewModel` 和 `SkillListUiState`
15. [ ] 实现 `SkillManagementScreen`(列表、查看、删除)
16. [ ] 实现 `SkillEditorViewModel` 和 `SkillEditorUiState`
17. [ ] 实现 `SkillEditorScreen`(创建、编辑、内置只读)
18. [ ] 添加技能界面的导航路由
19. [ ] 在设置或导航中添加"技能"入口

### Phase 5: 聊天集成
20. [ ] 实现 `SlashCommandPopup` composable
21. [ ] 在 `ChatViewModel` 中添加 `/` 检测逻辑
22. [ ] 实现 `SkillSelectionBottomSheet` composable
23. [ ] 在聊天输入区域添加技能按钮
24. [ ] 实现 `SkillParameterDialog` 用于必填参数
25. [ ] 将技能选择连接到发送相应消息/工具调用

### Phase 6: 导入/导出
26. [ ] 实现导出流程(技能 -> SKILL.md -> Android 分享 intent)
27. [ ] 实现导入流程(文件选择器 -> 验证 -> 保存)
28. [ ] 添加 `.md` 文件的 Android intent filter(带验证)
29. [ ] 处理导入时的名称冲突(重命名/替换对话框)

### Phase 7: 测试
30. [ ] Layer 1A: 所有新组件的 JVM 单元测试
31. [ ] Layer 1B: instrumented 测试(如适用)
32. [ ] Layer 1C: 新界面的 Roborazzi 截图测试
33. [ ] Layer 2: 技能流程的 adb 视觉验证
34. [ ] 编写测试报告

## 数据流

### 技能加载流程(AI 调用 load_skill)

```
1. AI 接收用户消息 + 系统提示词(含技能注册表)
2. AI 决定使用技能 → 生成工具调用: load_skill(name="summarize-file")
3. ToolExecutionEngine 接收工具调用
4. 调用 LoadSkillTool.execute({"name": "summarize-file"})
5. LoadSkillTool → SkillRegistry.loadSkillContent("summarize-file")
6. SkillRegistry → 从 assets/skills/summarize-file/SKILL.md 读取
7. SkillRegistry → 返回 prompt 内容(如提供参数值则进行替换)
8. LoadSkillTool → 返回 ToolResult.success(header + prompt content)
9. ToolExecutionEngine → 将结果返回给 SendMessageUseCase
10. SendMessageUseCase → 将工具结果发送回模型
11. AI 读取加载的技能指令 → 按工作流执行
12. AI 可能调用其他工具(read_file, http_request 等),按技能指令操作
```

### 斜杠命令流程(用户通过 / 触发)

```
1. 用户在聊天输入框中输入"/"
2. ChatViewModel 检测到"/"前缀 → 激活 SlashCommandState
3. UI 显示 SlashCommandPopup 和过滤后的技能列表
4. 用户选择"summarize-file"
5. 技能有必填参数"file_path" → 显示 SkillParameterDialog
6. 用户输入"/storage/emulated/0/notes.txt"
7. 系统构造消息:"Use the summarize-file skill on /storage/emulated/0/notes.txt"
8. 作为普通用户消息发送
9. AI 在系统提示词中看到技能注册表 → 调用 load_skill("summarize-file")
10. 流程继续,同"技能加载流程"
```

### 技能创建流程

```
1. 用户导航到技能管理界面 → 点击"创建"
2. 显示空表单的 SkillEditorScreen
3. 用户填写 name, display_name, description, prompt content
4. 用户点击"保存"
5. SkillEditorViewModel 验证所有字段
6. CreateSkillUseCase → SkillRegistry.createSkill()
7. SkillRegistry 创建目录: {filesDir}/skills/{name}/
8. SkillRegistry 写入 SKILL.md(frontmatter + content)
9. SkillRegistry 刷新索引
10. 用户返回技能列表,新技能可见
```

## 错误处理

### 错误分类

| 错误类型 | 来源 | 处理方式 |
|----------|------|----------|
| `skill_not_found` | LoadSkillTool | 返回错误并附带可用技能列表 |
| `parse_error` | SkillFileParser | 扫描时跳过;导入时显示错误 |
| `validation_error` | SkillEditorViewModel | 在编辑器 UI 中显示字段级错误 |
| `name_conflict` | SkillRegistry.createSkill/importSkill | 返回错误,调用方显示重命名/替换对话框 |
| `permission_error` | 文件 I/O | 返回 AppResult.Error 并附带消息 |
| `file_not_found` | SkillRegistry.loadSkillContent | 返回错误(索引过期,触发刷新) |
| `built_in_modification` | SkillRegistry.updateSkill/deleteSkill | 返回错误: "Cannot modify built-in skill" |

### 错误处理策略

```kotlin
// 技能扫描错误:记录并跳过,不崩溃
fun initialize() {
    scanDirectory("assets/skills/", isBuiltIn = true).forEach { path ->
        when (val result = parser.parse(path, isBuiltIn = true)) {
            is AppResult.Success -> skills[result.data.definition.name] = result.data.definition
            is AppResult.Error -> Log.w(TAG, "Skipping invalid skill at $path: ${result.message}")
        }
    }
}

// 导入错误:呈现给用户
fun importSkill(content: String): AppResult<SkillDefinition> {
    val parseResult = parser.parseContent(content, isBuiltIn = false, directoryPath = "")
    if (parseResult is AppResult.Error) {
        return AppResult.Error(ErrorCode.VALIDATION_ERROR, parseResult.message)
    }
    // ... 检查名称冲突,创建目录,写入文件
}
```

## 性能考虑

### 启动性能
- **技能扫描**: 初始化时仅解析 frontmatter(不加载完整 prompt 内容)
- **内置技能**: ~8 个技能,每个 frontmatter < 1KB → 总扫描 < 20ms
- **用户技能**: 扫描时间随技能数量线性增长。对于 < 100 个技能,应保持 < 50ms
- **Prompt 内容**: 延迟加载,仅在调用 `load_skill` 时加载。首次加载后缓存。

### 内存使用
- `SkillDefinition` 对象: 每个约 200 字节。100 个技能 = ~20KB(可忽略)
- Prompt 内容缓存: 每个技能平均约 5KB。缓存所有已加载技能 = ~50-100KB(可接受)
- 缓存失效: 技能更新/删除时从缓存移除

### 系统提示词开销
- 系统提示词中的技能注册表: 每个技能约 1 行(名称 + 描述)
- 8 个内置 + 20 个用户技能 = ~28 行, ~1.5KB 文本
- 在上下文窗口中的开销可接受

## 安全性考虑

### 导入技能安全
- 技能内容是纯文本 prompt 指令 -- 不能直接执行代码
- 技能只能引导 AI 使用现有工具,这些工具有自己的权限检查
- `load_skill` 工具仅从指定技能目录读取(不能读取任意路径)
- 最大技能文件大小: 100KB(防止超大文件滥用)

### 文件系统安全
- 内置技能: 只读(Android assets)
- 用户技能: 存储在 app 内部存储(`context.filesDir`),其他 app 不可访问
- `LoadSkillTool` 验证请求的技能名称存在于注册表中(无路径遍历)

### 导入验证
- Frontmatter 必须成功解析
- 名称必须匹配正则模式(无特殊字符,无路径分隔符)
- 内容不得超过 100KB
- 不需要可执行内容验证(技能是 prompt,不是代码)

## 测试策略

### 单元测试 (Layer 1A)
- `SkillFileParser`: 解析有效文件、拒绝无效文件、参数替换、序列化往返
- `SkillRegistry`: 使用模拟文件系统初始化、CRUD 操作、名称冲突、刷新
- `LoadSkillTool`: 成功场景、技能未找到、加载错误
- `generateRegistryPrompt()`: 正确格式、空注册表
- Use cases: 委托给注册表、错误传播
- ViewModels: 状态更新、验证逻辑

### Instrumented 测试 (Layer 1B)
- SkillRegistry 使用真实文件系统(创建/读取/删除目录)
- 内置技能的 Asset 读取

### 截图测试 (Layer 1C)
- SkillManagementScreen: 空状态、有技能、内置 vs 自定义
- SkillEditorScreen: 创建模式、编辑模式、只读模式
- SlashCommandPopup: 有匹配结果、过滤中
- SkillSelectionBottomSheet: 分类列表

### 视觉验证 (Layer 2)
- 通过编辑器创建自定义技能
- 通过 / 命令触发技能
- 通过 UI 按钮触发技能
- AI 自主调用技能
- 导出和导入技能
- 删除自定义技能

## 依赖关系

### 依赖的 RFC
- **RFC-004 (工具系统)**: `LoadSkillTool` 实现 `Tool` 接口,在 `ToolRegistry` 中注册
- **RFC-001 (聊天交互)**: 系统提示词修改, 聊天输入框中的 `/` 命令
- **RFC-002 (Agent 管理)**: Agent 系统提示词是技能注册表注入的基础

### 被依赖的 RFC
- 目前无

### 外部依赖
- `org.yaml.snakeyaml:snakeyaml:2.2`(或最小化自定义解析器)用于 YAML frontmatter 解析

## 风险和缓解

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|----------|
| YAML 解析器增加显著 APK 大小 | 低 | 低 | SnakeYAML 约 300KB;或使用最小化自定义解析器 |
| 大量用户技能拖慢启动 | 中 | 低 | 延迟加载,扫描时仅解析 frontmatter |
| 技能注册表膨胀系统提示词 | 中 | 中 | 限制注册表最多约 50 个技能;超出则截断 |
| AI 忽略技能指令 | 中 | 中 | 优化技能 prompt 措辞;在所有 3 个 provider 上测试 |
| 导入恶意技能内容 | 低 | 低 | 技能仅是 prompt;现有工具权限适用 |

## 替代方案

### 方案 A: Skill 作为 Room 实体
- **方式**: 将所有技能数据存在 Room 数据库中,仅导出时生成 SKILL.md
- **优点**: 查询快、结构化数据、熟悉的模式
- **缺点**: 偏离 Claude Code 的文件模式,高级用户难以直接编辑
- **未选择原因**: PRD 指定了基于文件的 SKILL.md 目录约定

### 方案 B: Skill 作为特殊 Agent 类型
- **方式**: 技能就是带特殊标记的 Agent,复用 Agent CRUD 基础设施
- **优点**: 新代码少,复用 Agent UI
- **缺点**: Agent 模型不适合(Agent 有自己的完整系统提示词,Skill 是 prompt 片段);混淆两个不同概念
- **未选择原因**: 技能和 Agent 服务于根本不同的目的

### 方案 C: 无 load_skill 工具,直接注入
- **方式**: 触发技能时,直接将完整 prompt 作为系统消息注入对话,无需 AI 调用工具
- **优点**: 更简单,不需要新工具,保证 prompt 注入
- **缺点**: AI 不能自主调用技能;仅支持用户触发;失去"AI 识别并加载"的能力
- **未选择原因**: PRD 要求 AI 自主调用能力(路径 3)

## 未来扩展

- **Frontmatter 中的技能分类**: 添加 `category` 字段以更好地组织
- **技能链式调用**: `load_skill` 可接受 `chain` 参数以按序加载多个技能
- **参数化工具需求**: 技能可条件性声明工具需求
- **技能执行追踪**: 计数和记录技能调用时间戳用于分析
- **技能目录资源**: 支持技能目录中的额外文件(示例、多语言变体)
- **云同步**: 通过 Google Drive 备份同步 `files/skills/` 目录

## 开放问题

- [x] YAML 解析器选择: SnakeYAML vs 自定义最小化解析器 -- 推荐 SnakeYAML 除非 APK 大小是关键
- [x] 系统提示词注入方式 -- 已决定: 合并加分隔符
- [x] 技能存储机制 -- 已决定: 纯文件,不用 Room
- [ ] 斜杠命令是否也应支持内置应用命令(如 `/clear`、`/settings`),还是仅支持技能?
- [ ] 系统提示词注册表中包含的最大技能数量(性能 vs 可发现性权衡)

## 参考资料

- [Claude Code Skill 系统](https://docs.anthropic.com/en/docs/claude-code) -- 参考实现
- [RFC-004: 工具系统](./RFC-004-tool-system.md) -- Tool 接口和执行引擎
- [RFC-001: 聊天交互](./RFC-001-chat-interaction.md) -- 聊天流程和系统提示词
- [RFC-002: Agent 管理](./RFC-002-agent-management.md) -- Agent 模型和系统提示词
- [FEAT-014: Agent Skill PRD](../../prd/features/FEAT-014-agent-skill.md) -- 产品需求

## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|----------|--------|
| 2026-02-28 | 0.1 | 初始版本 | - |
