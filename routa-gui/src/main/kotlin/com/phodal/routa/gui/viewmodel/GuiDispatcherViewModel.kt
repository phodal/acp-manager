package com.phodal.routa.gui.viewmodel

import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.runner.OrchestratorPhase
import com.phodal.routa.core.runner.OrchestratorResult
import com.phodal.routa.core.viewmodel.AgentMode
import com.phodal.routa.core.viewmodel.CrafterStreamState
import com.phodal.routa.core.viewmodel.RoutaViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Represents a single agent entry in the sidebar.
 */
data class AgentEntry(
    val id: String,
    val role: AgentRole,
    val displayName: String,
    val status: AgentStatus = AgentStatus.PENDING,
)

/**
 * Accumulated text output for a specific agent/phase.
 */
data class AgentOutput(
    val agentId: String,
    val role: AgentRole,
    val textChunks: List<String> = emptyList(),
) {
    val fullText: String get() = textChunks.joinToString("")
}

/**
 * GUI-specific ViewModel that wraps [RoutaViewModel] and provides
 * UI-ready state for the standalone Swing application.
 *
 * This ViewModel is designed for testability — all state is exposed
 * via [StateFlow] and [SharedFlow], and all mutations go through
 * public methods with no direct Swing dependencies.
 *
 * ## Usage
 * ```kotlin
 * val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
 * val routaVm = RoutaViewModel(scope)
 * val guiVm = GuiDispatcherViewModel(routaVm, scope)
 *
 * // Observe sidebar agents
 * launch { guiVm.agents.collect { updateSidebar(it) } }
 *
 * // Observe selected agent output
 * launch { guiVm.selectedAgentOutput.collect { updateContentArea(it) } }
 *
 * // Execute user request
 * guiVm.submitRequest("Add user authentication")
 * ```
 *
 * @param routaViewModel The platform-agnostic [RoutaViewModel] from routa-core.
 * @param scope The coroutine scope for background work.
 */
class GuiDispatcherViewModel(
    val routaViewModel: RoutaViewModel,
    private val scope: CoroutineScope,
) {

    // ── Sidebar Agent List ──────────────────────────────────────────────

    private val _agents = MutableStateFlow<List<AgentEntry>>(emptyList())
    /** Ordered list of agents for the sidebar (ROUTA, CRAFTERs, GATE). */
    val agents: StateFlow<List<AgentEntry>> = _agents.asStateFlow()

    // ── Selected Agent ──────────────────────────────────────────────────

    private val _selectedAgentId = MutableStateFlow("__routa__")
    /** Currently selected agent ID in the sidebar. */
    val selectedAgentId: StateFlow<String> = _selectedAgentId.asStateFlow()

    // ── Agent Outputs ───────────────────────────────────────────────────

    private val _agentOutputs = MutableStateFlow<Map<String, AgentOutput>>(emptyMap())
    /** All agent outputs keyed by agent ID. */
    val agentOutputs: StateFlow<Map<String, AgentOutput>> = _agentOutputs.asStateFlow()

    /** Output of the currently selected agent. */
    val selectedAgentOutput: StateFlow<AgentOutput?> = combine(
        _selectedAgentId, _agentOutputs
    ) { selectedId, outputs ->
        outputs[selectedId]
    }.stateIn(scope, SharingStarted.Eagerly, null)

    // ── Status Line ─────────────────────────────────────────────────────

    private val _statusText = MutableStateFlow("Ready")
    /** Human-readable status text for the status bar. */
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    // ── Mode ────────────────────────────────────────────────────────────

    private val _agentMode = MutableStateFlow(AgentMode.ACP_AGENT)
    /** Current agent execution mode. */
    val agentMode: StateFlow<AgentMode> = _agentMode.asStateFlow()

    // ── Error Messages ──────────────────────────────────────────────────

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Error messages to display to the user (one-shot events). */
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    // ── Initialization ──────────────────────────────────────────────────

    init {
        observeRoutaViewModel()
    }

    private fun observeRoutaViewModel() {
        // Build initial sidebar
        rebuildAgentList()

        // Observe phase changes → update status + sidebar
        scope.launch {
            routaViewModel.phase.collect { phase ->
                handlePhaseChange(phase)
            }
        }

        // Observe crafter states → update sidebar + outputs
        scope.launch {
            routaViewModel.crafterStates.collect { states ->
                handleCrafterStatesUpdate(states)
            }
        }

        // Observe ROUTA streaming chunks → accumulate output
        scope.launch {
            routaViewModel.routaChunks.collect { chunk ->
                appendChunkToOutput("__routa__", AgentRole.ROUTA, chunk)
            }
        }

        // Observe GATE streaming chunks → accumulate output
        scope.launch {
            routaViewModel.gateChunks.collect { chunk ->
                appendChunkToOutput("__gate__", AgentRole.GATE, chunk)
            }
        }

        // Observe CRAFTER streaming chunks → accumulate to task-based output
        scope.launch {
            routaViewModel.crafterChunks.collect { (taskId, chunk) ->
                appendChunkToOutput(taskId, AgentRole.CRAFTER, chunk)
            }
        }
    }

    // ── Public Actions ──────────────────────────────────────────────────

    /**
     * Submit a user request for execution.
     *
     * Clears previous output, sets mode, and launches execution in the background.
     * Results are reported via [statusText] and observable flows.
     */
    fun submitRequest(request: String) {
        if (request.isBlank()) return
        if (routaViewModel.isRunning.value) {
            _errorMessage.tryEmit("An execution is already running. Stop it first.")
            return
        }
        if (!routaViewModel.isInitialized()) {
            _errorMessage.tryEmit("ViewModel not initialized. Call initialize() first.")
            return
        }

        // Clear previous state
        clearOutputs()
        rebuildAgentList()
        _statusText.value = "Starting..."

        // Apply mode
        routaViewModel.agentMode = _agentMode.value

        scope.launch {
            try {
                val result = routaViewModel.execute(request)
                handleResult(result)
            } catch (e: Exception) {
                _statusText.value = "Error: ${e.message}"
                _errorMessage.tryEmit(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Stop the currently running execution.
     */
    fun stopExecution() {
        scope.launch {
            routaViewModel.stopExecution()
            _statusText.value = "Stopped"
        }
    }

    /**
     * Select an agent in the sidebar.
     */
    fun selectAgent(agentId: String) {
        _selectedAgentId.value = agentId
    }

    /**
     * Switch the agent execution mode.
     */
    fun setAgentMode(mode: AgentMode) {
        _agentMode.value = mode
        routaViewModel.agentMode = mode
    }

    /**
     * Reset all state for a new session.
     */
    fun reset() {
        routaViewModel.reset()
        clearOutputs()
        rebuildAgentList()
        _selectedAgentId.value = "__routa__"
        _statusText.value = "Ready"
    }

    // ── Internal: Phase Handling ─────────────────────────────────────────

    internal fun handlePhaseChange(phase: OrchestratorPhase) {
        _statusText.value = when (phase) {
            is OrchestratorPhase.Initializing -> "Ready"
            is OrchestratorPhase.Planning -> "ROUTA is planning..."
            is OrchestratorPhase.PlanReady -> "Plan ready"
            is OrchestratorPhase.TasksRegistered -> "${phase.count} tasks registered"
            is OrchestratorPhase.WaveStarting -> "Wave ${phase.wave} starting..."
            is OrchestratorPhase.CrafterRunning -> "CRAFTER running: ${phase.taskId}"
            is OrchestratorPhase.CrafterCompleted -> "CRAFTER completed: ${phase.taskId}"
            is OrchestratorPhase.VerificationStarting -> "GATE verifying (wave ${phase.wave})..."
            is OrchestratorPhase.VerificationCompleted -> "Verification completed"
            is OrchestratorPhase.NeedsFix -> "Needs fix (wave ${phase.wave})"
            is OrchestratorPhase.Completed -> "Completed"
            is OrchestratorPhase.MaxWavesReached -> "Max waves reached (${phase.waves})"
        }

        // Update agent status in sidebar based on phase
        updateAgentStatusFromPhase(phase)
    }

    internal fun handleCrafterStatesUpdate(states: Map<String, CrafterStreamState>) {
        if (states.isEmpty()) return

        val currentAgents = _agents.value.toMutableList()
        val existingCrafterIds = currentAgents
            .filter { it.role == AgentRole.CRAFTER }
            .map { it.id }
            .toSet()

        // Add new CRAFTER entries for tasks not yet in sidebar
        for ((taskId, state) in states) {
            if (taskId !in existingCrafterIds) {
                // Insert before GATE
                val gateIndex = currentAgents.indexOfFirst { it.id == "__gate__" }
                val insertIndex = if (gateIndex >= 0) gateIndex else currentAgents.size
                currentAgents.add(
                    insertIndex,
                    AgentEntry(
                        id = taskId,
                        role = AgentRole.CRAFTER,
                        displayName = state.taskTitle.ifBlank { "Task $taskId" },
                        status = state.status,
                    )
                )
            } else {
                // Update existing entry status
                val index = currentAgents.indexOfFirst { it.id == taskId }
                if (index >= 0) {
                    currentAgents[index] = currentAgents[index].copy(status = state.status)
                }
            }
        }

        _agents.value = currentAgents
    }

    // ── Internal: Output Accumulation ───────────────────────────────────

    internal fun appendChunkToOutput(agentId: String, role: AgentRole, chunk: StreamChunk) {
        val text = when (chunk) {
            is StreamChunk.Text -> chunk.content
            is StreamChunk.Thinking -> "[thinking] ${chunk.content}"
            is StreamChunk.ToolCall -> "[tool: ${chunk.name}] ${chunk.status}"
            is StreamChunk.Error -> "[error] ${chunk.message}"
            is StreamChunk.Completed -> null
            is StreamChunk.CompletionReport -> "[completed] ${chunk.summary}"
            is StreamChunk.Heartbeat -> null
        } ?: return

        val outputs = _agentOutputs.value.toMutableMap()
        val existing = outputs[agentId] ?: AgentOutput(agentId, role)
        outputs[agentId] = existing.copy(textChunks = existing.textChunks + text)
        _agentOutputs.value = outputs
    }

    // ── Internal: Helpers ───────────────────────────────────────────────

    private fun rebuildAgentList() {
        _agents.value = listOf(
            AgentEntry("__routa__", AgentRole.ROUTA, "ROUTA", AgentStatus.PENDING),
            AgentEntry("__gate__", AgentRole.GATE, "GATE", AgentStatus.PENDING),
        )
    }

    private fun clearOutputs() {
        _agentOutputs.value = emptyMap()
    }

    private fun updateAgentStatusFromPhase(phase: OrchestratorPhase) {
        val currentAgents = _agents.value.toMutableList()

        when (phase) {
            is OrchestratorPhase.Planning -> {
                updateAgentEntry(currentAgents, "__routa__") {
                    it.copy(status = AgentStatus.ACTIVE)
                }
            }
            is OrchestratorPhase.PlanReady -> {
                updateAgentEntry(currentAgents, "__routa__") {
                    it.copy(status = AgentStatus.COMPLETED)
                }
            }
            is OrchestratorPhase.CrafterRunning -> {
                updateAgentEntry(currentAgents, phase.taskId) {
                    it.copy(status = AgentStatus.ACTIVE)
                }
            }
            is OrchestratorPhase.CrafterCompleted -> {
                updateAgentEntry(currentAgents, phase.taskId) {
                    it.copy(status = AgentStatus.COMPLETED)
                }
            }
            is OrchestratorPhase.VerificationStarting -> {
                updateAgentEntry(currentAgents, "__gate__") {
                    it.copy(status = AgentStatus.ACTIVE)
                }
            }
            is OrchestratorPhase.VerificationCompleted -> {
                updateAgentEntry(currentAgents, "__gate__") {
                    it.copy(status = AgentStatus.COMPLETED)
                }
            }
            is OrchestratorPhase.Completed -> {
                // Mark all as completed
                for (i in currentAgents.indices) {
                    if (currentAgents[i].status != AgentStatus.ERROR) {
                        currentAgents[i] = currentAgents[i].copy(status = AgentStatus.COMPLETED)
                    }
                }
            }
            else -> { /* no sidebar update needed */ }
        }

        _agents.value = currentAgents
    }

    private fun updateAgentEntry(
        agents: MutableList<AgentEntry>,
        agentId: String,
        transform: (AgentEntry) -> AgentEntry,
    ) {
        val index = agents.indexOfFirst { it.id == agentId }
        if (index >= 0) {
            agents[index] = transform(agents[index])
        }
    }

    private fun handleResult(result: OrchestratorResult) {
        _statusText.value = when (result) {
            is OrchestratorResult.Success -> "✅ Completed — ${result.taskSummaries.size} tasks done"
            is OrchestratorResult.NoTasks -> "No tasks found in plan"
            is OrchestratorResult.MaxWavesReached -> "❌ Max waves reached (${result.waves})"
            is OrchestratorResult.Failed -> "❌ Error: ${result.error}"
        }
    }
}
