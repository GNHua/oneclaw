package com.oneclaw.shadow.tool.builtin

import android.content.Context
import android.util.Log
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class PdfInfoToolTest {

    private lateinit var context: Context
    private lateinit var tool: PdfInfoTool
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
        tool = PdfInfoTool(context)
        tempDir = createTempDir("pdf_info_test")
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
        tempDir.deleteRecursively()
    }

    private fun createTestPdf(fileName: String, pages: Int = 2, title: String? = null, author: String? = null): File {
        val file = File(tempDir, fileName)
        val doc = PDDocument()
        repeat(pages) { doc.addPage(PDPage()) }
        if (title != null || author != null) {
            val info = doc.documentInformation
            title?.let { info.title = it }
            author?.let { info.author = it }
        }
        doc.save(file)
        doc.close()
        return file
    }

    @Test
    fun testDefinition() {
        val def = tool.definition
        assertEquals("pdf_info", def.name)
        assertTrue(def.parametersSchema.required.contains("path"))
        assertTrue(def.parametersSchema.properties.containsKey("path"))
        assertTrue(def.requiredPermissions.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertEquals(15, def.timeoutSeconds)
    }

    @Test
    fun testExecute_missingPath() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("path", ignoreCase = true))
    }

    @Test
    fun testExecute_fileNotFound() = runTest {
        val result = tool.execute(mapOf("path" to "/nonexistent/path/file.pdf"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("file_not_found", result.errorType)
        assertTrue(result.errorMessage!!.contains("File not found"))
    }

    @Test
    fun testExecute_validPdf_basicInfo() = runTest {
        val pdfFile = createTestPdf("test.pdf", pages = 3)

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        val output = result.result!!
        assertTrue(output.contains("File: test.pdf"), "Should contain file name: $output")
        assertTrue(output.contains("Path:"), "Should contain path label: $output")
        assertTrue(output.contains("Pages: 3"), "Should contain page count: $output")
        assertTrue(output.contains("File size:"), "Should contain file size: $output")
    }

    @Test
    fun testExecute_validPdf_withMetadata() = runTest {
        val pdfFile = createTestPdf("meta.pdf", pages = 1, title = "Test Title", author = "Test Author")

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        val output = result.result!!
        assertTrue(output.contains("Title: Test Title"), "Should contain title: $output")
        assertTrue(output.contains("Author: Test Author"), "Should contain author: $output")
    }

    @Test
    fun testExecute_validPdf_singlePage() = runTest {
        val pdfFile = createTestPdf("single.pdf", pages = 1)

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Pages: 1"))
    }
}
