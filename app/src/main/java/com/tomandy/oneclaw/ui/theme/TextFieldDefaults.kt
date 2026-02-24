package com.tomandy.oneclaw.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shared text field colors for settings screens.
 * Transparent container so the label cutout blends with the parent Surface.
 */
@Composable
fun settingsTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent
)

/** Shared rounded shape for settings text fields. */
val settingsTextFieldShape: Shape = RoundedCornerShape(12.dp)
