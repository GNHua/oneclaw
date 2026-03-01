package com.oneclaw.shadow.core.theme

import androidx.appcompat.app.AppCompatDelegate
import com.oneclaw.shadow.core.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ThemeManagerTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var themeManager: ThemeManager

    @BeforeEach
    fun setUp() {
        mockkStatic(AppCompatDelegate::class)
        io.mockk.every { AppCompatDelegate.setDefaultNightMode(any()) } returns Unit
        settingsRepository = mockk()
        themeManager = ThemeManager(settingsRepository)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(AppCompatDelegate::class)
    }

    @Test
    fun `initialize with no stored value sets themeMode to SYSTEM`() = runTest {
        coEvery { settingsRepository.getString(ThemeManager.SETTINGS_KEY) } returns null

        themeManager.initialize()

        assertEquals(ThemeMode.SYSTEM, themeManager.themeMode.value)
    }

    @Test
    fun `initialize with stored dark sets themeMode to DARK`() = runTest {
        coEvery { settingsRepository.getString(ThemeManager.SETTINGS_KEY) } returns "dark"

        themeManager.initialize()

        assertEquals(ThemeMode.DARK, themeManager.themeMode.value)
    }

    @Test
    fun `initialize with stored light sets themeMode to LIGHT`() = runTest {
        coEvery { settingsRepository.getString(ThemeManager.SETTINGS_KEY) } returns "light"

        themeManager.initialize()

        assertEquals(ThemeMode.LIGHT, themeManager.themeMode.value)
    }

    @Test
    fun `setThemeMode DARK persists dark key and updates flow to DARK`() = runTest {
        coEvery { settingsRepository.setString(any(), any()) } returns Unit

        themeManager.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, themeManager.themeMode.value)
        coVerify { settingsRepository.setString(ThemeManager.SETTINGS_KEY, "dark") }
    }

    @Test
    fun `setThemeMode LIGHT after initialize updates flow from initial value to LIGHT`() = runTest {
        coEvery { settingsRepository.getString(ThemeManager.SETTINGS_KEY) } returns null
        coEvery { settingsRepository.setString(any(), any()) } returns Unit

        themeManager.initialize()
        assertEquals(ThemeMode.SYSTEM, themeManager.themeMode.value)

        themeManager.setThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, themeManager.themeMode.value)
    }

    @Test
    fun `initialize calls AppCompatDelegate setDefaultNightMode with MODE_NIGHT_FOLLOW_SYSTEM for SYSTEM`() = runTest {
        coEvery { settingsRepository.getString(ThemeManager.SETTINGS_KEY) } returns null

        themeManager.initialize()

        verify { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
    }

    @Test
    fun `initialize calls AppCompatDelegate setDefaultNightMode with MODE_NIGHT_YES for DARK`() = runTest {
        coEvery { settingsRepository.getString(ThemeManager.SETTINGS_KEY) } returns "dark"

        themeManager.initialize()

        verify { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
    }

    @Test
    fun `initialize calls AppCompatDelegate setDefaultNightMode with MODE_NIGHT_NO for LIGHT`() = runTest {
        coEvery { settingsRepository.getString(ThemeManager.SETTINGS_KEY) } returns "light"

        themeManager.initialize()

        verify { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
    }

    @Test
    fun `themeMode initial value is SYSTEM before initialize`() {
        assertEquals(ThemeMode.SYSTEM, themeManager.themeMode.value)
    }
}
