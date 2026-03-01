package com.oneclaw.shadow.di

import android.util.Log
import com.oneclaw.shadow.core.model.ToolSourceInfo
import com.oneclaw.shadow.core.model.ToolSourceType
import com.oneclaw.shadow.tool.browser.BrowserContentExtractor
import com.oneclaw.shadow.tool.browser.BrowserScreenshotCapture
import com.oneclaw.shadow.tool.browser.WebViewManager
import com.oneclaw.shadow.tool.builtin.BrowserTool
import com.oneclaw.shadow.tool.builtin.CreateAgentTool
import com.oneclaw.shadow.tool.builtin.CreateScheduledTaskTool
import com.oneclaw.shadow.tool.builtin.LoadSkillTool
import com.oneclaw.shadow.tool.builtin.PdfExtractTextTool
import com.oneclaw.shadow.tool.builtin.PdfInfoTool
import com.oneclaw.shadow.tool.builtin.PdfRenderPageTool
import com.oneclaw.shadow.tool.builtin.WebfetchTool
import com.oneclaw.shadow.tool.util.PdfToolUtils
import com.oneclaw.shadow.tool.engine.PermissionChecker
import com.oneclaw.shadow.tool.engine.ToolEnabledStateStore
import com.oneclaw.shadow.tool.engine.ToolExecutionEngine
import com.oneclaw.shadow.tool.engine.ToolRegistry
import com.oneclaw.shadow.tool.js.EnvironmentVariableStore
import com.oneclaw.shadow.tool.js.JsExecutionEngine
import com.oneclaw.shadow.tool.js.JsToolLoader
import com.oneclaw.shadow.tool.js.bridge.LibraryBridge
import com.oneclaw.shadow.tool.skill.SkillFileParser
import com.oneclaw.shadow.tool.skill.SkillRegistry
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val toolModule = module {

    // JS Execution Engine (OkHttpClient, LibraryBridge)
    single { JsExecutionEngine(get(), get()) }

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

    // RFC-020: create_agent built-in tool
    single { CreateAgentTool(get()) }

    // RFC-021: webfetch built-in tool (replaces JS webfetch)
    single { WebfetchTool(get()) }

    // RFC-022: Browser tool components
    single { BrowserScreenshotCapture() }
    single { BrowserContentExtractor(androidContext()) }
    single { WebViewManager(androidContext(), get(), get()) }
    single { BrowserTool(androidContext(), get()) }

    // RFC-033: PDF tools
    single {
        PdfToolUtils.initPdfBox(androidContext())
        PdfInfoTool(androidContext())
    }
    single { PdfExtractTextTool(androidContext()) }
    single { PdfRenderPageTool(androidContext()) }

    // RFC-017: Tool enabled state store
    single { ToolEnabledStateStore(androidContext()) }

    single {
        ToolRegistry().apply {
            // Only Kotlin built-in: LoadSkillTool
            try {
                register(get<LoadSkillTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register load_skill: ${e.message}")
            }

            try {
                register(get<CreateScheduledTaskTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register schedule_task: ${e.message}")
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

            // RFC-033: PDF tools
            try {
                register(get<PdfInfoTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register pdf_info: ${e.message}")
            }
            try {
                register(get<PdfExtractTextTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register pdf_extract_text: ${e.message}")
            }
            try {
                register(get<PdfRenderPageTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register pdf_render_page: ${e.message}")
            }

            // Built-in JS tools from assets (replaces Kotlin tool registration)
            val loader: JsToolLoader = get()
            try {
                val builtinResult = loader.loadBuiltinTools()
                // Register built-in JS tools with BUILTIN source type
                for (tool in builtinResult.loadedTools) {
                    if (hasTool(tool.definition.name)) {
                        Log.w("ToolModule", "Built-in JS tool '${tool.definition.name}' conflicts with existing tool")
                        continue
                    }
                    register(tool, ToolSourceInfo(type = ToolSourceType.BUILTIN))
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
