package com.github.phodal.acpmanager.ui.slash

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Tests for command parameter autocomplete functionality.
 */
class CommandParameterTest : BasePlatformTestCase() {

    @Test
    fun testCommandParameterCreation() {
        val param = CommandParameter(
            name = "filter",
            description = "Filter files by extension",
            required = false,
            autocomplete = { listOf(".kt", ".java") }
        )

        assertEquals("filter", param.name)
        assertEquals("Filter files by extension", param.description)
        assertFalse(param.required)
        assertNotNull(param.autocomplete)
    }

    @Test
    fun testCommandParameterAutocomplete() {
        val param = CommandParameter(
            name = "limit",
            description = "Maximum number of files",
            required = false,
            autocomplete = { listOf("5", "10", "20") }
        )

        val suggestions = param.autocomplete?.invoke()
        assertNotNull(suggestions)
        assertEquals(3, suggestions?.size)
        assertTrue(suggestions?.contains("5") == true)
    }

    @Test
    fun testSlashCommandWithParameters() {
        val command = SlashCommand(
            name = "files",
            description = "List open files",
            parameters = listOf(
                CommandParameter(
                    name = "filter",
                    description = "Filter by extension",
                    required = false,
                    autocomplete = { listOf(".kt", ".java") }
                ),
                CommandParameter(
                    name = "limit",
                    description = "Max files",
                    required = false,
                    autocomplete = { listOf("5", "10") }
                )
            ),
            execute = {}
        )

        assertEquals("files", command.name)
        assertEquals(2, command.parameters.size)
        assertEquals("filter", command.parameters[0].name)
        assertEquals("limit", command.parameters[1].name)
    }

    @Test
    fun testSlashCommandWithoutParameters() {
        val command = SlashCommand(
            name = "clear",
            description = "Clear chat",
            execute = {}
        )

        assertEquals("clear", command.name)
        assertTrue(command.parameters.isEmpty())
    }

    @Test
    fun testRequiredParameter() {
        val param = CommandParameter(
            name = "path",
            description = "File path",
            required = true
        )

        assertTrue(param.required)
        assertNull(param.autocomplete)
    }

    @Test
    fun testParameterWithoutAutocomplete() {
        val param = CommandParameter(
            name = "custom",
            description = "Custom parameter",
            required = false,
            autocomplete = null
        )

        assertFalse(param.required)
        assertNull(param.autocomplete)
    }
}

