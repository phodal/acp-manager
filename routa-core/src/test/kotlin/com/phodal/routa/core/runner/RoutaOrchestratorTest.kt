package com.phodal.routa.core.runner

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.coordinator.CoordinationPhase
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the full orchestration flow using a mock [AgentRunner]
 * that simulates LLM responses without requiring a real LLM.
 *
 * This verifies the ROUTA → CRAFTER → GATE pipeline works correctly,
 * including task parsing, delegation, completion reporting, and verification.
 */
class RoutaOrchestratorTest {

    /**
     * Mock AgentRunner that returns scripted responses based on the agent role.
     */
    private class MockAgentRunner : AgentRunner {
        val runLog = mutableListOf<Triple<AgentRole, String, String>>()

        override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
            runLog.add(Triple(role, agentId, prompt))

            return when (role) {
                AgentRole.ROUTA -> {
                    // ROUTA outputs a plan with @@@task blocks
                    """
                    Here is my plan:

                    @@@task
                    # Implement Login API

                    ## Objective
                    Create a POST /api/login endpoint with JWT authentication

                    ## Scope
                    - src/auth/LoginController.kt
                    - src/auth/JwtService.kt

                    ## Definition of Done
                    - POST /api/login accepts email + password
                    - Returns JWT token on success
                    - Returns 401 on invalid credentials

                    ## Verification
                    - ./gradlew test --tests LoginControllerTest
                    @@@

                    @@@task
                    # Add User Registration

                    ## Objective
                    Create a POST /api/register endpoint

                    ## Scope
                    - src/user/RegisterController.kt
                    - src/user/UserRepository.kt

                    ## Definition of Done
                    - POST /api/register creates a user
                    - Duplicate emails return 409

                    ## Verification
                    - ./gradlew test --tests RegisterControllerTest
                    @@@
                    """.trimIndent()
                }

                AgentRole.CRAFTER -> {
                    // CRAFTER "implements" the task and reports
                    """
                    I've implemented the task as requested.

                    Changes made:
                    - Created src/auth/LoginController.kt with POST /api/login endpoint
                    - Created src/auth/JwtService.kt for JWT token generation
                    - Added LoginControllerTest with 3 test cases

                    All tests pass. Implementation follows existing patterns.
                    """.trimIndent()
                }

                AgentRole.GATE -> {
                    // GATE verifies and APPROVES
                    """
                    ### Verification Summary
                    - Verdict: ✅ APPROVED
                    - Confidence: High

                    ### Acceptance Criteria Checklist
                    - ✅ VERIFIED: POST /api/login accepts email + password
                    - ✅ VERIFIED: Returns JWT token on success
                    - ✅ VERIFIED: Returns 401 on invalid credentials
                    - ✅ VERIFIED: POST /api/register creates a user
                    - ✅ VERIFIED: Duplicate emails return 409

                    ### Tests Run
                    - LoginControllerTest → PASS (3/3)
                    - RegisterControllerTest → PASS (2/2)
                    """.trimIndent()
                }
            }
        }
    }

    private fun withOrchestrator(
        runner: MockAgentRunner = MockAgentRunner(),
        block: suspend (RoutaOrchestrator, RoutaSystem, MockAgentRunner) -> Unit
    ) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        val phases = mutableListOf<OrchestratorPhase>()

        val orchestrator = RoutaOrchestrator(
            routa = routa,
            runner = runner,
            workspaceId = "test-workspace",
            onPhaseChange = { phases.add(it) }
        )

        try {
            runBlocking { block(orchestrator, routa, runner) }
        } finally {
            routa.coordinator.shutdown()
        }
    }

    @Test
    fun `full flow ROUTA plans - CRAFTER implements - GATE approves`() = withOrchestrator { orchestrator, routa, runner ->
        val result = orchestrator.execute("Add user authentication with login and registration")

        // Should succeed
        assertTrue("Expected Success but got: $result", result is OrchestratorResult.Success)

        val success = result as OrchestratorResult.Success
        assertEquals("Should have 2 tasks", 2, success.taskSummaries.size)

        // Verify agents were run in the right order
        val roles = runner.runLog.map { it.first }
        assertEquals("First agent should be ROUTA", AgentRole.ROUTA, roles[0])
        assertEquals("Second should be CRAFTER", AgentRole.CRAFTER, roles[1])
        assertEquals("Third should be CRAFTER", AgentRole.CRAFTER, roles[2])
        assertEquals("Fourth should be GATE", AgentRole.GATE, roles[3])
    }

    @Test
    fun `CRAFTER reports are recorded in stores`() = withOrchestrator { orchestrator, routa, runner ->
        orchestrator.execute("Add authentication")

        // Check that tasks went through REVIEW_REQUIRED → COMPLETED
        val tasks = routa.context.taskStore.listByWorkspace("test-workspace")
        assertTrue("All tasks should be completed", tasks.all { it.status == TaskStatus.COMPLETED })

        // Check that CRAFTER agents are marked as COMPLETED
        val crafters = routa.context.agentStore.listByRole("test-workspace", AgentRole.CRAFTER)
        assertTrue("All crafters should be completed",
            crafters.all { it.status == com.phodal.routa.core.model.AgentStatus.COMPLETED })
    }

    @Test
    fun `GATE rejection triggers retry wave`() {
        var gateCallCount = 0

        val runner = object : AgentRunner {
            override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
                return when (role) {
                    AgentRole.ROUTA -> """
                        @@@task
                        # Fix Bug
                        ## Objective
                        Fix the null pointer bug
                        ## Definition of Done
                        - Bug is fixed
                        ## Verification
                        - ./gradlew test
                        @@@
                    """.trimIndent()

                    AgentRole.CRAFTER -> "Fixed the bug. All tests pass."

                    AgentRole.GATE -> {
                        gateCallCount++
                        if (gateCallCount == 1) {
                            // First verification: NOT APPROVED
                            """
                            ### Verification Summary
                            - Verdict: ❌ NOT APPROVED
                            - The fix is incomplete. Tests still fail.
                            """.trimIndent()
                        } else {
                            // Second verification: APPROVED
                            """
                            ### Verification Summary
                            - Verdict: ✅ APPROVED
                            - All tests pass now.
                            """.trimIndent()
                        }
                    }
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        val phases = mutableListOf<OrchestratorPhase>()

        val orchestrator = RoutaOrchestrator(
            routa = routa,
            runner = runner,
            workspaceId = "test-workspace",
            onPhaseChange = { phases.add(it) }
        )

        try {
            val result = runBlocking { orchestrator.execute("Fix the null pointer bug") }

            // Should eventually succeed after retry
            assertTrue("Expected Success but got: $result", result is OrchestratorResult.Success)

            // GATE should have been called twice
            assertEquals("GATE should verify twice", 2, gateCallCount)

            // Should have a NeedsFix phase
            assertTrue("Should have NeedsFix phase",
                phases.any { it is OrchestratorPhase.NeedsFix })
        } finally {
            routa.coordinator.shutdown()
        }
    }

    @Test
    fun `no tasks in plan returns NoTasks`() = withOrchestrator { orchestrator, _, _ ->
        val runner = object : AgentRunner {
            override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
                return "I understand the request but there's nothing to implement."
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        val orchestrator = RoutaOrchestrator(
            routa = routa,
            runner = runner,
            workspaceId = "test-workspace",
        )

        try {
            val result = runBlocking { orchestrator.execute("Just say hello") }
            assertTrue("Expected NoTasks but got: $result", result is OrchestratorResult.NoTasks)
        } finally {
            routa.coordinator.shutdown()
        }
    }

    @Test
    fun `phase callbacks are emitted in correct order`() {
        val phases = mutableListOf<OrchestratorPhase>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)

        val orchestrator = RoutaOrchestrator(
            routa = routa,
            runner = MockAgentRunner(),
            workspaceId = "test-workspace",
            onPhaseChange = { phases.add(it) }
        )

        try {
            runBlocking { orchestrator.execute("Add auth") }

            // Verify phase order
            assertTrue("Should start with Initializing", phases[0] is OrchestratorPhase.Initializing)
            assertTrue("Then Planning", phases[1] is OrchestratorPhase.Planning)
            assertTrue("Then PlanReady", phases[2] is OrchestratorPhase.PlanReady)
            assertTrue("Then TasksRegistered", phases[3] is OrchestratorPhase.TasksRegistered)
            assertTrue("Then WaveStarting", phases[4] is OrchestratorPhase.WaveStarting)

            // Should have CrafterRunning and CrafterCompleted for each task
            val crafterPhases = phases.filterIsInstance<OrchestratorPhase.CrafterRunning>()
            assertEquals("Should run 2 crafters", 2, crafterPhases.size)

            // Should have verification
            assertTrue("Should have verification",
                phases.any { it is OrchestratorPhase.VerificationStarting })

            // Should end with Completed
            assertTrue("Should end with Completed",
                phases.any { it is OrchestratorPhase.Completed })
        } finally {
            routa.coordinator.shutdown()
        }
    }

    @Test
    fun `agents can read each others conversations`() = withOrchestrator { orchestrator, routa, runner ->
        orchestrator.execute("Add auth")

        // Verify cross-agent communication was recorded
        val routaId = routa.coordinator.coordinationState.value.routaAgentId
        val routaConversation = routa.context.conversationStore.getConversation(routaId)

        // Routa should have received completion reports from crafters
        assertTrue("Routa should have received reports",
            routaConversation.any { it.content.contains("Completion Report") })
    }
}
