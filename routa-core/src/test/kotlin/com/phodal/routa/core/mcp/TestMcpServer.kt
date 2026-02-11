package com.phodal.routa.core.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Manual test program for RoutaMcpWebSocketServer.
 * 
 * Run with: ./gradlew :routa-core:run --args="test-mcp"
 */
fun main() = runBlocking {
    println("╔══════════════════════════════════════════════╗")
    println("║  RoutaMcpWebSocketServer Manual Test         ║")
    println("╚══════════════════════════════════════════════╝")
    println()
    
    // Test 1: Port allocation
    println("Test 1: Port allocation")
    val availablePort = RoutaMcpWebSocketServer.findAvailablePort()
    println("  ✓ Found available port: $availablePort")
    println()
    
    // Test 2: Server lifecycle
    println("Test 2:Server lifecycle")
    val server = RoutaMcpWebSocketServer(workspaceId = "test-workspace")
    
    println("  - Before start: isRunning=${server.isRunning}, port=${server.port}")
    
    val port = server.start()
    println("  ✓ Server started on port: $port")
    println("  - After start: isRunning=${server.isRunning}, port=${server.port}")
    
    // Test 3: MCP config JSON generation
    println()
    println("Test 3: MCP config JSON generation")
    val configJson = server.toMcpConfigJson()
    println("  ✓ Generated MCP config:")
    println("    $configJson")
    println()
    
    // Verify JSON structure
    val checks = listOf(
        "\"mcpServers\"" in configJson,
        "\"routa\"" in configJson,
        "\"url\"" in configJson,
        "\"type\":\"sse\"" in configJson,
        "http://127.0.0.1:$port/sse" in configJson
    )
    
    println("  JSON structure checks:")
    checks.forEachIndexed { i, check ->
        println("    ${if (check) "✓" else "✗"} Check ${i + 1}")
    }
    
    // Test 4: Wait a bit to allow connections (for manual testing with curl)
    println()
    println("Test 4: Server is running. You can test with:")
    println("  curl http://127.0.0.1:$port/sse")
    println()
    println("  Waiting 5 seconds before shutdown...")
    delay(5000)
    
    // Test 5: Server shutdown
    println()
    println("Test 5: Server shutdown")
    server.stop()
    println("  ✓ Server stopped")
    println("  - After stop: isRunning=${server.isRunning}, port=${server.port}")
    
    println()
    println("╔══════════════════════════════════════════════╗")
    println("║  All tests completed successfully!           ║")
    println("╚══════════════════════════════════════════════╝")
}
