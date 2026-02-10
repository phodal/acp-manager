package com.github.phodal.acpmanager.mcp

import com.github.phodal.acpmanager.ide.IdeAcpClient
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

private val log = logger<McpService>()

/**
 * MCP Service — manages the MCP WebSocket server lifecycle.
 * 
 * Mirrors Claude Code's MCPService architecture, providing:
 * - WebSocket server for MCP protocol
 * - Tool registration and management
 * - Notification broadcasting
 * - Multiple client connection support
 */
@Service(Service.Level.PROJECT)
class McpService(private val project: Project) : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ktorServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var mcpServer: Server? = null
    private var toolManager: McpToolManager? = null
    private var notificationManager: McpNotificationManager? = null

    private val ideAcpClient: IdeAcpClient by lazy {
        IdeAcpClient.getInstance(project)
    }

    /**
     * Start the MCP WebSocket server on the specified port.
     */
    fun start(port: Int = 3000, host: String = "127.0.0.1") {
        if (ktorServer != null) {
            log.warn("MCP server already running")
            return
        }

        try {
            // Create MCP server instance
            val server = Server(
                serverInfo = Implementation(
                    name = "acp-manager-mcp",
                    version = "0.0.1"
                ),
                options = io.modelcontextprotocol.kotlin.sdk.server.ServerOptions(
                    capabilities = ServerCapabilities(
                        tools = ServerCapabilities.Tools(listChanged = false)
                    )
                )
            )

            this.mcpServer = server

            // Initialize tool manager
            val tools = McpToolManager(project, ideAcpClient)
            tools.registerTools(server)
            this.toolManager = tools

            // Initialize notification manager
            val notifications = McpNotificationManager(project, ideAcpClient, scope)
            notifications.initialize(server)
            this.notificationManager = notifications

            // Start Ktor WebSocket server
            val engine = embeddedServer(CIO, host = host, port = port) {
                install(WebSockets) {
                    pingPeriod = 15.seconds
                    timeout = 15.seconds
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }

                routing {
                    webSocket("/mcp") {
                        log.info("MCP WebSocket client connected")
                        
                        try {
                            // Send IDE_CONNECTED notification
                            notificationManager?.sendIdeConnected(
                                pluginVersion = "0.0.1",
                                ideVersion = "IntelliJ IDEA"
                            )

                            // Handle WebSocket messages
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        log.debug("Received MCP message: $text")
                                        // MCP SDK will handle protocol messages
                                        // This is a simplified version - full implementation
                                        // would use MCP SDK's WebSocket transport
                                    }
                                    else -> {
                                        log.debug("Received non-text frame: ${frame.frameType}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            log.warn("WebSocket error", e)
                        } finally {
                            log.info("MCP WebSocket client disconnected")
                        }
                    }
                }
            }.start(wait = false)

            ktorServer = engine

            log.info("MCP WebSocket server started on ws://$host:$port/mcp")
        } catch (e: Exception) {
            log.error("Failed to start MCP server", e)
            throw e
        }
    }

    /**
     * Stop the MCP WebSocket server.
     */
    fun stop() {
        try {
            ktorServer?.stop(1000, 2000)
            ktorServer = null
            mcpServer = null
            toolManager = null
            notificationManager?.dispose()
            notificationManager = null
            log.info("MCP server stopped")
        } catch (e: Exception) {
            log.warn("Error stopping MCP server", e)
        }
    }

    override fun dispose() {
        stop()
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): McpService {
            return project.getService(McpService::class.java)
        }
    }
}

