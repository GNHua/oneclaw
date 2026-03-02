package com.oneclaw.shadow.di

import android.util.Log
import com.oneclaw.shadow.core.model.ToolGroupDefinition
import com.oneclaw.shadow.core.model.ToolSourceInfo
import com.oneclaw.shadow.core.model.ToolSourceType
import com.oneclaw.shadow.tool.browser.BrowserContentExtractor
import com.oneclaw.shadow.tool.browser.BrowserScreenshotCapture
import com.oneclaw.shadow.tool.browser.WebViewManager
import com.oneclaw.shadow.feature.search.usecase.SearchHistoryUseCase
import com.oneclaw.shadow.tool.builtin.BrowserTool
import com.oneclaw.shadow.tool.builtin.CreateAgentTool
import com.oneclaw.shadow.tool.builtin.CreateJsToolTool
import com.oneclaw.shadow.tool.builtin.CreateScheduledTaskTool
import com.oneclaw.shadow.tool.builtin.DeleteJsToolTool
import com.oneclaw.shadow.tool.builtin.DeleteScheduledTaskTool
import com.oneclaw.shadow.tool.builtin.ExecTool
import com.oneclaw.shadow.tool.builtin.JsEvalTool
import com.oneclaw.shadow.tool.builtin.ListScheduledTasksTool
import com.oneclaw.shadow.tool.builtin.ListUserToolsTool
import com.oneclaw.shadow.tool.builtin.LoadSkillTool
import com.oneclaw.shadow.tool.builtin.LoadToolGroupTool
import com.oneclaw.shadow.tool.builtin.PdfExtractTextTool
import com.oneclaw.shadow.tool.builtin.PdfInfoTool
import com.oneclaw.shadow.tool.builtin.PdfRenderPageTool
import com.oneclaw.shadow.tool.builtin.RunScheduledTaskTool
import com.oneclaw.shadow.tool.builtin.SaveMemoryTool
import com.oneclaw.shadow.tool.builtin.SearchHistoryTool
import com.oneclaw.shadow.tool.builtin.UpdateJsToolTool
import com.oneclaw.shadow.tool.builtin.UpdateScheduledTaskTool
import com.oneclaw.shadow.tool.builtin.WebfetchTool
import com.oneclaw.shadow.tool.builtin.config.AddModelTool
import com.oneclaw.shadow.tool.builtin.config.CreateProviderTool
import com.oneclaw.shadow.tool.builtin.config.DeleteAgentTool
import com.oneclaw.shadow.tool.builtin.config.DeleteModelTool
import com.oneclaw.shadow.tool.builtin.config.DeleteProviderTool
import com.oneclaw.shadow.tool.builtin.config.FetchModelsTool
import com.oneclaw.shadow.tool.builtin.config.GetConfigTool
import com.oneclaw.shadow.tool.builtin.config.ListAgentsTool
import com.oneclaw.shadow.tool.builtin.config.ListModelsTool
import com.oneclaw.shadow.tool.builtin.config.ListProvidersTool
import com.oneclaw.shadow.tool.builtin.config.ListToolStatesTool
import com.oneclaw.shadow.tool.builtin.config.ManageEnvVarTool
import com.oneclaw.shadow.tool.builtin.config.SetConfigTool
import com.oneclaw.shadow.tool.builtin.config.SetDefaultModelTool
import com.oneclaw.shadow.tool.builtin.config.SetToolEnabledTool
import com.oneclaw.shadow.tool.builtin.config.UpdateAgentTool
import com.oneclaw.shadow.tool.builtin.config.UpdateProviderTool
import com.oneclaw.shadow.tool.util.PdfToolUtils
import com.oneclaw.shadow.tool.engine.PermissionChecker
import com.oneclaw.shadow.tool.engine.ToolEnabledStateStore
import com.oneclaw.shadow.tool.engine.ToolExecutionEngine
import com.oneclaw.shadow.tool.engine.ToolRegistry
import com.oneclaw.shadow.tool.js.EnvironmentVariableStore
import com.oneclaw.shadow.tool.js.JsExecutionEngine
import com.oneclaw.shadow.tool.js.JsToolLoader
import com.oneclaw.shadow.tool.js.UserToolManager
import com.oneclaw.shadow.tool.js.bridge.LibraryBridge
import com.oneclaw.shadow.tool.skill.SkillFileParser
import com.oneclaw.shadow.tool.skill.SkillRegistry
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val toolModule = module {

    // JS Execution Engine (OkHttpClient, LibraryBridge, GoogleAuthManager)
    single { JsExecutionEngine(get(), get(), get()) }

    // Environment Variable Store
    single { EnvironmentVariableStore(androidContext()) }

    // Library Bridge for shared JS libraries
    single { LibraryBridge(androidContext()) }

    // JS Tool Loader
    single { JsToolLoader(androidContext(), get(), get()) }

    // RFC-014: Skill infrastructure
    single { SkillFileParser() }
    single { SkillRegistry(androidContext(), get()).apply { initialize() } }
    single { LoadSkillTool(get()) }

    // RFC-019: schedule_task built-in tool
    single { CreateScheduledTaskTool(get()) }

    // RFC-027: Scheduled task management tools
    single { ListScheduledTasksTool(get()) }
    single { RunScheduledTaskTool(get()) }
    single { UpdateScheduledTaskTool(get(), get()) }
    single { DeleteScheduledTaskTool(get(), get()) }

    // RFC-020: create_agent built-in tool
    single { CreateAgentTool(get()) }

    // RFC-021: webfetch built-in tool (replaces JS webfetch)
    single { WebfetchTool(get()) }

    // RFC-023: save_memory built-in tool
    single { SaveMemoryTool(get()) }

    // RFC-022: Browser tool components
    single { BrowserScreenshotCapture() }
    single { BrowserContentExtractor(androidContext()) }
    single { WebViewManager(androidContext(), get(), get()) }
    single { BrowserTool(androidContext(), get()) }

    // RFC-029: exec built-in tool
    single { ExecTool(androidContext()) }

    // RFC-034: js_eval built-in tool
    single { JsEvalTool(get(), get()) }

    // RFC-032: search_history built-in tool
    single { SearchHistoryUseCase(get(), get(), get()) }
    single { SearchHistoryTool(get()) }

    // RFC-033: PDF tools
    single {
        PdfToolUtils.initPdfBox(androidContext())
        PdfInfoTool(androidContext())
    }
    single { PdfExtractTextTool(androidContext()) }
    single { PdfRenderPageTool(androidContext()) }

    // RFC-017: Tool enabled state store
    single { ToolEnabledStateStore(androidContext()) }

    // RFC-035: User tool manager (uses lazy provider to avoid circular dep with ToolRegistry)
    single {
        UserToolManager(
            context = androidContext(),
            toolRegistryProvider = { get() },
            jsExecutionEngine = get(),
            envVarStore = get()
        )
    }

    // RFC-035: JS tool CRUD tools
    single { CreateJsToolTool(get()) }
    single { ListUserToolsTool(get()) }
    single { UpdateJsToolTool(get()) }
    single { DeleteJsToolTool(get()) }

    // RFC-036: Configuration management tools
    // Provider tools
    single { ListProvidersTool(get(), get()) }
    single { CreateProviderTool(get()) }
    single { UpdateProviderTool(get()) }
    single { DeleteProviderTool(get()) }

    // Model tools
    single { ListModelsTool(get()) }
    single { FetchModelsTool(get(), get()) }
    single { SetDefaultModelTool(get()) }
    single { AddModelTool(get()) }
    single { DeleteModelTool(get()) }

    // Agent tools
    single { ListAgentsTool(get()) }
    single { UpdateAgentTool(get()) }
    single { DeleteAgentTool(get()) }

    // Settings tools
    single { GetConfigTool(get()) }
    single { SetConfigTool(get(), get()) }

    // Env var tool
    single { ManageEnvVarTool(get()) }

    single {
        ToolRegistry().apply {
            // --- core group: always-available tools ---
            val coreSourceInfo = ToolSourceInfo(
                type = ToolSourceType.BUILTIN,
                groupName = ToolRegistry.CORE_GROUP
            )
            try {
                register(get<LoadSkillTool>(), coreSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register load_skill: ${e.message}")
            }
            // Pass `this` to avoid circular Koin dependency
            try {
                register(LoadToolGroupTool(this), coreSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register load_tool_group: ${e.message}")
            }
            try {
                register(get<SaveMemoryTool>(), coreSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register save_memory: ${e.message}")
            }
            try {
                register(get<SearchHistoryTool>(), coreSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register search_history: ${e.message}")
            }

            // --- web group ---
            val webSourceInfo = ToolSourceInfo(
                type = ToolSourceType.BUILTIN,
                groupName = "web"
            )
            try {
                register(get<WebfetchTool>(), webSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register webfetch: ${e.message}")
            }
            try {
                register(get<BrowserTool>(), webSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register browser: ${e.message}")
            }

            // --- system group ---
            val systemSourceInfo = ToolSourceInfo(
                type = ToolSourceType.BUILTIN,
                groupName = "system"
            )
            try {
                register(get<ExecTool>(), systemSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register exec: ${e.message}")
            }
            try {
                register(get<JsEvalTool>(), systemSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register js_eval: ${e.message}")
            }

            // --- agent group ---
            val agentSourceInfo = ToolSourceInfo(
                type = ToolSourceType.BUILTIN,
                groupName = "agent"
            )
            try {
                register(get<CreateAgentTool>(), agentSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register create_agent: ${e.message}")
            }
            try {
                register(get<ListAgentsTool>(), agentSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register list_agents: ${e.message}")
            }
            try {
                register(get<UpdateAgentTool>(), agentSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register update_agent: ${e.message}")
            }
            try {
                register(get<DeleteAgentTool>(), agentSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register delete_agent: ${e.message}")
            }

            // --- scheduled_tasks group (unchanged) ---
            val scheduledTasksSourceInfo = ToolSourceInfo(
                type = ToolSourceType.BUILTIN,
                groupName = "scheduled_tasks"
            )
            try {
                register(get<CreateScheduledTaskTool>(), scheduledTasksSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register schedule_task: ${e.message}")
            }
            try {
                register(get<ListScheduledTasksTool>(), scheduledTasksSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register list_scheduled_tasks: ${e.message}")
            }
            try {
                register(get<RunScheduledTaskTool>(), scheduledTasksSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register run_scheduled_task: ${e.message}")
            }
            try {
                register(get<UpdateScheduledTaskTool>(), scheduledTasksSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register update_scheduled_task: ${e.message}")
            }
            try {
                register(get<DeleteScheduledTaskTool>(), scheduledTasksSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register delete_scheduled_task: ${e.message}")
            }

            // --- pdf group (unchanged) ---
            val pdfSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "pdf")
            try {
                register(get<PdfInfoTool>(), pdfSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register pdf_info: ${e.message}")
            }
            try {
                register(get<PdfExtractTextTool>(), pdfSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register pdf_extract_text: ${e.message}")
            }
            try {
                register(get<PdfRenderPageTool>(), pdfSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register pdf_render_page: ${e.message}")
            }

            // --- js_tools group (renamed from js_tool_management) ---
            val jsToolsSourceInfo = ToolSourceInfo(
                type = ToolSourceType.BUILTIN,
                groupName = "js_tools"
            )
            try {
                register(get<CreateJsToolTool>(), jsToolsSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register create_js_tool: ${e.message}")
            }
            try {
                register(get<ListUserToolsTool>(), jsToolsSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register list_user_tools: ${e.message}")
            }
            try {
                register(get<UpdateJsToolTool>(), jsToolsSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register update_js_tool: ${e.message}")
            }
            try {
                register(get<DeleteJsToolTool>(), jsToolsSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register delete_js_tool: ${e.message}")
            }

            // --- provider group ---
            val providerSourceInfo = ToolSourceInfo(
                type = ToolSourceType.BUILTIN,
                groupName = "provider"
            )
            listOf(
                get<ListProvidersTool>(),
                get<CreateProviderTool>(),
                get<UpdateProviderTool>(),
                get<DeleteProviderTool>()
            ).forEach { tool ->
                try {
                    register(tool, providerSourceInfo)
                } catch (e: Exception) {
                    Log.e("ToolModule", "Failed to register ${tool.definition.name}: ${e.message}")
                }
            }

            // --- model group ---
            val modelSourceInfo = ToolSourceInfo(
                type = ToolSourceType.BUILTIN,
                groupName = "model"
            )
            listOf(
                get<ListModelsTool>(),
                get<FetchModelsTool>(),
                get<SetDefaultModelTool>(),
                get<AddModelTool>(),
                get<DeleteModelTool>()
            ).forEach { tool ->
                try {
                    register(tool, modelSourceInfo)
                } catch (e: Exception) {
                    Log.e("ToolModule", "Failed to register ${tool.definition.name}: ${e.message}")
                }
            }

            // --- settings group ---
            val settingsSourceInfo = ToolSourceInfo(
                type = ToolSourceType.BUILTIN,
                groupName = "settings"
            )
            listOf(
                get<GetConfigTool>(),
                get<SetConfigTool>(),
                get<ManageEnvVarTool>()
            ).forEach { tool ->
                try {
                    register(tool, settingsSourceInfo)
                } catch (e: Exception) {
                    Log.e("ToolModule", "Failed to register ${tool.definition.name}: ${e.message}")
                }
            }
            // Tool state tools: pass `this` (ToolRegistry) to avoid circular Koin dependency
            try {
                register(ListToolStatesTool(this, get()), settingsSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register list_tool_states: ${e.message}")
            }
            try {
                register(SetToolEnabledTool(this, get()), settingsSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register set_tool_enabled: ${e.message}")
            }

            // --- Register Kotlin tool group metadata ---
            registerGroup(ToolGroupDefinition(
                name = ToolRegistry.CORE_GROUP,
                displayName = "Core",
                description = "Core tools always available: skills, tool groups, memory, time, file I/O"
            ))
            registerGroup(ToolGroupDefinition(
                name = "web",
                displayName = "Web",
                description = "Fetch web pages, browse with a headless browser, and make HTTP requests"
            ))
            registerGroup(ToolGroupDefinition(
                name = "system",
                displayName = "System",
                description = "Execute shell commands and evaluate JavaScript code"
            ))
            registerGroup(ToolGroupDefinition(
                name = "agent",
                displayName = "Agents",
                description = "Create, list, update, and delete AI agents"
            ))
            registerGroup(ToolGroupDefinition(
                name = "provider",
                displayName = "Providers",
                description = "List, create, update, and delete API providers"
            ))
            registerGroup(ToolGroupDefinition(
                name = "model",
                displayName = "Models",
                description = "List, fetch, add, delete models and set the default model"
            ))
            registerGroup(ToolGroupDefinition(
                name = "settings",
                displayName = "Settings",
                description = "Get/set app configuration, manage environment variables, and control tool states"
            ))
            registerGroup(ToolGroupDefinition(
                name = "pdf",
                displayName = "PDF Tools",
                description = "Extract text, get info, and render pages from PDF files"
            ))
            registerGroup(ToolGroupDefinition(
                name = "scheduled_tasks",
                displayName = "Scheduled Tasks",
                description = "Create, list, run, update, and delete scheduled tasks"
            ))
            registerGroup(ToolGroupDefinition(
                name = "js_tools",
                displayName = "JS Tools",
                description = "Create, list, update, and delete user JavaScript tools"
            ))

            // --- Built-in JS tools from assets ---
            // Override map: assign specific JS built-in tools to Kotlin-defined groups
            val jsToolGroupOverrides = mapOf(
                "get_current_time" to ToolRegistry.CORE_GROUP,
                "read_file" to ToolRegistry.CORE_GROUP,
                "write_file" to ToolRegistry.CORE_GROUP,
                "http_request" to "web",
            )

            val loader: JsToolLoader = get()
            try {
                val builtinResult = loader.loadBuiltinTools()
                // Register JS group definitions from _meta entries
                for (groupDef in builtinResult.groupDefinitions) {
                    registerGroup(groupDef)
                }
                // Register built-in JS tools with appropriate source info
                for (tool in builtinResult.loadedTools) {
                    if (hasTool(tool.definition.name)) {
                        Log.w("ToolModule", "Built-in JS tool '${tool.definition.name}' conflicts with existing tool")
                        continue
                    }
                    val overrideGroup = jsToolGroupOverrides[tool.definition.name]
                    val groupName = overrideGroup ?: builtinResult.groupNames[tool.definition.name]
                    val sourceInfo = if (groupName != null) {
                        val sourceType = if (overrideGroup != null) ToolSourceType.BUILTIN else ToolSourceType.TOOL_GROUP
                        ToolSourceInfo(type = sourceType, groupName = groupName)
                    } else {
                        ToolSourceInfo(type = ToolSourceType.BUILTIN)
                    }
                    register(tool, sourceInfo)
                    Log.i("ToolModule", "Registered built-in JS tool: ${tool.definition.name}")
                }
                builtinResult.errors.forEach { error ->
                    Log.e("ToolModule", "Built-in JS tool error [${error.fileName}]: ${error.error}")
                }
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to load built-in JS tools: ${e.message}")
            }

            // User JS tools from file system (can override built-in)
            try {
                val userResult = loader.loadTools()

                for (tool in userResult.loadedTools) {
                    val sourceInfo = ToolSourceInfo(
                        type = ToolSourceType.JS_EXTENSION,
                        filePath = tool.jsFilePath.ifEmpty { null }
                    )

                    if (hasTool(tool.definition.name)) {
                        // Override: unregister old, register new
                        unregister(tool.definition.name)
                        register(tool, sourceInfo)
                        Log.i("ToolModule", "User JS tool '${tool.definition.name}' overrides built-in")
                    } else {
                        register(tool, sourceInfo)
                        Log.i("ToolModule", "Registered user JS tool: ${tool.definition.name}")
                    }
                }

                userResult.errors.forEach { error ->
                    Log.w("ToolModule", "User JS tool load error [${error.fileName}]: ${error.error}")
                }
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to load user JS tools: ${e.message}")
            }
        }
    }

    single { PermissionChecker(androidContext()) }

    single { ToolExecutionEngine(get(), get(), get()) }
}
