package com.tomandy.oneclaw.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shared text field colors for settings screens.
 * Container is white in light mode, black in dark mode.
 */
@Composable
fun settingsTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.background,
    unfocusedContainerColor = MaterialTheme.colorScheme.background,
    disabledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
    errorContainerColor = MaterialTheme.colorScheme.background
)

/** Shared rounded shape for settings text fields. */
val settingsTextFieldShape: Shape = RoundedCornerShape(12.dp)
