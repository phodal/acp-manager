package com.phodal.routa.core.mcp

import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.CompletionReport
import com.phodal.routa.core.model.ModelTier
import com.phodal.routa.core.tool.AgentTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*

/**
 * Registers the 6 Routa coordination tools with an MCP [Server].
 *
 * This exposes the coordination tools over the Model Context Protocol,
 * allowing any MCP-compatible client (e.g., Claude, Cursor, VS Code)
 * to use them for multi-agent coordination.
 *
 * Usage:
 * ```kotlin
 * val mcpServer = Server(...)
 * RoutaMcpToolManager(agentTools, "my-workspace").registerTools(mcpServer)
 * ```
 */
class RoutaMcpToolManager(
    private val agentTools: AgentTools,
    private val defaultWorkspaceId: String,
) {

    /**
     * Register all 6 coordination tools with the MCP server.
     */
    fun registerTools(server: Server) {
        registerListAgents(server)
        registerReadAgentConversation(server)
        registerCreateAgent(server)
        registerDelegateTask(server)
        registerMessageAgent(server)
        registerReportToParent(server)
    }

    private fun registerListAgents(server: Server) {
        server.addTool(
            name = "list_agents",
            description = "List all agents in the workspace. Shows each agent's ID, name, role " +
                "(ROUTA/CRAFTER/GATE), status, and parent.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("workspaceId") {
                        put("type", "string")
                        put("description", "The workspace ID (uses default if empty)")
                    }
                },
                required = emptyList()
            )
        ) { request ->
            val workspaceId = request.arguments?.get("workspaceId")
                ?.jsonPrimitive?.contentOrNull ?: defaultWorkspaceId
            val result = agentTools.listAgents(workspaceId)
            toCallToolResult(result)
        }
    }

    private fun registerReadAgentConversation(server: Server) {
        server.addTool(
            name = "read_agent_conversation",
            description = "Read another agent's conversation history. Supports full history, " +
                "last N messages, or a specific turn range. Use to review work or avoid conflicts.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("agentId") {
                        put("type", "string")
                        put("description", "ID of the agent whose conversation to read")
                    }
                    putJsonObject("lastN") {
                        put("type", "integer")
                        put("description", "Only return the last N messages (optional)")
                    }
                    putJsonObject("includeToolCalls") {
                        put("type", "boolean")
                        put("description", "Include tool calls in the output (default: true)")
                    }
                },
                required = listOf("agentId")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val agentId = args["agentId"]!!.jsonPrimitive.content
            val lastN = args["lastN"]?.jsonPrimitive?.intOrNull
            val includeToolCalls = args["includeToolCalls"]?.jsonPrimitive?.booleanOrNull ?: true

            val result = agentTools.readAgentConversation(
                agentId = agentId,
                lastN = lastN,
                includeToolCalls = includeToolCalls,
            )
            toCallToolResult(result)
        }
    }

    private fun registerCreateAgent(server: Server) {
        server.addTool(
            name = "create_agent",
            description = "Create a new agent. Roles: ROUTA (coordinator), CRAFTER (implementor), " +
                "GATE (verifier). Model tiers: SMART (planning/verification), FAST (implementation).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Human-readable name for the agent")
                    }
                    putJsonObject("role") {
                        put("type", "string")
                        put("description", "Agent role: ROUTA, CRAFTER, or GATE")
                        putJsonArray("enum") {
                            add("ROUTA"); add("CRAFTER"); add("GATE")
                        }
                    }
                    putJsonObject("parentId") {
                        put("type", "string")
                        put("description", "ID of the parent agent (optional)")
                    }
                    putJsonObject("modelTier") {
                        put("type", "string")
                        put("description", "Model tier: SMART or FAST (optional, uses role default)")
                        putJsonArray("enum") {
                            add("SMART"); add("FAST")
                        }
                    }
                },
                required = listOf("name", "role")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val name = args["name"]!!.jsonPrimitive.content
            val roleStr = args["role"]!!.jsonPrimitive.content
            val parentId = args["parentId"]?.jsonPrimitive?.contentOrNull
            val modelTierStr = args["modelTier"]?.jsonPrimitive?.contentOrNull

            val role = try {
                AgentRole.valueOf(roleStr.uppercase())
            } catch (e: IllegalArgumentException) {
                return@addTool CallToolResult(
                    content = listOf(TextContent(text = "Invalid role: $roleStr")),
                    isError = true
                )
            }

            val modelTier = modelTierStr?.let {
                try { ModelTier.valueOf(it.uppercase()) } catch (e: IllegalArgumentException) { null }
            }

            val result = agentTools.createAgent(
                name = name,
                role = role,
                workspaceId = defaultWorkspaceId,
                parentId = parentId,
                modelTier = modelTier,
            )
            toCallToolResult(result)
        }
    }

    private fun registerDelegateTask(server: Server) {
        server.addTool(
            name = "delegate_task",
            description = "Delegate a task to a specific agent. Assigns the task and activates the agent.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("agentId") {
                        put("type", "string")
                        put("description", "ID of the agent to delegate to")
                    }
                    putJsonObject("taskId") {
                        put("type", "string")
                        put("description", "ID of the task to assign")
                    }
                    putJsonObject("callerAgentId") {
                        put("type", "string")
                        put("description", "ID of the agent performing the delegation")
                    }
                },
                required = listOf("agentId", "taskId", "callerAgentId")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val result = agentTools.delegate(
                agentId = args["agentId"]!!.jsonPrimitive.content,
                taskId = args["taskId"]!!.jsonPrimitive.content,
                callerAgentId = args["callerAgentId"]!!.jsonPrimitive.content,
            )
            toCallToolResult(result)
        }
    }

    private fun registerMessageAgent(server: Server) {
        server.addTool(
            name = "send_message_to_agent",
            description = "Send a message to another agent for inter-agent communication: " +
                "conflict reports, fix requests, additional context.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("fromAgentId") {
                        put("type", "string")
                        put("description", "ID of the sending agent")
                    }
                    putJsonObject("toAgentId") {
                        put("type", "string")
                        put("description", "ID of the receiving agent")
                    }
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "The message content")
                    }
                },
                required = listOf("fromAgentId", "toAgentId", "message")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val result = agentTools.messageAgent(
                fromAgentId = args["fromAgentId"]!!.jsonPrimitive.content,
                toAgentId = args["toAgentId"]!!.jsonPrimitive.content,
                message = args["message"]!!.jsonPrimitive.content,
            )
            toCallToolResult(result)
        }
    }

    private fun registerReportToParent(server: Server) {
        server.addTool(
            name = "report_to_parent",
            description = "Send a completion report to the parent agent. REQUIRED for all delegated " +
                "agents (Crafter and Gate). Include: what you did, verification results, risks.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("agentId") {
                        put("type", "string")
                        put("description", "ID of the reporting agent")
                    }
                    putJsonObject("taskId") {
                        put("type", "string")
                        put("description", "ID of the task being reported on")
                    }
                    putJsonObject("summary") {
                        put("type", "string")
                        put("description", "1-3 sentence summary: what you did, verification, risks")
                    }
                    putJsonObject("filesModified") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "List of files that were modified")
                    }
                    putJsonObject("success") {
                        put("type", "boolean")
                        put("description", "Whether the task was completed successfully")
                    }
                },
                required = listOf("agentId", "taskId", "summary")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val agentId = args["agentId"]!!.jsonPrimitive.content
            val filesModified = args["filesModified"]?.jsonArray
                ?.map { it.jsonPrimitive.content } ?: emptyList()
            val success = args["success"]?.jsonPrimitive?.booleanOrNull ?: true

            val report = CompletionReport(
                agentId = agentId,
                taskId = args["taskId"]!!.jsonPrimitive.content,
                summary = args["summary"]!!.jsonPrimitive.content,
                filesModified = filesModified,
                success = success,
            )

            // Log the report for verification
            println("🎯 MCP Tool Called: report_to_parent")
            println("   Agent: $agentId")
            println("   Task: ${report.taskId}")
            println("   Success: ${report.success}")
            println("   Summary: ${report.summary}")
            if (filesModified.isNotEmpty()) {
                println("   Files: ${filesModified.joinToString(", ")}")
            }

            val result = agentTools.reportToParent(agentId, report)
            toCallToolResult(result)
        }
    }

    private fun toCallToolResult(result: com.phodal.routa.core.tool.ToolResult): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(text = if (result.success) result.data else (result.error ?: "Unknown error"))),
            isError = !result.success
        )
    }
}
