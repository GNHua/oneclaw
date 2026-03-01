package com.oneclaw.shadow.tool.js.bridge

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for GoogleAuthBridge.
 *
 * Tests the JS wrapper code syntax and bridge configuration.
 * QuickJS injection is not tested here (requires native JNI libraries);
 * integration is verified via JsExecutionEngine.
 */
class GoogleAuthBridgeTest {

    @Test
    fun `GOOGLE_AUTH_WRAPPER_JS is not null or blank`() {
        val js = GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
        assertNotNull(js)
        assertTrue(js.isNotBlank(), "GOOGLE_AUTH_WRAPPER_JS should not be blank")
    }

    @Test
    fun `GOOGLE_AUTH_WRAPPER_JS declares const google object`() {
        val js = GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
        assertTrue(js.contains("const google"), "JS should declare 'const google'")
    }

    @Test
    fun `GOOGLE_AUTH_WRAPPER_JS contains getAccessToken function`() {
        val js = GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
        assertTrue(js.contains("getAccessToken"), "JS should contain getAccessToken")
    }

    @Test
    fun `GOOGLE_AUTH_WRAPPER_JS contains getAccountEmail function`() {
        val js = GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
        assertTrue(js.contains("getAccountEmail"), "JS should contain getAccountEmail")
    }

    @Test
    fun `GOOGLE_AUTH_WRAPPER_JS calls __googleGetAccessToken native function`() {
        val js = GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
        assertTrue(js.contains("__googleGetAccessToken"), "JS should call __googleGetAccessToken")
    }

    @Test
    fun `GOOGLE_AUTH_WRAPPER_JS calls __googleGetAccountEmail native function`() {
        val js = GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
        assertTrue(js.contains("__googleGetAccountEmail"), "JS should call __googleGetAccountEmail")
    }

    @Test
    fun `GOOGLE_AUTH_WRAPPER_JS throws error when token is null or empty`() {
        val js = GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
        assertTrue(js.contains("throw new Error"), "JS should throw error when not signed in")
        assertTrue(js.contains("Not signed in to Google"), "Error message should mention Google sign-in")
    }

    @Test
    fun `GOOGLE_AUTH_WRAPPER_JS contains async keyword for async functions`() {
        val js = GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
        // Both getAccessToken and getAccountEmail should be async
        val asyncCount = js.split("async").size - 1
        assertTrue(asyncCount >= 2, "Both getAccessToken and getAccountEmail should be async, found $asyncCount async keywords")
    }

    @Test
    fun `GOOGLE_AUTH_WRAPPER_JS is valid JavaScript-like syntax (basic checks)`() {
        val js = GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
        // Check balanced braces
        val openBraces = js.count { it == '{' }
        val closeBraces = js.count { it == '}' }
        assertEquals(openBraces, closeBraces, "Braces should be balanced in wrapper JS")
    }

    @Test
    fun `GOOGLE_AUTH_WRAPPER_JS settings message includes Settings text`() {
        val js = GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
        assertTrue(js.contains("Settings"), "Error message should mention Settings screen")
    }

    private fun assertEquals(expected: Int, actual: Int, message: String) {
        if (expected != actual) {
            throw AssertionError("$message: expected=$expected, actual=$actual")
        }
    }
}
