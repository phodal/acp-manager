package com.github.phodal.acpmanager.mcp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * MCP Configuration Test
 * 
 * Tests loading and parsing MCP server configurations similar to:
 * - Claude Desktop's MCP configuration
 * - Standard MCP server registry format
 */
class McpConfigTest {

    @Test
    fun testLoadMemoryServerConfig() {
        val configJson = """
        {
          "mcpServers": {
            "memory": {
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-memory"]
            }
          }
        }
        """.trimIndent()

        val config = McpConfigLoader.loadFromString(configJson)
        
        assertNotNull(config.mcpServers["memory"])
        assertEquals("npx", config.mcpServers["memory"]?.command)
        assertEquals(2, config.mcpServers["memory"]?.args?.size)
        assertEquals("-y", config.mcpServers["memory"]?.args?.get(0))
        assertEquals("@modelcontextprotocol/server-memory", config.mcpServers["memory"]?.args?.get(1))
    }

    @Test
    fun testLoadFilesystemServerConfig() {
        val configJson = """
        {
          "mcpServers": {
            "filesystem": {
              "command": "npx",
              "args": [
                "-y",
                "@modelcontextprotocol/server-filesystem",
                "/path/to/allowed/files"
              ]
            }
          }
        }
        """.trimIndent()

        val config = McpConfigLoader.loadFromString(configJson)
        
        assertNotNull(config.mcpServers["filesystem"])
        assertEquals("npx", config.mcpServers["filesystem"]?.command)
        assertEquals(3, config.mcpServers["filesystem"]?.args?.size)
        assertEquals("/path/to/allowed/files", config.mcpServers["filesystem"]?.args?.get(2))
    }

    @Test
    fun testLoadMultipleServersConfig() {
        val configJson = """
        {
          "mcpServers": {
            "memory": {
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-memory"]
            },
            "filesystem": {
              "command": "npx",
              "args": [
                "-y",
                "@modelcontextprotocol/server-filesystem",
                "/Users/username/Desktop"
              ]
            },
            "github": {
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-github"],
              "env": {
                "GITHUB_PERSONAL_ACCESS_TOKEN": "ghp_xxxxxx"
              }
            }
          }
        }
        """.trimIndent()

        val config = McpConfigLoader.loadFromString(configJson)
        
        assertEquals(3, config.mcpServers.size)
        assertNotNull(config.mcpServers["memory"])
        assertNotNull(config.mcpServers["filesystem"])
        assertNotNull(config.mcpServers["github"])
        
        // Check environment variables
        assertEquals("ghp_xxxxxx", config.mcpServers["github"]?.env?.get("GITHUB_PERSONAL_ACCESS_TOKEN"))
    }

    @Test
    fun testDisabledServers() {
        val configJson = """
        {
          "mcpServers": {
            "memory": {
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-memory"]
            },
            "filesystem": {
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
              "disabled": true
            }
          }
        }
        """.trimIndent()

        val config = McpConfigLoader.loadFromString(configJson)
        val enabledServers = McpConfigLoader.getEnabledServers(config)
        
        assertEquals(1, enabledServers.size)
        assertNotNull(enabledServers["memory"])
        assertEquals(null, enabledServers["filesystem"])
    }

    @Test
    fun testSaveAndLoadConfig() {
        val tempFile = File.createTempFile("mcp-config-test", ".json")
        tempFile.deleteOnExit()

        val originalConfig = McpServerConfig(
            mcpServers = mapOf(
                "memory" to McpServerEntry(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-memory")
                ),
                "test-server" to McpServerEntry(
                    command = "node",
                    args = listOf("server.js"),
                    env = mapOf("PORT" to "3000"),
                    disabled = true
                )
            )
        )

        // Save
        McpConfigLoader.saveToFile(originalConfig, tempFile)
        assertTrue(tempFile.exists())

        // Load
        val loadedConfig = McpConfigLoader.loadFromFile(tempFile)
        assertEquals(2, loadedConfig.mcpServers.size)
        assertEquals("npx", loadedConfig.mcpServers["memory"]?.command)
        assertEquals(true, loadedConfig.mcpServers["test-server"]?.disabled)
    }

    @Test
    fun testDefaultConfig() {
        val config = McpConfigLoader.createDefaultConfig()
        
        assertTrue(config.mcpServers.isNotEmpty())
        assertNotNull(config.mcpServers["memory"])
        assertNotNull(config.mcpServers["filesystem"])
        
        // Most servers should be disabled by default for security
        val enabledServers = McpConfigLoader.getEnabledServers(config)
        assertTrue("Most default servers should be disabled", enabledServers.size <= 2)
    }
}

