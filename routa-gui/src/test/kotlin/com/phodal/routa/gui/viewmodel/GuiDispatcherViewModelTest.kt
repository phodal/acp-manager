package com.phodal.routa.gui.viewmodel

import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.provider.AgentProvider
import com.phodal.routa.core.provider.ProviderCapabilities
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.provider.ToolCallStatus
import com.phodal.routa.core.runner.OrchestratorPhase
import com.phodal.routa.core.runner.OrchestratorResult
import com.phodal.routa.core.viewmodel.AgentMode
import com.phodal.routa.core.viewmodel.RoutaViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [GuiDispatcherViewModel] — the GUI-specific ViewModel layer.
 *
 * Validates state management, agent list updates, output accumulation,
 * phase handling, and error management without any Swing dependencies.
 */
class GuiDispatcherViewModelTest {

    // ── Mock Provider ───────────────────────────────────────────────────

    private class MockAgentProvider : AgentProvider {
        override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
            return when (role) {
                AgentRole.ROUTA -> PLAN_TWO_TASKS
                AgentRole.CRAFTER -> CRAFTER_SUCCESS
                AgentRole.GATE -> GATE_APPROVED
            }
        }

        override fun capabilities() = ProviderCapabilities(
            name = "Mock",
            supportsStreaming = false,
            supportsFileEditing = true,
            supportsTerminal = true,
            supportsToolCalling = true,
        )
    }

    // ── Test Fixtures ───────────────────────────────────────────────────

    private fun createViewModels(): Triple<GuiDispatcherViewModel, RoutaViewModel, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routaVm = RoutaViewModel(scope).apply {
            useEnhancedRoutaPrompt = false
        }
        val guiVm = GuiDispatcherViewModel(routaVm, scope)
        return Triple(guiVm, routaVm, scope)
    }

    // ── Initial State Tests ─────────────────────────────────────────────

    @Test
    fun `initial state has ROUTA and GATE agents`() {
        val (guiVm, routaVm, _) = createViewModels()

        val agents = guiVm.agents.value
        assertEquals("Should have 2 initial agents", 2, agents.size)
        assertEquals("First agent should be ROUTA", "__routa__", agents[0].id)
        assertEquals("Second agent should be GATE", "__gate__", agents[1].id)

        routaVm.dispose()
    }

    @Test
    fun `initial selected agent is ROUTA`() {
        val (guiVm, routaVm, _) = createViewModels()

        assertEquals("__routa__", guiVm.selectedAgentId.value)

        routaVm.dispose()
    }

    @Test
    fun `initial status text is Ready`() {
        val (guiVm, routaVm, _) = createViewModels()

        assertEquals("Ready", guiVm.statusText.value)

        routaVm.dispose()
    }

    @Test
    fun `initial agent mode is ACP_AGENT`() {
        val (guiVm, routaVm, _) = createViewModels()

        assertEquals(AgentMode.ACP_AGENT, guiVm.agentMode.value)

        routaVm.dispose()
    }

    @Test
    fun `initial agent outputs are empty`() {
        val (guiVm, routaVm, _) = createViewModels()

        assertTrue(guiVm.agentOutputs.value.isEmpty())

        routaVm.dispose()
    }

    // ── Agent Selection Tests ───────────────────────────────────────────

    @Test
    fun `selectAgent updates selectedAgentId`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.selectAgent("__gate__")

        assertEquals("__gate__", guiVm.selectedAgentId.value)

        routaVm.dispose()
    }

    // ── Mode Switching Tests ────────────────────────────────────────────

    @Test
    fun `setAgentMode updates mode and syncs with RoutaViewModel`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.setAgentMode(AgentMode.WORKSPACE)

        assertEquals(AgentMode.WORKSPACE, guiVm.agentMode.value)
        assertEquals(AgentMode.WORKSPACE, routaVm.agentMode)

        routaVm.dispose()
    }

    // ── Phase Handling Tests ────────────────────────────────────────────

    @Test
    fun `handlePhaseChange updates status text for Planning`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.handlePhaseChange(OrchestratorPhase.Planning)

        assertEquals("ROUTA is planning...", guiVm.statusText.value)

        routaVm.dispose()
    }

    @Test
    fun `handlePhaseChange updates status text for Completed`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.handlePhaseChange(OrchestratorPhase.Completed)

        assertEquals("Completed", guiVm.statusText.value)

        routaVm.dispose()
    }

    @Test
    fun `handlePhaseChange updates ROUTA agent status on Planning`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.handlePhaseChange(OrchestratorPhase.Planning)

        val routaAgent = guiVm.agents.value.find { it.id == "__routa__" }
        assertNotNull(routaAgent)
        assertEquals(AgentStatus.ACTIVE, routaAgent!!.status)

        routaVm.dispose()
    }

    @Test
    fun `handlePhaseChange updates GATE agent status on VerificationStarting`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.handlePhaseChange(OrchestratorPhase.VerificationStarting(1))

        val gateAgent = guiVm.agents.value.find { it.id == "__gate__" }
        assertNotNull(gateAgent)
        assertEquals(AgentStatus.ACTIVE, gateAgent!!.status)

        routaVm.dispose()
    }

    // ── Output Accumulation Tests ───────────────────────────────────────

    @Test
    fun `appendChunkToOutput accumulates text chunks`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.appendChunkToOutput("__routa__", AgentRole.ROUTA, StreamChunk.Text("Hello "))
        guiVm.appendChunkToOutput("__routa__", AgentRole.ROUTA, StreamChunk.Text("World"))

        val output = guiVm.agentOutputs.value["__routa__"]
        assertNotNull(output)
        assertEquals("Hello World", output!!.fullText)

        routaVm.dispose()
    }

    @Test
    fun `appendChunkToOutput handles tool call chunks`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.appendChunkToOutput(
            "__routa__", AgentRole.ROUTA,
            StreamChunk.ToolCall("read_file", ToolCallStatus.STARTED)
        )

        val output = guiVm.agentOutputs.value["__routa__"]
        assertNotNull(output)
        assertTrue(output!!.fullText.contains("read_file"))

        routaVm.dispose()
    }

    @Test
    fun `appendChunkToOutput ignores Completed chunks`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.appendChunkToOutput("__routa__", AgentRole.ROUTA, StreamChunk.Completed("done"))

        assertTrue(guiVm.agentOutputs.value.isEmpty())

        routaVm.dispose()
    }

    @Test
    fun `appendChunkToOutput separates outputs per agent`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.appendChunkToOutput("__routa__", AgentRole.ROUTA, StreamChunk.Text("ROUTA output"))
        guiVm.appendChunkToOutput("__gate__", AgentRole.GATE, StreamChunk.Text("GATE output"))

        val routaOutput = guiVm.agentOutputs.value["__routa__"]
        val gateOutput = guiVm.agentOutputs.value["__gate__"]
        assertEquals("ROUTA output", routaOutput?.fullText)
        assertEquals("GATE output", gateOutput?.fullText)

        routaVm.dispose()
    }

    // ── Reset Tests ─────────────────────────────────────────────────────

    @Test
    fun `reset clears outputs and restores initial state`() {
        val (guiVm, routaVm, _) = createViewModels()

        // Simulate some state
        guiVm.appendChunkToOutput("__routa__", AgentRole.ROUTA, StreamChunk.Text("test"))
        guiVm.selectAgent("__gate__")
        guiVm.handlePhaseChange(OrchestratorPhase.Planning)

        // Reset
        guiVm.reset()

        assertTrue(guiVm.agentOutputs.value.isEmpty())
        assertEquals("__routa__", guiVm.selectedAgentId.value)
        assertEquals("Ready", guiVm.statusText.value)
        assertEquals(2, guiVm.agents.value.size)

        routaVm.dispose()
    }

    // ── Crafter State Handling Tests ─────────────────────────────────────

    @Test
    fun `handleCrafterStatesUpdate adds new CRAFTER entries before GATE`() {
        val (guiVm, routaVm, _) = createViewModels()

        val crafterStates = mapOf(
            "task-1" to com.phodal.routa.core.viewmodel.CrafterStreamState(
                agentId = "agent-1",
                taskId = "task-1",
                taskTitle = "Implement Login",
                status = AgentStatus.ACTIVE,
            ),
            "task-2" to com.phodal.routa.core.viewmodel.CrafterStreamState(
                agentId = "agent-2",
                taskId = "task-2",
                taskTitle = "Add Registration",
                status = AgentStatus.PENDING,
            ),
        )

        guiVm.handleCrafterStatesUpdate(crafterStates)

        val agents = guiVm.agents.value
        assertEquals("Should have 4 agents (ROUTA + 2 CRAFTERs + GATE)", 4, agents.size)
        assertEquals("First should be ROUTA", "__routa__", agents[0].id)
        assertEquals("Last should be GATE", "__gate__", agents.last().id)

        // CRAFTERs should be between ROUTA and GATE
        val crafterAgents = agents.filter { it.role == AgentRole.CRAFTER }
        assertEquals(2, crafterAgents.size)
        assertTrue(crafterAgents.any { it.displayName == "Implement Login" })
        assertTrue(crafterAgents.any { it.displayName == "Add Registration" })

        routaVm.dispose()
    }

    @Test
    fun `handleCrafterStatesUpdate updates existing CRAFTER status`() {
        val (guiVm, routaVm, _) = createViewModels()

        // First update: add CRAFTERs
        guiVm.handleCrafterStatesUpdate(mapOf(
            "task-1" to com.phodal.routa.core.viewmodel.CrafterStreamState(
                agentId = "agent-1", taskId = "task-1",
                taskTitle = "Task", status = AgentStatus.PENDING,
            ),
        ))

        assertEquals(AgentStatus.PENDING, guiVm.agents.value.find { it.id == "task-1" }?.status)

        // Second update: change status
        guiVm.handleCrafterStatesUpdate(mapOf(
            "task-1" to com.phodal.routa.core.viewmodel.CrafterStreamState(
                agentId = "agent-1", taskId = "task-1",
                taskTitle = "Task", status = AgentStatus.COMPLETED,
            ),
        ))

        assertEquals(AgentStatus.COMPLETED, guiVm.agents.value.find { it.id == "task-1" }?.status)

        routaVm.dispose()
    }

    // ── Error Handling Tests ────────────────────────────────────────────

    @Test
    fun `submitRequest emits error when not initialized`() {
        val (guiVm, routaVm, _) = createViewModels()
        val errors = mutableListOf<String>()

        // Collect errors in background
        val collectScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val job = collectScope.launch {
            guiVm.errorMessage.collect { errors.add(it) }
        }

        // Give collector time to subscribe
        runBlocking { delay(100) }

        guiVm.submitRequest("Test")

        runBlocking { delay(100) }
        job.cancel()

        assertTrue("Should emit error, got: $errors", errors.any { it.contains("not initialized") })

        routaVm.dispose()
    }

    @Test
    fun `submitRequest ignores blank input`() {
        val (guiVm, routaVm, _) = createViewModels()

        guiVm.submitRequest("")
        guiVm.submitRequest("   ")

        assertEquals("Ready", guiVm.statusText.value)

        routaVm.dispose()
    }

    // ── Full Integration Test ───────────────────────────────────────────

    @Test
    fun `full flow - submit request updates agents and status`() {
        val (guiVm, routaVm, _) = createViewModels()
        routaVm.initialize(MockAgentProvider(), "test-workspace")

        guiVm.submitRequest("Add user authentication")

        // Wait for async execution
        runBlocking { delay(3000) }

        // Status should indicate completion
        assertTrue(
            "Status should indicate completion: ${guiVm.statusText.value}",
            guiVm.statusText.value.contains("Completed") || guiVm.statusText.value.contains("✅")
        )

        // Should have CRAFTER agents added
        val agents = guiVm.agents.value
        assertTrue("Should have more than 2 agents", agents.size > 2)

        routaVm.dispose()
    }

    // ── Companion: Test Data ────────────────────────────────────────────

    companion object {
        val PLAN_TWO_TASKS = """
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

            ## Verification
            - ./gradlew test --tests LoginControllerTest
            @@@

            @@@task
            # Add User Registration

            ## Objective
            Create a POST /api/register endpoint

            ## Scope
            - src/user/RegisterController.kt

            ## Definition of Done
            - POST /api/register creates a user

            ## Verification
            - ./gradlew test --tests RegisterControllerTest
            @@@
        """.trimIndent()

        val CRAFTER_SUCCESS = """
            I've implemented the task as requested.
            All tests pass.
        """.trimIndent()

        val GATE_APPROVED = """
            ### Verification Summary
            - Verdict: ✅ APPROVED
            - Confidence: High
            All acceptance criteria verified.
        """.trimIndent()
    }
}
