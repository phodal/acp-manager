package com.phodal.routa.core.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test for [RoutaMcpWebSocketServer] to verify:
 * - Dynamic port allocation
 * - MCP config JSON generation
 * - Server lifecycle (start/stop)
 */
class RoutaMcpWebSocketServerTest {

    @Test
    fun `test server starts on dynamic port and generates valid MCP config`() = runBlocking {
        val server = RoutaMcpWebSocketServer(workspaceId = "test-workspace")
        
        // Verify server is not running initially
        assertFalse("Server should not be running before start()", server.isRunning)
        assertEquals("Port should be 0 before start()", 0, server.port)
        
        // Start the server
        val port = server.start()
        
        // Verify server started
        assertTrue("Server should be running after start()", server.isRunning)
        assertTrue("Port should be allocated (>0)", port > 0)
        assertEquals("start() should return the same port as server.port", port, server.port)
        
        // Verify MCP config JSON is valid
        val configJson = server.toMcpConfigJson()
        assertTrue("Config should contain mcpServers", configJson.contains("\"mcpServers\""))
        assertTrue("Config should contain routa server name", configJson.contains("\"routa\""))
        assertTrue("Config should contain url field", configJson.contains("\"url\""))
        assertTrue("URL should point to correct SSE endpoint", configJson.contains("http://127.0.0.1:$port/sse"))
        assertTrue("Config should specify SSE transport type", configJson.contains("\"type\":\"sse\""))
        
        println("✓ MCP Server started on port $port")
        println("✓ MCP Config JSON: $configJson")
        
        // Test SSE endpoint exists (just verify URL format)
        val expectedSseUrl = "http://127.0.0.1:$port/sse"
        assertTrue("Config should contain correct SSE URL", configJson.contains(expectedSseUrl))
        
        // Stop the server
        server.stop()
        
        // Verify server stopped
        assertFalse("Server should not be running after stop()", server.isRunning)
        assertEquals("Port should be reset to 0 after stop()", 0, server.port)
        
        println("✓ Server stopped successfully")
    }
    
    @Test
    fun `test findAvailablePort allocates a port`() {
        val port = RoutaMcpWebSocketServer.findAvailablePort()
        assertTrue("findAvailablePort() should return a port greater than 0", port > 0)
        println("✓ Available port found: $port")
    }
}
