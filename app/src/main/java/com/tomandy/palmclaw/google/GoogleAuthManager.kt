package com.tomandy.palmclaw.google

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.tomandy.palmclaw.engine.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Manages Google authentication using Google Sign-In (Play Services).
 *
 * Users see the standard Google account picker and consent screen.
 * Token management is handled entirely by Play Services -- no manual
 * refresh token storage needed.
 *
 * Setup (developer, one-time):
 *   1. Create GCP project, enable Gmail API + Calendar API
 *   2. Configure OAuth consent screen
 *   3. Create "Android" OAuth client with package name + SHA-1
 *
 * This is a singleton managed by Koin.
 */
class GoogleAuthManager(
    private val context: Context
) : GoogleAuthProvider {

    companion object {
        private const val TAG = "GoogleAuthManager"

        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/gmail.modify",
            "https://www.googleapis.com/auth/calendar"
        )

        private val SCOPE_STRING = "oauth2:" + SCOPES.joinToString(" ")
    }

    private val signInOptions: GoogleSignInOptions = GoogleSignInOptions.Builder(
        GoogleSignInOptions.DEFAULT_SIGN_IN
    )
        .requestEmail()
        .requestScopes(Scope(SCOPES[0]), *SCOPES.drop(1).map { Scope(it) }.toTypedArray())
        .build()

    val signInClient: GoogleSignInClient = GoogleSignIn.getClient(context, signInOptions)

    /**
     * Get the intent to launch the Google Sign-In flow.
     */
    fun getSignInIntent(): Intent = signInClient.signInIntent

    /**
     * Handle the sign-in result from the activity result.
     * Throws ApiException if sign-in failed.
     */
    fun handleSignInResult(data: Intent?) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        task.getResult(ApiException::class.java)
        Log.i(TAG, "Google Sign-In successful")
    }

    override suspend fun getAccessToken(): String? {
        val account = GoogleSignIn.getLastSignedInAccount(context)?.account ?: return null
        return withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.getToken(context, account, SCOPE_STRING)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get access token", e)
                // Token might be stale -- clear and retry once
                try {
                    val staleToken = GoogleAuthUtil.getToken(context, account, SCOPE_STRING)
                    GoogleAuthUtil.clearToken(context, staleToken)
                    GoogleAuthUtil.getToken(context, account, SCOPE_STRING)
                } catch (retryEx: Exception) {
                    Log.e(TAG, "Retry also failed", retryEx)
                    null
                }
            }
        }
    }

    override suspend fun isSignedIn(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    override suspend fun getAccountEmail(): String? {
        return GoogleSignIn.getLastSignedInAccount(context)?.email
    }

    /**
     * Sign out and disconnect the Google account.
     */
    suspend fun signOut() {
        suspendCoroutine { cont ->
            signInClient.revokeAccess()
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }
}
