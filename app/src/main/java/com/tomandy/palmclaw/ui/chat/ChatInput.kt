package com.tomandy.palmclaw.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Chat input field component with send button.
 *
 * Features:
 * - Multi-line text input (max 4 lines)
 * - Send button enabled only when text is not blank
 * - IME action for keyboard send
 * - Disabled state during message processing
 * - Rounded corners for modern appearance
 * - Material3 design system
 * - Keyboard integration with ImeAction.Send
 *
 * @param value Current input text value
 * @param onValueChange Callback when text changes
 * @param onSend Callback when send button is clicked
 * @param enabled Whether input is enabled (false during processing)
 * @param modifier Modifier for styling
 */
@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a message...") },
            enabled = enabled,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = { if (value.isNotBlank()) onSend() }
            ),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onSend,
            enabled = enabled && value.isNotBlank()
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send message",
                tint = if (enabled && value.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
