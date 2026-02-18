package com.tomandy.palmclaw.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.google.OAuthGoogleAuthManager
import kotlinx.coroutines.launch

@Composable
fun GoogleAccountScreen(
    googleAuthManager: OAuthGoogleAuthManager,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var hasCredentials by remember { mutableStateOf(false) }
    var isSignedIn by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Credential input fields
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasCredentials = googleAuthManager.hasOAuthCredentials()
        isSignedIn = googleAuthManager.isSignedIn()
        email = googleAuthManager.getAccountEmail()
    }

    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null) {
            scope.launch {
                val success = googleAuthManager.handleAuthorizationResponse(data)
                if (success) {
                    isSignedIn = true
                    email = googleAuthManager.getAccountEmail()
                    errorMessage = null
                } else {
                    errorMessage = "Authorization failed. Check your Client ID and Secret."
                }
            }
        } else {
            errorMessage = "Authorization cancelled."
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Phase 3: Connected
        if (isSignedIn && hasCredentials) {
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
                }
            }

            // Permissions info
            PermissionsCard()

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
        }
        // Phase 2: Credentials saved, not authorized yet
        else if (hasCredentials && !isSignedIn) {
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
                    Text(
                        text = "OAuth credentials configured. Authorize to connect your Google account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        try {
                            val intent = googleAuthManager.buildAuthorizationIntent()
                            authLauncher.launch(intent)
                        } catch (e: Exception) {
                            errorMessage = "Failed to start authorization: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Authorize with Google")
            }

            // Option to clear credentials and re-enter
            OutlinedButton(
                onClick = {
                    scope.launch {
                        googleAuthManager.signOut()
                        // Clear client credentials too by saving empty
                        hasCredentials = false
                        isSignedIn = false
                        email = null
                        clientId = ""
                        clientSecret = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Change OAuth Credentials")
            }
        }
        // Phase 1: No credentials configured
        else {
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

            // Setup instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = "Setup Instructions",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    val steps = listOf(
                        "1. Go to console.cloud.google.com and create a project",
                        "2. Enable the APIs you need (Gmail, Calendar, Drive, etc.)",
                        "3. Configure the OAuth consent screen (External, Testing mode)",
                        "4. Add your Google account as a test user",
                        "5. Create an OAuth Client ID (type: Web application)",
                        "6. Add redirect URI: com.tomandy.palmclaw:/oauth2callback",
                        "7. Copy the Client ID and Client Secret below"
                    )
                    steps.forEach { step ->
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Credential input
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = "OAuth Credentials",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        label = { Text("Client ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !saving,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        label = { Text("Client Secret") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !saving,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    googleAuthManager.saveOAuthCredentials(clientId, clientSecret)
                                    hasCredentials = true
                                    errorMessage = null
                                } catch (e: Exception) {
                                    errorMessage = "Failed to save credentials: ${e.message}"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = clientId.isNotBlank() && clientSecret.isNotBlank() && !saving
                    ) {
                        Text(if (saving) "Saving..." else "Save Credentials")
                    }
                }
            }
        }

        // Play Services Sign-In (pending approval)
        PlayServicesCard()

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
    }
}

@Composable
private fun PlayServicesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "Google Sign-In (Play Services)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Pending Google verification. This method uses Play Services for seamless sign-in without needing your own OAuth credentials, but requires Google to approve the app for restricted scopes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                enabled = false
            ) {
                Text("Sign in with Google")
            }
        }
    }
}

@Composable
private fun PermissionsCard() {
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
