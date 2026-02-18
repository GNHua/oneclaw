package com.tomandy.oneclaw.ui.settings

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.google.OAuthGoogleAuthManager
import kotlinx.coroutines.launch

@Composable
fun GoogleAccountScreen(
    googleAuthManager: OAuthGoogleAuthManager,
    onSignInChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCredentials by remember { mutableStateOf(false) }
    var isSignedIn by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Credential input fields
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var authorizing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasCredentials = googleAuthManager.hasOAuthCredentials()
        isSignedIn = googleAuthManager.isSignedIn()
        email = googleAuthManager.getAccountEmail()
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
                            onSignInChanged(false)
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
                        authorizing = true
                        errorMessage = null
                        try {
                            val error = googleAuthManager.authorize { intent ->
                                context.startActivity(intent)
                            }
                            if (error == null) {
                                isSignedIn = true
                                email = googleAuthManager.getAccountEmail()
                                onSignInChanged(true)
                            } else {
                                errorMessage = error
                            }
                        } catch (e: Exception) {
                            errorMessage = "Failed to start authorization: ${e.message}"
                        } finally {
                            authorizing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !authorizing
            ) {
                Text(if (authorizing) "Waiting for authorization..." else "Authorize with Google")
            }

            // Option to clear credentials and re-enter
            OutlinedButton(
                onClick = {
                    scope.launch {
                        googleAuthManager.signOut()
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
                    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    val linkColor = MaterialTheme.colorScheme.primary
                    val bodySmall = MaterialTheme.typography.bodySmall
                    val step1 = buildAnnotatedString {
                        withStyle(SpanStyle(color = textColor)) {
                            append("1. Go to ")
                        }
                        pushLink(LinkAnnotation.Url("https://console.cloud.google.com"))
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append("console.cloud.google.com")
                        }
                        pop()
                        withStyle(SpanStyle(color = textColor)) {
                            append(" and create a project")
                        }
                    }
                    Text(
                        text = step1,
                        style = bodySmall
                    )
                    val steps = listOf(
                        "2. Go to APIs & Services > Library > search and enable each API:",
                        "   Gmail API, Google Calendar API, Google Tasks API,",
                        "   People API, Drive API, Docs API, Sheets API, Slides API, Forms API",
                        "3. Go to APIs & Services > OAuth consent screen",
                        "4. Under Branding: set an app name, user support email, and developer email",
                        "5. Under Audience: select External, then click Publish App",
                        "   (keeps tokens valid indefinitely; shows a warning screen during sign-in which is fine for personal use)",
                        "6. Go to APIs & Services > Credentials > + Create Credentials > OAuth client ID",
                        "7. Set Application type to \"Desktop app\", give it any name, click Create",
                        "8. Copy the Client ID and Client Secret below"
                    )
                    steps.forEach { step ->
                        Text(
                            text = step,
                            style = bodySmall,
                            color = textColor
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
