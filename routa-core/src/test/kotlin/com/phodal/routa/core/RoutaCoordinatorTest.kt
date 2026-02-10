package com.phodal.routa.core

import com.phodal.routa.core.coordinator.CoordinationPhase
import com.phodal.routa.core.coordinator.TaskParser
import com.phodal.routa.core.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoutaCoordinatorTest {

    /**
     * Helper to create a Routa system with a dedicated scope that won't hang tests.
     */
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
    fun `test task parser extracts tasks from routa output`() {
        val routaOutput = """
            Here is the plan:
            
            @@@task
            # Implement Auth Module
            
            ## Objective
            Add JWT authentication to the API
            
            ## Scope
            - src/auth/AuthService.kt
            - src/auth/JwtProvider.kt
            
            ## Definition of Done
            - JWT tokens are issued on login
            - Protected endpoints require valid tokens
            - Token expiry is enforced
            
            ## Verification
            - ./gradlew test --tests AuthServiceTest
            - curl localhost:8080/api/protected (should return 401)
            @@@
            
            @@@task
            # Add User Registration
            
            ## Objective
            Create user registration endpoint
            
            ## Scope
            - src/user/UserController.kt
            - src/user/UserService.kt
            
            ## Definition of Done
            - POST /register creates a user
            - Duplicate emails are rejected
            
            ## Verification
            - ./gradlew test --tests UserRegistrationTest
            @@@
        """.trimIndent()

        val tasks = TaskParser.parse(routaOutput, "test-workspace")

        assertEquals(2, tasks.size)

        val authTask = tasks[0]
        assertEquals("Implement Auth Module", authTask.title)
        assertEquals("Add JWT authentication to the API", authTask.objective)
        assertEquals(2, authTask.scope.size)
        assertEquals("src/auth/AuthService.kt", authTask.scope[0])
        assertEquals(3, authTask.acceptanceCriteria.size)
        assertEquals(2, authTask.verificationCommands.size)
        assertEquals(TaskStatus.PENDING, authTask.status)
        assertEquals("test-workspace", authTask.workspaceId)

        val userTask = tasks[1]
        assertEquals("Add User Registration", userTask.title)
        assertEquals(2, userTask.acceptanceCriteria.size)
        assertEquals(1, userTask.verificationCommands.size)
    }

    @Test
    fun `test initialize creates routa agent and sets planning phase`() = withRouta { routa ->
        val coordinator = routa.coordinator

        val routaAgentId = coordinator.initialize("test-workspace")

        assertNotNull(routaAgentId)

        val state = coordinator.coordinationState.value
        assertEquals(CoordinationPhase.PLANNING, state.phase)
        assertEquals("test-workspace", state.workspaceId)
        assertEquals(routaAgentId, state.routaAgentId)

        // Verify agent was created in store
        val agent = routa.context.agentStore.get(routaAgentId)
        assertNotNull(agent)
        assertEquals(AgentRole.ROUTA, agent!!.role)
        assertEquals(AgentStatus.ACTIVE, agent.status)
        assertEquals("routa-main", agent.name)
    }

    @Test
    fun `test register tasks parses and stores tasks`() = withRouta { routa ->
        val coordinator = routa.coordinator
        coordinator.initialize("test-workspace")

        val planOutput = """
            @@@task
            # Task One
            ## Objective
            Do thing one
            ## Definition of Done
            - Thing one is done
            @@@
        """.trimIndent()

        val taskIds = coordinator.registerTasks(planOutput)

        assertEquals(1, taskIds.size)
        val state = coordinator.coordinationState.value
        assertEquals(CoordinationPhase.READY, state.phase)

        val task = routa.context.taskStore.get(taskIds[0])
        assertNotNull(task)
        assertEquals("Task One", task!!.title)
        assertEquals("Do thing one", task.objective)
    }

    @Test
    fun `test execute next wave creates crafters and delegates`() = withRouta { routa ->
        val coordinator = routa.coordinator
        coordinator.initialize("test-workspace")

        val planOutput = """
            @@@task
            # Build Feature
            ## Objective
            Build the feature
            ## Definition of Done
            - Feature works
            @@@
        """.trimIndent()
        coordinator.registerTasks(planOutput)

        val delegations = coordinator.executeNextWave()

        assertEquals(1, delegations.size)

        val (crafterId, taskId) = delegations[0]

        // Verify Crafter was created
        val crafter = routa.context.agentStore.get(crafterId)
        assertNotNull(crafter)
        assertEquals(AgentRole.CRAFTER, crafter!!.role)
        assertEquals(AgentStatus.ACTIVE, crafter.status)

        // Verify task was delegated
        val task = routa.context.taskStore.get(taskId)
        assertNotNull(task)
        assertEquals(TaskStatus.IN_PROGRESS, task!!.status)
        assertEquals(crafterId, task.assignedTo)

        // Verify phase
        val state = coordinator.coordinationState.value
        assertEquals(CoordinationPhase.EXECUTING, state.phase)
    }

    @Test
    fun `test report to parent transitions task to review required`() = withRouta { routa ->
        val coordinator = routa.coordinator
        coordinator.initialize("test-workspace")

        coordinator.registerTasks("""
            @@@task
            # Build Feature
            ## Objective
            Build it
            ## Definition of Done
            - It works
            @@@
        """.trimIndent())

        val delegations = coordinator.executeNextWave()
        val (crafterId, taskId) = delegations[0]

        // Simulate Crafter completing and reporting
        val report = CompletionReport(
            agentId = crafterId,
            taskId = taskId,
            summary = "Built the feature. Tests pass.",
            filesModified = listOf("src/Feature.kt"),
            success = true,
        )
        routa.tools.reportToParent(crafterId, report)

        // Task should now be REVIEW_REQUIRED
        val task = routa.context.taskStore.get(taskId)
        assertEquals(TaskStatus.REVIEW_REQUIRED, task!!.status)
        assertEquals("Built the feature. Tests pass.", task.completionSummary)
    }

    @Test
    fun `test agent tools list agents and read conversation`() = withRouta { routa ->
        val coordinator = routa.coordinator
        coordinator.initialize("test-workspace")

        // List agents — should find the Routa
        val listResult = routa.tools.listAgents("test-workspace")
        assertTrue(listResult.success)
        assertTrue(listResult.data.contains("routa-main"))
        assertTrue(listResult.data.contains("ROUTA"))

        // Send a message to Routa
        val routaId = coordinator.coordinationState.value.routaAgentId
        routa.tools.messageAgent(routaId, routaId, "Hello self")

        // Read conversation
        val convResult = routa.tools.readAgentConversation(routaId)
        assertTrue(convResult.success)
        assertTrue(convResult.data.contains("Hello self"))
    }
}
