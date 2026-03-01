package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.tool.js.EnvironmentVariableStore
import com.oneclaw.shadow.tool.js.JsExecutionEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JsEvalToolTest {

    private lateinit var jsExecutionEngine: JsExecutionEngine
    private lateinit var envVarStore: EnvironmentVariableStore
    private lateinit var tool: JsEvalTool

    @BeforeEach
    fun setup() {
        jsExecutionEngine = mockk()
        envVarStore = mockk()
        tool = JsEvalTool(jsExecutionEngine, envVarStore)

        coEvery { envVarStore.getAll() } returns emptyMap()
    }

    @Test
    fun testDefinition() {
        assertEquals("js_eval", tool.definition.name)
        assertTrue(tool.definition.parametersSchema.required.contains("code"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("code"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("timeout_seconds"))
        assertNotNull(tool.definition.description)
        assertTrue(tool.definition.description.isNotBlank())
    }

    @Test
    fun testExecute_emptyCode() = runTest {
        val result = tool.execute(mapOf("code" to ""))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("code", ignoreCase = true))
    }

    @Test
    fun testExecute_blankCode() = runTest {
        val result = tool.execute(mapOf("code" to "   "))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun testExecute_nullCode() = runTest {
        val result = tool.execute(mapOf("code" to null))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun testExecute_missingCode() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun testExecute_validCode() = runTest {
        val code = "2 + 2"
        val expectedResult = ToolResult.success("4")

        coEvery {
            jsExecutionEngine.executeFromSource(
                jsSource = any(),
                toolName = "js_eval",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        } returns expectedResult

        val result = tool.execute(mapOf("code" to code))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("4", result.result)

        coVerify {
            jsExecutionEngine.executeFromSource(
                jsSource = any(),
                toolName = "js_eval",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        }
    }

    @Test
    fun testExecute_defaultTimeout() = runTest {
        val code = "1 + 1"
        val expectedResult = ToolResult.success("2")

        coEvery {
            jsExecutionEngine.executeFromSource(
                jsSource = any(),
                toolName = "js_eval",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        } returns expectedResult

        // No timeout_seconds provided -- should default to 30
        val result = tool.execute(mapOf("code" to code))

        assertEquals(ToolResultStatus.SUCCESS, result.status)

        coVerify {
            jsExecutionEngine.executeFromSource(
                jsSource = any(),
                toolName = "js_eval",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        }
    }

    @Test
    fun testExecute_timeoutClamped() = runTest {
        val code = "42"
        val expectedResult = ToolResult.success("42")

        // timeout_seconds > 120 should be clamped to 120
        coEvery {
            jsExecutionEngine.executeFromSource(
                jsSource = any(),
                toolName = "js_eval",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 120
            )
        } returns expectedResult

        val result = tool.execute(mapOf("code" to code, "timeout_seconds" to 999))

        assertEquals(ToolResultStatus.SUCCESS, result.status)

        coVerify {
            jsExecutionEngine.executeFromSource(
                jsSource = any(),
                toolName = "js_eval",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 120
            )
        }
    }

    @Test
    fun testExecute_customTimeout() = runTest {
        val code = "Math.PI"
        val expectedResult = ToolResult.success("3.141592653589793")

        coEvery {
            jsExecutionEngine.executeFromSource(
                jsSource = any(),
                toolName = "js_eval",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 60
            )
        } returns expectedResult

        val result = tool.execute(mapOf("code" to code, "timeout_seconds" to 60))

        assertEquals(ToolResultStatus.SUCCESS, result.status)

        coVerify {
            jsExecutionEngine.executeFromSource(
                jsSource = any(),
                toolName = "js_eval",
                functionName = null,
                params = emptyMap(),
                env = emptyMap(),
                timeoutSeconds = 60
            )
        }
    }

    @Test
    fun testBuildExecuteWrapper_containsUserCode() {
        val code = "2 + 2"
        val wrapper = tool.buildExecuteWrapper(code)

        assertTrue(wrapper.contains("async function execute(params)"))
        assertTrue(wrapper.contains("eval(`"))
        assertTrue(wrapper.contains("main"))
        assertTrue(wrapper.contains("__result__"))
    }

    @Test
    fun testBuildExecuteWrapper_escapesBacktick() {
        val code = "const s = `hello`"
        val wrapper = tool.buildExecuteWrapper(code)

        // Backtick in user code must be escaped to \` in the template literal
        assertTrue(wrapper.contains("\\`hello\\`"))
    }

    @Test
    fun testBuildExecuteWrapper_escapesBackslash() {
        val code = "const s = 'line1\\nline2'"
        val wrapper = tool.buildExecuteWrapper(code)

        // Backslash must be doubled
        assertTrue(wrapper.contains("\\\\n"))
    }

    @Test
    fun testBuildExecuteWrapper_escapesDollarSign() {
        val code = "const x = \$100"
        val wrapper = tool.buildExecuteWrapper(code)

        // Dollar sign must be escaped to avoid template literal interpolation
        assertTrue(wrapper.contains("\\$"))
    }
}
