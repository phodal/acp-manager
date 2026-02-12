package com.phodal.routa.hub.a2a

import com.phodal.routa.core.tool.ToolResult
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Registers A2A protocol tools with an MCP [Server].
 *
 * This extends the Agent Hub with A2A (Agent-to-Agent) protocol capabilities,
 * enabling MCP clients to:
 * 1. Discover remote A2A agents by fetching their agent cards
 * 2. Send messages to remote A2A agents
 * 3. Query and cancel A2A tasks
 * 4. View the local A2A agent card (how this hub appears to remote A2A clients)
 *
 * These tools complement the existing agent management tools in [AgentHubToolManager][com.phodal.routa.hub.mcp.AgentHubToolManager],
 * adding cross-system agent interoperability via the A2A protocol.
 *
 * Tools registered:
 * 1. `a2a_discover_agent` — Fetch a remote agent's A2A card
 * 2. `a2a_send_message` — Send a message to a remote A2A agent
 * 3. `a2a_get_task` — Get task status from a remote A2A agent
 * 4. `a2a_cancel_task` — Cancel a task on a remote A2A agent
 * 5. `a2a_get_agent_card` — Get this hub's own A2A agent card
 *
 * @param a2aServer The A2A server instance (for the local agent card).
 */
class A2AToolManager(
    private val a2aServer: A2AServer?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // Cache of A2A clients per URL to avoid creating new clients for each request
    private val clients = java.util.concurrent.ConcurrentHashMap<String, A2AClient>()

    /**
     * Register all A2A protocol tools with the MCP server.
     */
    fun registerTools(server: Server) {
        registerDiscoverAgent(server)
        registerSendMessage(server)
        registerGetTask(server)
        registerCancelTask(server)
        registerGetAgentCard(server)
    }

    private fun registerDiscoverAgent(server: Server) {
        server.addTool(
            name = "a2a_discover_agent",
            description = "Discover a remote A2A agent by fetching its agent card. " +
                "The agent card describes the agent's capabilities, skills, and supported protocols. " +
                "Use this to find out what a remote agent can do before sending messages.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("agentUrl") {
                        put("type", "string")
                        put("description", "Base URL of the A2A agent (e.g., http://localhost:10001)")
                    }
                },
                required = listOf("agentUrl")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val agentUrl = args["agentUrl"]!!.jsonPrimitive.content

            val result = runBlocking {
                discoverAgent(agentUrl)
            }
            toCallToolResult(result)
        }
    }

    private fun registerSendMessage(server: Server) {
        server.addTool(
            name = "a2a_send_message",
            description = "Send a text message to a remote A2A agent. " +
                "Returns a task representing the agent's response. " +
                "The task may be in progress (agent is still working) or completed.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("agentUrl") {
                        put("type", "string")
                        put("description", "Base URL of the A2A agent")
                    }
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "The text message to send")
                    }
                    putJsonObject("contextId") {
                        put("type", "string")
                        put("description", "Optional context ID for conversation threading")
                    }
                },
                required = listOf("agentUrl", "message")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val agentUrl = args["agentUrl"]!!.jsonPrimitive.content
            val message = args["message"]!!.jsonPrimitive.content
            val contextId = args["contextId"]?.jsonPrimitive?.contentOrNull

            val result = runBlocking {
                sendMessage(agentUrl, message, contextId)
            }
            toCallToolResult(result)
        }
    }

    private fun registerGetTask(server: Server) {
        server.addTool(
            name = "a2a_get_task",
            description = "Get the current state of a task from a remote A2A agent. " +
                "Use this to check on the progress of a previously sent message.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("agentUrl") {
                        put("type", "string")
                        put("description", "Base URL of the A2A agent")
                    }
                    putJsonObject("taskId") {
                        put("type", "string")
                        put("description", "The task ID to query")
                    }
                },
                required = listOf("agentUrl", "taskId")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val agentUrl = args["agentUrl"]!!.jsonPrimitive.content
            val taskId = args["taskId"]!!.jsonPrimitive.content

            val result = runBlocking {
                getTask(agentUrl, taskId)
            }
            toCallToolResult(result)
        }
    }

    private fun registerCancelTask(server: Server) {
        server.addTool(
            name = "a2a_cancel_task",
            description = "Cancel a running task on a remote A2A agent.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("agentUrl") {
                        put("type", "string")
                        put("description", "Base URL of the A2A agent")
                    }
                    putJsonObject("taskId") {
                        put("type", "string")
                        put("description", "The task ID to cancel")
                    }
                },
                required = listOf("agentUrl", "taskId")
            )
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            val agentUrl = args["agentUrl"]!!.jsonPrimitive.content
            val taskId = args["taskId"]!!.jsonPrimitive.content

            val result = runBlocking {
                cancelTask(agentUrl, taskId)
            }
            toCallToolResult(result)
        }
    }

    private fun registerGetAgentCard(server: Server) {
        server.addTool(
            name = "a2a_get_agent_card",
            description = "Get this hub's own A2A agent card. " +
                "Shows what capabilities, skills, and protocols this Routa Agent Hub exposes " +
                "to remote A2A clients.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList()
            )
        ) { _ ->
            val result = getLocalAgentCard()
            toCallToolResult(result)
        }
    }

    // ── Tool Implementations ────────────────────────────────────────

    internal suspend fun discoverAgent(agentUrl: String): ToolResult {
        val client = getOrCreateClient(agentUrl)
        val card = client.fetchAgentCard()
            ?: return ToolResult.fail("Failed to fetch agent card from $agentUrl")

        return ToolResult.ok(json.encodeToString(card))
    }

    internal suspend fun sendMessage(agentUrl: String, message: String, contextId: String?): ToolResult {
        val client = getOrCreateClient(agentUrl)
        val task = client.sendMessage(message, contextId)
            ?: return ToolResult.fail("Failed to send message to $agentUrl")

        return ToolResult.ok(json.encodeToString(task))
    }

    internal suspend fun getTask(agentUrl: String, taskId: String): ToolResult {
        val client = getOrCreateClient(agentUrl)
        val task = client.getTask(taskId)
            ?: return ToolResult.fail("Failed to get task $taskId from $agentUrl")

        return ToolResult.ok(json.encodeToString(task))
    }

    internal suspend fun cancelTask(agentUrl: String, taskId: String): ToolResult {
        val client = getOrCreateClient(agentUrl)
        val task = client.cancelTask(taskId)
            ?: return ToolResult.fail("Failed to cancel task $taskId on $agentUrl")

        return ToolResult.ok(json.encodeToString(task))
    }

    internal fun getLocalAgentCard(): ToolResult {
        val server = a2aServer ?: return ToolResult.fail("A2A server is not configured")
        val card = server.buildAgentCard()
        return ToolResult.ok(json.encodeToString(card))
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun getOrCreateClient(agentUrl: String): A2AClient {
        return clients.getOrPut(agentUrl) { A2AClient(agentUrl) }
    }

    /**
     * Close all cached A2A clients, releasing HTTP resources.
     * Should be called during shutdown.
     */
    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private fun toCallToolResult(result: ToolResult): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(text = if (result.success) result.data else (result.error ?: "Unknown error"))),
            isError = !result.success
        )
    }
}
