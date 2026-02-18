package com.tomandy.palmclaw.scheduler.receiver

import android.content.Context
import android.content.Intent
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class BootCompletedReceiverTest {

    @Test
    fun `receiver ignores non-BOOT_COMPLETED actions`() {
        val receiver = BootCompletedReceiver()
        val context = mockk<Context>(relaxed = true)
        val intent = Intent("com.example.SOME_OTHER_ACTION")

        receiver.onReceive(context, intent)

        verify(exactly = 0) { context.getSystemService(any<String>()) }
    }
}
