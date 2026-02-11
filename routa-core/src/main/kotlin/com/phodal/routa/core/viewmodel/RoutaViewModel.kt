package com.phodal.routa.core.viewmodel

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.coordinator.CoordinationState
import com.phodal.routa.core.event.AgentEvent
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.provider.AgentProvider
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.role.RouteDefinitions
import com.phodal.routa.core.runner.OrchestratorPhase
import com.phodal.routa.core.runner.OrchestratorResult
import com.phodal.routa.core.runner.RoutaOrchestrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Platform-agnostic ViewModel for the Routa multi-agent orchestrator.
 *
 * Encapsulates all orchestration state and business logic, providing observable
 * [StateFlow] / [SharedFlow] properties for UI binding. Shared between:
 * - **CLI** — [com.phodal.routa.core.cli.RoutaCli]
 * - **IntelliJ Plugin** — `IdeaRoutaService` / `DispatcherPanel`
 * - **Tests** — unit & integration tests
 *
 * ## Typical Usage
 * ```kotlin
 * val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
 * val vm = RoutaViewModel(scope)
 *
 * // Build a provider (e.g., via RoutaFactory.createProvider)
 * val provider = RoutaFactory.createProvider(...)
 * vm.initialize(provider, "my-workspace")
 *
 * // Observe state
 * launch { vm.phase.collect { phase -> println("Phase: $phase") } }
 * launch { vm.crafterStates.collect { states -> updateUI(states) } }
 *
 * // Execute
 * val result = vm.execute("Add user authentication")
 * ```
 *
 * ## Architecture
 * ```
 * RoutaViewModel
 *   ├── RoutaSystem (stores, coordinator, event bus)
 *   ├── RoutaOrchestrator (ROUTA → CRAFTER → GATE flow)
 *   ├── AgentProvider (pluggable: ACP, Koog, Claude, mock)
 *   └── Observable State (phase, chunks, crafter states, result)
 * ```
 *
 * @param scope The coroutine scope for background work. The caller owns the scope's lifecycle.
 */
class RoutaViewModel(
    private val scope: CoroutineScope,
) {

    // ── Observable State ────────────────────────────────────────────────

    private val _phase = MutableStateFlow<OrchestratorPhase>(OrchestratorPhase.Initializing)
    /** Current orchestration phase (ROUTA planning, CRAFTER running, GATE verifying, etc.). */
    val phase: StateFlow<OrchestratorPhase> = _phase.asStateFlow()

    private val _routaChunks = MutableSharedFlow<StreamChunk>(extraBufferCapacity = 512)
    /** Streaming chunks from the ROUTA (planning) agent. */
    val routaChunks: SharedFlow<StreamChunk> = _routaChunks.asSharedFlow()

    private val _gateChunks = MutableSharedFlow<StreamChunk>(extraBufferCapacity = 512)
    /** Streaming chunks from the GATE (verification) agent. */
    val gateChunks: SharedFlow<StreamChunk> = _gateChunks.asSharedFlow()

    private val _crafterChunks = MutableSharedFlow<Pair<String, StreamChunk>>(extraBufferCapacity = 512)
    /** Streaming chunks from CRAFTER agents, keyed by agent ID. */
    val crafterChunks: SharedFlow<Pair<String, StreamChunk>> = _crafterChunks.asSharedFlow()

    private val _crafterStates = MutableStateFlow<Map<String, CrafterStreamState>>(emptyMap())
    /** Per-CRAFTER streaming state for UI rendering. */
    val crafterStates: StateFlow<Map<String, CrafterStreamState>> = _crafterStates.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    /** All agent events from the event bus (for detailed logging / debugging). */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    /** Whether an orchestration is currently running. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _result = MutableStateFlow<OrchestratorResult?>(null)
    /** The result of the last orchestration (null if none has completed). */
    val result: StateFlow<OrchestratorResult?> = _result.asStateFlow()

    // ── Internal State ──────────────────────────────────────────────────

    private var routaSystem: RoutaSystem? = null
    private var orchestrator: RoutaOrchestrator? = null
    private var provider: AgentProvider? = null
    private var eventListenerJob: Job? = null

    /** Track agentId → role for routing stream chunks. */
    private val agentRoleMap = mutableMapOf<String, AgentRole>()

    /** Track crafterId → taskId mapping. */
    private val crafterTaskMap = mutableMapOf<String, String>()

    /** Track crafterId → task title (cached from handlePhaseChange). */
    private val crafterTitleMap = mutableMapOf<String, String>()

    /** Lock for thread-safe updates to _crafterStates. */
    private val crafterStateLock = Any()

    // ── Configuration ───────────────────────────────────────────────────

    /**
     * Whether to prepend ROUTA system prompt to user requests.
     *
     * When `true` (default), the ROUTA coordinator instructions from [RouteDefinitions.ROUTA]
     * are prepended to user input, providing context for ACP-based ROUTA agents.
     * Set to `false` when using Koog (which injects system prompt via its own mechanism)
     * or when the prompt is already enhanced.
     */
    var useEnhancedRoutaPrompt: Boolean = true

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Get the underlying [RoutaSystem] for advanced usage (e.g., accessing stores, event bus).
     * Returns `null` if not initialized.
     */
    val system: RoutaSystem? get() = routaSystem

    /**
     * Get the coordination state from the underlying [com.phodal.routa.core.coordinator.RoutaCoordinator].
     *
     * Useful for observing the low-level coordination phase (IDLE, PLANNING, EXECUTING, etc.)
     * and tracking which agents are active.
     */
    val coordinationState: StateFlow<CoordinationState>
        get() = routaSystem?.coordinator?.coordinationState ?: MutableStateFlow(CoordinationState())

    /**
     * Check if the ViewModel has been initialized with a provider.
     */
    fun isInitialized(): Boolean = orchestrator != null

    /**
     * Initialize the ViewModel with a provider and workspace.
     *
     * Creates the underlying [RoutaSystem] and [RoutaOrchestrator], and starts
     * listening for events from the event bus.
     *
     * If a [RoutaSystem] is provided, it is used directly (useful when the provider
     * needs to share the same system, e.g., [com.phodal.routa.core.provider.KoogAgentProvider]
     * which requires access to agent tools from the same stores). Otherwise, a fresh
     * in-memory system is created.
     *
     * @param provider The [AgentProvider] to use for execution (ACP, Koog, Claude, mock, etc.)
     * @param workspaceId The workspace identifier for this orchestration session.
     * @param system Optional pre-created [RoutaSystem]. If null, a new in-memory system is created.
     */
    fun initialize(
        provider: AgentProvider,
        workspaceId: String,
        system: RoutaSystem? = null,
    ) {
        // Clean up any previous session
        resetInternal()

        this.provider = provider

        val routaSys = system ?: RoutaFactory.createInMemory(scope)
        routaSystem = routaSys

        orchestrator = RoutaOrchestrator(
            routa = routaSys,
            runner = provider,
            workspaceId = workspaceId,
            onPhaseChange = { phase -> handlePhaseChange(phase) },
            onStreamChunk = { agentId, chunk -> handleStreamChunk(agentId, chunk) },
        )

        // Listen to events from the event bus
        eventListenerJob = scope.launch {
            routaSys.eventBus.events.collect { event ->
                handleEvent(event)
                _events.tryEmit(event)
            }
        }
    }

    /**
     * Execute a user request through the full ROUTA → CRAFTER → GATE pipeline.
     *
     * This is a suspending function that runs the complete orchestration flow:
     * 1. ROUTA plans tasks from the user request
     * 2. CRAFTERs execute the planned tasks
     * 3. GATE verifies all completed work
     * 4. If not approved, CRAFTERs retry (up to max waves)
     *
     * State changes are observable via [phase], [crafterStates], [routaChunks], etc.
     *
     * @param userRequest The user's task description / requirement.
     * @return The [OrchestratorResult] indicating success, failure, or other outcomes.
     * @throws IllegalStateException if not initialized.
     */
    suspend fun execute(userRequest: String): OrchestratorResult {
        val orch = orchestrator
            ?: throw IllegalStateException("ViewModel not initialized. Call initialize() first.")

        _isRunning.value = true
        _result.value = null
        _crafterStates.value = emptyMap()
        agentRoleMap.clear()
        crafterTaskMap.clear()
        crafterTitleMap.clear()

        val request = if (useEnhancedRoutaPrompt) {
            buildRoutaEnhancedPrompt(userRequest)
        } else {
            userRequest
        }

        return try {
            val result = orch.execute(request)
            _result.value = result
            result
        } catch (e: Exception) {
            val failedResult = OrchestratorResult.Failed(e.message ?: "Unknown error")
            _result.value = failedResult
            failedResult
        } finally {
            _isRunning.value = false
        }
    }

    /**
     * Stop all running agents and cancel the current execution.
     *
     * Interrupts all active CRAFTER agents, marks them as CANCELLED,
     * and resets the ViewModel to its initial state.
     */
    fun stopExecution() {
        if (!_isRunning.value) return

        scope.launch {
            try {
                // Interrupt all active CRAFTER agents
                val activeAgents = _crafterStates.value.filter { it.value.status == AgentStatus.ACTIVE }
                for ((agentId, _) in activeAgents) {
                    try {
                        provider?.interrupt(agentId)
                        updateCrafterState(agentId) { current ->
                            current.copy(status = AgentStatus.CANCELLED)
                        }
                    } catch (_: Exception) {
                        // Best effort
                    }
                }

                resetInternal()
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    /**
     * Stop a specific CRAFTER agent by its ID.
     *
     * @param agentId The agent ID to interrupt.
     */
    fun stopCrafter(agentId: String) {
        scope.launch {
            try {
                provider?.interrupt(agentId)
                updateCrafterState(agentId) { current ->
                    current.copy(status = AgentStatus.CANCELLED)
                }
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    /**
     * Reset the ViewModel, cleaning up all internal resources.
     *
     * Does NOT shut down the provider — the provider lifecycle is managed externally.
     * After reset, [isInitialized] returns `false` and a new [initialize] call is needed.
     */
    fun reset() {
        resetInternal()
    }

    /**
     * Dispose the ViewModel. Call when the owning component is destroyed.
     * Equivalent to [reset].
     */
    fun dispose() {
        resetInternal()
    }

    // ── Internal reset ──────────────────────────────────────────────────

    private fun resetInternal() {
        eventListenerJob?.cancel()
        eventListenerJob = null

        routaSystem?.coordinator?.reset()
        routaSystem = null
        orchestrator = null
        // Don't shutdown provider — it's externally managed
        provider = null

        _phase.value = OrchestratorPhase.Initializing
        _crafterStates.value = emptyMap()
        _isRunning.value = false
        _result.value = null
        agentRoleMap.clear()
        crafterTaskMap.clear()
        crafterTitleMap.clear()
    }

    // ── Event Handlers ──────────────────────────────────────────────────

    private suspend fun handlePhaseChange(phase: OrchestratorPhase) {
        _phase.value = phase

        when (phase) {
            is OrchestratorPhase.CrafterRunning -> {
                agentRoleMap[phase.crafterId] = AgentRole.CRAFTER
                crafterTaskMap[phase.crafterId] = phase.taskId

                // Get task title from the store with fallback logic
                val task = routaSystem?.context?.taskStore?.get(phase.taskId)
                val title = when {
                    task?.title?.isNotBlank() == true -> task.title
                    else -> {
                        val allTasks = routaSystem?.context?.taskStore?.listByWorkspace(
                            routaSystem?.coordinator?.coordinationState?.value?.workspaceId ?: ""
                        )
                        val matchingTask = allTasks?.find { it.id == phase.taskId }
                        matchingTask?.title?.takeIf { it.isNotBlank() }
                            ?: "Task ${phase.taskId.take(8)}..."
                    }
                }

                // Cache the title for updateCrafterState fallback
                crafterTitleMap[phase.crafterId] = title

                updateCrafterState(phase.crafterId) {
                    CrafterStreamState(
                        agentId = phase.crafterId,
                        taskId = phase.taskId,
                        taskTitle = title,
                        status = AgentStatus.ACTIVE,
                    )
                }
            }

            is OrchestratorPhase.CrafterCompleted -> {
                updateCrafterState(phase.crafterId) { current ->
                    current.copy(status = AgentStatus.COMPLETED)
                }
            }

            else -> {
                // Other phases are observable via the phase StateFlow
            }
        }
    }

    private fun handleStreamChunk(agentId: String, chunk: StreamChunk) {
        // Determine the agent's role for routing
        val role = agentRoleMap[agentId] ?: run {
            val state = routaSystem?.coordinator?.coordinationState?.value
            when {
                state?.routaAgentId == agentId -> {
                    agentRoleMap[agentId] = AgentRole.ROUTA
                    AgentRole.ROUTA
                }
                else -> null
            }
        }

        when (role) {
            AgentRole.ROUTA -> _routaChunks.tryEmit(chunk)
            AgentRole.GATE -> _gateChunks.tryEmit(chunk)
            AgentRole.CRAFTER -> {
                // Emit chunk for real-time UI updates
                _crafterChunks.tryEmit(agentId to chunk)

                // Also update accumulated state
                updateCrafterState(agentId) { current ->
                    val newOutput = when (chunk) {
                        is StreamChunk.Text -> current.outputText + chunk.content
                        is StreamChunk.ToolCall -> current.outputText + "\n[${chunk.status}] ${chunk.name}"
                        is StreamChunk.Error -> current.outputText + "\n[ERROR] ${chunk.message}"
                        else -> current.outputText
                    }
                    current.copy(
                        chunks = current.chunks + chunk,
                        outputText = newOutput,
                    )
                }
            }

            null -> {
                // Unknown agent — skip routing
            }
        }
    }

    private suspend fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AgentCreated -> {
                val agent = routaSystem?.context?.agentStore?.get(event.agentId)
                if (agent != null) {
                    agentRoleMap[agent.id] = agent.role
                }
            }

            is AgentEvent.AgentStatusChanged -> {
                if (event.newStatus == AgentStatus.ERROR) {
                    val role = agentRoleMap[event.agentId]
                    if (role == AgentRole.CRAFTER) {
                        updateCrafterState(event.agentId) { current ->
                            current.copy(status = AgentStatus.ERROR)
                        }
                    }
                }
            }

            is AgentEvent.TaskDelegated -> {
                agentRoleMap[event.agentId] = AgentRole.CRAFTER
                crafterTaskMap[event.agentId] = event.taskId
            }

            is AgentEvent.AgentCompleted -> {
                val role = agentRoleMap[event.agentId]
                if (role == AgentRole.CRAFTER) {
                    val report = event.report
                    val completionChunk = StreamChunk.CompletionReport(
                        agentId = event.agentId,
                        taskId = report.taskId,
                        summary = report.summary,
                        filesModified = report.filesModified,
                        success = report.success,
                    )
                    _crafterChunks.tryEmit(event.agentId to completionChunk)
                }
            }

            else -> {
                // Other events are observable via the events SharedFlow
            }
        }
    }

    private fun updateCrafterState(agentId: String, updater: (CrafterStreamState) -> CrafterStreamState) {
        synchronized(crafterStateLock) {
            val current = _crafterStates.value.toMutableMap()
            val existing = current[agentId] ?: CrafterStreamState(
                agentId = agentId,
                taskId = crafterTaskMap[agentId] ?: "",
                taskTitle = crafterTitleMap[agentId] ?: "",
            )
            current[agentId] = updater(existing)
            _crafterStates.value = current
        }
    }

    // ── Prompt Enhancement ──────────────────────────────────────────────

    private fun buildRoutaEnhancedPrompt(userRequest: String): String {
        val routaSystemPrompt = RouteDefinitions.ROUTA.systemPrompt
        val routaRoleReminder = RouteDefinitions.ROUTA.roleReminder

        return buildString {
            appendLine("# ROUTA Coordinator Instructions")
            appendLine()
            appendLine(routaSystemPrompt)
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("**Role Reminder:** $routaRoleReminder")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("# User Request")
            appendLine()
            appendLine(userRequest)
        }
    }
}
