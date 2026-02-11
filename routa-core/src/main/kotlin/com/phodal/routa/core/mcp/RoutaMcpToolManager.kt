package com.phodal.routa.core.mcp

import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.CompletionReport
import com.phodal.routa.core.model.ModelTier
import com.phodal.routa.core.tool.AgentTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*

/**
 * Registers the 12 Routa coordination tools with an MCP [Server].
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
     * Register all 12 coordination tools with the MCP server.
     */
    fun registerTools(server: Server) {
        // Core coordination tools
        registerListAgents(server)
        registerReadAgentConversation(server)
        registerCreateAgent(server)
        registerDelegateTask(server)
        registerMessageAgent(server)
        registerReportToParent(server)
        // Task-agent lifecycle tools
        registerWakeOrCreateTaskAgent(server)
        registerSendMessageToTaskAgent(server)
        registerGetAgentStatus(server)
        registerGetAgentSummary(server)
        // Event subscription tools
        registerSubscribeToEvents(server)
        registerUnsubscribeFromEvents(server)
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

    private fun registerWakeOrCreateTaskAgent(server: Server) {
        server.addTool(
            name = "wake_or_create_task_agent",
            description = "Wake an existing agent or create a new one for a task. " +
                "Checks if the task has an active/pending agent and wakes it with the context message. " +
                "If no viable agent, creates a new Crafter and assigns it. " +
                "Use when task dependencies become ready.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("taskId") {
                        put("type", "string")
                        put("description", "ID of the task to wake or create an agent for")
                    }
                    putJsonObject("contextMessage") {
                        put("type", "string")
                        put("description", "Message with synthesized context from completed dependencies")
                    }
                    putJsonObject("callerAgentId") {
                        put("type", "string")
                        put("description", "ID of the calling agent (for auditing)")
                    }
                    putJsonObject("agentName") {
                        put("type", "string")
                        put("description", "Optional custom name for a new agent")
                    }
                    putJsonObject("modelTier") {
                        put("type", "string")
                        put("description", "Model tier for a new agent: SMART or FAST (optional)")
                        putJsonArray("enum") {
                            add("SMART"); add("FAST")
                        }
                    }
                },
                required = listOf("taskId", "contextMessage", "callerAgentId")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val modelTierStr = args["modelTier"]?.jsonPrimitive?.contentOrNull
            val modelTier = modelTierStr?.let {
                try {
                    com.phodal.routa.core.model.ModelTier.valueOf(it.uppercase())
                } catch (e: IllegalArgumentException) { null }
            }

            val result = agentTools.wakeOrCreateTaskAgent(
                taskId = args["taskId"]!!.jsonPrimitive.content,
                contextMessage = args["contextMessage"]!!.jsonPrimitive.content,
                callerAgentId = args["callerAgentId"]!!.jsonPrimitive.content,
                workspaceId = defaultWorkspaceId,
                agentName = args["agentName"]?.jsonPrimitive?.contentOrNull,
                modelTier = modelTier,
            )
            toCallToolResult(result)
        }
    }

    private fun registerSendMessageToTaskAgent(server: Server) {
        server.addTool(
            name = "send_message_to_task_agent",
            description = "Send a message to the agent working on a specific task. " +
                "More convenient than send_message_to_agent — you only need the task ID. " +
                "Use to ask corrections, provide context, or request changes.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("taskId") {
                        put("type", "string")
                        put("description", "ID of the task whose agent should receive the message")
                    }
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "The message content. Be specific about what changes or corrections you need.")
                    }
                    putJsonObject("callerAgentId") {
                        put("type", "string")
                        put("description", "ID of the sending agent")
                    }
                },
                required = listOf("taskId", "message", "callerAgentId")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val result = agentTools.sendMessageToTaskAgent(
                taskId = args["taskId"]!!.jsonPrimitive.content,
                message = args["message"]!!.jsonPrimitive.content,
                callerAgentId = args["callerAgentId"]!!.jsonPrimitive.content,
            )
            toCallToolResult(result)
        }
    }

    private fun registerGetAgentStatus(server: Server) {
        server.addTool(
            name = "get_agent_status",
            description = "Get detailed status of a specific agent including current status, " +
                "role, message count, assigned tasks, and timestamps.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("agentId") {
                        put("type", "string")
                        put("description", "ID of the agent to check")
                    }
                },
                required = listOf("agentId")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val result = agentTools.getAgentStatus(
                agentId = args["agentId"]!!.jsonPrimitive.content,
            )
            toCallToolResult(result)
        }
    }

    private fun registerGetAgentSummary(server: Server) {
        server.addTool(
            name = "get_agent_summary",
            description = "Get a summary of what an agent did. Returns status, last response, " +
                "tool call counts, and assigned tasks. Quick overview before reading full conversation.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("agentId") {
                        put("type", "string")
                        put("description", "ID of the agent to summarize")
                    }
                },
                required = listOf("agentId")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val result = agentTools.getAgentSummary(
                agentId = args["agentId"]!!.jsonPrimitive.content,
            )
            toCallToolResult(result)
        }
    }

    private fun registerSubscribeToEvents(server: Server) {
        server.addTool(
            name = "subscribe_to_events",
            description = "Subscribe to workspace events. You will be notified when matching events occur. " +
                "Event types: \"agent:*\" (all agent events), \"agent:created\", \"agent:completed\", " +
                "\"agent:status_changed\", \"agent:message\", \"task:*\" (all task events), " +
                "\"task:status_changed\", \"task:delegated\", \"*\" (all events).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("agentId") {
                        put("type", "string")
                        put("description", "ID of the subscribing agent")
                    }
                    putJsonObject("agentName") {
                        put("type", "string")
                        put("description", "Name of the subscribing agent (optional)")
                    }
                    putJsonObject("eventTypes") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Event type patterns to match (e.g., [\"agent:*\", \"task:completed\"])")
                    }
                    putJsonObject("excludeSelf") {
                        put("type", "boolean")
                        put("description", "Exclude events caused by yourself (default: true)")
                    }
                },
                required = listOf("agentId", "eventTypes")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val agentId = args["agentId"]!!.jsonPrimitive.content
            val agentName = args["agentName"]?.jsonPrimitive?.contentOrNull ?: ""
            val eventTypes = args["eventTypes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("*")
            val excludeSelf = args["excludeSelf"]?.jsonPrimitive?.booleanOrNull ?: true

            val result = agentTools.subscribeToEvents(
                agentId = agentId,
                agentName = agentName,
                eventTypes = eventTypes,
                excludeSelf = excludeSelf,
            )
            toCallToolResult(result)
        }
    }

    private fun registerUnsubscribeFromEvents(server: Server) {
        server.addTool(
            name = "unsubscribe_from_events",
            description = "Unsubscribe from workspace events. " +
                "Use the subscription ID returned from subscribe_to_events.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("subscriptionId") {
                        put("type", "string")
                        put("description", "The subscription ID to cancel")
                    }
                },
                required = listOf("subscriptionId")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val subscriptionId = args["subscriptionId"]!!.jsonPrimitive.content

            val result = agentTools.unsubscribeFromEvents(subscriptionId)
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
