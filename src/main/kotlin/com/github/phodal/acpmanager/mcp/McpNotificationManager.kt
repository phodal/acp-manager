package com.github.phodal.acpmanager.mcp

import com.github.phodal.acpmanager.ide.IdeAcpClient
import com.github.phodal.acpmanager.ide.IdeNotification
import com.github.phodal.acpmanager.ide.IdeNotifications
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val log = logger<McpNotificationManager>()

/**
 * MCP Notification Manager — bridges IDE notifications to MCP protocol.
 * 
 * Mirrors Claude Code's NotificationManager, converting IdeNotification events
 * into MCP notification messages sent to connected clients.
 */
class McpNotificationManager(
    private val project: Project,
    private val ideAcpClient: IdeAcpClient,
    private val scope: CoroutineScope
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private var mcpServer: Server? = null

    /**
     * Initialize notification forwarding from IDE to MCP server.
     */
    fun initialize(server: Server) {
        this.mcpServer = server
        
        // Register listener for IDE notifications
        ideAcpClient.ideNotifications.addListener { notification ->
            forwardNotificationToMcp(notification)
        }
        
        log.info("MCP notification manager initialized")
    }

    /**
     * Send IDE_CONNECTED notification to MCP clients.
     * This is sent when a new MCP client connects.
     */
    fun sendIdeConnected(pluginVersion: String, ideVersion: String) {
        scope.launch {
            try {
                val notification = IdeConnectedNotification(
                    pid = ProcessHandle.current().pid(),
                    pluginVersion = pluginVersion,
                    ideVersion = ideVersion,
                    isPluginVersionUnsupported = false
                )
                
                val payload = json.encodeToString(notification)
                // TODO: Implement notification sending when MCP SDK supports it
                // mcpServer?.sendNotification(NotificationMethods.IDE_CONNECTED, payload)

                log.info("IDE_CONNECTED notification prepared (sending not yet implemented)")
            } catch (e: Exception) {
                log.warn("Error sending IDE_CONNECTED notification", e)
            }
        }
    }

    /**
     * Forward IDE notification to MCP server.
     */
    private fun forwardNotificationToMcp(notification: IdeNotification) {
        scope.launch {
            try {
                when (notification) {
                    is IdeNotification.SelectionChanged -> {
                        val mcpNotification = SelectionChangedNotification(
                            range = CodeRange(
                                start = CursorPosition(
                                    line = notification.startLine,
                                    character = notification.startColumn
                                ),
                                end = CursorPosition(
                                    line = notification.endLine,
                                    character = notification.endColumn
                                )
                            ),
                            text = notification.selectedText,
                            filePath = notification.filePath
                        )
                        
                        val payload = json.encodeToString(mcpNotification)
                        // TODO: Implement notification sending when MCP SDK supports it
                        // mcpServer?.sendNotification(NotificationMethods.SELECTION_CHANGED, payload)
                    }

                    is IdeNotification.AtMentioned -> {
                        val mcpNotification = AtMentionedNotification(
                            filePath = notification.filePath,
                            startLine = notification.startLine,
                            endLine = notification.endLine
                        )
                        
                        val payload = json.encodeToString(mcpNotification)
                        // TODO: Implement notification sending when MCP SDK supports it
                        // mcpServer?.sendNotification(NotificationMethods.AT_MENTIONED, payload)
                    }

                    is IdeNotification.DiagnosticsChanged -> {
                        val mcpNotification = DiagnosticsChangedNotification(
                            uri = notification.uri
                        )
                        
                        val payload = json.encodeToString(mcpNotification)
                        // TODO: Implement notification sending when MCP SDK supports it
                        // mcpServer?.sendNotification(NotificationMethods.DIAGNOSTICS_CHANGED, payload)
                    }
                }
            } catch (e: Exception) {
                log.warn("Error forwarding notification to MCP: ${notification.method}", e)
            }
        }
    }

    /**
     * Cleanup when shutting down.
     */
    fun dispose() {
        mcpServer = null
        log.info("MCP notification manager disposed")
    }
}

