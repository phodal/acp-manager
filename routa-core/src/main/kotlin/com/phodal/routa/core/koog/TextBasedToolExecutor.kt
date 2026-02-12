package com.phodal.routa.core.koog

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Executes tool calls extracted from LLM text responses.
 *
 * Inspired by Intent's `agent-tool-executor.ts`. Instead of going through Koog's
 * native tool registry (which requires LLM function-calling parameters), this
 * executor works with text-based tool calls parsed by [ToolCallExtractor].
 *
 * The executor supports workspace file operations:
 * - `read_file` — read a file's contents
 * - `write_file` — write/create a file
 * - `list_files` — list directory contents
 *
 * ## Usage
 *
 * ```kotlin
 * val executor = TextBasedToolExecutor(cwd = "/path/to/project")
 * val toolCalls = ToolCallExtractor.extractToolCalls(llmResponse)
 * val results = executor.executeAll(toolCalls)
 * val feedback = executor.formatResults(results)
 * // Feed `feedback` back to the LLM as the next user message
 * ```
 */
class TextBasedToolExecutor(
    private val cwd: String,
) {

    /**
     * Result of a single tool execution.
     */
    data class ToolResult(
        val toolName: String,
        val success: Boolean,
        val output: String,
    )

    /**
     * Execute all tool calls and return results.
     */
    fun executeAll(toolCalls: List<ToolCallExtractor.ToolCall>): List<ToolResult> {
        return toolCalls.map { execute(it) }
    }

    /**
     * Execute a single tool call.
     *
     * Note: The Workspace Agent is a Coordinator role with READ-ONLY access.
     * - `read_file` and `list_files` are available for exploring the codebase
     * - `write_file` is NOT available — implementation must be delegated to Implementor agents
     */
    fun execute(toolCall: ToolCallExtractor.ToolCall): ToolResult {
        return try {
            when (toolCall.name) {
                "read_file" -> executeReadFile(toolCall.arguments)
                "list_files" -> executeListFiles(toolCall.arguments)
                "write_file" -> ToolResult(
                    toolName = "write_file",
                    success = false,
                    output = "Error: write_file is not available. As a Coordinator, you cannot edit files directly. " +
                            "Create an @@@task block to delegate implementation to an Implementor agent.",
                )
                else -> ToolResult(
                    toolName = toolCall.name,
                    success = false,
                    output = "Error: Unknown tool '${toolCall.name}'. Available tools: read_file, list_files. " +
                            "Note: write_file is not available — delegate to Implementor agents via @@@task blocks.",
                )
            }
        } catch (e: Exception) {
            ToolResult(
                toolName = toolCall.name,
                success = false,
                output = "Error executing ${toolCall.name}: ${e.message}",
            )
        }
    }

    /**
     * Format tool results into a message to feed back to the LLM.
     *
     * Each result is wrapped in `<tool_result>` XML for clear parsing by the LLM.
     */
    fun formatResults(results: List<ToolResult>): String {
        if (results.isEmpty()) return ""

        val parts = results.map { result ->
            val status = if (result.success) "success" else "error"
            buildString {
                appendLine("<tool_result>")
                appendLine("<tool_name>${result.toolName}</tool_name>")
                appendLine("<status>$status</status>")
                appendLine("<output>")
                appendLine(result.output)
                appendLine("</output>")
                appendLine("</tool_result>")
            }
        }

        return parts.joinToString("\n")
    }

    // ── Tool implementations ─────────────────────────────────────────────

    private fun executeReadFile(args: Map<String, String>): ToolResult {
        val filePath = args["path"]
            ?: return ToolResult("read_file", false, "Error: 'path' argument is required")

        val resolved = resolveSafely(filePath)
            ?: return ToolResult("read_file", false, "Error: Access denied — path outside workspace")

        val file = resolved.toFile()
        if (!file.exists()) {
            return ToolResult("read_file", false, "Error: File not found: $filePath")
        }
        if (!file.isFile) {
            return ToolResult("read_file", false, "Error: Not a file: $filePath")
        }

        val content = file.readText()
        return ToolResult("read_file", true, content)
    }

    private fun executeWriteFile(args: Map<String, String>): ToolResult {
        val filePath = args["path"]
            ?: return ToolResult("write_file", false, "Error: 'path' argument is required")
        val content = args["content"]
            ?: return ToolResult("write_file", false, "Error: 'content' argument is required")

        val resolved = resolveSafely(filePath)
            ?: return ToolResult("write_file", false, "Error: Access denied — path outside workspace")

        // Create parent directories if needed
        resolved.parent?.let { Files.createDirectories(it) }
        resolved.toFile().writeText(content)

        return ToolResult("write_file", true, "File written successfully: $filePath (${content.length} bytes)")
    }

    private fun executeListFiles(args: Map<String, String>): ToolResult {
        val dirPath = args["path"]?.ifBlank { "." } ?: "."

        val resolved = resolveSafely(dirPath)
            ?: return ToolResult("list_files", false, "Error: Access denied — path outside workspace")

        val dir = resolved.toFile()
        if (!dir.exists()) {
            return ToolResult("list_files", false, "Error: Directory not found: $dirPath")
        }
        if (!dir.isDirectory) {
            return ToolResult("list_files", false, "Error: Not a directory: $dirPath")
        }

        val entries = dir.listFiles()
            ?.sortedBy { it.name }
            ?.joinToString("\n") { entry ->
                val type = if (entry.isDirectory) "[dir]" else "[file]"
                "$type ${entry.name}"
            }
            ?: "(empty)"

        return ToolResult("list_files", true, entries)
    }

    // ── Path safety ─────────────────────────────────────────────────────

    private fun resolveSafely(relativePath: String): Path? {
        val base = Paths.get(cwd).toAbsolutePath().normalize()
        val resolved = base.resolve(relativePath).normalize()
        return if (resolved.startsWith(base)) resolved else null
    }
}
