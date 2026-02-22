package com.tomandy.oneclaw.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.skill.SkillEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillPickerSheet(
    skills: List<SkillEntry>,
    onSkillSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Text(
            text = "Skills",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )

        LazyColumn {
            items(skills, key = { it.metadata.name }) { skill ->
                ListItem(
                    headlineContent = { Text(skill.metadata.name) },
                    supportingContent = {
                        if (skill.metadata.description.isNotBlank()) {
                            Text(skill.metadata.description)
                        }
                    },
                    modifier = Modifier.clickable {
                        onSkillSelected(skill.metadata.command)
                        onDismiss()
                    }
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}
