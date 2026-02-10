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
        // File Tools
        server.addTool(
            name = "openFile",
            description = "Opens the specified file in the IDE.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("filePath") {
                        put("type", "string")
                        put("description", "Absolute path to the file to open")
                    }
                    putJsonObject("makeFrontmost") {
                        put("type", "boolean")
                        put("description", "Whether to bring the file to front")
                        put("default", true)
                    }
                },
                required = listOf("filePath")
            )
        ) { request ->
            handleToolCall("openFile", request.arguments ?: JsonObject(emptyMap()))
        }

        log.info("MCP tools registered: 1 tool (openFile)")
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

