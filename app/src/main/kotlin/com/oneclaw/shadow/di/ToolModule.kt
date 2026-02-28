package com.oneclaw.shadow.di

import android.util.Log
import com.oneclaw.shadow.tool.builtin.GetCurrentTimeTool
import com.oneclaw.shadow.tool.builtin.HttpRequestTool
import com.oneclaw.shadow.tool.builtin.LoadSkillTool
import com.oneclaw.shadow.tool.builtin.ReadFileTool
import com.oneclaw.shadow.tool.builtin.WriteFileTool
import com.oneclaw.shadow.tool.engine.PermissionChecker
import com.oneclaw.shadow.tool.engine.ToolExecutionEngine
import com.oneclaw.shadow.tool.engine.ToolRegistry
import com.oneclaw.shadow.tool.js.EnvironmentVariableStore
import com.oneclaw.shadow.tool.js.JsExecutionEngine
import com.oneclaw.shadow.tool.js.JsToolLoader
import com.oneclaw.shadow.tool.skill.SkillFileParser
import com.oneclaw.shadow.tool.skill.SkillRegistry
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val toolModule = module {

    // NEW: JS Execution Engine
    single { JsExecutionEngine(get()) }  // get() = OkHttpClient

    // NEW: Environment Variable Store
    single { EnvironmentVariableStore(androidContext()) }

    // NEW: JS Tool Loader
    single { JsToolLoader(androidContext(), get(), get()) }  // JsExecutionEngine, EnvironmentVariableStore

    // RFC-014: Skill infrastructure
    single { SkillFileParser() }
    single { SkillRegistry(androidContext(), get()).apply { initialize() } }
    single { LoadSkillTool(get()) }  // get() = SkillRegistry

    single {
        ToolRegistry().apply {
            // Built-in Kotlin tools
            register(GetCurrentTimeTool())
            register(ReadFileTool())
            register(WriteFileTool())
            register(HttpRequestTool(get()))  // get() = OkHttpClient from NetworkModule

            // RFC-014: load_skill tool (always available to all agents)
            register(get<LoadSkillTool>())  // get() = LoadSkillTool from above

            // JS tools: loaded from file system
            val loader: JsToolLoader = get()
            val loadResult = loader.loadTools()
            val conflicts = loader.registerTools(this, loadResult.loadedTools)

            // Log results
            val totalErrors = loadResult.errors + conflicts
            if (loadResult.loadedTools.isNotEmpty()) {
                Log.i("ToolModule", "Loaded ${loadResult.loadedTools.size} JS tool(s)")
            }
            totalErrors.forEach { error ->
                Log.w("ToolModule", "JS tool load error [${error.fileName}]: ${error.error}")
            }
        }
    }

    single { PermissionChecker(androidContext()) }

    single { ToolExecutionEngine(get(), get()) }  // ToolRegistry, PermissionChecker
}
