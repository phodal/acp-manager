# MCP (Model Context Protocol) Integration

This package provides MCP protocol support for the ACP Manager plugin, enabling WebSocket-based communication with MCP-compatible clients and connection to external MCP servers.

## Architecture

### Server Mode (IDE as MCP Server)

The plugin can act as an MCP server, exposing IDE capabilities to MCP clients:

- **McpService** - Manages WebSocket server lifecycle
- **McpToolManager** - Registers IDE tools (openFile, reformat, diff, diagnostics, etc.)
- **McpNotificationManager** - Forwards IDE events to MCP clients
- **McpModels** - MCP-specific data models

### Client Mode (Connect to External MCP Servers)

The plugin can connect to external MCP servers like memory, filesystem, etc.:

- **McpClientConnector** - Connects to external MCP servers via stdio
- **McpServerConfig** - Configuration for external MCP servers
- **McpConfigLoader** - Loads MCP server configurations

## Usage

### Starting the MCP Server

```kotlin
val mcpService = McpService.getInstance(project)
mcpService.start(port = 3000) // Default: ws://127.0.0.1:3000/mcp
```

### Connecting with MCP Inspector

```bash
npx -y @modelcontextprotocol/inspector --connect ws://localhost:3000/mcp
```

### Connecting to External MCP Servers

```kotlin
// Load configuration
val config = McpConfigLoader.loadFromFile(File("mcp-config.json"))

// Connect to memory server
val connector = McpClientConnector(
    name = "memory",
    command = "npx",
    args = listOf("-y", "@modelcontextprotocol/server-memory")
)
connector.start()
val tools = connector.listTools()
val result = connector.callTool("store_memory", mapOf("key" to "test", "value" to "data"))
connector.stop()
```

## MCP Server Configuration

Example configuration file (`mcp-config.json`):

```json
{
  "mcpServers": {
    "memory": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-memory"]
    },
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/allowed/files"]
    },
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": {
        "GITHUB_PERSONAL_ACCESS_TOKEN": "your-token"
      },
      "disabled": true
    }
  }
}
```

## Available Tools

### IDE Tools (Server Mode)

1. **openFile** - Opens a file in the IDE
2. **open_files** - Opens multiple files
3. **close_tab** - Closes an editor tab
4. **get_open_files** - Lists all open files
5. **reformat_file** - Formats code using IDE formatter
6. **open_diff** - Shows diff view with accept/reject UI
7. **get_diagnostics** - Gets errors and warnings

### External MCP Servers (Client Mode)

- **memory** - Key-value storage
- **filesystem** - File operations
- **brave-search** - Web search
- **github** - GitHub operations
- **postgres** - Database access
- **puppeteer** - Browser automation
- **slack** - Slack integration

## Testing

### Run All Tests

```bash
./gradlew test --tests "com.github.phodal.acpmanager.mcp.*"
```

### Test Files

- **McpServerTest** - Server lifecycle and WebSocket connections
- **McpToolCallTest** - Tool invocation
- **McpConfigTest** - Configuration loading
- **McpClientConnectorTest** - Integration with real MCP servers (requires npx)

### Integration Tests

```bash
# Install npx first
npm install -g npx

# Run integration tests
./gradlew test --tests "McpClientConnectorTest"
```

## Dependencies

```kotlin
// MCP Kotlin SDK
implementation("io.modelcontextprotocol:kotlin-sdk:0.8.3")

// Ktor WebSocket Server
implementation("io.ktor:ktor-server-core:3.0.3")
implementation("io.ktor:ktor-server-websockets:3.0.3")
implementation("io.ktor:ktor-server-cio:3.0.3")
```

## Protocol Details

- **Transport**: WebSocket (server mode) or stdio (client mode)
- **Protocol**: JSON-RPC 2.0
- **Version**: MCP 2024-11-05
- **Endpoint**: `/mcp` (WebSocket)
- **Port**: 3000 (default, configurable)

## Known Limitations

1. **Notification Sending** - MCP SDK doesn't expose `sendNotification` API yet
2. **Single Server Instance** - Only one MCP server can run per project
3. **No Authentication** - WebSocket connections are not authenticated

## Future Enhancements

- [ ] Implement notification sending when SDK supports it
- [ ] Add authentication for WebSocket connections
- [ ] Support for MCP prompts and resources
- [ ] Configuration UI for MCP settings
- [ ] MCP server process management
- [ ] TLS/WSS support

