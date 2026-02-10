package com.github.phodal.acpmanager.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * MCP Server Configuration
 * 
 * Supports the standard MCP server configuration format used by Claude Desktop
 * and other MCP clients.
 * 
 * Example configuration:
 * ```json
 * {
 *   "mcpServers": {
 *     "memory": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-memory"]
 *     },
 *     "filesystem": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/allowed/files"]
 *     }
 *   }
 * }
 * ```
 */
@Serializable
data class McpServerConfig(
    val mcpServers: Map<String, McpServerEntry> = emptyMap()
)

@Serializable
data class McpServerEntry(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val disabled: Boolean = false
)

/**
 * MCP Server Configuration Loader
 */
object McpConfigLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
    }

    /**
     * Load MCP server configuration from a JSON file.
     */
    fun loadFromFile(file: File): McpServerConfig {
        if (!file.exists()) {
            return McpServerConfig()
        }

        return try {
            val content = file.readText()
            json.decodeFromString<McpServerConfig>(content)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to load MCP config from ${file.absolutePath}: ${e.message}", e)
        }
    }

    /**
     * Load MCP server configuration from JSON string.
     */
    fun loadFromString(jsonString: String): McpServerConfig {
        return try {
            json.decodeFromString<McpServerConfig>(jsonString)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse MCP config: ${e.message}", e)
        }
    }

    /**
     * Save MCP server configuration to a JSON file.
     */
    fun saveToFile(config: McpServerConfig, file: File) {
        try {
            file.parentFile?.mkdirs()
            val jsonString = json.encodeToString(McpServerConfig.serializer(), config)
            file.writeText(jsonString)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to save MCP config to ${file.absolutePath}: ${e.message}", e)
        }
    }

    /**
     * Create a default MCP server configuration with common servers.
     */
    fun createDefaultConfig(): McpServerConfig {
        return McpServerConfig(
            mcpServers = mapOf(
                "memory" to McpServerEntry(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-memory")
                ),
                "filesystem" to McpServerEntry(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-filesystem", System.getProperty("user.home")),
                    disabled = true // Disabled by default for security
                ),
                "brave-search" to McpServerEntry(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-brave-search"),
                    env = mapOf("BRAVE_API_KEY" to "your-api-key-here"),
                    disabled = true
                ),
                "github" to McpServerEntry(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-github"),
                    env = mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "your-token-here"),
                    disabled = true
                ),
                "postgres" to McpServerEntry(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-postgres", "postgresql://localhost/mydb"),
                    disabled = true
                ),
                "puppeteer" to McpServerEntry(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-puppeteer"),
                    disabled = true
                ),
                "sequential-thinking" to McpServerEntry(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-sequential-thinking"),
                    disabled = true
                ),
                "slack" to McpServerEntry(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-slack"),
                    env = mapOf(
                        "SLACK_BOT_TOKEN" to "your-bot-token",
                        "SLACK_TEAM_ID" to "your-team-id"
                    ),
                    disabled = true
                )
            )
        )
    }

    /**
     * Get enabled servers from configuration.
     */
    fun getEnabledServers(config: McpServerConfig): Map<String, McpServerEntry> {
        return config.mcpServers.filterValues { !it.disabled }
    }
}

