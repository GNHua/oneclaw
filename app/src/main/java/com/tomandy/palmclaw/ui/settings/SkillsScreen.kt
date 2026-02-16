package com.tomandy.palmclaw.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.skill.SkillEntry
import com.tomandy.palmclaw.skill.SkillPreferences
import com.tomandy.palmclaw.skill.SkillRepository
import com.tomandy.palmclaw.skill.SkillSource

@Composable
fun SkillsScreen(
    skillRepository: SkillRepository,
    skillPreferences: SkillPreferences,
    onSkillToggled: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val skills by skillRepository.skills.collectAsState()

    if (skills.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No skills available",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(skills, key = { it.metadata.name }) { skill ->
                SkillCard(
                    skill = skill,
                    enabled = skillPreferences.isSkillEnabled(skill.metadata.name),
                    onToggle = { enabled ->
                        onSkillToggled(skill.metadata.name, enabled)
                    }
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillEntry,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = skill.metadata.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (skill.source == SkillSource.BUNDLED) "Built-in" else "User",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )
                }
                Text(
                    text = skill.metadata.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = skill.metadata.command,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}
