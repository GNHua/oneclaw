package com.oneclaw.shadow.tool.js.bridge

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define

/**
 * Injects console.log/warn/error into the QuickJS context.
 * Output goes to Android Logcat.
 */
object ConsoleBridge {

    fun inject(quickJs: QuickJs, toolName: String) {
        val tag = "JSTool:$toolName"

        quickJs.define("console") {
            function("log") { args: Array<Any?> ->
                Log.d(tag, args.joinToString(" ") { it?.toString() ?: "undefined" })
            }
            function("warn") { args: Array<Any?> ->
                Log.w(tag, args.joinToString(" ") { it?.toString() ?: "undefined" })
            }
            function("error") { args: Array<Any?> ->
                Log.e(tag, args.joinToString(" ") { it?.toString() ?: "undefined" })
            }
        }
    }
}
