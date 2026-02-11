package com.github.phodal.acpmanager.dispatcher.routa

import com.github.phodal.acpmanager.acp.AcpSessionManager
import com.github.phodal.acpmanager.config.AcpConfigService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.config.RoutaConfigLoader
import com.phodal.routa.core.coordinator.CoordinationState
import com.phodal.routa.core.event.AgentEvent
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.provider.AgentProvider
import com.phodal.routa.core.provider.CapabilityBasedRouter
import com.phodal.routa.core.provider.KoogAgentProvider
import com.phodal.routa.core.provider.ResilientAgentProvider
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.runner.OrchestratorPhase
import com.phodal.routa.core.runner.OrchestratorResult
import com.phodal.routa.core.viewmodel.CrafterStreamState
import com.phodal.routa.core.viewmodel.RoutaViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val log = logger<IdeaRoutaService>()

/**
 * Project-level service that bridges routa-core's [RoutaViewModel] with
 * the IDEA plugin infrastructure.
 *
 * **Responsibilities:**
 * - Creates IntelliJ-specific providers ([IdeaAcpAgentProvider])
 * - Manages ACP sessions and MCP server pre-connection
 * - Delegates all orchestration state and logic to [RoutaViewModel]
 *
 * **Architecture:**
 * ```
 * DispatcherPanel → IdeaRoutaService → RoutaViewModel (routa-core)
 *                        ↓
 *                  IdeaAcpAgentProvider (IntelliJ-specific)
 * ```
 *
 * The same [RoutaViewModel] is used by RoutaCli for headless/automated execution.
 */
@Service(Service.Level.PROJECT)
class IdeaRoutaService(private val project: Project) : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Core ViewModel (from routa-core) ────────────────────────────────

    /** The platform-agnostic ViewModel that holds all orchestration state and logic. */
    val viewModel = RoutaViewModel(scope)

    // ── Delegated Observable State ──────────────────────────────────────
    // These delegate to the ViewModel so existing UI code (DispatcherPanel)
    // continues to work without changes.

    /** Current orchestration phase. */
    val phase: StateFlow<OrchestratorPhase> get() = viewModel.phase

    /** Streaming chunks from the ROUTA (planning) agent. */
    val routaChunks: SharedFlow<StreamChunk> get() = viewModel.routaChunks

    /** Streaming chunks from the GATE (verification) agent. */
    val gateChunks: SharedFlow<StreamChunk> get() = viewModel.gateChunks

    /** Streaming chunks from CRAFTER agents, keyed by agent ID. */
    val crafterChunks: SharedFlow<Pair<String, StreamChunk>> get() = viewModel.crafterChunks

    /** Per-CRAFTER streaming state. */
    val crafterStates: StateFlow<Map<String, CrafterStreamState>> get() = viewModel.crafterStates

    /** All agent events. */
    val events: SharedFlow<AgentEvent> get() = viewModel.events

    /** Whether an orchestration is currently running. */
    val isRunning: StateFlow<Boolean> get() = viewModel.isRunning

    /** The result of the last orchestration. */
    val result: StateFlow<OrchestratorResult?> get() = viewModel.result

    /** Coordination state from the underlying RoutaCoordinator. */
    val coordinationState: StateFlow<CoordinationState> get() = viewModel.coordinationState

    // ── IDE-Specific Configuration ──────────────────────────────────────

    val crafterModelKey = MutableStateFlow("")
    val routaModelKey = MutableStateFlow("")
    val gateModelKey = MutableStateFlow("")

    /** Whether to use ACP Agent for ROUTA planning (instead of KoogAgent). */
    private val _useAcpForRouta = MutableStateFlow(true)
    val useAcpForRouta: StateFlow<Boolean> = _useAcpForRouta.asStateFlow()

    /** The active LLM model config for ROUTA/GATE (KoogAgent). */
    private val _llmModelConfig = MutableStateFlow<NamedModelConfig?>(null)
    val llmModelConfig: StateFlow<NamedModelConfig?> = _llmModelConfig.asStateFlow()

    private val _mcpServerUrl = MutableStateFlow<String?>(null)
    /** The MCP server SSE URL exposed to Claude Code, if running. */
    val mcpServerUrl: StateFlow<String?> = _mcpServerUrl.asStateFlow()

    // ── IDE-Specific Internal State ─────────────────────────────────────

    private var router: CapabilityBasedRouter? = null
    private var acpProvider: IdeaAcpAgentProvider? = null

    // ── Public API (LLM Config) ─────────────────────────────────────────

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

    // ── Public API (Orchestration) ──────────────────────────────────────

    /**
     * Check if the service has been initialized.
     */
    fun isInitialized(): Boolean = viewModel.isInitialized()

    /**
     * Stop all running agents and cancel the current execution.
     */
    fun stopExecution() {
        if (!isRunning.value) return

        log.info("Stopping execution...")

        scope.launch {
            try {
                // Interrupt all active CRAFTER agents via the IDE-specific provider
                val activeAgents = crafterStates.value.filter { it.value.status == com.phodal.routa.core.model.AgentStatus.ACTIVE }
                for ((agentId, _) in activeAgents) {
                    try {
                        acpProvider?.interrupt(agentId)
                        log.info("Interrupted agent $agentId")
                    } catch (e: Exception) {
                        log.warn("Failed to interrupt agent $agentId: ${e.message}")
                    }
                }

                // Reset via ViewModel
                viewModel.reset()
                // Reset IDE-specific state
                _mcpServerUrl.value = null
                disconnectMcpSession()
            } catch (e: Exception) {
                log.warn("Error during stop: ${e.message}", e)
            }
        }
    }

    /**
     * Stop a specific CRAFTER agent by its ID.
     */
    fun stopCrafter(agentId: String) {
        log.info("Stopping CRAFTER agent $agentId...")
        viewModel.stopCrafter(agentId)
    }

    /**
     * Initialize the Routa system with the specified agent keys.
     *
     * Uses [CapabilityBasedRouter] to route:
     * - ROUTA (planning) → [KoogAgentProvider] (LLM with tool calling)
     * - CRAFTER (implementation) → [IdeaAcpAgentProvider] (ACP agents like Codex, Claude Code)
     * - GATE (verification) → [KoogAgentProvider] or [IdeaAcpAgentProvider]
     *
     * This matches the architecture used in RoutaCli, using the shared [RoutaViewModel].
     *
     * @param crafterAgent ACP agent key for CRAFTERs
     * @param routaAgent ACP agent key for ROUTA
     * @param gateAgent ACP agent key for GATE
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

        val workspaceId = project.basePath ?: "default-workspace"

        // Create a shared RoutaSystem for both provider and orchestrator
        val system = RoutaFactory.createInMemory(scope)

        // Build capability-based router (like RoutaCli.buildProvider)
        val providers = mutableListOf<AgentProvider>()

        // When useAcpForRouta is false, add KoogAgentProvider for ROUTA
        if (!_useAcpForRouta.value) {
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
        } else {
            log.info("Using ACP Agent for ROUTA planning (useAcpForRouta=true)")
        }

        // IdeaAcpAgentProvider for CRAFTER and ROUTA (when useAcpForRouta=true)
        val ideaAcpProvider = IdeaAcpAgentProvider(
            project = project,
            scope = scope,
            crafterAgentKey = crafterAgent,
            gateAgentKey = gateAgent,
            routaAgentKey = routaAgent,
        )
        acpProvider = ideaAcpProvider
        providers.add(ideaAcpProvider)

        // Build the CapabilityBasedRouter
        val capRouter = CapabilityBasedRouter(*providers.toTypedArray())
        router = capRouter

        // Configure and initialize the ViewModel
        viewModel.useEnhancedRoutaPrompt = _useAcpForRouta.value
        viewModel.initialize(capRouter, workspaceId, system)

        // Log provider routing info
        val routerInfo = capRouter.listProviders().joinToString(", ") { "${it.name}[p=${it.priority}]" }
        val routaMode = if (_useAcpForRouta.value) "ACP Agent ($routaAgent)" else "KoogAgent"
        log.info("IdeaRoutaService initialized: crafter=$crafterAgent, routa=$routaMode, providers=[$routerInfo]")

        // Pre-connect a crafter session to start MCP server for coordination tools
        preConnectMcpSession(crafterAgent)
    }

    /**
     * Execute a user request through the full Routa → CRAFTER → GATE pipeline.
     * Delegates to [RoutaViewModel.execute].
     */
    suspend fun execute(userRequest: String): OrchestratorResult {
        return viewModel.execute(userRequest)
    }

    /**
     * Reset the service, cleaning up all resources.
     */
    fun reset() {
        viewModel.reset()
        _mcpServerUrl.value = null

        // Clean up IDE-specific resources
        disconnectMcpSession()

        scope.launch {
            router?.shutdown()
            router = null
            acpProvider = null
        }
    }

    // ── IDE-Specific: MCP Session Management ────────────────────────────

    private fun preConnectMcpSession(crafterAgent: String) {
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
                        // Wait for MCP server to start
                        delay(500)
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

    private fun disconnectMcpSession() {
        scope.launch {
            try {
                val sessionManager = AcpSessionManager.getInstance(project)
                sessionManager.disconnectAgent("routa-mcp-crafter")
            } catch (e: Exception) {
                log.debug("Failed to disconnect MCP session: ${e.message}")
            }
        }
    }

    override fun dispose() {
        reset()
        viewModel.dispose()
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): IdeaRoutaService = project.service()
    }
}
