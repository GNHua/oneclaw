package com.tomandy.oneclaw.workspace

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class WorkspaceOperationsTest {

    private lateinit var workspaceRoot: File
    private lateinit var ops: WorkspaceOperations

    @Before
    fun setUp() {
        workspaceRoot = createTempDir("workspace-test")
        ops = WorkspaceOperations(workspaceRoot, shellPath = "/bin/sh")
    }

    @After
    fun tearDown() {
        workspaceRoot.deleteRecursively()
    }

    // --- resolveSafePath ---

    @Test
    fun `resolveSafePath accepts simple relative path`() {
        val file = ops.resolveSafePath("hello.txt")
        assertEquals(File(workspaceRoot, "hello.txt").canonicalPath, file.path)
    }

    @Test
    fun `resolveSafePath accepts nested relative path`() {
        val file = ops.resolveSafePath("sub/dir/file.txt")
        assertTrue(file.path.startsWith(workspaceRoot.canonicalPath))
    }

    @Test(expected = SecurityException::class)
    fun `resolveSafePath rejects absolute path`() {
        ops.resolveSafePath("/etc/passwd")
    }

    @Test(expected = SecurityException::class)
    fun `resolveSafePath rejects dot-dot traversal`() {
        ops.resolveSafePath("../outside.txt")
    }

    @Test(expected = SecurityException::class)
    fun `resolveSafePath rejects nested dot-dot traversal`() {
        ops.resolveSafePath("sub/../../outside.txt")
    }

    @Test
    fun `resolveSafePath allows dot-dot that stays within workspace`() {
        val file = ops.resolveSafePath("sub/../file.txt")
        assertEquals(File(workspaceRoot, "file.txt").canonicalPath, file.path)
    }

    // --- readFile ---

    @Test
    fun `readFile returns content with line numbers`() {
        val file = File(workspaceRoot, "test.txt")
        file.writeText("line one\nline two\nline three")

        val result = ops.readFile(file)

        assertTrue(result.content.contains("1 | line one"))
        assertTrue(result.content.contains("2 | line two"))
        assertTrue(result.content.contains("3 | line three"))
        assertEquals(3, result.totalLines)
        assertFalse(result.truncated)
    }

    @Test
    fun `readFile with offset skips lines`() {
        val file = File(workspaceRoot, "test.txt")
        file.writeText("a\nb\nc\nd\ne")

        val result = ops.readFile(file, offset = 3, limit = 2)

        assertTrue(result.content.contains("3 | c"))
        assertTrue(result.content.contains("4 | d"))
        assertFalse(result.content.contains("1 | a"))
        assertFalse(result.content.contains("5 | e"))
        assertEquals(5, result.totalLines)
        assertTrue(result.truncated)
    }

    @Test
    fun `readFile truncates at line limit`() {
        val file = File(workspaceRoot, "test.txt")
        val lines = (1..100).joinToString("\n") { "line $it" }
        file.writeText(lines)

        val result = ops.readFile(file, limit = 10)

        assertEquals(100, result.totalLines)
        assertTrue(result.truncated)
        assertTrue(result.content.contains("10 | line 10"))
        assertFalse(result.content.contains("11 | line 11"))
    }

    @Test
    fun `readFile handles empty file`() {
        val file = File(workspaceRoot, "empty.txt")
        file.writeText("")

        val result = ops.readFile(file)

        assertEquals(0, result.totalLines)
    }

    @Test
    fun `readFile truncates at byte limit`() {
        val file = File(workspaceRoot, "large.txt")
        // Create lines that are large enough to hit 20KB limit
        val bigLine = "x".repeat(500)
        val lines = (1..100).joinToString("\n") { bigLine }
        file.writeText(lines)

        val result = ops.readFile(file)

        assertTrue(result.truncated)
        assertTrue(result.bytesRead <= WorkspaceOperations.MAX_READ_BYTES)
    }

    // --- writeFile ---

    @Test
    fun `writeFile creates file with content`() {
        val file = ops.resolveSafePath("new.txt")
        val bytes = ops.writeFile(file, "hello world")

        assertEquals("hello world", file.readText())
        assertEquals(11L, bytes)
    }

    @Test
    fun `writeFile creates parent directories`() {
        val file = ops.resolveSafePath("deep/nested/dir/file.txt")
        ops.writeFile(file, "content")

        assertTrue(file.exists())
        assertEquals("content", file.readText())
    }

    @Test
    fun `writeFile overwrites existing file`() {
        val file = ops.resolveSafePath("existing.txt")
        file.parentFile?.mkdirs()
        file.writeText("old content")

        ops.writeFile(file, "new content")

        assertEquals("new content", file.readText())
    }

    // --- editFile ---

    @Test
    fun `editFile replaces exact match`() {
        val file = File(workspaceRoot, "code.kt")
        file.writeText("fun hello() {\n    println(\"Hello\")\n}")

        val result = ops.editFile(file, "println(\"Hello\")", "println(\"World\")")

        assertTrue(result is EditResult.Success)
        val success = result as EditResult.Success
        assertTrue(success.diff.contains("-"))
        assertTrue(success.diff.contains("+"))
        assertEquals("fun hello() {\n    println(\"World\")\n}", file.readText())
    }

    @Test
    fun `editFile returns NoMatch when text not found`() {
        val file = File(workspaceRoot, "code.kt")
        file.writeText("fun hello() {}")

        val result = ops.editFile(file, "nonexistent text", "replacement")

        assertTrue(result is EditResult.NoMatch)
    }

    @Test
    fun `editFile rejects multiple occurrences`() {
        val file = File(workspaceRoot, "code.kt")
        file.writeText("val x = 1\nval y = 1\nval z = 1")

        val result = ops.editFile(file, "val", "var")

        assertTrue(result is EditResult.NoMatch)
        val noMatch = result as EditResult.NoMatch
        assertTrue(noMatch.message.contains("3 occurrences"))
    }

    @Test
    fun `editFile reports correct match line`() {
        val file = File(workspaceRoot, "code.kt")
        file.writeText("line 1\nline 2\ntarget line\nline 4")

        val result = ops.editFile(file, "target line", "replaced line")

        assertTrue(result is EditResult.Success)
        assertEquals(3, (result as EditResult.Success).matchLine)
    }

    // --- editFileFuzzy ---

    @Test
    fun `editFileFuzzy matches with different whitespace`() {
        val file = File(workspaceRoot, "code.kt")
        file.writeText("fun hello() {\n    println(  \"Hello\"  )\n}")

        val result = ops.editFileFuzzy(
            file,
            "println( \"Hello\" )",
            "println(\"World\")"
        )

        assertTrue(result is EditResult.Success)
        assertTrue(file.readText().contains("println(\"World\")"))
    }

    @Test
    fun `editFileFuzzy returns NoMatch when truly different`() {
        val file = File(workspaceRoot, "code.kt")
        file.writeText("fun hello() {}")

        val result = ops.editFileFuzzy(file, "completely different", "replacement")

        assertTrue(result is EditResult.NoMatch)
    }

    // --- listFiles ---

    @Test
    fun `listFiles returns sorted entries`() {
        File(workspaceRoot, "beta.txt").writeText("b")
        File(workspaceRoot, "alpha.txt").writeText("a")
        File(workspaceRoot, "subdir").mkdir()

        val result = ops.listFiles(workspaceRoot)

        assertEquals(3, result.totalCount)
        assertEquals("alpha.txt  (1 B)", result.entries[0])
        assertEquals("beta.txt  (1 B)", result.entries[1])
        assertEquals("subdir/", result.entries[2])
        assertFalse(result.truncated)
    }

    @Test
    fun `listFiles respects limit`() {
        for (i in 1..10) {
            File(workspaceRoot, "file$i.txt").writeText("content")
        }

        val result = ops.listFiles(workspaceRoot, limit = 3)

        assertEquals(3, result.entries.size)
        assertEquals(10, result.totalCount)
        assertTrue(result.truncated)
    }

    @Test
    fun `listFiles handles empty directory`() {
        val emptyDir = File(workspaceRoot, "empty")
        emptyDir.mkdir()

        val result = ops.listFiles(emptyDir)

        assertEquals(0, result.totalCount)
        assertTrue(result.entries.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun `listFiles shows directory suffix`() {
        File(workspaceRoot, "mydir").mkdir()

        val result = ops.listFiles(workspaceRoot)

        assertEquals("mydir/", result.entries[0])
    }

    // --- execCommand ---

    @Test
    fun `execCommand runs basic command`() {
        val result = ops.execCommand("echo hello", workspaceRoot, 30)

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.output.trim() == "hello")
    }

    @Test
    fun `execCommand captures non-zero exit code`() {
        val result = ops.execCommand("exit 42", workspaceRoot, 30)

        assertEquals(42, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `execCommand times out long-running command`() {
        val result = ops.execCommand("sleep 30", workspaceRoot, 1)

        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun `execCommand respects cwd`() {
        val subdir = File(workspaceRoot, "mydir")
        subdir.mkdir()
        File(subdir, "test.txt").writeText("content")

        val result = ops.execCommand("ls", subdir, 30)

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("test.txt"))
    }

    @Test
    fun `execCommand captures stderr`() {
        val result = ops.execCommand("echo error >&2", workspaceRoot, 30)

        assertEquals(0, result.exitCode)
        assertTrue(result.output.trim() == "error")
    }

    @Test
    fun `execCommand tail-truncates large output`() {
        // Generate output larger than 16KB
        val result = ops.execCommand(
            "yes 'abcdefghijklmnopqrstuvwxyz' | head -n 2000",
            workspaceRoot, 30
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("[Truncated:"))
        // The tail-truncated output should end with the repeated line
        assertTrue(result.output.contains("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `execCommand supports pipe chaining`() {
        val result = ops.execCommand(
            "printf 'banana\\napple\\ncherry\\n' | sort",
            workspaceRoot, 30
        )

        assertEquals(0, result.exitCode)
        val lines = result.output.trim().lines()
        assertEquals("apple", lines[0])
        assertEquals("banana", lines[1])
        assertEquals("cherry", lines[2])
    }

    @Test
    fun `execCommand can access workspace files`() {
        File(workspaceRoot, "data.txt").writeText("line1\nline2\nline3\n")

        val result = ops.execCommand("wc -l data.txt", workspaceRoot, 30)

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("3"))
    }
}
