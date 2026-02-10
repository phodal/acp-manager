package com.github.phodal.acpmanager.mcp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

private val log = logger<McpClientConnector>()

/**
 * MCP Client Connector
 * 
 * Connects to external MCP servers (like memory, filesystem) via stdio transport.
 * Similar to how Claude Desktop connects to MCP servers.
 * 
 * Example usage:
 * ```kotlin
 * val connector = McpClientConnector(
 *     command = "npx",
 *     args = listOf("-y", "@modelcontextprotocol/server-memory"),
 *     env = emptyMap()
 * )
 * connector.start()
 * val tools = connector.listTools()
 * val result = connector.callTool("store_memory", mapOf("key" to "test", "value" to "data"))
 * connector.stop()
 * ```
 */
class McpClientConnector(
    private val name: String,
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String> = emptyMap()
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var requestId = 0
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /**
     * Start the MCP server process and initialize connection.
     */
    suspend fun start() {
        try {
            val commandLine = GeneralCommandLine(command)
            commandLine.addParameters(args)
            env.forEach { (key, value) ->
                commandLine.environment[key] = value
            }

            process = commandLine.createProcess()
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            // Send initialize request
            val initRequest = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", nextRequestId())
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("clientInfo") {
                        put("name", "acp-manager")
                        put("version", "0.0.1")
                    }
                    putJsonObject("capabilities") {}
                }
            }

            sendRequest(initRequest)
            val response = receiveResponse()
            
            if (response["error"] != null) {
                throw IllegalStateException("Failed to initialize MCP server: ${response["error"]}")
            }

            log.info("MCP client connected to $name")
        } catch (e: Exception) {
            log.error("Failed to start MCP client for $name", e)
            throw e
        }
    }

    /**
     * List available tools from the MCP server.
     */
    suspend fun listTools(): List<JsonObject> {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", nextRequestId())
            put("method", "tools/list")
            putJsonObject("params") {}
        }

        sendRequest(request)
        val response = receiveResponse()

        val result = response["result"]?.jsonObject ?: return emptyList()
        val tools = result["tools"]?.jsonArray ?: return emptyList()

        return tools.mapNotNull { it.jsonObject }
    }

    /**
     * Call a tool on the MCP server.
     */
    suspend fun callTool(toolName: String, arguments: Map<String, Any?>): JsonObject {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", nextRequestId())
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", toolName)
                putJsonObject("arguments") {
                    arguments.forEach { (key, value) ->
                        when (value) {
                            is String -> put(key, value)
                            is Number -> put(key, value)
                            is Boolean -> put(key, value)
                            is List<*> -> putJsonArray(key) {
                                value.forEach { item -> add(JsonPrimitive(item.toString())) }
                            }
                            else -> put(key, value.toString())
                        }
                    }
                }
            }
        }

        sendRequest(request)
        return receiveResponse()
    }

    /**
     * Stop the MCP server process.
     */
    fun stop() {
        try {
            writer?.close()
            reader?.close()
            process?.destroy()
            process?.waitFor()
            scope.cancel()
            log.info("MCP client disconnected from $name")
        } catch (e: Exception) {
            log.warn("Error stopping MCP client for $name", e)
        }
    }

    private fun sendRequest(request: JsonObject) {
        val requestStr = request.toString()
        writer?.write(requestStr)
        writer?.newLine()
        writer?.flush()
    }

    private fun receiveResponse(): JsonObject {
        val line = reader?.readLine() ?: throw IllegalStateException("No response from MCP server")
        return json.parseToJsonElement(line).jsonObject
    }

    private fun nextRequestId(): Int = ++requestId
}

