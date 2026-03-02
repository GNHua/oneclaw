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
            // Core meta-tools (always available, no groupName)
            try {
                register(get<LoadSkillTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register load_skill: ${e.message}")
            }
            // Pass `this` to avoid circular Koin dependency (same pattern as ListToolStatesTool)
            try {
                register(LoadToolGroupTool(this), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register load_tool_group: ${e.message}")
            }

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

            try {
                register(get<CreateAgentTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register create_agent: ${e.message}")
            }

            try {
                register(get<WebfetchTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register webfetch: ${e.message}")
            }

            try {
                register(get<BrowserTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register browser: ${e.message}")
            }

            try {
                register(get<SaveMemoryTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register save_memory: ${e.message}")
            }

            try {
                register(get<ExecTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register exec: ${e.message}")
            }

            try {
                register(get<JsEvalTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register js_eval: ${e.message}")
            }

            try {
                register(get<SearchHistoryTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register search_history: ${e.message}")
            }

            // RFC-033: PDF tools (grouped)
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

            // RFC-035: JS tool CRUD tools (grouped)
            val jsToolMgmtSourceInfo = ToolSourceInfo(
                type = ToolSourceType.BUILTIN,
                groupName = "js_tool_management"
            )
            try {
                register(get<CreateJsToolTool>(), jsToolMgmtSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register create_js_tool: ${e.message}")
            }
            try {
                register(get<ListUserToolsTool>(), jsToolMgmtSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register list_user_tools: ${e.message}")
            }
            try {
                register(get<UpdateJsToolTool>(), jsToolMgmtSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register update_js_tool: ${e.message}")
            }
            try {
                register(get<DeleteJsToolTool>(), jsToolMgmtSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register delete_js_tool: ${e.message}")
            }

            // RFC-036: Configuration management tools (grouped)
            val configSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "config")
            val configTools = listOf(
                get<ListProvidersTool>(),
                get<CreateProviderTool>(),
                get<UpdateProviderTool>(),
                get<DeleteProviderTool>(),
                get<ListModelsTool>(),
                get<FetchModelsTool>(),
                get<SetDefaultModelTool>(),
                get<AddModelTool>(),
                get<DeleteModelTool>(),
                get<ListAgentsTool>(),
                get<UpdateAgentTool>(),
                get<DeleteAgentTool>(),
                get<GetConfigTool>(),
                get<SetConfigTool>(),
                get<ManageEnvVarTool>()
            )
            configTools.forEach { tool ->
                try {
                    register(tool, configSourceInfo)
                } catch (e: Exception) {
                    Log.e("ToolModule", "Failed to register ${tool.definition.name}: ${e.message}")
                }
            }

            // Tool state tools: pass `this` (ToolRegistry) to avoid circular Koin dependency
            try {
                register(ListToolStatesTool(this, get()), configSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register list_tool_states: ${e.message}")
            }
            try {
                register(SetToolEnabledTool(this, get()), configSourceInfo)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register set_tool_enabled: ${e.message}")
            }

            // RFC-040: Register Kotlin tool group metadata
            registerGroup(ToolGroupDefinition(
                name = "config",
                displayName = "Configuration",
                description = "Manage providers, models, agents, app settings, environment variables, and tool states"
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
                name = "js_tool_management",
                displayName = "JS Tool Management",
                description = "Create, list, update, and delete user JavaScript tools"
            ))

            // Built-in JS tools from assets (replaces Kotlin tool registration)
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
                    val groupName = builtinResult.groupNames[tool.definition.name]
                    val sourceInfo = if (groupName != null) {
                        ToolSourceInfo(type = ToolSourceType.TOOL_GROUP, groupName = groupName)
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
                    // Determine source info: single-file tools = JS_EXTENSION
                    // Group tools (from array manifests) have their filePath set and
                    // the baseName from the loader can be used as groupName.
                    // For now we detect groups by checking if the tool has a non-null jsFilePath
                    // and if the JSON was an array (group) or object (single).
                    // The JsToolLoader does not currently expose groupName per tool,
                    // so we register all user tools as JS_EXTENSION for now.
                    // RFC-018 will refine this when JsToolLoader exposes group metadata.
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
