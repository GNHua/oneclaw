package com.oneclaw.shadow.di

import com.oneclaw.shadow.tool.builtin.GetCurrentTimeTool
import com.oneclaw.shadow.tool.builtin.HttpRequestTool
import com.oneclaw.shadow.tool.builtin.ReadFileTool
import com.oneclaw.shadow.tool.builtin.WriteFileTool
import com.oneclaw.shadow.tool.engine.PermissionChecker
import com.oneclaw.shadow.tool.engine.ToolExecutionEngine
import com.oneclaw.shadow.tool.engine.ToolRegistry
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val toolModule = module {

    single {
        ToolRegistry().apply {
            register(GetCurrentTimeTool())
            register(ReadFileTool())
            register(WriteFileTool())
            register(HttpRequestTool(get()))  // get() = OkHttpClient from NetworkModule
        }
    }

    single { PermissionChecker(androidContext()) }

    single { ToolExecutionEngine(get(), get()) }  // ToolRegistry, PermissionChecker
}
