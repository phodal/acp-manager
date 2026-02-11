package com.phodal.routa.core.mcp

import com.phodal.routa.core.RoutaSystem
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.modelcontextprotocol.kotlin.sdk.server.mcpWebSocket
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.CompletableDeferred
import java.net.ServerSocket

/**
 * Manages an MCP server (SSE or WebSocket) backed by [RoutaMcpServer].
 *
 * Claude Code can connect to this server using `--mcp-config` with SSE transport,
 * or via WebSocket for other MCP clients.
 *
 * The server uses a **dynamic port** (port 0) so it can coexist with other services.
 * After starting, the actual port is available via [port].
 *
 * Usage from the IDE plugin:
 * ```kotlin
 * val server = RoutaMcpWebSocketServer("my-workspace")
 * server.start()
 * val port = server.port  // actual allocated port
 * // pass to ClaudeCodeClient via --mcp-config
 * server.stop()
 * ```
 */
class RoutaMcpWebSocketServer(
    private val workspaceId: String,
    private val host: String = "127.0.0.1",
    private val routa: RoutaSystem? = null,
) {
    private var ktorServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var routaSystem: RoutaSystem? = null

    /**
     * The actual port the server is listening on. Available after [start].
     */
    var port: Int = 0
        private set

    val isRunning: Boolean get() = ktorServer != null

    /**
     * Start the MCP server on a dynamically allocated port.
     *
     * Uses SSE transport (compatible with Claude Code `--mcp-config` + `http` transport)
     * and WebSocket transport (compatible with MCP Inspector and other WS clients).
     */
    fun start(): Int {
        if (ktorServer != null) {
            return port
        }

        // Pre-allocate a port so we don't need the suspend resolvedConnectors()
        val allocatedPort = findAvailablePort()

        // Create the Routa MCP server + system
        val (mcpServer, system) = RoutaMcpServer.create(workspaceId, routa)
        this.routaSystem = system

        val engine = embeddedServer(CIO, host = host, port = allocatedPort) {
            // SSE transport at /sse (for Claude Code `--mcp-config` with http transport)
            mcp {
                mcpServer
            }

            // WebSocket transport at /mcp (for MCP Inspector and other WS clients)
            mcpWebSocket("/mcp") {
                // Each WebSocket connection gets a fresh MCP server sharing the same Routa system
                val (wsMcpServer, _) = RoutaMcpServer.create(workspaceId, system)
                wsMcpServer
            }
        }.start(wait = false)

        this.ktorServer = engine
        this.port = allocatedPort

        return port
    }

    /**
     * Stop the MCP server and release resources.
     */
    fun stop() {
        try {
            ktorServer?.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
        } catch (_: Exception) {
        }
        ktorServer = null
        routaSystem = null
        port = 0
    }

    /**
     * Generate a JSON MCP config string that Claude Code can use via `--mcp-config`.
     *
     * Example output:
     * ```json
     * {"mcpServers":{"routa":{"url":"http://127.0.0.1:12345/sse","type":"sse"}}}
     * ```
     */
    fun toMcpConfigJson(): String {
        require(isRunning) { "Server is not running, call start() first" }
        return """{"mcpServers":{"routa":{"url":"http://$host:$port/sse","type":"sse"}}}"""
    }

    companion object {
        /**
         * Find an available port on the system.
         */
        fun findAvailablePort(): Int {
            return ServerSocket(0).use { it.localPort }
        }
    }
}
