package com.oneclaw.shadow.tool.js.bridge

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.oneclaw.shadow.data.security.GoogleAuthManager

/**
 * Injects Google authentication functions into the QuickJS context.
 * Provides google.getAccessToken() and google.getAccountEmail() to JS tools.
 *
 * Follows the same pattern as FetchBridge:
 * - inject() registers low-level async functions (__googleGetAccessToken, __googleGetAccountEmail)
 * - GOOGLE_AUTH_WRAPPER_JS provides the high-level JS API (google.*)
 */
object GoogleAuthBridge {

    /**
     * JS wrapper code that provides the google.* API.
     * Must be evaluated in the QuickJS context before tool code runs.
     */
    val GOOGLE_AUTH_WRAPPER_JS = """
        const google = {
            async getAccessToken() {
                const token = await __googleGetAccessToken();
                if (!token) {
                    throw new Error(
                        "Not signed in to Google. Connect your Google account in Settings."
                    );
                }
                return token;
            },
            async getAccountEmail() {
                return await __googleGetAccountEmail();
            }
        };
    """.trimIndent()

    /**
     * Inject the low-level async functions into the QuickJS context.
     *
     * @param quickJs The QuickJS context to inject into.
     * @param googleAuthManager The GoogleAuthManager instance for token access.
     */
    fun inject(quickJs: QuickJs, googleAuthManager: GoogleAuthManager?) {
        quickJs.asyncFunction("__googleGetAccessToken") { _: Array<Any?> ->
            googleAuthManager?.getAccessToken() ?: ""
        }

        quickJs.asyncFunction("__googleGetAccountEmail") { _: Array<Any?> ->
            googleAuthManager?.getAccountEmail() ?: ""
        }
    }
}
