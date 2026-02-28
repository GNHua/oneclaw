package com.oneclaw.shadow.feature.skill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.oneclaw.shadow.core.model.SkillDefinition

/**
 * Floating popup shown above the chat input when the user types "/".
 * Displays matching skills for autocomplete.
 * RFC-014
 */
@Composable
fun SlashCommandPopup(
    skills: List<SkillDefinition>,
    onSkillSelected: (SkillDefinition) -> Unit,
    modifier: Modifier = Modifier
) {
    if (skills.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        skills.forEachIndexed { index, skill ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSkillSelected(skill) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Column {
                    Text(
                        text = skill.displayName,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
