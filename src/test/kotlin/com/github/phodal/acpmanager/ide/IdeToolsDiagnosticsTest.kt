package com.github.phodal.acpmanager.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Tests for IdeTools diagnostic functionality.
 */
class IdeToolsDiagnosticsTest : BasePlatformTestCase() {

    private lateinit var ideTools: IdeTools

    override fun setUp() {
        super.setUp()
        ideTools = IdeTools(project)
    }

    @Test
    fun testDiagnosticSeverityEnum() {
        // Test that all severity levels are defined
        assertEquals(5, DiagnosticSeverity.values().size)
        assertTrue(DiagnosticSeverity.values().contains(DiagnosticSeverity.ERROR))
        assertTrue(DiagnosticSeverity.values().contains(DiagnosticSeverity.WARNING))
        assertTrue(DiagnosticSeverity.values().contains(DiagnosticSeverity.WEAK_WARNING))
        assertTrue(DiagnosticSeverity.values().contains(DiagnosticSeverity.INFO))
        assertTrue(DiagnosticSeverity.values().contains(DiagnosticSeverity.HINT))
    }

    @Test
    fun testDiagnosticSeverityFromString() {
        // Test severity parsing from string
        assertEquals(DiagnosticSeverity.ERROR, DiagnosticSeverity.from("ERROR"))
        assertEquals(DiagnosticSeverity.WARNING, DiagnosticSeverity.from("WARNING"))
        assertEquals(DiagnosticSeverity.WEAK_WARNING, DiagnosticSeverity.from("WEAK_WARNING"))
        assertEquals(DiagnosticSeverity.WEAK_WARNING, DiagnosticSeverity.from("WEAK WARNING"))
        assertEquals(DiagnosticSeverity.HINT, DiagnosticSeverity.from("HINT"))
        assertEquals(DiagnosticSeverity.INFO, DiagnosticSeverity.from("UNKNOWN"))
    }

    @Test
    fun testDiagnosticSeverityFromStringCaseInsensitive() {
        // Test case-insensitive parsing
        assertEquals(DiagnosticSeverity.ERROR, DiagnosticSeverity.from("error"))
        assertEquals(DiagnosticSeverity.WARNING, DiagnosticSeverity.from("warning"))
        assertEquals(DiagnosticSeverity.HINT, DiagnosticSeverity.from("hint"))
    }

    @Test
    fun testDiagnosticItemSerialization() {
        // Test that DiagnosticItem can be serialized to JSON
        val item = DiagnosticItem(
            message = "Test error",
            severity = "ERROR",
            startLine = 10,
            startColumn = 5,
            endLine = 10,
            endColumn = 15
        )

        val json = Json { encodeDefaults = true }
        val jsonString = json.encodeToString(DiagnosticItem.serializer(), item)
        
        assertTrue(jsonString.contains("\"message\":\"Test error\""))
        assertTrue(jsonString.contains("\"severity\":\"ERROR\""))
        assertTrue(jsonString.contains("\"startLine\":10"))
    }

    @Test
    fun testFileDiagnosticsSerialization() {
        // Test that FileDiagnostics can be serialized to JSON
        val diagnostics = listOf(
            DiagnosticItem("Error 1", "ERROR", 1, 0, 1, 10),
            DiagnosticItem("Warning 1", "WARNING", 5, 0, 5, 20)
        )
        val fileDiags = FileDiagnostics("file:///test.kt", diagnostics)

        val json = Json { encodeDefaults = true }
        val jsonString = json.encodeToString(FileDiagnostics.serializer(), fileDiags)
        
        assertTrue(jsonString.contains("\"uri\":\"file:///test.kt\""))
        assertTrue(jsonString.contains("\"diagnostics\""))
        assertTrue(jsonString.contains("\"Error 1\""))
        assertTrue(jsonString.contains("\"Warning 1\""))
    }

    @Test
    fun testToolCallResultOk() {
        // Test ToolCallResult.ok() factory method
        val result = ToolCallResult.ok("Success message")
        assertFalse(result.isError)
        assertNotNull(result.content)
        assertEquals("Success message", result.content)
    }

    @Test
    fun testToolCallResultError() {
        // Test ToolCallResult.error() factory method
        val result = ToolCallResult.error("Error message")
        assertTrue(result.isError)
        assertNotNull(result.errorMessage)
        assertEquals("Error message", result.errorMessage)
    }

    @Test
    fun testDiagnosticItemWithAllFields() {
        // Test DiagnosticItem with all fields populated
        val item = DiagnosticItem(
            message = "Unused variable",
            severity = "WARNING",
            startLine = 5,
            startColumn = 4,
            endLine = 5,
            endColumn = 12
        )

        assertEquals("Unused variable", item.message)
        assertEquals("WARNING", item.severity)
        assertEquals(5, item.startLine)
        assertEquals(4, item.startColumn)
        assertEquals(5, item.endLine)
        assertEquals(12, item.endColumn)
    }

    @Test
    fun testFileDiagnosticsWithMultipleDiagnostics() {
        // Test FileDiagnostics with multiple diagnostic items
        val diagnostics = listOf(
            DiagnosticItem("Error 1", "ERROR", 1, 0, 1, 10),
            DiagnosticItem("Warning 1", "WARNING", 5, 0, 5, 20),
            DiagnosticItem("Info 1", "INFO", 10, 0, 10, 15)
        )
        val fileDiags = FileDiagnostics("file:///test.kt", diagnostics)

        assertEquals("file:///test.kt", fileDiags.uri)
        assertEquals(3, fileDiags.diagnostics.size)
        assertEquals("ERROR", fileDiags.diagnostics[0].severity)
        assertEquals("WARNING", fileDiags.diagnostics[1].severity)
        assertEquals("INFO", fileDiags.diagnostics[2].severity)
    }
}

