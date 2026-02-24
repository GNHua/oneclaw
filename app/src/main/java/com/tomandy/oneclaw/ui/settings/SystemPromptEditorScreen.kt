package com.tomandy.oneclaw.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomandy.oneclaw.ui.chat.ChatMarkdown
import com.tomandy.oneclaw.ui.drawColumnScrollbar
import com.tomandy.oneclaw.ui.theme.settingsTextFieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptEditorScreen(
    viewModel: AgentProfilesViewModel,
    onNavigateBack: () -> Unit
) {
    var showRaw by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Prompt") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRaw = !showRaw }) {
                        Icon(
                            imageVector = if (showRaw)
                                Icons.Default.Description
                            else
                                Icons.Default.Code,
                            contentDescription = if (showRaw) "Show rendered" else "Show raw"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            if (showRaw) {
                OutlinedTextField(
                    value = viewModel.draftSystemPrompt,
                    onValueChange = { viewModel.draftSystemPrompt = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    placeholder = { Text("Enter system prompt...") },
                    colors = settingsTextFieldColors()
                )
            } else {
                val scrollState = rememberScrollState()
                val scrollbarColor =
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                ChatMarkdown(
                    text = viewModel.draftSystemPrompt,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawColumnScrollbar(scrollState, scrollbarColor)
                        .verticalScroll(scrollState)
                        .padding(16.dp)
                )
            }
        }
    }
}
