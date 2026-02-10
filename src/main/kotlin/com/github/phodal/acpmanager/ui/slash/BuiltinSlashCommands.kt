package com.github.phodal.acpmanager.ui.slash

import com.github.phodal.acpmanager.acp.AcpSessionManager
import com.github.phodal.acpmanager.ide.IdeNotifications
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

private val log = logger<BuiltinSlashCommands>()

/**
 * Built-in slash commands for the chat interface.
 *
 * Provides core commands:
 * - /clear - Clear chat history
 * - /help - Show available commands
 * - /context - Show current editor context
 * - /files - List open files
 */
class BuiltinSlashCommands(
    private val project: Project,
    private val sessionManager: AcpSessionManager,
    private val ideNotifications: IdeNotifications,
) {
    /**
     * Get all built-in commands.
     */
    fun getCommands(): List<SlashCommand> = listOf(
        createClearCommand(),
        createHelpCommand(),
        createContextCommand(),
        createFilesCommand(),
    )

    /**
     * /clear - Clear chat history
     */
    private fun createClearCommand(): SlashCommand {
        return SlashCommand(
            name = "clear",
            description = "Clear chat history",
            execute = {
                val session = sessionManager.getActiveSession()
                if (session != null) {
                    session.clearMessages()
                    log.info("Chat history cleared for agent '${session.agentKey}'")
                } else {
                    log.warn("No active session to clear")
                }
            }
        )
    }

    /**
     * /help - Show available commands
     */
    private fun createHelpCommand(): SlashCommand {
        return SlashCommand(
            name = "help",
            description = "Show available commands",
            execute = {
                // Get all commands from registry (includes built-in + skills)
                val registry = com.github.phodal.acpmanager.ui.slash.SlashCommandRegistry.getInstance()
                val allCommands = registry.getAllCommands().sortedBy { it.name }
                val helpText = buildString {
                    appendLine("Available commands:")
                    allCommands.forEach { cmd ->
                        appendLine("  /${cmd.name} - ${cmd.description}")
                    }
                }
                log.info(helpText)
            }
        )
    }

    /**
     * /context - Show current editor context
     */
    private fun createContextCommand(): SlashCommand {
        return SlashCommand(
            name = "context",
            description = "Show current editor context",
            execute = {
                val context = ideNotifications.captureEditorContext()
                if (context != null) {
                    val contextText = buildString {
                        appendLine("Current editor context:")
                        appendLine("  File: ${context.filePath}")
                        if (context.startLine != null) {
                            appendLine("  Start line: ${context.startLine}")
                        }
                        if (context.endLine != null) {
                            appendLine("  End line: ${context.endLine}")
                        }
                        if (context.selectedText != null) {
                            appendLine("  Selected text: ${context.selectedText.take(100)}")
                        }
                    }
                    log.info(contextText)
                } else {
                    log.info("No editor context available")
                }
            }
        )
    }

    /**
     * /files - List open files
     */
    private fun createFilesCommand(): SlashCommand {
        return SlashCommand(
            name = "files",
            description = "List open files",
            parameters = listOf(
                CommandParameter(
                    name = "filter",
                    description = "Filter files by extension (e.g., .kt, .java)",
                    required = false,
                    autocomplete = { listOf(".kt", ".java", ".py", ".js", ".ts", ".go", ".rs") }
                ),
                CommandParameter(
                    name = "limit",
                    description = "Maximum number of files to show",
                    required = false,
                    autocomplete = { listOf("5", "10", "20", "50") }
                )
            ),
            execute = {
                val ideClient = com.github.phodal.acpmanager.ide.IdeAcpClient.getInstance(project)
                val result = ideClient.ideTools.getOpenFiles()
                if (!result.isError) {
                    val filesText = buildString {
                        appendLine("Open files:")
                        result.content.forEach { filePath ->
                            appendLine("  - $filePath")
                        }
                    }
                    log.info(filesText)
                } else {
                    log.warn("Failed to get open files: ${result.content.joinToString()}")
                }
            }
        )
    }
}

/**
 * Represents a parameter for a slash command.
 */
data class CommandParameter(
    val name: String,
    val description: String,
    val required: Boolean = false,
    val autocomplete: (() -> List<String>)? = null,
)

/**
 * Represents a slash command.
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val parameters: List<CommandParameter> = emptyList(),
    val execute: () -> Unit,
)

