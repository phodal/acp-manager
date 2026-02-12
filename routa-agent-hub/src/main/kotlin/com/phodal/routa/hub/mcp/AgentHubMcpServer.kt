package com.phodal.routa.hub.mcp

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.RoutaSystem
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Creates and configures an MCP [Server] that exposes Agent management tools.
 *
 * This provides a standalone MCP server focused on agent lifecycle management:
 * creating agents, querying status, sending messages, and managing subscriptions.
 *
 * Unlike the full Routa MCP server in `routa-core`, this server is dedicated to
 * agent management operations and can be used independently by any MCP client
 * (Cursor, Claude, VS Code, etc.).
 *
 * Usage:
 * ```kotlin
 * val mcpServer = AgentHubMcpServer.create("my-workspace")
 * // Connect via stdio or WebSocket transport...
 * ```
 */
object AgentHubMcpServer {

    /**
     * Create an MCP Server with agent management tools registered.
     *
     * @param workspaceId The workspace ID for the agent management session.
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
                name = "routa-agent-hub",
                version = "0.1.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )

        // Register agent management tools
        AgentHubToolManager(system.tools, workspaceId).registerTools(server)

        return server to system
    }
}
