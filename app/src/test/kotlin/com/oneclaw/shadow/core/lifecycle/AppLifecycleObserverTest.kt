package com.oneclaw.shadow.core.lifecycle

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppLifecycleObserverTest {

    private lateinit var observer: AppLifecycleObserver

    @BeforeEach
    fun setUp() {
        observer = AppLifecycleObserver()
    }

    @Test
    fun `initial isInForeground is false`() {
        assertFalse(observer.isInForeground)
    }

    @Test
    fun `after onStart isInForeground is true`() {
        val owner = mockk<androidx.lifecycle.LifecycleOwner>(relaxed = true)
        observer.onStart(owner)
        assertTrue(observer.isInForeground)
    }

    @Test
    fun `after onStop isInForeground is false`() {
        val owner = mockk<androidx.lifecycle.LifecycleOwner>(relaxed = true)
        observer.onStart(owner)
        observer.onStop(owner)
        assertFalse(observer.isInForeground)
    }

    @Test
    fun `onStart then onStop then onStart correctly toggles state`() {
        val owner = mockk<androidx.lifecycle.LifecycleOwner>(relaxed = true)
        observer.onStart(owner)
        assertTrue(observer.isInForeground)
        observer.onStop(owner)
        assertFalse(observer.isInForeground)
        observer.onStart(owner)
        assertTrue(observer.isInForeground)
    }
}
