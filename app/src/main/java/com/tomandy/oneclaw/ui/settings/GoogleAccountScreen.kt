package com.tomandy.oneclaw.ui.settings

import android.app.Activity
import android.util.Log
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.ui.theme.Dimens
import com.tomandy.oneclaw.google.GoogleAuthManager
import com.tomandy.oneclaw.google.OAuthGoogleAuthManager
import kotlinx.coroutines.launch

@Composable
fun GoogleAccountScreen(
    playServicesAuthManager: GoogleAuthManager,
    oauthAuthManager: OAuthGoogleAuthManager,
    onSignInChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Play Services state
    var psSignedIn by remember { mutableStateOf(false) }
    var psEmail by remember { mutableStateOf<String?>(null) }

    // BYOK state
    var hasCredentials by remember { mutableStateOf(false) }
    var byokSignedIn by remember { mutableStateOf(false) }
    var byokEmail by remember { mutableStateOf<String?>(null) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    // BYOK credential input fields
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var authorizing by remember { mutableStateOf(false) }
    var editingCredentials by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        psSignedIn = playServicesAuthManager.isSignedIn()
        psEmail = playServicesAuthManager.getAccountEmail()
        hasCredentials = oauthAuthManager.hasOAuthCredentials()
        byokSignedIn = oauthAuthManager.isSignedIn()
        byokEmail = oauthAuthManager.getAccountEmail()
    }

    val isConnected = psSignedIn || byokSignedIn
    val connectedEmail = if (byokSignedIn) byokEmail else psEmail

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status card
        if (isConnected) {
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
                        text = if (connectedEmail != null) "Connected: $connectedEmail" else "Connected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val method = if (byokSignedIn) "Custom OAuth" else "Play Services"
                    Text(
                        text = "Google Workspace plugins are available (via $method)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            PermissionsCard()
        } else {
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
                        text = "Optional. Only needed for Google Workspace plugins (Gmail, Calendar, Drive, Tasks, Contacts, Docs, Sheets, Slides, Forms). Only one sign-in method is needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Unverified app warning (applies to both sign-in methods)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "About the \"unverified app\" warning",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "During sign-in, Google may show a warning saying \"Google hasn't verified this app.\" This is expected. Click \"Advanced\" then \"Go to OneClaw (unsafe)\" to proceed. This is safe -- OneClaw is fully on-device with no backend server, so your data never leaves your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // -- Play Services section --
        Text(
            text = "Option 1: Google Sign-In",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        PlayServicesCard(
            authManager = playServicesAuthManager,
            isSignedIn = psSignedIn,
            email = psEmail,
            errorMessage = errorMessage,
            onError = { errorMessage = it },
            onSignedIn = { email ->
                psSignedIn = true
                psEmail = email
                errorMessage = null
                onSignInChanged(true)
            },
            onSignedOut = {
                psSignedIn = false
                psEmail = null
                if (!byokSignedIn) onSignInChanged(false)
            }
        )

        // Divider between sections
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // -- BYOK OAuth section --
        Text(
            text = "Option 2: Custom OAuth (Advanced)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        ByokSection(
            oauthAuthManager = oauthAuthManager,
            hasCredentials = hasCredentials,
            isSignedIn = byokSignedIn,
            email = byokEmail,
            clientId = clientId,
            clientSecret = clientSecret,
            saving = saving,
            authorizing = authorizing,
            editingCredentials = editingCredentials,
            onClientIdChange = { clientId = it },
            onClientSecretChange = { clientSecret = it },
            onError = { errorMessage = it },
            onCredentialsSaved = {
                hasCredentials = true
                editingCredentials = false
                errorMessage = null
            },
            onSavingChange = { saving = it },
            onAuthorizingChange = { authorizing = it },
            onSignedIn = { email ->
                byokSignedIn = true
                byokEmail = email
                errorMessage = null
                onSignInChanged(true)
            },
            onEditCredentials = {
                scope.launch {
                    clientId = oauthAuthManager.getClientId() ?: ""
                    clientSecret = oauthAuthManager.getClientSecret() ?: ""
                    editingCredentials = true
                }
            },
            onCancelEdit = {
                editingCredentials = false
                clientId = ""
                clientSecret = ""
            },
            onCredentialsCleared = {
                hasCredentials = false
                byokSignedIn = false
                byokEmail = null
                clientId = ""
                clientSecret = ""
                editingCredentials = false
            },
            onSignedOut = {
                byokSignedIn = false
                byokEmail = null
                if (!psSignedIn) onSignInChanged(false)
            }
        )

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
private fun PlayServicesCard(
    authManager: GoogleAuthManager,
    isSignedIn: Boolean,
    email: String?,
    errorMessage: String?,
    onError: (String?) -> Unit,
    onSignedIn: (String?) -> Unit,
    onSignedOut: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                authManager.handleSignInResult(result.data)
                scope.launch {
                    val accountEmail = authManager.getAccountEmail()
                    onSignedIn(accountEmail)
                }
            } catch (e: Exception) {
                Log.e("GoogleAccountScreen", "Play Services sign-in failed", e)
                onError("Sign-in failed: ${e.message}")
            }
        } else {
            onError("Sign-in was cancelled")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "Sign in with your Google account. No setup required.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Note: This method has a limited daily sign-in quota shared across all users, and some Google plugins (e.g. Gmail) may not work due to scope restrictions. If sign-in fails or plugins return errors, use Custom OAuth below instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (isSignedIn) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                authManager.signOut()
                                onSignedOut()
                            } catch (e: Exception) {
                                onError("Sign-out failed: ${e.message}")
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
                        onError(null)
                        signInLauncher.launch(authManager.getSignInIntent())
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign in with Google")
                }
            }
        }
    }
}

@Composable
private fun ByokSection(
    oauthAuthManager: OAuthGoogleAuthManager,
    hasCredentials: Boolean,
    isSignedIn: Boolean,
    email: String?,
    clientId: String,
    clientSecret: String,
    saving: Boolean,
    authorizing: Boolean,
    editingCredentials: Boolean,
    onClientIdChange: (String) -> Unit,
    onClientSecretChange: (String) -> Unit,
    onError: (String?) -> Unit,
    onCredentialsSaved: () -> Unit,
    onSavingChange: (Boolean) -> Unit,
    onAuthorizingChange: (Boolean) -> Unit,
    onSignedIn: (String?) -> Unit,
    onEditCredentials: () -> Unit,
    onCancelEdit: () -> Unit,
    onCredentialsCleared: () -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var secretVisible by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "Bring your own GCP OAuth client credentials. No quota limits and full control over your tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (isSignedIn && hasCredentials && !editingCredentials) {
                Text(
                    text = if (email != null) "Connected: $email" else "Connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                oauthAuthManager.signOut()
                                onSignedOut()
                            } catch (e: Exception) {
                                onError("Sign-out failed: ${e.message}")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Disconnect Custom OAuth")
                }
            } else if (hasCredentials && !isSignedIn && !editingCredentials) {
                Text(
                    text = "OAuth credentials configured. Authorize to connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            onAuthorizingChange(true)
                            onError(null)
                            try {
                                val error = oauthAuthManager.authorize { intent ->
                                    context.startActivity(intent)
                                }
                                if (error == null) {
                                    val accountEmail = oauthAuthManager.getAccountEmail()
                                    onSignedIn(accountEmail)
                                } else {
                                    onError(error)
                                }
                            } catch (e: Exception) {
                                onError("Failed to start authorization: ${e.message}")
                            } finally {
                                onAuthorizingChange(false)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !authorizing
                ) {
                    Text(if (authorizing) "Waiting for authorization..." else "Authorize with Google")
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "If authorization fails, return to this screen and try again. The first attempt may fail due to a network timing issue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onEditCredentials,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Change OAuth Credentials")
                }
            } else {
                // Show credential input (new setup or editing existing)
                if (!editingCredentials) {
                    // Setup instructions (only for first-time setup)
                    Spacer(Modifier.height(4.dp))
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
                    Text(text = step1, style = bodySmall)
                    val steps = listOf(
                        "2. Go to APIs & Services > Library > search and enable each API:",
                        "   Gmail API, Google Calendar API, Google Tasks API,",
                        "   People API, Drive API, Docs API, Sheets API, Slides API, Forms API",
                        "3. Go to APIs & Services > OAuth consent screen",
                        "4. Under Branding: set an app name, user support email, and developer email",
                        "5. Under Audience: select External, then click Publish App",
                        "   (this keeps your refresh tokens valid indefinitely)",
                        "6. Go to APIs & Services > Credentials > + Create Credentials > OAuth client ID",
                        "7. Set Application type to \"Desktop app\", give it any name, click Create",
                        "8. Copy the Client ID and Client Secret below"
                    )
                    steps.forEach { step ->
                        Text(text = step, style = bodySmall, color = textColor)
                    }

                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = clientId,
                    onValueChange = onClientIdChange,
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
                    onValueChange = onClientSecretChange,
                    label = { Text("Client Secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !saving,
                    visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { secretVisible = !secretVisible }) {
                            Icon(
                                imageVector = if (secretVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (secretVisible) "Hide secret" else "Show secret"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        onSavingChange(true)
                        scope.launch {
                            try {
                                oauthAuthManager.saveOAuthCredentials(clientId, clientSecret)
                                onCredentialsSaved()
                            } catch (e: Exception) {
                                onError("Failed to save credentials: ${e.message}")
                            } finally {
                                onSavingChange(false)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = clientId.isNotBlank() && clientSecret.isNotBlank() && !saving
                ) {
                    Text(if (saving) "Saving..." else "Save Credentials")
                }

                if (editingCredentials) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onCancelEdit,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                }
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
