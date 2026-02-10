package com.phodal.routa.core.coordinator

import com.phodal.routa.core.event.AgentEvent
import com.phodal.routa.core.model.*
import com.phodal.routa.core.role.RouteDefinitions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.UUID

/**
 * The coordination state machine that implements the Routa→Crafter→Gate workflow.
 *
 * ## Workflow
 * ```
 * User Request
 *   → Routa plans (@@@task blocks)
 *     → Wave of Crafter agents (parallel)
 *       → Each Crafter reports to Routa
 *         → Gate verifies
 *           → APPROVED: next wave or done
 *           → NOT APPROVED: fix tasks → Crafter again
 * ```
 *
 * This class is the headless/core orchestrator. It does NOT contain UI logic.
 * Platform-specific integrations (IntelliJ, CLI, etc.) build on top of this.
 */
class RoutaCoordinator(
    private val context: AgentExecutionContext,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {

    // ── Observable state ────────────────────────────────────────────────

    private var eventListenerJob: Job? = null

    private val _coordinationState = MutableStateFlow(CoordinationState())

    /** Observable state of the coordination workflow. */
    val coordinationState: StateFlow<CoordinationState> = _coordinationState.asStateFlow()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Initialize a new coordination session.
     *
     * Creates the Routa (coordinator) agent and returns its ID.
     * The caller is responsible for feeding user input to the Routa agent
     * via their preferred execution backend (ACP, Koog, etc.).
     *
     * @param workspaceId The workspace to coordinate in.
     * @return The Routa agent's ID.
     */
    suspend fun initialize(workspaceId: String): String {
        val routaAgent = createRouta(workspaceId)
        _coordinationState.value = CoordinationState(
            workspaceId = workspaceId,
            routaAgentId = routaAgent.id,
            phase = CoordinationPhase.PLANNING,
        )
        startEventListener()
        return routaAgent.id
    }

    /**
     * Register tasks from Routa's planning output.
     *
     * Parses `@@@task` blocks and stores them.
     *
     * @param planOutput The Routa agent's output containing task blocks.
     * @return List of created task IDs.
     */
    suspend fun registerTasks(planOutput: String): List<String> {
        val state = _coordinationState.value
        val tasks = TaskParser.parse(planOutput, state.workspaceId)

        tasks.forEach { task ->
            context.taskStore.save(task)
        }

        _coordinationState.value = state.copy(
            taskIds = tasks.map { it.id },
            phase = CoordinationPhase.READY,
        )

        return tasks.map { it.id }
    }

    /**
     * Start executing the next wave of tasks.
     *
     * Creates Crafter agents for each ready task and delegates the work.
     *
     * @return List of (agentId, taskId) pairs for the created Crafters.
     */
    suspend fun executeNextWave(): List<Pair<String, String>> {
        val state = _coordinationState.value
        val readyTasks = context.taskStore.findReadyTasks(state.workspaceId)

        if (readyTasks.isEmpty()) {
            // Check if all tasks are done
            val allTasks = context.taskStore.listByWorkspace(state.workspaceId)
            if (allTasks.all { it.status == TaskStatus.COMPLETED }) {
                _coordinationState.value = state.copy(phase = CoordinationPhase.COMPLETED)
            }
            return emptyList()
        }

        _coordinationState.value = state.copy(phase = CoordinationPhase.EXECUTING)

        val delegations = mutableListOf<Pair<String, String>>()

        for (task in readyTasks) {
            // Create a Crafter agent
            val result = context.agentTools.createAgent(
                name = "crafter-${task.title.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                role = AgentRole.CRAFTER,
                workspaceId = state.workspaceId,
                parentId = state.routaAgentId,
            )

            if (result.success) {
                // Extract agent ID from result
                val agentId = extractAgentId(result.data)
                if (agentId != null) {
                    // Delegate the task
                    context.agentTools.delegate(agentId, task.id, state.routaAgentId)
                    delegations.add(agentId to task.id)
                }
            }
        }

        _coordinationState.value = _coordinationState.value.copy(
            activeCrafterIds = delegations.map { it.first },
        )

        return delegations
    }

    /**
     * Start verification for completed tasks.
     *
     * Creates a Gate agent for tasks in REVIEW_REQUIRED status.
     *
     * @return The Gate agent ID, or null if no tasks need verification.
     */
    suspend fun startVerification(): String? {
        val state = _coordinationState.value
        val reviewTasks = context.taskStore.listByStatus(state.workspaceId, TaskStatus.REVIEW_REQUIRED)

        if (reviewTasks.isEmpty()) return null

        _coordinationState.value = state.copy(phase = CoordinationPhase.VERIFYING)

        // Create a single Gate agent for this verification wave
        val result = context.agentTools.createAgent(
            name = "gate-wave-${System.currentTimeMillis()}",
            role = AgentRole.GATE,
            workspaceId = state.workspaceId,
            parentId = state.routaAgentId,
        )

        if (!result.success) return null

        val gateAgentId = extractAgentId(result.data) ?: return null

        _coordinationState.value = _coordinationState.value.copy(
            activeGateId = gateAgentId,
        )

        return gateAgentId
    }

    /**
     * Get the task context for a specific agent.
     *
     * This builds the prompt context that should be sent to a Crafter or Gate agent,
     * including the task definition, acceptance criteria, and relevant conversation history.
     *
     * @param agentId The agent to build context for.
     * @return The formatted context string, or null if the agent has no assigned task.
     */
    suspend fun buildAgentContext(agentId: String): String? {
        val agent = context.agentStore.get(agentId) ?: return null
        val tasks = context.taskStore.listByAssignee(agentId)
        val task = tasks.firstOrNull() ?: return null

        val roleDefinition = RouteDefinitions.forRole(agent.role)

        return buildString {
            appendLine(roleDefinition.systemPrompt)
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("# Your Assigned Task")
            appendLine()
            appendLine("## ${task.title}")
            appendLine()
            appendLine("### Objective")
            appendLine(task.objective)
            appendLine()
            if (task.scope.isNotEmpty()) {
                appendLine("### Scope")
                task.scope.forEach { appendLine("- $it") }
                appendLine()
            }
            if (task.acceptanceCriteria.isNotEmpty()) {
                appendLine("### Acceptance Criteria")
                task.acceptanceCriteria.forEach { appendLine("- $it") }
                appendLine()
            }
            if (task.verificationCommands.isNotEmpty()) {
                appendLine("### Verification Commands")
                task.verificationCommands.forEach { appendLine("- `$it`") }
                appendLine()
            }
            appendLine("---")
            appendLine()
            appendLine("**Reminder:** ${roleDefinition.roleReminder}")
        }
    }

    /**
     * Get the current summary of all tasks and their statuses.
     */
    suspend fun getTaskSummary(): List<TaskSummary> {
        val state = _coordinationState.value
        val tasks = context.taskStore.listByWorkspace(state.workspaceId)
        return tasks.map { task ->
            val assignee = task.assignedTo?.let { context.agentStore.get(it) }
            TaskSummary(
                taskId = task.id,
                title = task.title,
                status = task.status,
                assignedAgent = assignee?.name,
                assignedRole = assignee?.role,
                verdict = task.verificationVerdict,
            )
        }
    }

    /**
     * Reset the coordinator, clearing all state.
     */
    fun reset() {
        eventListenerJob?.cancel()
        eventListenerJob = null
        _coordinationState.value = CoordinationState()
    }

    /**
     * Shutdown the coordinator, cancelling the event listener.
     */
    fun shutdown() {
        eventListenerJob?.cancel()
        eventListenerJob = null
    }

    // ── Internal ────────────────────────────────────────────────────────

    private suspend fun createRouta(workspaceId: String): Agent {
        val now = Instant.now().toString()
        val agent = Agent(
            id = UUID.randomUUID().toString(),
            name = "routa-main",
            role = AgentRole.ROUTA,
            modelTier = ModelTier.SMART,
            workspaceId = workspaceId,
            status = AgentStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        context.agentStore.save(agent)
        return agent
    }

    private fun startEventListener() {
        eventListenerJob?.cancel()
        eventListenerJob = scope.launch {
            context.eventBus.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    private suspend fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AgentCompleted -> {
                val state = _coordinationState.value

                // If a Crafter completed, check if all Crafters in this wave are done
                val completedAgent = context.agentStore.get(event.agentId)
                if (completedAgent?.role == AgentRole.CRAFTER) {
                    val activeCrafters = state.activeCrafterIds
                    val allCompleted = activeCrafters.all { crafterId ->
                        val agent = context.agentStore.get(crafterId)
                        agent?.status == AgentStatus.COMPLETED || agent?.status == AgentStatus.ERROR
                    }
                    if (allCompleted && activeCrafters.isNotEmpty()) {
                        _coordinationState.value = state.copy(
                            phase = CoordinationPhase.WAVE_COMPLETE,
                            activeCrafterIds = emptyList(),
                        )
                    }
                }

                // If a Gate completed, update phase
                if (completedAgent?.role == AgentRole.GATE) {
                    val reviewTasks = context.taskStore.listByStatus(state.workspaceId, TaskStatus.NEEDS_FIX)
                    if (reviewTasks.isNotEmpty()) {
                        _coordinationState.value = state.copy(
                            phase = CoordinationPhase.NEEDS_FIX,
                            activeGateId = null,
                        )
                    } else {
                        // Check if more tasks are pending
                        val pendingTasks = context.taskStore.findReadyTasks(state.workspaceId)
                        if (pendingTasks.isNotEmpty()) {
                            _coordinationState.value = state.copy(
                                phase = CoordinationPhase.READY,
                                activeGateId = null,
                            )
                        } else {
                            _coordinationState.value = state.copy(
                                phase = CoordinationPhase.COMPLETED,
                                activeGateId = null,
                            )
                        }
                    }
                }
            }
            else -> { /* Other events are informational */ }
        }
    }

    private fun extractAgentId(jsonData: String): String? {
        // Simple extraction — the agent JSON contains "id": "..."
        val idRegex = Regex(""""id"\s*:\s*"([^"]+)"""")
        return idRegex.find(jsonData)?.groupValues?.get(1)
    }
}

// ── State types ─────────────────────────────────────────────────────────

/**
 * The phases of the coordination workflow.
 */
enum class CoordinationPhase {
    /** No active coordination. */
    IDLE,

    /** Routa is planning tasks. */
    PLANNING,

    /** Plan is ready, waiting for approval/execution. */
    READY,

    /** Crafters are executing tasks. */
    EXECUTING,

    /** Current wave of Crafters completed, ready for verification. */
    WAVE_COMPLETE,

    /** Gate is verifying completed work. */
    VERIFYING,

    /** Gate found issues, tasks need fixes. */
    NEEDS_FIX,

    /** All tasks completed and verified. */
    COMPLETED,

    /** Coordination failed. */
    FAILED;
}

/**
 * Observable state of the coordination workflow.
 */
data class CoordinationState(
    val workspaceId: String = "",
    val routaAgentId: String = "",
    val phase: CoordinationPhase = CoordinationPhase.IDLE,
    val taskIds: List<String> = emptyList(),
    val activeCrafterIds: List<String> = emptyList(),
    val activeGateId: String? = null,
    val error: String? = null,
)

/**
 * Summary of a task for display purposes.
 */
data class TaskSummary(
    val taskId: String,
    val title: String,
    val status: TaskStatus,
    val assignedAgent: String?,
    val assignedRole: AgentRole?,
    val verdict: VerificationVerdict?,
)
