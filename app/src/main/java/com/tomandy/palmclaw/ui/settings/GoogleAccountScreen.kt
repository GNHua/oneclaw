package com.tomandy.palmclaw.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.api.ApiException
import com.tomandy.palmclaw.google.GoogleAuthManager
import kotlinx.coroutines.launch

@Composable
fun GoogleAccountScreen(
    googleAuthManager: GoogleAuthManager,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isSignedIn by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isSignedIn = googleAuthManager.isSignedIn()
        email = googleAuthManager.getAccountEmail()
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            googleAuthManager.handleSignInResult(result.data)
            scope.launch {
                isSignedIn = googleAuthManager.isSignedIn()
                email = googleAuthManager.getAccountEmail()
                errorMessage = null
            }
        } catch (e: ApiException) {
            errorMessage = "Sign-in failed (code ${e.statusCode}): ${e.message}"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                if (isSignedIn && email != null) {
                    Text(
                        text = "Connected: $email",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Google Workspace plugins are available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Not connected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Optional. Only needed for Google Workspace plugins (Gmail, Calendar, Drive, Tasks, Contacts, Docs, Sheets, Slides, Forms).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Sign in / Sign out
        if (isSignedIn) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            googleAuthManager.signOut()
                            isSignedIn = false
                            email = null
                        } catch (e: Exception) {
                            errorMessage = "Sign-out failed: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Disconnect Google Account")
            }
        } else {
            Button(
                onClick = {
                    signInLauncher.launch(googleAuthManager.getSignInIntent())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign in with Google")
            }
        }

        // Error display
        errorMessage?.let { msg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Scopes info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "Requested Permissions",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                val permissions = listOf(
                    "Gmail -- read, send, and manage messages, labels, drafts, filters, and settings",
                    "Calendar -- view and manage events, free/busy, and calendars",
                    "Tasks -- view and manage task lists and tasks",
                    "Contacts -- view and manage contacts",
                    "Drive -- view and manage files and folders",
                    "Docs -- view and edit documents",
                    "Sheets -- view and edit spreadsheets",
                    "Slides -- view and edit presentations",
                    "Forms -- view form structure and responses (read-only)"
                )
                permissions.forEach { perm ->
                    Text(
                        "- $perm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
