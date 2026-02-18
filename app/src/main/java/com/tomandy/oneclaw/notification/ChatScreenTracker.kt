package com.tomandy.oneclaw.notification

/**
 * Tracks the currently visible chat conversation ID.
 * Set by ChatScreen's DisposableEffect, read by ChatNotificationHelper.
 *
 * When null, the user is not viewing any chat conversation
 * (on another screen, or app is in background).
 */
object ChatScreenTracker {
    @Volatile
    var activeConversationId: String? = null
}
