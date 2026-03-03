# RFC-014: Agent Skill System

## Document Information
- **RFC ID**: RFC-014
- **Related PRD**: FEAT-014 (Agent Skill)
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
OneClaw currently has a Tool System (RFC-004) that provides atomic operations (read file, HTTP request, etc.) and an Agent System (RFC-002) that configures AI personas with system prompts and tool sets. However, there is no layer for reusable, structured workflows -- the kind of multi-step prompt templates that guide the AI through a specific task pattern.

Claude Code demonstrates this pattern effectively with its "Skill" system: lightweight prompt templates that orchestrate existing tools into repeatable workflows, triggered via `/` commands or AI self-invocation, with skills registered in the system prompt for discoverability.

### Goals
1. Implement a Skill framework: Markdown-based prompt templates stored in `<skill-name>/SKILL.md` directories
2. Add `load_skill` as a new built-in Tool that loads skill prompt content on demand
3. Inject a lightweight skill registry into the system prompt (appended to agent system prompt with separator)
4. Support three trigger paths: `/` command in chat input, UI skill button, AI self-invocation
5. Enable user-defined skills: create, edit, delete via in-app editor
6. Enable skill sharing: export and import via `SKILL.md` files

### Non-Goals
- Skill marketplace or cloud-based skill distribution
- Skill chaining (one skill invoking another)
- Conditional logic or templating engine (Handlebars, Mustache, etc.) -- V1 uses simple `{{param}}` substitution only
- Room database storage for skills -- skills are file-based only
- Skill versioning or change history tracking
- Skill-specific permissions beyond existing tool permissions

## Technical Design

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                          UI Layer                               │
│                                                                 │
│  SkillManagementScreen    SkillEditorScreen    ChatScreen       │
│  (list/view/delete)       (create/edit)        (/ cmd + btn)   │
│         │                       │                    │          │
│  SkillListViewModel     SkillEditorViewModel   ChatViewModel   │
└────────┬───────────────────────┬────────────────────┬──────────┘
         │                       │                    │
┌────────┴───────────────────────┴────────────────────┴──────────┐
│                        Domain Layer                             │
│                                                                 │
│  SkillRegistry          LoadSkillTool         SkillFileParser   │
│  (scan, index,          (Tool interface,      (parse YAML      │
│   lookup, CRUD)          loads skill)          frontmatter)     │
│         │                       │                    │          │
│  GetAllSkillsUseCase   ExportSkillUseCase  ImportSkillUseCase  │
│  CreateSkillUseCase    DeleteSkillUseCase                      │
└────────┬───────────────────────┬────────────────────┬──────────┘
         │                       │                    │
┌────────┴───────────────────────┴────────────────────┴──────────┐
│                         Data Layer                              │
│                                                                 │
│  assets/skills/              files/skills/                      │
│    summarize-file/             my-custom-skill/                 │
│      SKILL.md                    SKILL.md                      │
│    translate-file/                                              │
│      SKILL.md                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Core Components

#### 1. SkillDefinition (Domain Model)

The in-memory representation of a skill's metadata, parsed from SKILL.md frontmatter.

```kotlin
data class SkillDefinition(
    val name: String,                    // Unique identifier, e.g. "summarize-file"
    val displayName: String,             // Human-readable name, e.g. "Summarize File"
    val description: String,             // One-line description
    val version: String,                 // Version string, e.g. "1.0"
    val toolsRequired: List<String>,     // Tool names this skill needs
    val parameters: List<SkillParameter>, // Parameter definitions
    val isBuiltIn: Boolean,              // Whether this is a built-in skill
    val directoryPath: String            // Absolute path to skill directory
)

data class SkillParameter(
    val name: String,                    // Parameter name, snake_case
    val type: String,                    // "string" (V1 only)
    val required: Boolean,               // Whether this parameter is required
    val description: String              // Human-readable description
)
```

**Location**: `app/src/main/kotlin/com/oneclaw/shadow/core/model/SkillDefinition.kt`

#### 2. SkillFileParser

Parses SKILL.md files into `SkillDefinition` metadata and raw prompt content.

```kotlin
class SkillFileParser {

    data class ParseResult(
        val definition: SkillDefinition,
        val promptContent: String         // Everything after frontmatter
    )

    /**
     * Parse a SKILL.md file into metadata + prompt content.
     * Returns AppResult.Error if file is invalid.
     */
    fun parse(
        filePath: String,
        isBuiltIn: Boolean
    ): AppResult<ParseResult>

    /**
     * Parse from raw content string (for import validation).
     */
    fun parseContent(
        content: String,
        isBuiltIn: Boolean,
        directoryPath: String
    ): AppResult<ParseResult>

    /**
     * Serialize a SkillDefinition + prompt content back to SKILL.md format.
     */
    fun serialize(
        definition: SkillDefinition,
        promptContent: String
    ): String

    /**
     * Substitute parameter values into prompt content.
     * Replaces {{param_name}} with actual values.
     */
    fun substituteParameters(
        promptContent: String,
        parameterValues: Map<String, String>
    ): String
}
```

**YAML Frontmatter Parsing**: Use a lightweight YAML parser. Since the frontmatter structure is fixed and simple (no nested objects beyond the `parameters` list), we can use `org.yaml.snakeyaml:snakeyaml` (already available in many Android projects) or implement a minimal parser for the known schema.

**Format**:
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

# Summarize File

## Instructions
1. Use `read_file` to read the file at {{file_path}}
...
```

**Location**: `app/src/main/kotlin/com/oneclaw/shadow/tool/skill/SkillFileParser.kt`

#### 3. SkillRegistry

Manages the lifecycle and lookup of all skills. Scans directories on initialization, maintains an in-memory index.

```kotlin
class SkillRegistry(
    private val context: Context,
    private val parser: SkillFileParser
) {
    private val skills = mutableMapOf<String, SkillDefinition>()
    private val promptCache = mutableMapOf<String, String>()

    /**
     * Initialize: scan built-in and user-defined skill directories.
     * Called once at app startup.
     */
    fun initialize()

    /**
     * Get all skill definitions (for system prompt registry and UI).
     */
    fun getAllSkills(): List<SkillDefinition>

    /**
     * Get built-in skills only.
     */
    fun getBuiltInSkills(): List<SkillDefinition>

    /**
     * Get user-defined skills only.
     */
    fun getUserSkills(): List<SkillDefinition>

    /**
     * Get a skill definition by name.
     */
    fun getSkill(name: String): SkillDefinition?

    /**
     * Load the full prompt content for a skill (reads file, caches result).
     * Optionally substitutes parameter values.
     */
    fun loadSkillContent(
        name: String,
        parameterValues: Map<String, String> = emptyMap()
    ): AppResult<String>

    /**
     * Create a new user-defined skill.
     * Creates directory + SKILL.md file. Returns error if name conflicts.
     */
    fun createSkill(
        definition: SkillDefinition,
        promptContent: String
    ): AppResult<SkillDefinition>

    /**
     * Update an existing user-defined skill.
     * Overwrites SKILL.md. Returns error if built-in or not found.
     */
    fun updateSkill(
        name: String,
        definition: SkillDefinition,
        promptContent: String
    ): AppResult<SkillDefinition>

    /**
     * Delete a user-defined skill (remove directory).
     * Returns error if built-in or not found.
     */
    fun deleteSkill(name: String): AppResult<Unit>

    /**
     * Import a skill from SKILL.md content string.
     * Creates new directory. Returns error if name conflicts (caller handles rename/replace).
     */
    fun importSkill(content: String): AppResult<SkillDefinition>

    /**
     * Export a skill as SKILL.md content string.
     */
    fun exportSkill(name: String): AppResult<String>

    /**
     * Check if a skill name exists.
     */
    fun hasSkill(name: String): Boolean

    /**
     * Generate the skill registry text for system prompt injection.
     * Returns a formatted string listing all skills.
     */
    fun generateRegistryPrompt(): String

    /**
     * Refresh the registry (re-scan directories).
     * Called after create/update/delete/import operations.
     */
    fun refresh()
}
```

**Directory Scanning Logic**:
1. Scan `assets/skills/` for built-in skills (list asset directories, read each `SKILL.md`)
2. Scan `{filesDir}/skills/` for user-defined skills (list subdirectories, read each `SKILL.md`)
3. Parse frontmatter of each SKILL.md to build the index
4. On parse error: log warning, skip the skill (don't crash)

**Caching**: Prompt content is cached in memory after first load. Cache invalidated on update/delete.

**Location**: `app/src/main/kotlin/com/oneclaw/shadow/tool/skill/SkillRegistry.kt`

#### 4. LoadSkillTool

A new built-in Tool that implements the `Tool` interface, enabling the AI to load skill content.

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
                val warnings = buildList {
                    // Check if required tools are available
                    // (This is informational only -- we don't block loading)
                    if (skill.toolsRequired.isNotEmpty()) {
                        // Tool availability check could be added here
                        // For now, just include the tools_required info
                    }
                }

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

**Location**: `app/src/main/kotlin/com/oneclaw/shadow/tool/builtin/LoadSkillTool.kt`

#### 5. System Prompt Integration

Modify `SendMessageUseCase` to append the skill registry to the agent's system prompt.

```kotlin
// In SendMessageUseCase, when building the system prompt:

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

**SkillRegistry.generateRegistryPrompt()** output:

```
## Available Skills

The following skills are available. Use the `load_skill` tool to load full instructions when needed. You can proactively load a skill when you recognize the user's request matches one.

- summarize-file: Read a local file and produce a structured summary
- translate-file: Translate a file's content to a target language
- fetch-webpage: Fetch and summarize a webpage's content
- rewrite-text: Rewrite text in a specified style or tone
- device-info: Gather and report device information
```

**Key detail**: The `load_skill` tool must be available to ALL agents regardless of their tool set configuration, since skills are global resources. This means `load_skill` is always included in the tool definitions sent to the model, even if the agent has a restricted tool set.

**Implementation**: In `SendMessageUseCase`, when resolving agent tools:
```kotlin
val agentToolDefs = buildList {
    // Always include load_skill
    toolRegistry.getTool("load_skill")?.let { add(it.definition) }
    // Add agent's configured tools
    if (agent.toolIds.isNotEmpty()) {
        addAll(toolRegistry.getToolDefinitionsByNames(agent.toolIds))
    }
}
```

#### 6. Slash Command System (Chat Input Integration)

The `/` command system in the chat input detects when the user types `/` as the first character and shows an autocomplete popup.

```kotlin
/**
 * State for the slash command autocomplete popup.
 */
data class SlashCommandState(
    val isActive: Boolean = false,
    val query: String = "",                     // Text after "/"
    val matchingSkills: List<SkillDefinition> = emptyList(),
    val selectedIndex: Int = -1
)

/**
 * ViewModel logic for slash command handling in ChatViewModel.
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

**Skill Selection Flow**:
1. User selects a skill from the autocomplete list
2. If skill has required parameters with no values, show a parameter input dialog
3. Construct a user message: `"Use the ${skill.displayName} skill"` (with parameter context if applicable)
4. Send the message normally -- the AI sees the skill registry in system prompt and calls `load_skill`

Alternatively, the system can directly inject a tool call to `load_skill` as a pre-populated action, bypassing the need for the AI to decide. Both approaches work; the simpler one is sending a descriptive user message and letting the AI call `load_skill`.

#### 7. UI Skill Button

A button near the chat input that opens a bottom sheet with categorized skill list.

```kotlin
/**
 * Bottom sheet showing available skills grouped by category.
 * Categories are inferred from tools_required or can be hardcoded for built-in.
 */
@Composable
fun SkillSelectionBottomSheet(
    skills: List<SkillDefinition>,
    onSkillSelected: (SkillDefinition) -> Unit,
    onDismiss: () -> Unit
)
```

**Category inference**: For V1, built-in skills have known categories. User-defined skills default to "Custom" category unless we add a `category` frontmatter field (deferred to future).

### Data Model

No Room entities are needed. Skills are entirely file-based.

#### File Structure

```
# Built-in (read-only, bundled with APK)
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

# User-defined (app internal storage, read-write)
{context.filesDir}/
  skills/
    my-custom-skill/
      SKILL.md
    daily-report/
      SKILL.md
```

#### SKILL.md Format Specification

```yaml
---
# Required fields
name: summarize-file              # Unique ID, lowercase + hyphens, 2-50 chars
display_name: "Summarize File"    # Human-readable name
description: "Read a local file and produce a structured summary"

# Optional fields
version: "1.0"                    # Default: "1.0"
tools_required:                   # Default: empty list
  - read_file
parameters:                       # Default: empty list
  - name: file_path
    type: string                  # V1: only "string" supported
    required: true
    description: "Absolute path to the file to summarize"
  - name: language
    type: string
    required: false
    description: "Output language (default: same as source)"
---

# Prompt content starts here (everything after the closing ---)
# This is the content returned by load_skill tool.

## Instructions

1. Use `read_file` to read the file at {{file_path}}
2. Analyze the content...
```

**Frontmatter Validation Rules**:
- `name`: required, must match `^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$`
- `display_name`: required, non-empty
- `description`: required, non-empty
- `version`: optional, defaults to "1.0"
- `tools_required`: optional, each entry must be a valid tool name string
- `parameters`: optional, each entry must have `name`, `type`, `required`, `description`
- Prompt content (after `---`): must be non-empty

### API Design

#### Internal API (Use Cases)

```kotlin
/**
 * Get all skills for display (management screen, autocomplete, etc.)
 */
class GetAllSkillsUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(): List<SkillDefinition> = skillRegistry.getAllSkills()
}

/**
 * Create a new user-defined skill.
 */
class CreateSkillUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(
        definition: SkillDefinition,
        promptContent: String
    ): AppResult<SkillDefinition> = skillRegistry.createSkill(definition, promptContent)
}

/**
 * Update an existing user-defined skill.
 */
class UpdateSkillUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(
        name: String,
        definition: SkillDefinition,
        promptContent: String
    ): AppResult<SkillDefinition> = skillRegistry.updateSkill(name, definition, promptContent)
}

/**
 * Delete a user-defined skill.
 */
class DeleteSkillUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(name: String): AppResult<Unit> = skillRegistry.deleteSkill(name)
}

/**
 * Export a skill as SKILL.md content.
 */
class ExportSkillUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(name: String): AppResult<String> = skillRegistry.exportSkill(name)
}

/**
 * Import a skill from SKILL.md content.
 */
class ImportSkillUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(content: String): AppResult<SkillDefinition> =
        skillRegistry.importSkill(content)
}

/**
 * Load skill prompt content (used by LoadSkillTool internally,
 * and by SkillEditorViewModel for preview).
 */
class LoadSkillContentUseCase(
    private val skillRegistry: SkillRegistry
) {
    operator fun invoke(name: String): AppResult<String> =
        skillRegistry.loadSkillContent(name)
}
```

### UI Layer Design

#### Page/Screen Definitions

##### 1. SkillManagementScreen
- **Route**: `skills` (accessible from Settings or bottom navigation)
- **ViewModel**: `SkillListViewModel`
- **State**: `SkillListUiState`
- **Sections**: Built-in skills (read-only), User-defined skills (with actions)
- **Actions**: Create (FAB), tap to view/edit, swipe to delete/export

##### 2. SkillEditorScreen
- **Route**: `skills/create` or `skills/edit/{skillName}`
- **ViewModel**: `SkillEditorViewModel`
- **State**: `SkillEditorUiState`
- **Mode**: Create (empty form) or Edit (pre-populated from existing skill)
- **Fields**: name, display_name, description, version, parameters, tools_required, prompt content
- **For built-in**: Read-only mode with Clone and Export buttons

##### 3. SlashCommandPopup (in ChatScreen)
- **Component**: `SlashCommandPopup` composable
- **State**: Managed by `ChatViewModel.slashCommandState`
- **Trigger**: User types `/` as first character in input
- **Display**: Floating popup above input box with filtered skill list

##### 4. SkillSelectionBottomSheet (in ChatScreen)
- **Component**: `SkillSelectionBottomSheet` composable
- **Trigger**: User taps Skill button near input area
- **Display**: Bottom sheet with categorized skill list

##### 5. ParameterInputDialog
- **Component**: `SkillParameterDialog` composable
- **Trigger**: When a skill with required parameters is selected
- **Display**: Dialog with text fields for each required parameter

#### State Management

```kotlin
// Skill List Screen
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

    fun loadSkills() { /* scan and categorize */ }
    fun deleteSkill(name: String) { /* delete + refresh */ }
    fun exportSkill(name: String) { /* export to share intent */ }
    fun importSkill(content: String) { /* import + refresh */ }
}

// Skill Editor Screen
data class SkillEditorUiState(
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val version: String = "1.0",
    val parameters: List<SkillParameter> = emptyList(),
    val toolsRequired: List<String> = emptyList(),
    val promptContent: String = "",
    val isBuiltIn: Boolean = false,
    val isEditMode: Boolean = false,       // true if editing existing
    val isSaving: Boolean = false,
    val validationErrors: Map<String, String> = emptyMap(),
    val saveResult: AppResult<Unit>? = null
)

class SkillEditorViewModel(
    private val createSkillUseCase: CreateSkillUseCase,
    private val updateSkillUseCase: UpdateSkillUseCase,
    private val loadSkillContentUseCase: LoadSkillContentUseCase,
    private val skillRegistry: SkillRegistry,
    private val toolRegistry: ToolRegistry     // for tool selection list
) : ViewModel() {
    // ...
    fun save() { /* validate + create or update */ }
}

// Slash Command State (in ChatViewModel)
data class SlashCommandState(
    val isActive: Boolean = false,
    val query: String = "",
    val matchingSkills: List<SkillDefinition> = emptyList()
)
```

### Technology Stack

| Technology | Purpose | Reason |
|-----------|---------|--------|
| SnakeYAML (`org.yaml.snakeyaml`) | Parse YAML frontmatter | Lightweight, well-tested, pure Java (no Android deps) |
| Kotlin `File` API | Read/write SKILL.md files | Standard, no extra dependency |
| Android `AssetManager` | Read built-in skills from assets | Standard Android API |
| Jetpack Compose | UI screens | Consistent with existing app |

**Note on SnakeYAML**: If adding a new dependency is undesirable, we can implement a minimal frontmatter parser for the known schema (since the structure is flat and predictable). This is a tradeoff between dependency management and robustness.

### Directory Structure

```
app/src/main/
├── assets/
│   └── skills/                          # Built-in skills
│       ├── summarize-file/
│       │   └── SKILL.md
│       ├── translate-file/
│       │   └── SKILL.md
│       ├── fetch-webpage/
│       │   └── SKILL.md
│       ├── rewrite-text/
│       │   └── SKILL.md
│       ├── extract-key-points/
│       │   └── SKILL.md
│       ├── device-info/
│       │   └── SKILL.md
│       ├── storage-check/
│       │   └── SKILL.md
│       └── check-api/
│       │   └── SKILL.md
│
├── kotlin/com/oneclaw/shadow/
│   ├── core/model/
│   │   └── SkillDefinition.kt          # SkillDefinition, SkillParameter
│   │
│   ├── tool/
│   │   ├── skill/
│   │   │   ├── SkillFileParser.kt      # Parse/serialize SKILL.md
│   │   │   └── SkillRegistry.kt        # Skill lifecycle management
│   │   └── builtin/
│   │       └── LoadSkillTool.kt        # load_skill Tool implementation
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
│   │   ├── SkillManagementScreen.kt    # List + actions
│   │   ├── SkillEditorScreen.kt        # Create/edit form
│   │   ├── SlashCommandPopup.kt        # / command autocomplete
│   │   ├── SkillSelectionBottomSheet.kt # Skill button popup
│   │   └── SkillParameterDialog.kt     # Parameter input dialog
│   │
│   └── di/
│       └── SkillModule.kt              # Koin DI module
```

### Koin DI Module

```kotlin
val skillModule = module {
    // Core
    single { SkillFileParser() }
    single { SkillRegistry(androidContext(), get()).apply { initialize() } }

    // Tool registration: add LoadSkillTool to existing ToolRegistry
    // This is done in ToolModule or via a skill-specific initializer
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
    viewModel { params ->
        SkillEditorViewModel(get(), get(), get(), get(), get())
    }
}
```

**ToolModule Integration**: Register `LoadSkillTool` in the existing `ToolModule`:

```kotlin
val toolModule = module {
    single {
        ToolRegistry().apply {
            register(GetCurrentTimeTool())
            register(ReadFileTool())
            register(WriteFileTool())
            register(HttpRequestTool(get()))
            register(LoadSkillTool(get()))  // NEW: Add load_skill tool
        }
    }
    // ... rest unchanged
}
```

**Dependency ordering**: `SkillRegistry` must be initialized before `LoadSkillTool` is used, but since Koin resolves lazily, the `single { SkillRegistry(...).apply { initialize() } }` ensures it's ready when first accessed.

## Implementation Steps

### Phase 1: Core Infrastructure
1. [ ] Add SnakeYAML dependency (or implement minimal YAML parser)
2. [ ] Create `SkillDefinition` and `SkillParameter` domain models
3. [ ] Implement `SkillFileParser` (parse, serialize, parameter substitution)
4. [ ] Implement `SkillRegistry` (directory scanning, indexing, CRUD)
5. [ ] Write unit tests for parser and registry

### Phase 2: Tool Integration
6. [ ] Implement `LoadSkillTool` (Tool interface implementation)
7. [ ] Register `LoadSkillTool` in `ToolModule`
8. [ ] Modify `SendMessageUseCase` to inject skill registry into system prompt
9. [ ] Ensure `load_skill` is always included in tool definitions sent to model
10. [ ] Write unit tests for LoadSkillTool and system prompt integration

### Phase 3: Built-in Skills
11. [ ] Write built-in skill SKILL.md files (8 skills across 4 categories)
12. [ ] Add skill files to `assets/skills/` directories
13. [ ] Verify skills load correctly from assets

### Phase 4: Skill Management UI
14. [ ] Implement `SkillListViewModel` and `SkillListUiState`
15. [ ] Implement `SkillManagementScreen` (list, view, delete)
16. [ ] Implement `SkillEditorViewModel` and `SkillEditorUiState`
17. [ ] Implement `SkillEditorScreen` (create, edit, read-only for built-in)
18. [ ] Add navigation routes for skill screens
19. [ ] Add "Skills" entry point in Settings or navigation

### Phase 5: Chat Integration
20. [ ] Implement `SlashCommandPopup` composable
21. [ ] Add `/` detection logic in `ChatViewModel`
22. [ ] Implement `SkillSelectionBottomSheet` composable
23. [ ] Add Skill button to chat input area
24. [ ] Implement `SkillParameterDialog` for required parameters
25. [ ] Wire up skill selection to send appropriate message/tool call

### Phase 6: Import/Export
26. [ ] Implement export flow (skill -> SKILL.md -> Android share intent)
27. [ ] Implement import flow (file picker -> validate -> save)
28. [ ] Add Android intent filter for `.md` files (with validation)
29. [ ] Handle name conflicts on import (rename/replace dialog)

### Phase 7: Testing
30. [ ] Layer 1A: JVM unit tests for all new components
31. [ ] Layer 1B: Instrumented tests (if applicable)
32. [ ] Layer 1C: Roborazzi screenshot tests for new screens
33. [ ] Layer 2: adb visual verification of skill flows
34. [ ] Write test report

## Data Flow

### Skill Loading Flow (AI calls load_skill)

```
1. AI receives user message + system prompt (with skill registry)
2. AI decides to use a skill → generates tool call: load_skill(name="summarize-file")
3. ToolExecutionEngine receives tool call
4. LoadSkillTool.execute({"name": "summarize-file"}) called
5. LoadSkillTool → SkillRegistry.loadSkillContent("summarize-file")
6. SkillRegistry → reads SKILL.md from assets/skills/summarize-file/SKILL.md
7. SkillRegistry → returns prompt content (with parameter substitution if values provided)
8. LoadSkillTool → returns ToolResult.success(header + prompt content)
9. ToolExecutionEngine → returns result to SendMessageUseCase
10. SendMessageUseCase → sends tool result back to model
11. AI reads the loaded skill instructions → follows the workflow
12. AI may call additional tools (read_file, http_request, etc.) as instructed by the skill
```

### Slash Command Flow (User triggers via /)

```
1. User types "/" in chat input
2. ChatViewModel detects "/" prefix → activates SlashCommandState
3. UI shows SlashCommandPopup with filtered skill list
4. User selects "summarize-file"
5. Skill has required param "file_path" → SkillParameterDialog shown
6. User enters "/storage/emulated/0/notes.txt"
7. System constructs message: "Use the summarize-file skill on /storage/emulated/0/notes.txt"
8. Message sent as normal user message
9. AI sees skill registry in system prompt → calls load_skill("summarize-file")
10. Flow continues as "Skill Loading Flow" above
```

### Skill Create Flow

```
1. User navigates to Skill Management → taps "Create"
2. SkillEditorScreen shown with empty form
3. User fills in name, display_name, description, prompt content
4. User taps "Save"
5. SkillEditorViewModel validates all fields
6. CreateSkillUseCase → SkillRegistry.createSkill()
7. SkillRegistry creates directory: {filesDir}/skills/{name}/
8. SkillRegistry writes SKILL.md with frontmatter + content
9. SkillRegistry refreshes index
10. User returns to skill list, new skill is visible
```

## Error Handling

### Error Classification

| Error Type | Source | Handling |
|-----------|--------|----------|
| `skill_not_found` | LoadSkillTool | Return error with list of available skills |
| `parse_error` | SkillFileParser | Skip skill on scan; show error on import |
| `validation_error` | SkillEditorViewModel | Show field-level errors in editor UI |
| `name_conflict` | SkillRegistry.createSkill/importSkill | Return error, caller shows rename/replace dialog |
| `permission_error` | File I/O | Return AppResult.Error with message |
| `file_not_found` | SkillRegistry.loadSkillContent | Return error (skill index stale, trigger refresh) |
| `built_in_modification` | SkillRegistry.updateSkill/deleteSkill | Return error: "Cannot modify built-in skill" |

### Error Handling Strategy

```kotlin
// Skill scan errors: log and skip, don't crash
fun initialize() {
    scanDirectory("assets/skills/", isBuiltIn = true).forEach { path ->
        when (val result = parser.parse(path, isBuiltIn = true)) {
            is AppResult.Success -> skills[result.data.definition.name] = result.data.definition
            is AppResult.Error -> Log.w(TAG, "Skipping invalid skill at $path: ${result.message}")
        }
    }
    // ... same for user skills
}

// Import errors: surface to user
fun importSkill(content: String): AppResult<SkillDefinition> {
    val parseResult = parser.parseContent(content, isBuiltIn = false, directoryPath = "")
    if (parseResult is AppResult.Error) {
        return AppResult.Error(ErrorCode.VALIDATION_ERROR, parseResult.message)
    }
    // ... check name conflict, create directory, write file
}
```

## Performance Considerations

### Startup Performance
- **Skill scanning**: Only parses frontmatter (not full prompt content) during initialization
- **Built-in skills**: ~8 skills, each frontmatter < 1KB → total scan < 20ms
- **User skills**: Scan time grows linearly with number of skills. For < 100 skills, should remain < 50ms
- **Prompt content**: Loaded lazily, only when `load_skill` is called. Cached after first load.

### Memory Usage
- `SkillDefinition` objects: ~200 bytes each. 100 skills = ~20KB (negligible)
- Prompt content cache: ~5KB average per skill. Cache all loaded skills = ~50-100KB (acceptable)
- Cache invalidation: On skill update/delete, remove from cache

### System Prompt Overhead
- Skill registry in system prompt: ~1 line per skill (name + description)
- 8 built-in + 20 user skills = ~28 lines, ~1.5KB of text
- Acceptable overhead in context window

## Security Considerations

### Imported Skill Safety
- Skill content is plain text prompt instructions -- cannot execute code directly
- Skills can only guide the AI to use existing tools, which have their own permission checks
- `load_skill` tool only reads from designated skill directories (cannot read arbitrary paths)
- Maximum skill file size: 100KB (prevents abuse via extremely large files)

### File System Safety
- Built-in skills: read-only (Android assets)
- User skills: stored in app-internal storage (`context.filesDir`), not accessible by other apps
- `LoadSkillTool` validates that the requested skill name exists in the registry (no path traversal)

### Validation on Import
- Frontmatter must parse successfully
- Name must match the regex pattern (no special characters, no path separators)
- Content must not exceed 100KB
- No executable content validation needed (skills are prompts, not code)

## Testing Strategy

### Unit Tests (Layer 1A)
- `SkillFileParser`: parse valid files, reject invalid files, parameter substitution, serialization round-trip
- `SkillRegistry`: initialize with mock file system, CRUD operations, name conflicts, refresh
- `LoadSkillTool`: success case, skill not found, load error
- `generateRegistryPrompt()`: correct format, empty registry
- Use cases: delegation to registry, error propagation
- ViewModels: state updates, validation logic

### Instrumented Tests (Layer 1B)
- SkillRegistry with real file system (create/read/delete directories)
- Asset reading for built-in skills

### Screenshot Tests (Layer 1C)
- SkillManagementScreen: empty, with skills, built-in vs custom
- SkillEditorScreen: create mode, edit mode, read-only mode
- SlashCommandPopup: with matches, filtering
- SkillSelectionBottomSheet: categorized list

### Visual Verification (Layer 2)
- Create a custom skill via editor
- Trigger skill via / command
- Trigger skill via UI button
- AI self-invokes a skill
- Export and import a skill
- Delete a custom skill

## Dependencies

### Depends On
- **RFC-004 (Tool System)**: `LoadSkillTool` implements `Tool` interface, registered in `ToolRegistry`
- **RFC-001 (Chat Interaction)**: System prompt modification, `/` command in chat input
- **RFC-002 (Agent Management)**: Agent system prompt is the base for skill registry injection

### Depended On By
- None currently

### External Dependencies
- `org.yaml.snakeyaml:snakeyaml:2.2` (or minimal custom parser) for YAML frontmatter parsing

## Risks and Mitigation

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| YAML parser adds significant APK size | Low | Low | SnakeYAML is ~300KB; or use minimal custom parser |
| Large number of user skills slows startup | Medium | Low | Lazy loading, only parse frontmatter on scan |
| Skill registry bloats system prompt | Medium | Medium | Cap at ~50 skills in registry; truncate if too many |
| AI ignores skill instructions | Medium | Medium | Optimize skill prompt wording; test with all 3 providers |
| Import of malicious skill content | Low | Low | Skills are just prompts; existing tool permissions apply |

## Alternative Solutions

### Alternative A: Skill as a Room Entity
- **Approach**: Store all skill data in Room database, generate SKILL.md only for export
- **Pros**: Fast queries, structured data, familiar pattern
- **Cons**: Diverges from Claude Code file-based model, harder for advanced users to edit directly
- **Why not chosen**: PRD specifies file-based storage with SKILL.md directory convention

### Alternative B: Skill as a Special Agent Type
- **Approach**: Skills are just Agents with a special flag, reusing the Agent CRUD infrastructure
- **Pros**: Less new code, reuses Agent UI
- **Cons**: Agent model doesn't fit (Agents have their own system prompt, Skills are prompt fragments); conflates two different concepts
- **Why not chosen**: Skills and Agents serve fundamentally different purposes

### Alternative C: No load_skill Tool, Direct Injection
- **Approach**: When a skill is triggered, inject the full prompt directly into the conversation as a system message, without the AI calling a tool
- **Pros**: Simpler, doesn't need a new tool, guaranteed prompt injection
- **Cons**: AI cannot self-invoke skills; only user triggers work; loses the "AI recognizes and loads" capability
- **Why not chosen**: PRD requires AI self-invocation capability (Path 3)

## Future Enhancements

- **Skill categories in frontmatter**: Add `category` field to SKILL.md for better organization
- **Skill chaining**: `load_skill` could accept a `chain` parameter to load multiple skills in sequence
- **Parameterized tool requirements**: Skills could declare tool requirements conditionally
- **Skill execution tracking**: Count and timestamp skill invocations for analytics
- **Skill directory resources**: Support additional files in skill directory (examples, locale variants)
- **Cloud sync**: Sync `files/skills/` directory via Google Drive backup

## Open Questions

- [x] YAML parser choice: SnakeYAML vs custom minimal parser -- recommend SnakeYAML unless APK size is critical
- [x] System prompt injection method -- decided: merge with separator
- [x] Skill storage mechanism -- decided: pure file, no Room
- [ ] Should slash command also support built-in app commands (e.g., `/clear`, `/settings`) in addition to skills, or only skills?
- [ ] Maximum number of skills to include in system prompt registry (performance vs discoverability tradeoff)

## References

- [Claude Code Skill System](https://docs.anthropic.com/en/docs/claude-code) -- reference implementation
- [RFC-004: Tool System](./RFC-004-tool-system.md) -- Tool interface and execution engine
- [RFC-001: Chat Interaction](./RFC-001-chat-interaction.md) -- Chat flow and system prompt
- [RFC-002: Agent Management](./RFC-002-agent-management.md) -- Agent model and system prompt
- [FEAT-014: Agent Skill PRD](../../prd/features/FEAT-014-agent-skill.md) -- Product requirements

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
