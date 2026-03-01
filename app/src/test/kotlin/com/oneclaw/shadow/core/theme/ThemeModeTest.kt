package com.oneclaw.shadow.core.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThemeModeTest {

    @Test
    fun `fromKey with system returns SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("system"))
    }

    @Test
    fun `fromKey with light returns LIGHT`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey("light"))
    }

    @Test
    fun `fromKey with dark returns DARK`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("dark"))
    }

    @Test
    fun `fromKey with null returns SYSTEM as default`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
    }

    @Test
    fun `fromKey with invalid key returns SYSTEM as fallback`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("invalid"))
    }

    @Test
    fun `fromKey with empty string returns SYSTEM as fallback`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(""))
    }

    @Test
    fun `ThemeMode keys are correct`() {
        assertEquals("system", ThemeMode.SYSTEM.key)
        assertEquals("light", ThemeMode.LIGHT.key)
        assertEquals("dark", ThemeMode.DARK.key)
    }
}
