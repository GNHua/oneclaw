package com.oneclaw.shadow.screenshot

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.oneclaw.shadow.core.model.ProviderType
import com.oneclaw.shadow.core.repository.SettingsRepository
import com.oneclaw.shadow.core.theme.ThemeManager
import com.oneclaw.shadow.feature.provider.ConnectionStatus
import com.oneclaw.shadow.feature.provider.ProviderListItem
import com.oneclaw.shadow.feature.provider.ProviderListScreenContent
import com.oneclaw.shadow.feature.provider.ProviderListUiState
import com.oneclaw.shadow.feature.provider.SettingsScreen
import com.oneclaw.shadow.ui.theme.OneClawShadowTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-440dpi", application = Application::class)
class ProviderScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var themeManager: ThemeManager

    @Before
    fun setUp() {
        mockkStatic(AppCompatDelegate::class)
        every { AppCompatDelegate.setDefaultNightMode(any()) } returns Unit
        val settingsRepository = mockk<SettingsRepository>()
        themeManager = ThemeManager(settingsRepository)
    }

    @After
    fun tearDown() {
        unmockkStatic(AppCompatDelegate::class)
    }

    private fun themed(content: @Composable () -> Unit) {
        composeRule.setContent {
            OneClawShadowTheme(darkTheme = false, dynamicColor = false) {
                content()
            }
        }
    }

    // --- SettingsScreen ---

    @Test
    fun settingsScreen() {
        themed {
            SettingsScreen(
                onNavigateBack = {},
                onManageProviders = {},
                themeManager = themeManager
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/Phase1-3_SettingsScreen.png")
    }

    // --- ProviderListScreen (stateless variant) ---

    @Test
    fun providerListScreen_loading() {
        themed {
            ProviderListScreenContent(
                uiState = ProviderListUiState(isLoading = true),
                onProviderClick = {},
                onNavigateBack = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/Phase1-3_ProviderListScreen_loading.png")
    }

    @Test
    fun providerListScreen_withPreConfiguredProviders() {
        val providers = listOf(
            ProviderListItem(
                id = "provider-openai",
                name = "OpenAI",
                type = ProviderType.OPENAI,
                modelCount = 4,
                isActive = true,
                isPreConfigured = true,
                hasApiKey = true,
                connectionStatus = ConnectionStatus.DISCONNECTED
            ),
            ProviderListItem(
                id = "provider-anthropic",
                name = "Anthropic",
                type = ProviderType.ANTHROPIC,
                modelCount = 2,
                isActive = true,
                isPreConfigured = true,
                hasApiKey = false,
                connectionStatus = ConnectionStatus.NOT_CONFIGURED
            ),
            ProviderListItem(
                id = "provider-gemini",
                name = "Google Gemini",
                type = ProviderType.GEMINI,
                modelCount = 2,
                isActive = true,
                isPreConfigured = true,
                hasApiKey = true,
                connectionStatus = ConnectionStatus.CONNECTED
            )
        )
        themed {
            ProviderListScreenContent(
                uiState = ProviderListUiState(providers = providers, isLoading = false),
                onProviderClick = {},
                onNavigateBack = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/Phase1-3_ProviderListScreen_populated.png")
    }

    @Test
    fun providerListScreen_empty() {
        themed {
            ProviderListScreenContent(
                uiState = ProviderListUiState(providers = emptyList(), isLoading = false),
                onProviderClick = {},
                onNavigateBack = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/Phase1-3_ProviderListScreen_empty.png")
    }

    @Test
    fun providerListScreen_darkTheme() {
        composeRule.setContent {
            OneClawShadowTheme(darkTheme = true, dynamicColor = false) {
                ProviderListScreenContent(
                    uiState = ProviderListUiState(
                        providers = listOf(
                            ProviderListItem(
                                id = "provider-anthropic",
                                name = "Anthropic",
                                type = ProviderType.ANTHROPIC,
                                modelCount = 2,
                                isActive = true,
                                isPreConfigured = true,
                                hasApiKey = true,
                                connectionStatus = ConnectionStatus.CONNECTED
                            )
                        ),
                        isLoading = false
                    ),
                    onProviderClick = {},
                    onNavigateBack = {}
                )
            }
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/Phase1-3_ProviderListScreen_dark.png")
    }
}
