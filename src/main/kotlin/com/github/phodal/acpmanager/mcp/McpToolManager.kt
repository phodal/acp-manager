package com.github.phodal.acpmanager.mcp

import com.github.phodal.acpmanager.ide.IdeAcpClient
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

private val log = logger<McpToolManager>()

/**
 * MCP Tool Manager — registers IDE tools with MCP server.
 * 
 * Mirrors Claude Code's ToolManager architecture, bridging MCP tool calls
 * to the existing IdeTools implementation.
 */
class McpToolManager(
    private val project: Project,
    private val ideAcpClient: IdeAcpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * Register all IDE tools with the MCP server.
     */
    fun registerTools(server: Server) {
        registerFileTool(server, "openFile", "Opens the specified file in the IDE.", listOf("filePath")) {
            putJsonObject("filePath") {
                put("type", "string")
                put("description", "Absolute path to the file to open")
            }
            putJsonObject("makeFrontmost") {
                put("type", "boolean")
                put("description", "Whether to bring the file to front")
                put("default", true)
            }
        }

        registerFileTool(server, "open_files", "Opens multiple files in the IDE.", listOf("file_paths")) {
            putJsonObject("file_paths") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "string")
                }
                put("description", "List of absolute file paths to open")
            }
        }

        registerFileTool(server, "close_tab", "Closes a tab in the IDE by file path.", listOf("tab_name")) {
            putJsonObject("tab_name") {
                put("type", "string")
                put("description", "File path or tab name to close")
            }
        }

        registerFileTool(server, "get_open_files", "Gets a list of all currently open file paths.", emptyList()) {
            // No parameters needed
        }

        registerFileTool(server, "reformat_file", "Reformats the specified file using IDE's formatter.", listOf("file_path")) {
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "Absolute path to the file to reformat")
            }
        }

        registerFileTool(server, "open_diff", "Opens a diff view showing changes with accept/reject UI.", listOf("old_file_path", "new_file_contents")) {
            putJsonObject("old_file_path") {
                put("type", "string")
                put("description", "Path to the original file")
            }
            putJsonObject("new_file_contents") {
                put("type", "string")
                put("description", "Proposed new file contents")
            }
            putJsonObject("tab_name") {
                put("type", "string")
                put("description", "Name for the diff tab")
                put("default", "Diff")
            }
        }

        registerFileTool(server, "get_diagnostics", "Gets diagnostic information (errors, warnings) for a file.", emptyList()) {
            putJsonObject("uri") {
                put("type", "string")
                put("description", "File URI or path (optional, uses active file if omitted)")
            }
            putJsonObject("severity") {
                put("type", "string")
                put("description", "Filter by severity: ERROR, WARNING, INFO, or HINT (optional)")
            }
        }

        log.info("MCP tools registered: 7 tools")
    }

    /**
     * Helper to register a tool with the server.
     */
    private fun registerFileTool(
        server: Server,
        name: String,
        description: String,
        required: List<String>,
        propertiesBuilder: JsonObjectBuilder.() -> Unit
    ) {
        server.addTool(
            name = name,
            description = description,
            inputSchema = ToolSchema(
                properties = buildJsonObject(propertiesBuilder),
                required = required
            )
        ) { request ->
            handleToolCall(name, request.arguments ?: JsonObject(emptyMap()))
        }
    }

    /**
     * Handle a tool call by delegating to IdeAcpClient.
     */
    private fun handleToolCall(toolName: String, arguments: JsonObject): CallToolResult {
        return try {
            // Convert JsonObject to Map<String, Any?>
            val argsMap = arguments.toMap()

            // Call the IDE tool handler
            val result = runBlocking {
                ideAcpClient.handleToolCall(toolName, argsMap)
            }

            if (result.isError) {
                CallToolResult(
                    content = listOf(TextContent(text = result.errorMessage ?: "Unknown error")),
                    isError = true
                )
            } else {
                CallToolResult(
                    content = listOf(TextContent(text = result.content ?: "OK")),
                    isError = false
                )
            }
        } catch (e: Exception) {
            log.warn("Error handling tool call: $toolName", e)
            CallToolResult(
                content = listOf(TextContent(text = "Error: ${e.message}")),
                isError = true
            )
        }
    }

    /**
     * Convert JsonObject to Map<String, Any?> for compatibility with IdeAcpClient.
     */
    private fun JsonObject.toMap(): Map<String, Any?> {
        return this.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> {
                    when {
                        value.isString -> value.content
                        value.booleanOrNull != null -> value.boolean
                        value.intOrNull != null -> value.int
                        value.longOrNull != null -> value.long
                        value.doubleOrNull != null -> value.double
                        else -> value.content
                    }
                }
                is JsonArray -> value.map { it.toString() }
                is JsonObject -> value.toMap()
                else -> value.toString()
            }
        }
    }
}

