package com.phodal.routa.core.mcp

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.tool.AgentTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Creates and configures an MCP [Server] that exposes all Routa coordination tools.
 *
 * This provides a standalone MCP server that can be used with any MCP client
 * (Cursor, Claude, VS Code, etc.) to enable multi-agent coordination.
 *
 * Usage:
 * ```kotlin
 * val mcpServer = RoutaMcpServer.create("my-workspace")
 * // Connect via stdio or WebSocket transport...
 * ```
 */
object RoutaMcpServer {

    /**
     * Create an MCP Server with all Routa coordination tools registered.
     *
     * @param workspaceId The workspace ID for the coordination session.
     * @param routa Optional pre-configured RoutaSystem (creates in-memory if null).
     * @return A pair of (Server, RoutaSystem).
     */
    fun create(
        workspaceId: String,
        routa: RoutaSystem? = null,
    ): Pair<Server, RoutaSystem> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val system = routa ?: RoutaFactory.createInMemory(scope)

        val server = Server(
            serverInfo = Implementation(
                name = "routa-mcp",
                version = "0.1.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )

        // Register all 12 coordination tools
        RoutaMcpToolManager(system.tools, workspaceId).registerTools(server)

        return server to system
    }
}
