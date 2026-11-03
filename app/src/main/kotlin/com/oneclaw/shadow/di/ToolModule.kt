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
import com.oneclaw.shadow.tool.builtin.MobileLegendTool
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
import com.oneclaw.shadow.tool.builtin.GitBundleTool
import com.oneclaw.shadow.tool.builtin.GitDiffTool
import com.oneclaw.shadow.tool.builtin.GitLogTool
import com.oneclaw.shadow.tool.builtin.GitRestoreTool
import com.oneclaw.shadow.tool.builtin.GitShowTool
import com.oneclaw.shadow.tool.builtin.SaveMemoryTool
import com.oneclaw.shadow.tool.builtin.UpdateMemoryTool
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

    single { JsExecutionEngine(get(), get(), get(), androidContext().filesDir, get()) }
    single { EnvironmentVariableStore(androidContext()) }
    single { LibraryBridge(androidContext()) }
    single { JsToolLoader(androidContext(), get(), get()) }
    single { SkillFileParser() }
    single { SkillRegistry(androidContext(), get()).apply { initialize() } }
    single { LoadSkillTool(get()) }
    single { CreateScheduledTaskTool(get()) }
    single { ListScheduledTasksTool(get()) }
    single { RunScheduledTaskTool(get()) }
    single { UpdateScheduledTaskTool(get(), get()) }
    single { DeleteScheduledTaskTool(get(), get()) }
    single { CreateAgentTool(get()) }
    single { WebfetchTool(get()) }
    single { SaveMemoryTool(get()) }
    single { UpdateMemoryTool(get()) }
    single { BrowserScreenshotCapture() }
    single { BrowserContentExtractor(androidContext()) }
    single { WebViewManager(androidContext(), get(), get()) }
    single { BrowserTool(androidContext(), get()) }
    single { MobileLegendTool() }
    single { ExecTool(androidContext()) }
    single { JsEvalTool(get(), get()) }
    single { SearchHistoryUseCase(get(), get(), get()) }
    single { SearchHistoryTool(get()) }
    single {
        PdfToolUtils.initPdfBox(androidContext())
        PdfInfoTool(androidContext())
    }
    single { PdfExtractTextTool(androidContext()) }
    single { PdfRenderPageTool(androidContext()) }
    single { ToolEnabledStateStore(androidContext()) }
    single {
        UserToolManager(
            context = androidContext(),
            toolRegistryProvider = { get() },
            jsExecutionEngine = get(),
            envVarStore = get()
        )
    }
    single { CreateJsToolTool(get()) }
    single { ListUserToolsTool(get()) }
    single { UpdateJsToolTool(get()) }
    single { DeleteJsToolTool(get()) }
    single { ListProvidersTool(get(), get()) }
    single { CreateProviderTool(get()) }
    single { UpdateProviderTool(get()) }
    single { DeleteProviderTool(get()) }
    single { ListModelsTool(get()) }
    single { FetchModelsTool(get(), get()) }
    single { SetDefaultModelTool(get()) }
    single { AddModelTool(get()) }
    single { DeleteModelTool(get()) }
    single { ListAgentsTool(get()) }
    single { UpdateAgentTool(get()) }
    single { DeleteAgentTool(get()) }
    single { GetConfigTool(get()) }
    single { SetConfigTool(get(), get()) }
    single { ManageEnvVarTool(get()) }

    single {
        ToolRegistry().apply {
            val coreSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = ToolRegistry.CORE_GROUP)
            try { register(get<LoadSkillTool>(), coreSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register load_skill: ${e.message}") }
            try { register(LoadToolGroupTool(this), coreSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register load_tool_group: ${e.message}") }
            try { register(get<SaveMemoryTool>(), coreSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register save_memory: ${e.message}") }
            try { register(get<UpdateMemoryTool>(), coreSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register update_memory: ${e.message}") }
            try { register(get<SearchHistoryTool>(), coreSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register search_history: ${e.message}") }

            val webSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "web")
            try { register(get<WebfetchTool>(), webSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register webfetch: ${e.message}") }
            try { register(get<BrowserTool>(), webSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register browser: ${e.message}") }

            val gameSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "game")
            try { register(get<MobileLegendTool>(), gameSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register mobile_legend: ${e.message}") }

            val systemSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "system")
            try { register(get<ExecTool>(), systemSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register exec: ${e.message}") }
            try { register(get<JsEvalTool>(), systemSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register js_eval: ${e.message}") }

            val agentSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "agent")
            try { register(get<CreateAgentTool>(), agentSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register create_agent: ${e.message}") }
            try { register(get<ListAgentsTool>(), agentSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register list_agents: ${e.message}") }
            try { register(get<UpdateAgentTool>(), agentSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register update_agent: ${e.message}") }
            try { register(get<DeleteAgentTool>(), agentSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register delete_agent: ${e.message}") }

            val scheduledTasksSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "scheduled_tasks")
            try { register(get<CreateScheduledTaskTool>(), scheduledTasksSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register schedule_task: ${e.message}") }
            try { register(get<ListScheduledTasksTool>(), scheduledTasksSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register list_scheduled_tasks: ${e.message}") }
            try { register(get<RunScheduledTaskTool>(), scheduledTasksSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register run_scheduled_task: ${e.message}") }
            try { register(get<UpdateScheduledTaskTool>(), scheduledTasksSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register update_scheduled_task: ${e.message}") }
            try { register(get<DeleteScheduledTaskTool>(), scheduledTasksSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register delete_scheduled_task: ${e.message}") }

            val pdfSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "pdf")
            try { register(get<PdfInfoTool>(), pdfSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register pdf_info: ${e.message}") }
            try { register(get<PdfExtractTextTool>(), pdfSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register pdf_extract_text: ${e.message}") }
            try { register(get<PdfRenderPageTool>(), pdfSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register pdf_render_page: ${e.message}") }

            val jsToolsSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "js_tools")
            try { register(get<CreateJsToolTool>(), jsToolsSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register create_js_tool: ${e.message}") }
            try { register(get<ListUserToolsTool>(), jsToolsSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register list_user_tools: ${e.message}") }
            try { register(get<UpdateJsToolTool>(), jsToolsSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register update_js_tool: ${e.message}") }
            try { register(get<DeleteJsToolTool>(), jsToolsSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register delete_js_tool: ${e.message}") }

            val gitSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "git")
            listOf(get<GitLogTool>(), get<GitShowTool>(), get<GitDiffTool>(), get<GitRestoreTool>(), get<GitBundleTool>()).forEach { tool ->
                try { register(tool, gitSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register ${tool.definition.name}: ${e.message}") }
            }
            val providerSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "provider")
            listOf(get<ListProvidersTool>(), get<CreateProviderTool>(), get<UpdateProviderTool>(), get<DeleteProviderTool>()).forEach { tool ->
                try { register(tool, providerSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register ${tool.definition.name}: ${e.message}") }
            }
            val modelSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "model")
            listOf(get<ListModelsTool>(), get<FetchModelsTool>(), get<SetDefaultModelTool>(), get<AddModelTool>(), get<DeleteModelTool>()).forEach { tool ->
                try { register(tool, modelSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register ${tool.definition.name}: ${e.message}") }
            }
            val settingsSourceInfo = ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "settings")
            listOf(get<GetConfigTool>(), get<SetConfigTool>(), get<ManageEnvVarTool>()).forEach { tool ->
                try { register(tool, settingsSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register ${tool.definition.name}: ${e.message}") }
            }
            try { register(ListToolStatesTool(this, get()), settingsSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register list_tool_states: ${e.message}") }
            try { register(SetToolEnabledTool(this, get()), settingsSourceInfo) } catch (e: Exception) { Log.e("ToolModule", "Failed to register set_tool_enabled: ${e.message}") }

            registerGroup(ToolGroupDefinition(name = ToolRegistry.CORE_GROUP, displayName = "Core", description = "Core tools always available"))
            registerGroup(ToolGroupDefinition(name = "web", displayName = "Web", description = "Fetch web pages, browse"))
            registerGroup(ToolGroupDefinition(name = "game", displayName = "Game", description = "Tools for playing games like Mobile Legends"))
            registerGroup(ToolGroupDefinition(name = "system", displayName = "System", description = "Execute shell commands"))
            registerGroup(ToolGroupDefinition(name = "agent", displayName = "Agents", description = "Create, list, update, and delete AI agents"))
            registerGroup(ToolGroupDefinition(name = "provider", displayName = "Providers", description = "List, create, update, and delete API providers"))
            registerGroup(ToolGroupDefinition(name = "model", displayName = "Models", description = "List, fetch, add, delete models"))
            registerGroup(ToolGroupDefinition(name = "settings", displayName = "Settings", description = "Get/set app configuration"))
            registerGroup(ToolGroupDefinition(name = "pdf", displayName = "PDF Tools", description = "Extract text from PDF"))
            registerGroup(ToolGroupDefinition(name = "scheduled_tasks", displayName = "Scheduled Tasks", description = "Create, list, run, update, and delete scheduled tasks"))
            registerGroup(ToolGroupDefinition(name = "js_tools", displayName = "JS Tools", description = "Create, list, update, and delete user JavaScript tools"))
            registerGroup(ToolGroupDefinition(name = "git", displayName = "Git Versioning", description = "View git history, inspect commits, diff versions"))

            val jsToolGroupOverrides = mapOf(
                "get_current_time" to ToolRegistry.CORE_GROUP,
                "read_file" to ToolRegistry.CORE_GROUP,
                "write_file" to ToolRegistry.CORE_GROUP,
                "http_request" to "web",
            )
            val loader: JsToolLoader = get()
            try {
                val builtinResult = loader.loadBuiltinTools()
                for (groupDef in builtinResult.groupDefinitions) { registerGroup(groupDef) }
                for (tool in builtinResult.loadedTools) {
                    if (hasTool(tool.definition.name)) { Log.w("ToolModule", "Built-in JS tool '${tool.definition.name}' conflicts"); continue }
                    val overrideGroup = jsToolGroupOverrides[tool.definition.name]
                    val groupName = overrideGroup ?: builtinResult.groupNames[tool.definition.name]
                    val sourceInfo = if (groupName != null) {
                        val sourceType = if (overrideGroup != null) ToolSourceType.BUILTIN else ToolSourceType.TOOL_GROUP
                        ToolSourceInfo(type = sourceType, groupName = groupName)
                    } else { ToolSourceInfo(type = ToolSourceType.BUILTIN) }
                    register(tool, sourceInfo)
                }
            } catch (e: Exception) { Log.e("ToolModule", "Failed to load built-in JS tools: ${e.message}") }
            try {
                val userResult = loader.loadTools()
                for (tool in userResult.loadedTools) {
                    val sourceInfo = ToolSourceInfo(type = ToolSourceType.JS_EXTENSION, filePath = tool.jsFilePath.ifEmpty { null })
                    if (hasTool(tool.definition.name)) { unregister(tool.definition.name); register(tool, sourceInfo) } else { register(tool, sourceInfo) }
                }
            } catch (e: Exception) { Log.e("ToolModule", "Failed to load user JS tools: ${e.message}") }
        }
    }
    single { PermissionChecker(androidContext()) }
    single { ToolExecutionEngine(get(), get(), get()) }
}
