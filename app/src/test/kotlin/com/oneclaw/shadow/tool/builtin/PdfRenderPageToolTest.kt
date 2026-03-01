package com.oneclaw.shadow.tool.builtin

import android.content.Context
import android.util.Log
import com.oneclaw.shadow.core.model.ToolResultStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class PdfRenderPageToolTest {

    private lateinit var context: Context
    private lateinit var tool: PdfRenderPageTool
    private lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0

        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        tempDir = createTempDir("pdf_render_test")
        every { context.filesDir } returns tempDir
        tool = PdfRenderPageTool(context)
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
        tempDir.deleteRecursively()
    }

    @Test
    fun testDefinition() {
        val def = tool.definition
        assertEquals("pdf_render_page", def.name)
        assertTrue(def.parametersSchema.required.contains("path"))
        assertTrue(def.parametersSchema.required.contains("page"))
        assertFalse(def.parametersSchema.required.contains("dpi"))
        assertTrue(def.parametersSchema.properties.containsKey("path"))
        assertTrue(def.parametersSchema.properties.containsKey("page"))
        assertTrue(def.parametersSchema.properties.containsKey("dpi"))
        assertTrue(def.requiredPermissions.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertEquals(30, def.timeoutSeconds)
    }

    @Test
    fun testExecute_missingPath() = runTest {
        val result = tool.execute(mapOf("page" to 1))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("path", ignoreCase = true))
    }

    @Test
    fun testExecute_missingPage() = runTest {
        val result = tool.execute(mapOf("path" to "/some/file.pdf"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("page", ignoreCase = true))
    }

    @Test
    fun testExecute_fileNotFound() = runTest {
        val result = tool.execute(mapOf("path" to "/nonexistent/path/file.pdf", "page" to 1))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("file_not_found", result.errorType)
        assertTrue(result.errorMessage!!.contains("File not found"))
    }

    @Test
    fun testExecute_nonPdfFile_returnsError() = runTest {
        // Create a non-PDF file to trigger an error from Android PdfRenderer
        val fakeFile = File(tempDir, "fake.pdf")
        fakeFile.writeText("this is not a PDF")

        val result = tool.execute(mapOf("path" to fakeFile.absolutePath, "page" to 1))

        // Android PdfRenderer will throw an exception for non-PDF files in JVM tests
        assertEquals(ToolResultStatus.ERROR, result.status)
        // Should be pdf_error since the file exists but is not valid
        assertEquals("pdf_error", result.errorType)
    }

    @Test
    fun testDefinition_dpiDescription() {
        val dpiParam = tool.definition.parametersSchema.properties["dpi"]!!
        assertTrue(
            dpiParam.description.contains("150"),
            "DPI description should mention default 150"
        )
        assertTrue(
            dpiParam.description.contains("72"),
            "DPI description should mention minimum 72"
        )
        assertTrue(
            dpiParam.description.contains("300"),
            "DPI description should mention maximum 300"
        )
    }

    @Test
    fun testDefinition_pageDescription() {
        val pageParam = tool.definition.parametersSchema.properties["page"]!!
        assertTrue(
            pageParam.description.contains("1"),
            "Page description should mention 1-based indexing"
        )
    }

    @Test
    fun testExecute_bothParamsMissing() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }
}
