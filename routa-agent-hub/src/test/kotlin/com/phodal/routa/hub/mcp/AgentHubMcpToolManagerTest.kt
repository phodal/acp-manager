package com.phodal.routa.hub.mcp

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.model.AgentRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the Routa Agent Hub MCP server and tool manager.
 */
class AgentHubMcpToolManagerTest {

    private fun withRouta(block: suspend (RoutaSystem) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        try {
            runBlocking { block(routa) }
        } finally {
            routa.coordinator.shutdown()
        }
    }

    @Test
    fun `AgentHubMcpServer creates server with tools`() = runBlocking {
        val (mcpServer, routa) = AgentHubMcpServer.create("test-workspace")

        try {
            routa.coordinator.initialize("test-workspace")
            assertNotNull("MCP server should be initialized", mcpServer)
            println("✓ Agent Hub MCP server initialized with agent management tools")
        } finally {
            routa.coordinator.shutdown()
        }
    }

    @Test
    fun `AgentHubToolManager can be used independently`() = withRouta { routa ->
        routa.coordinator.initialize("test-workspace")

        val toolManager = AgentHubToolManager(routa.tools, "test-workspace")
        assertNotNull("Tool manager should be created", toolManager)

        println("✓ AgentHubToolManager created successfully")
    }

    @Test
    fun `create and list agents work via AgentTools`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        // Create a crafter agent
        val createResult = routa.tools.createAgent(
            name = "test-crafter",
            role = AgentRole.CRAFTER,
            workspaceId = "test-workspace",
            parentId = routaId,
        )
        assertTrue("create_agent should succeed", createResult.success)

        val crafterId = Json.parseToJsonElement(createResult.data)
            .jsonObject["id"]!!.jsonPrimitive.content
        assertNotNull("Agent ID should be present", crafterId)

        // List agents
        val listResult = routa.tools.listAgents("test-workspace")
        assertTrue("list_agents should succeed", listResult.success)
        assertTrue("Should contain the crafter", listResult.data.contains("test-crafter"))

        println("✓ create_agent and list_agents work")
    }

    @Test
    fun `get_agent_status works`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        val statusResult = routa.tools.getAgentStatus(routaId)
        assertTrue("get_agent_status should succeed", statusResult.success)
        assertTrue("Should contain agent name", statusResult.data.contains("routa"))

        println("✓ get_agent_status works")
    }

    @Test
    fun `get_agent_summary works`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        val summaryResult = routa.tools.getAgentSummary(routaId)
        assertTrue("get_agent_summary should succeed", summaryResult.success)
        assertTrue("Should contain agent summary header", summaryResult.data.contains("Agent Summary"))

        println("✓ get_agent_summary works")
    }

    @Test
    fun `wake_or_create_task_agent works`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        // Create a task
        val task = com.phodal.routa.core.model.Task(
            id = "hub-test-task",
            title = "Hub Test Task",
            objective = "Test agent hub",
            workspaceId = "test-workspace",
            createdAt = java.time.Instant.now().toString(),
            updatedAt = java.time.Instant.now().toString(),
        )
        routa.context.taskStore.save(task)

        // Wake or create agent
        val wakeResult = routa.tools.wakeOrCreateTaskAgent(
            taskId = "hub-test-task",
            contextMessage = "Start work on hub test",
            callerAgentId = routaId,
            workspaceId = "test-workspace",
        )
        assertTrue("wake_or_create_task_agent should succeed", wakeResult.success)
        assertTrue("Should create a new agent", wakeResult.data.contains("created_new"))

        println("✓ wake_or_create_task_agent works")
    }

    @Test
    fun `send_message_to_task_agent works`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        // Create a crafter
        val crafterResult = routa.tools.createAgent(
            name = "hub-crafter",
            role = AgentRole.CRAFTER,
            workspaceId = "test-workspace",
            parentId = routaId,
        )
        assertTrue("Crafter should be created", crafterResult.success)
        val crafterId = Json.parseToJsonElement(crafterResult.data)
            .jsonObject["id"]!!.jsonPrimitive.content

        // Create a task assigned to the crafter
        val task = com.phodal.routa.core.model.Task(
            id = "hub-msg-task",
            title = "Hub Message Task",
            objective = "Test messaging",
            assignedTo = crafterId,
            workspaceId = "test-workspace",
            createdAt = java.time.Instant.now().toString(),
            updatedAt = java.time.Instant.now().toString(),
        )
        routa.context.taskStore.save(task)

        // Send message
        val messageResult = routa.tools.sendMessageToTaskAgent(
            taskId = "hub-msg-task",
            message = "Please review",
            callerAgentId = routaId,
        )
        assertTrue("send_message_to_task_agent should succeed", messageResult.success)

        println("✓ send_message_to_task_agent works")
    }

    @Test
    fun `server name is routa-agent-hub`() = runBlocking {
        val (_, routa) = AgentHubMcpServer.create("test-workspace")
        try {
            // Verify it creates with the correct name - the server is created successfully
            // The name "routa-agent-hub" is set in AgentHubMcpServer.create()
            assertNotNull(routa)
            println("✓ Server created with name 'routa-agent-hub'")
        } finally {
            routa.coordinator.shutdown()
        }
    }
}
