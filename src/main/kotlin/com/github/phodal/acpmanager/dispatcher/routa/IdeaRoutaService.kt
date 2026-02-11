package com.github.phodal.acpmanager.dispatcher.routa

import com.github.phodal.acpmanager.acp.AcpSessionManager
import com.github.phodal.acpmanager.config.AcpConfigService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.config.RoutaConfigLoader
import com.phodal.routa.core.coordinator.CoordinationPhase
import com.phodal.routa.core.coordinator.CoordinationState
import com.phodal.routa.core.event.AgentEvent
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.provider.AgentProvider
import com.phodal.routa.core.provider.CapabilityBasedRouter
import com.phodal.routa.core.provider.KoogAgentProvider
import com.phodal.routa.core.provider.ResilientAgentProvider
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
    private var router: CapabilityBasedRouter? = null
    private var acpProvider: IdeaAcpAgentProvider? = null
    private var eventListenerJob: Job? = null

    /** Track agentId → role for routing stream chunks */
    private val agentRoleMap = mutableMapOf<String, AgentRole>()

    /** Track crafterId → taskId mapping */
    private val crafterTaskMap = mutableMapOf<String, String>()

    // ── Model Configuration ─────────────────────────────────────────────

    val crafterModelKey = MutableStateFlow("")
    val routaModelKey = MutableStateFlow("")
    val gateModelKey = MutableStateFlow("")

    /** The active LLM model config for ROUTA/GATE (KoogAgent). */
    private val _llmModelConfig = MutableStateFlow<NamedModelConfig?>(null)
    val llmModelConfig: StateFlow<NamedModelConfig?> = _llmModelConfig.asStateFlow()

    private val _mcpServerUrl = MutableStateFlow<String?>(null)
    /** The MCP server SSE URL exposed to Claude Code, if running. */
    val mcpServerUrl: StateFlow<String?> = _mcpServerUrl.asStateFlow()

    /**
     * Get available LLM model configs from ~/.autodev/config.yaml.
     */
    fun getAvailableLlmConfigs(): List<NamedModelConfig> {
        return try {
            RoutaConfigLoader.load().configs
        } catch (e: Exception) {
            log.warn("Failed to load LLM configs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get the active LLM model config.
     */
    fun getActiveLlmConfig(): NamedModelConfig? {
        return _llmModelConfig.value ?: RoutaConfigLoader.getActiveModelConfig()
    }

    /**
     * Set the LLM model config for ROUTA/GATE.
     */
    fun setLlmModelConfig(config: NamedModelConfig) {
        _llmModelConfig.value = config
    }

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
     * Uses [CapabilityBasedRouter] to route:
     * - ROUTA (planning) → [KoogAgentProvider] (LLM with tool calling)
     * - CRAFTER (implementation) → [IdeaAcpAgentProvider] (ACP agents like Codex, Claude Code)
     * - GATE (verification) → [KoogAgentProvider] or [IdeaAcpAgentProvider] (auto-selected by capabilities)
     *
     * This matches the architecture used in [RoutaCli].
     *
     * @param crafterAgent ACP agent key for CRAFTERs
     * @param routaAgent ACP agent key for ROUTA (used as fallback if no LLM config)
     * @param gateAgent ACP agent key for GATE (used as fallback if no LLM config)
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

        val workspaceId = project.basePath ?: "default-workspace"

        // Build capability-based router (like RoutaCli.buildProvider)
        val providers = mutableListOf<AgentProvider>()

        // 1. KoogAgentProvider for ROUTA (planning via LLM with tool calling)
        val modelConfig = _llmModelConfig.value ?: RoutaConfigLoader.getActiveModelConfig()
        if (modelConfig != null) {
            val koog: AgentProvider = KoogAgentProvider(
                agentTools = system.tools,
                workspaceId = workspaceId,
                modelConfig = modelConfig,
            )
            providers.add(ResilientAgentProvider(koog, system.context.conversationStore))
            log.info("Added KoogAgentProvider for ROUTA/GATE: ${modelConfig.provider}/${modelConfig.model}")
        } else {
            log.warn("No LLM config found. ROUTA/GATE will fall back to ACP provider.")
        }

        // 2. IdeaAcpAgentProvider for CRAFTER (real coding agents)
        val ideaAcpProvider = IdeaAcpAgentProvider(
            project = project,
            scope = scope,
            crafterAgentKey = crafterAgent,
            gateAgentKey = gateAgent,
            routaAgentKey = routaAgent,
        )
        acpProvider = ideaAcpProvider
        providers.add(ideaAcpProvider)

        // 3. Build the CapabilityBasedRouter
        val capRouter = CapabilityBasedRouter(*providers.toTypedArray())
        router = capRouter

        orchestrator = RoutaOrchestrator(
            routa = system,
            runner = capRouter,
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

        // Log provider routing info
        val routerInfo = capRouter.listProviders().joinToString(", ") { "${it.name}[p=${it.priority}]" }
        log.info("IdeaRoutaService initialized: crafter=$crafterAgent, providers=[$routerInfo]")
        if (modelConfig != null) {
            log.info("ROUTA/GATE LLM: ${modelConfig.provider}/${modelConfig.model}")
        }

        // Pre-connect a crafter session to start MCP server for coordination tools
        scope.launch {
            try {
                val configService = AcpConfigService.getInstance(project)
                val crafterConfig = configService.getAgentConfig(crafterAgent)
                if (crafterConfig != null) {
                    log.info("Pre-connecting crafter session to start MCP server...")
                    val sessionManager = AcpSessionManager.getInstance(project)
                    val sessionKey = "routa-mcp-crafter"
                    val session = sessionManager.getOrCreateSession(sessionKey)
                    if (!session.isConnected) {
                        session.connect(crafterConfig)
                        // Wait a bit for the MCP server to start
                        delay(500)
                        // Refresh MCP URL immediately
                        _mcpServerUrl.value = session.mcpServerUrl
                        if (session.mcpServerUrl != null) {
                            log.info("MCP server started at: ${session.mcpServerUrl}")
                        } else {
                            log.info("Session connected, but no MCP server (only Claude Code starts MCP server)")
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to pre-connect crafter session: ${e.message}", e)
            }
        }
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
        _mcpServerUrl.value = null

        // Disconnect pre-connected MCP session
        scope.launch {
            try {
                val sessionManager = AcpSessionManager.getInstance(project)
                sessionManager.disconnectAgent("routa-mcp-crafter")
            } catch (e: Exception) {
                log.debug("Failed to disconnect MCP session: ${e.message}")
            }
        }

        scope.launch {
            router?.shutdown()
            router = null
            acpProvider = null
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
        // Try to get role from cache
        // If not found, check if this is the ROUTA agent from coordination state
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
                // Unknown agent — log and skip
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
