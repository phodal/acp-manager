package com.phodal.routa.hub.mcp

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * CLI entry point for the Routa Agent Hub MCP server.
 *
 * Runs as a standalone MCP server using stdio transport, compatible with
 * any MCP client (Claude, Cursor, VS Code, etc.).
 *
 * Usage:
 * ```bash
 * # Run via Gradle
 * ./gradlew :routa-agent-hub:run
 *
 * # Or build a fat JAR and run directly
 * java -jar routa-agent-hub.jar
 * ```
 *
 * MCP client configuration (e.g., in Claude Desktop or Cursor):
 * ```json
 * {
 *   "mcpServers": {
 *     "routa-agent-hub": {
 *       "command": "./gradlew",
 *       "args": [":routa-agent-hub:run", "-q", "--console=plain"]
 *     }
 *   }
 * }
 * ```
 */
fun main() {
    val workspaceId = System.getenv("ROUTA_WORKSPACE_ID") ?: "default"

    val (mcpServer, _) = AgentHubMcpServer.create(workspaceId)

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    runBlocking {
        val session = mcpServer.createSession(transport)

        // The server will keep running until the transport is closed (stdin EOF)
        val done = CompletableDeferred<Unit>()
        session.onClose { done.complete(Unit) }
        done.await()
    }
}
