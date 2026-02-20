package com.tomandy.oneclaw.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.MainActivity
import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.ui.theme.Dimens

@Composable
fun AppearanceScreen(
    modelPreferences: ModelPreferences,
    modifier: Modifier = Modifier
) {
    val activity = LocalContext.current as? MainActivity
    var themeMode by remember { mutableStateOf(modelPreferences.getThemeMode()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = Dimens.CardElevation)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.CardInnerPadding)
            ) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Choose light or dark appearance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = ModelPreferences.ThemeMode.entries
                    options.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = {
                                themeMode = mode
                                modelPreferences.saveThemeMode(mode)
                                activity?.themeMode?.value = mode
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = options.size
                            )
                        ) {
                            Text(
                                when (mode) {
                                    ModelPreferences.ThemeMode.SYSTEM -> "System"
                                    ModelPreferences.ThemeMode.LIGHT -> "Light"
                                    ModelPreferences.ThemeMode.DARK -> "Dark"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
