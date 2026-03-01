package com.oneclaw.shadow.data.storage

import io.mockk.mockk
import java.io.File

/**
 * Test double for UserFileStorage that overrides rootDir to use a real temp directory
 * instead of an Android Context.
 */
class TestableUserFileStorage(rootParentDir: File) : UserFileStorage(mockk(relaxed = true)) {

    private val testRoot: File = File(rootParentDir, "user_files").also { it.mkdirs() }

    override val rootDir: File
        get() = testRoot
}
