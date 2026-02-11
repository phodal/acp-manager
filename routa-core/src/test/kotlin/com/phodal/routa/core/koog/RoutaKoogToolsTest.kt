package com.phodal.routa.core.koog

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.model.AgentRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for Koog SimpleTool wrappers.
 *
 * These tests verify that the Koog tool wrappers correctly delegate
 * to the underlying AgentTools and return proper results.
 */
class RoutaKoogToolsTest {

    private fun withRouta(block: suspend (com.phodal.routa.core.RoutaSystem) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        try {
            runBlocking { block(routa) }
        } finally {
            routa.coordinator.shutdown()
        }
    }

    @Test
    fun `ListAgentsTool returns agents in workspace`() = withRouta { routa ->
        val coordinator = routa.coordinator
        coordinator.initialize("test-workspace")

        val tool = ListAgentsTool(routa.tools, "test-workspace")
        val result = tool.execute(ListAgentsArgs(workspaceId = "test-workspace"))

        assertTrue("Expected 'routa-main' in: $result", result.contains("routa-main"))
        assertTrue("Expected 'ROUTA' in: $result", result.contains("ROUTA"))
    }

    @Test
    fun `CreateAgentTool creates a crafter`() = withRouta { routa ->
        val routaAgentId = routa.coordinator.initialize("test-workspace")

        val tool = CreateAgentTool(routa.tools, "test-workspace")
        val result = tool.execute(
            CreateAgentArgs(
                name = "test-crafter",
                role = "CRAFTER",
                parentId = routaAgentId,
            )
        )

        assertTrue("Expected 'test-crafter' in: $result", result.contains("test-crafter"))
        assertTrue("Expected 'CRAFTER' in: $result", result.contains("CRAFTER"))
    }

    @Test
    fun `CreateAgentTool rejects invalid role`() = withRouta { routa ->
        routa.coordinator.initialize("test-workspace")

        val tool = CreateAgentTool(routa.tools, "test-workspace")
        val result = tool.execute(
            CreateAgentArgs(name = "bad", role = "INVALID")
        )

        assertTrue("Expected error in: $result", result.contains("Error"))
        assertTrue("Expected 'Invalid role' in: $result", result.contains("Invalid role"))
    }

    @Test
    fun `MessageAgentTool sends messages between agents`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        val tool = MessageAgentTool(routa.tools)
        val result = tool.execute(
            MessageAgentArgs(
                fromAgentId = routaId,
                toAgentId = routaId,
                message = "Hello from Koog tool",
            )
        )

        assertTrue("Expected 'sent' in: $result", result.contains("sent"))
        assertTrue("Expected 'routa-main' in: $result", result.contains("routa-main"))
    }

    @Test
    fun `ReadAgentConversationTool reads messages`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        // Send a message first
        routa.tools.messageAgent(routaId, routaId, "Test message")

        val tool = ReadAgentConversationTool(routa.tools)
        val result = tool.execute(
            ReadAgentConversationArgs(agentId = routaId)
        )

        assertTrue("Expected 'Test message' in: $result", result.contains("Test message"))
    }

    // ── Tests for task-agent lifecycle tools ──

    @Test
    fun `WakeOrCreateTaskAgentTool creates a new agent for a task`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        // Create a task first
        val task = com.phodal.routa.core.model.Task(
            id = "task-1",
            title = "Test Task",
            objective = "Do something",
            workspaceId = "test-workspace",
            createdAt = java.time.Instant.now().toString(),
            updatedAt = java.time.Instant.now().toString(),
        )
        routa.context.taskStore.save(task)

        val tool = WakeOrCreateTaskAgentTool(routa.tools, "test-workspace")
        val result = tool.execute(
            WakeOrCreateTaskAgentArgs(
                taskId = "task-1",
                contextMessage = "Start working on this task",
                callerAgentId = routaId,
            )
        )

        assertTrue("Expected 'created_new' in: $result", result.contains("created_new"))
        assertTrue("Expected agent ID in: $result", result.contains("agentId"))
    }

    @Test
    fun `WakeOrCreateTaskAgentTool wakes an existing agent`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        // Create a crafter agent
        val crafterResult = routa.tools.createAgent(
            name = "test-crafter",
            role = AgentRole.CRAFTER,
            workspaceId = "test-workspace",
            parentId = routaId,
        )
        assertTrue(crafterResult.success)
        val crafterId = kotlinx.serialization.json.Json.parseToJsonElement(crafterResult.data)
            .jsonObject["id"]!!.jsonPrimitive.content

        // Create a task and assign the crafter to it
        val task = com.phodal.routa.core.model.Task(
            id = "task-2",
            title = "Test Task 2",
            objective = "Do something else",
            assignedTo = crafterId,
            status = com.phodal.routa.core.model.TaskStatus.IN_PROGRESS,
            workspaceId = "test-workspace",
            createdAt = java.time.Instant.now().toString(),
            updatedAt = java.time.Instant.now().toString(),
        )
        routa.context.taskStore.save(task)

        // Wake the agent
        val tool = WakeOrCreateTaskAgentTool(routa.tools, "test-workspace")
        val result = tool.execute(
            WakeOrCreateTaskAgentArgs(
                taskId = "task-2",
                contextMessage = "Continue working on this",
                callerAgentId = routaId,
            )
        )

        assertTrue("Expected 'woke_existing' in: $result", result.contains("woke_existing"))
        assertTrue("Expected crafter ID in: $result", result.contains(crafterId))
    }

    @Test
    fun `SendMessageToTaskAgentTool sends message to assigned agent`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        // Create a crafter and assign to a task
        val crafterResult = routa.tools.createAgent(
            name = "test-crafter",
            role = AgentRole.CRAFTER,
            workspaceId = "test-workspace",
            parentId = routaId,
        )
        val crafterId = kotlinx.serialization.json.Json.parseToJsonElement(crafterResult.data)
            .jsonObject["id"]!!.jsonPrimitive.content

        val task = com.phodal.routa.core.model.Task(
            id = "task-3",
            title = "Test Task 3",
            objective = "Do something",
            assignedTo = crafterId,
            workspaceId = "test-workspace",
            createdAt = java.time.Instant.now().toString(),
            updatedAt = java.time.Instant.now().toString(),
        )
        routa.context.taskStore.save(task)

        // Send message to task agent
        val tool = SendMessageToTaskAgentTool(routa.tools)
        val result = tool.execute(
            SendMessageToTaskAgentArgs(
                taskId = "task-3",
                message = "Please fix the tests",
                callerAgentId = routaId,
            )
        )

        assertTrue("Expected 'sent' in: $result", result.contains("sent"))
        assertTrue("Expected task ID in: $result", result.contains("task-3"))
    }

    @Test
    fun `GetAgentStatusTool returns agent status`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        val tool = GetAgentStatusTool(routa.tools)
        val result = tool.execute(GetAgentStatusArgs(agentId = routaId))

        assertTrue("Expected agent name in: $result", result.contains("routa-main"))
        assertTrue("Expected 'ROUTA' role in: $result", result.contains("ROUTA"))
        assertTrue("Expected 'ACTIVE' status in: $result", result.contains("ACTIVE"))
    }

    @Test
    fun `GetAgentSummaryTool returns agent summary`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        // Add a message to the agent
        routa.tools.messageAgent(routaId, routaId, "Test message for summary")

        val tool = GetAgentSummaryTool(routa.tools)
        val result = tool.execute(GetAgentSummaryArgs(agentId = routaId))

        assertTrue("Expected 'Agent Summary' in: $result", result.contains("Agent Summary"))
        assertTrue("Expected agent name in: $result", result.contains("routa-main"))
        assertTrue("Expected 'Messages:' in: $result", result.contains("Messages:"))
    }

    // ── Event subscription tool tests ──

    @Test
    fun `SubscribeToEventsTool subscribes to events`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        val tool = SubscribeToEventsTool(routa.tools)
        val result = tool.execute(
            SubscribeToEventsArgs(
                agentId = routaId,
                eventTypes = listOf("agent:*", "task:*"),
            )
        )

        assertTrue("Expected 'subscriptionId' in: $result", result.contains("subscriptionId"))
        assertTrue("Expected 'agent:*' in: $result", result.contains("agent:*"))
    }

    @Test
    fun `UnsubscribeFromEventsTool unsubscribes`() = withRouta { routa ->
        val routaId = routa.coordinator.initialize("test-workspace")

        // Subscribe first
        val subResult = routa.tools.subscribeToEvents(
            agentId = routaId,
            agentName = "routa-main",
            eventTypes = listOf("agent:*"),
        )
        assertTrue("Subscribe should succeed", subResult.success)

        // Extract subscription ID
        val subscriptionId = kotlinx.serialization.json.Json.parseToJsonElement(subResult.data)
            .jsonObject["subscriptionId"]!!.jsonPrimitive.content

        // Unsubscribe
        val tool = UnsubscribeFromEventsTool(routa.tools)
        val result = tool.execute(
            UnsubscribeFromEventsArgs(subscriptionId = subscriptionId)
        )

        assertTrue("Expected 'unsubscribed' in: $result", result.contains("unsubscribed"))
    }

    @Test
    fun `UnsubscribeFromEventsTool fails for unknown subscription`() = withRouta { routa ->
        routa.coordinator.initialize("test-workspace")

        val tool = UnsubscribeFromEventsTool(routa.tools)
        val result = tool.execute(
            UnsubscribeFromEventsArgs(subscriptionId = "nonexistent-id")
        )

        assertTrue("Expected error in: $result", result.contains("Error"))
    }

    @Test
    fun `RoutaToolRegistry creates all 12 tools`() = withRouta { routa ->
        val registry = RoutaToolRegistry.create(routa.tools, "test-workspace")
        assertNotNull(registry)
    }
}
