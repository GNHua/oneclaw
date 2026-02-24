package com.tomandy.oneclaw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable

/**
 * Shared text field colors for settings screens.
 * Container is white in light mode, black in dark mode, providing
 * clear contrast against the group Surface backgrounds.
 */
@Composable
fun settingsTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.background,
    unfocusedContainerColor = MaterialTheme.colorScheme.background,
    disabledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
    errorContainerColor = MaterialTheme.colorScheme.background
)
