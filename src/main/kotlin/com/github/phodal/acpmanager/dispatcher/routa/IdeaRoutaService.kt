package com.github.phodal.acpmanager.dispatcher.routa

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.coordinator.CoordinationPhase
import com.phodal.routa.core.coordinator.CoordinationState
import com.phodal.routa.core.event.AgentEvent
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.runner.OrchestratorPhase
import com.phodal.routa.core.runner.OrchestratorResult
import com.phodal.routa.core.runner.RoutaOrchestrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val log = logger<IdeaRoutaService>()

/**
 * Data class representing a CRAFTER agent's streaming state for the UI.
 */
data class CrafterStreamState(
    val agentId: String,
    val taskId: String,
    val taskTitle: String = "",
    val status: AgentStatus = AgentStatus.PENDING,
    val chunks: List<StreamChunk> = emptyList(),
    val outputText: String = "",
)

/**
 * Project-level service that bridges routa-core's multi-agent coordination
 * with the IDEA plugin infrastructure.
 *
 * Provides observable state flows for the UI to bind to:
 * - [phase] — current orchestration phase
 * - [coordinationState] — full coordination state from RoutaCoordinator
 * - [routaChunks] — streaming chunks from the ROUTA agent
 * - [crafterStates] — per-CRAFTER streaming state
 * - [gateChunks] — streaming chunks from the GATE agent
 * - [events] — all agent events for detailed logging
 */
@Service(Service.Level.PROJECT)
class IdeaRoutaService(private val project: Project) : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Observable State ────────────────────────────────────────────────

    private val _phase = MutableStateFlow<OrchestratorPhase>(OrchestratorPhase.Initializing)
    val phase: StateFlow<OrchestratorPhase> = _phase.asStateFlow()

    private val _routaChunks = MutableSharedFlow<StreamChunk>(extraBufferCapacity = 512)
    val routaChunks: SharedFlow<StreamChunk> = _routaChunks.asSharedFlow()

    private val _gateChunks = MutableSharedFlow<StreamChunk>(extraBufferCapacity = 512)
    val gateChunks: SharedFlow<StreamChunk> = _gateChunks.asSharedFlow()

    private val _crafterStates = MutableStateFlow<Map<String, CrafterStreamState>>(emptyMap())
    val crafterStates: StateFlow<Map<String, CrafterStreamState>> = _crafterStates.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _result = MutableStateFlow<OrchestratorResult?>(null)
    val result: StateFlow<OrchestratorResult?> = _result.asStateFlow()

    // ── Internal State ──────────────────────────────────────────────────

    private var routaSystem: RoutaSystem? = null
    private var orchestrator: RoutaOrchestrator? = null
    private var provider: IdeaAcpAgentProvider? = null
    private var eventListenerJob: Job? = null

    /** Track agentId → role for routing stream chunks */
    private val agentRoleMap = mutableMapOf<String, AgentRole>()

    /** Track crafterId → taskId mapping */
    private val crafterTaskMap = mutableMapOf<String, String>()

    // ── Model Configuration ─────────────────────────────────────────────

    val crafterModelKey = MutableStateFlow("")
    val routaModelKey = MutableStateFlow("")
    val gateModelKey = MutableStateFlow("")

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Check if the service has been initialized.
     */
    fun isInitialized(): Boolean = orchestrator != null

    /**
     * Get the coordination state from the underlying RoutaCoordinator.
     */
    val coordinationState: StateFlow<CoordinationState>
        get() = routaSystem?.coordinator?.coordinationState ?: MutableStateFlow(CoordinationState())

    /**
     * Initialize the Routa system with the specified agent keys.
     *
     * @param crafterAgent ACP agent key for CRAFTERs
     * @param routaAgent ACP agent key for ROUTA (defaults to crafterAgent)
     * @param gateAgent ACP agent key for GATE (defaults to crafterAgent)
     */
    fun initialize(
        crafterAgent: String,
        routaAgent: String = crafterAgent,
        gateAgent: String = crafterAgent,
    ) {
        // Clean up previous session
        reset()

        crafterModelKey.value = crafterAgent
        routaModelKey.value = routaAgent
        gateModelKey.value = gateAgent

        val system = RoutaFactory.createInMemory(scope)
        routaSystem = system

        val acpProvider = IdeaAcpAgentProvider(
            project = project,
            scope = scope,
            crafterAgentKey = crafterAgent,
            gateAgentKey = gateAgent,
            routaAgentKey = routaAgent,
        )
        provider = acpProvider

        val workspaceId = project.basePath ?: "default-workspace"

        orchestrator = RoutaOrchestrator(
            routa = system,
            runner = acpProvider,
            workspaceId = workspaceId,
            onPhaseChange = { phase -> handlePhaseChange(phase) },
            onStreamChunk = { agentId, chunk -> handleStreamChunk(agentId, chunk) },
        )

        // Listen to events from the event bus
        eventListenerJob = scope.launch {
            system.eventBus.events.collect { event ->
                handleEvent(event)
                _events.tryEmit(event)
            }
        }

        log.info("IdeaRoutaService initialized: crafter=$crafterAgent, routa=$routaAgent, gate=$gateAgent")
    }

    /**
     * Execute a user request through the full Routa → CRAFTER → GATE pipeline.
     */
    suspend fun execute(userRequest: String): OrchestratorResult {
        val orch = orchestrator
            ?: throw IllegalStateException("Service not initialized. Call initialize() first.")

        _isRunning.value = true
        _result.value = null
        _crafterStates.value = emptyMap()
        agentRoleMap.clear()
        crafterTaskMap.clear()

        return try {
            val result = orch.execute(userRequest)
            _result.value = result
            result
        } catch (e: Exception) {
            log.warn("Orchestration failed: ${e.message}", e)
            val failedResult = OrchestratorResult.Failed(e.message ?: "Unknown error")
            _result.value = failedResult
            failedResult
        } finally {
            _isRunning.value = false
        }
    }

    /**
     * Reset the service, cleaning up all resources.
     */
    fun reset() {
        eventListenerJob?.cancel()
        eventListenerJob = null

        routaSystem?.coordinator?.reset()
        routaSystem = null
        orchestrator = null

        scope.launch {
            provider?.shutdown()
            provider = null
        }

        _phase.value = OrchestratorPhase.Initializing
        _crafterStates.value = emptyMap()
        _isRunning.value = false
        _result.value = null
        agentRoleMap.clear()
        crafterTaskMap.clear()
    }

    // ── Event Handlers ──────────────────────────────────────────────────

    private suspend fun handlePhaseChange(phase: OrchestratorPhase) {
        log.info("Phase changed: $phase")
        _phase.value = phase

        // Track crafter start/completion for UI
        when (phase) {
            is OrchestratorPhase.CrafterRunning -> {
                agentRoleMap[phase.crafterId] = AgentRole.CRAFTER
                crafterTaskMap[phase.crafterId] = phase.taskId

                // Get task title from the store
                val task = routaSystem?.context?.taskStore?.get(phase.taskId)
                val title = task?.title ?: phase.taskId

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

            is OrchestratorPhase.VerificationStarting -> {
                // The next agent that streams will be the GATE
            }

            else -> {}
        }
    }

    private fun handleStreamChunk(agentId: String, chunk: StreamChunk) {
        val role = agentRoleMap[agentId]

        when (role) {
            AgentRole.ROUTA -> _routaChunks.tryEmit(chunk)
            AgentRole.GATE -> _gateChunks.tryEmit(chunk)
            AgentRole.CRAFTER -> {
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
                // Unknown agent — try to determine role from event
                log.debug("Stream chunk for unknown agent $agentId, skipping routing")
            }
        }
    }

    private suspend fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AgentCreated -> {
                // Look up the agent to determine its role
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

            else -> {}
        }
    }

    private fun updateCrafterState(agentId: String, updater: (CrafterStreamState) -> CrafterStreamState) {
        val current = _crafterStates.value.toMutableMap()
        val existing = current[agentId] ?: CrafterStreamState(
            agentId = agentId,
            taskId = crafterTaskMap[agentId] ?: "",
        )
        current[agentId] = updater(existing)
        _crafterStates.value = current
    }

    override fun dispose() {
        reset()
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): IdeaRoutaService = project.service()
    }
}
