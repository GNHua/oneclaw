package com.tomandy.oneclaw.google

import android.util.Log
import com.tomandy.oneclaw.engine.GoogleAuthProvider

/**
 * Delegates to whichever Google auth method is signed in.
 *
 * Priority: BYOK OAuth first, then Play Services.
 *
 * BYOK reliably grants all requested scopes (including restricted ones
 * like Gmail) because users consent directly via their own GCP project.
 * Play Services may not grant restricted scopes for unverified apps,
 * so it serves as a convenient fallback when BYOK isn't configured.
 */
class CompositeGoogleAuthProvider(
    private val playServices: GoogleAuthManager,
    private val byok: OAuthGoogleAuthManager
) : GoogleAuthProvider {

    companion object {
        private const val TAG = "CompositeGoogleAuth"
    }

    override suspend fun isSignedIn(): Boolean {
        return byok.isSignedIn() || playServices.isSignedIn()
    }

    override suspend fun getAccessToken(): String? {
        if (byok.isSignedIn()) {
            val token = byok.getAccessToken()
            if (token != null) return token
            Log.w(TAG, "BYOK signed in but token was null, trying Play Services")
        }
        if (playServices.isSignedIn()) {
            return playServices.getAccessToken()
        }
        return null
    }

    override suspend fun getAccountEmail(): String? {
        if (byok.isSignedIn()) {
            return byok.getAccountEmail()
        }
        return playServices.getAccountEmail()
    }
}
