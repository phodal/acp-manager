package com.github.phodal.acpmanager.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*

private val log = logger<IdeAcpClient>()

/**
 * IdeAcpClient — bridges ACP agent sessions with IntelliJ IDEA's IDE capabilities.
 *
 * Inspired by Claude Code's MCPService/ToolManager/NotificationManager architecture,
 * this class provides IDE integration features for ACP agents:
 *
 * ## Tools (IDE capabilities exposed to agents):
 * - **reformat_file** — Format a file using IDEA's code formatter
 * - **open_file** — Open a file in the editor
 * - **open_files** — Open multiple files at once
 * - **close_tab** — Close an editor tab
 * - **get_open_files** — List all open file paths
 * - **open_diff** — Show a diff view with accept/reject for proposed changes
 * - **get_diagnostics** — Get diagnostic info (errors, warnings) for a file
 *
 * ## Notifications (IDE events pushed to agents):
 * - **selection_changed** — Editor selection changes (file, range, text)
 * - **diagnostics_changed** — File diagnostics updated
 * - **at_mentioned** — User explicitly sends context to the agent
 *
 * Unlike Claude Code which uses MCP over WebSocket, this integrates directly
 * via the ACP ClientSessionOperations interface.
 */
@Service(Service.Level.PROJECT)
class IdeAcpClient(private val project: Project) : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val ideTools = IdeTools(project)
    val ideNotifications = IdeNotifications(project, scope)
    val ideSelectionTracker = IdeSelectionTracker(project, scope)

    /**
     * Initialize IDE integration: start tracking editor events.
     */
    fun initialize() {
        log.info("IdeAcpClient initialized for project '${project.name}'")
        ideSelectionTracker.startTracking { notification ->
            ideNotifications.broadcastNotification(notification)
        }
    }

    /**
     * Handle a tool call from the agent.
     * Returns the tool result as a string.
     */
    suspend fun handleToolCall(toolName: String, args: Map<String, Any?>): ToolCallResult {
        return when (toolName) {
            "reformat_file" -> {
                val filePath = args["file_path"] as? String
                    ?: return ToolCallResult.error("Missing file_path argument")
                ideTools.reformatFile(filePath)
            }

            "open_file", "openFile" -> {
                val filePath = args["filePath"] as? String ?: args["file_path"] as? String
                    ?: return ToolCallResult.error("Missing filePath argument")
                val makeFrontmost = args["makeFrontmost"] as? Boolean ?: true
                ideTools.openFile(filePath, makeFrontmost)
            }

            "open_files" -> {
                @Suppress("UNCHECKED_CAST")
                val filePaths = args["file_paths"] as? List<String>
                    ?: return ToolCallResult.error("Missing file_paths argument")
                ideTools.openFiles(filePaths)
            }

            "close_tab" -> {
                val tabName = args["tab_name"] as? String
                    ?: return ToolCallResult.error("Missing tab_name argument")
                ideTools.closeTab(tabName)
            }

            "get_open_files", "get_all_opened_file_paths" -> {
                ideTools.getOpenFiles()
            }

            "open_diff", "openDiff" -> {
                val oldFilePath = args["old_file_path"] as? String
                    ?: return ToolCallResult.error("Missing old_file_path argument")
                val newFileContents = args["new_file_contents"] as? String
                    ?: return ToolCallResult.error("Missing new_file_contents argument")
                val tabName = args["tab_name"] as? String ?: "Diff"
                ideTools.openDiff(oldFilePath, newFileContents, tabName)
            }

            "get_diagnostics", "getDiagnostics" -> {
                val uri = args["uri"] as? String
                ideTools.getDiagnostics(uri)
            }

            else -> ToolCallResult.error("Unknown tool: $toolName")
        }
    }

    /**
     * Get the list of supported tool definitions.
     */
    fun getSupportedTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "reformat_file",
            description = "Reformats the specified file (entire file) in the IDE given an absolute file path.",
            parameters = mapOf("file_path" to ToolParam("string", required = true))
        ),
        ToolDefinition(
            name = "open_file",
            description = "Opens the specified file in the IDE.",
            parameters = mapOf(
                "filePath" to ToolParam("string", required = true),
                "makeFrontmost" to ToolParam("boolean", required = false)
            )
        ),
        ToolDefinition(
            name = "open_files",
            description = "Opens the specified files in the IDE using their absolute file paths.",
            parameters = mapOf("file_paths" to ToolParam("array", required = true))
        ),
        ToolDefinition(
            name = "close_tab",
            description = "Closes a tab in the IDE, given either the tab_name or the absolute file path.",
            parameters = mapOf("tab_name" to ToolParam("string", required = true))
        ),
        ToolDefinition(
            name = "get_open_files",
            description = "Gets a list of the absolute file paths that are currently open in the IDE.",
            parameters = emptyMap()
        ),
        ToolDefinition(
            name = "open_diff",
            description = "Opens a diff view in the IDE, showing the difference between the original file and proposed new content. The user can accept or reject the changes.",
            parameters = mapOf(
                "old_file_path" to ToolParam("string", required = true),
                "new_file_contents" to ToolParam("string", required = true),
                "tab_name" to ToolParam("string", required = false)
            )
        ),
        ToolDefinition(
            name = "get_diagnostics",
            description = "Gets diagnostic info (errors, warnings) for a file. If uri is omitted, uses the currently active file.",
            parameters = mapOf("uri" to ToolParam("string", required = false))
        ),
    )

    /**
     * Send an @mention notification to agents (user explicitly sending context).
     */
    fun sendAtMention(filePath: String, startLine: Int?, endLine: Int?) {
        val notification = IdeNotification.AtMentioned(
            filePath = filePath,
            startLine = startLine,
            endLine = endLine
        )
        ideNotifications.broadcastNotification(notification)
    }

    override fun dispose() {
        ideSelectionTracker.stopTracking()
        scope.cancel()
        log.info("IdeAcpClient disposed for project '${project.name}'")
    }

    companion object {
        fun getInstance(project: Project): IdeAcpClient {
            return project.getService(IdeAcpClient::class.java)
        }
    }
}
