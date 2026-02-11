package com.github.phodal.acpmanager.dispatcher.ui

import com.github.phodal.acpmanager.config.AcpConfigService
import com.github.phodal.acpmanager.dispatcher.routa.IdeaRoutaService
import com.github.phodal.acpmanager.services.CoroutineScopeHolder
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.phodal.routa.core.coordinator.CoordinationPhase
import com.phodal.routa.core.runner.OrchestratorPhase
import com.phodal.routa.core.runner.OrchestratorResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

private val log = logger<DispatcherPanel>()

/**
 * Main panel for the Multi-Agent Dispatcher ToolWindow tab.
 *
 * Redesigned with DAG + multi-agent architecture:
 *
 * ```
 * ┌─────────────────────────────────┐
 * │  ROUTA (Coordinator)            │  ← Plans tasks, streaming output
 * │         ↓                       │
 * ├─────────────────────────────────┤
 * │  CRAFTERs (Implementors)       │  ← Tabbed, model config, main focus
 * │  [Tab1] [Tab2] [Tab3]          │
 * │  Task info + streaming output   │
 * │         ↓                       │
 * ├─────────────────────────────────┤
 * │  GATE (Verifier)               │  ← Verdict, streaming verification
 * ├─────────────────────────────────┤
 * │  [Input area]            [Send] │  ← User request input
 * └─────────────────────────────────┘
 * ```
 *
 * The flow follows the Routa multi-agent DAG:
 * ROUTA plans → CRAFTERs execute (parallel) → GATE verifies
 */
class DispatcherPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val scopeHolder = CoroutineScopeHolder.getInstance(project)
    private val scope = scopeHolder.createScope("DispatcherPanel")
    private val configService = AcpConfigService.getInstance(project)
    private val routaService = IdeaRoutaService.getInstance(project)

    // ── UI Sections ─────────────────────────────────────────────────────

    private val routaSection = RoutaSectionPanel()
    private val crafterSection = CrafterSectionPanel()
    private val gateSection = GateSectionPanel()

    init {
        setupUI()
        loadAgents()
        observeRoutaService()
    }

    // ── UI Setup ────────────────────────────────────────────────────────

    private fun setupUI() {
        val mainPanel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(0x0D1117, 0x0D1117)
        }

        // DAG sections stacked vertically
        val dagPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        dagPanel.add(routaSection)
        dagPanel.add(crafterSection)
        dagPanel.add(gateSection)

        // Input area at the bottom
        val inputPanel = createInputPanel()

        // Scrollable DAG area
        val scrollPane = JScrollPane(dagPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(inputPanel, BorderLayout.SOUTH)

        setContent(mainPanel)

        // Wire up CRAFTER model change (ACP agent selection)
        crafterSection.onModelChanged = { model ->
            routaService.crafterModelKey.value = model
        }

        // Wire up ROUTA model change (LLM model selection for KoogAgent)
        routaSection.onModelChanged = { modelName ->
            val configs = routaService.getAvailableLlmConfigs()
            val selected = configs.find { it.name == modelName }
            if (selected != null) {
                routaService.setLlmModelConfig(selected)
                log.info("ROUTA LLM model changed to: ${selected.provider}/${selected.model}")
            }
        }
    }

    private fun createInputPanel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(0x161B22, 0x161B22)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineTop(JBColor(0x21262D, 0x21262D)),
                JBUI.Borders.empty(6, 12)
            )
        }

        val inputArea = JBTextArea(2, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            background = JBColor(0x0D1117, 0x0D1117)
            foreground = JBColor(0xC9D1D9, 0xC9D1D9)
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(JBColor(0x30363D, 0x30363D)),
                JBUI.Borders.empty(6, 8)
            )
            font = Font("SansSerif", Font.PLAIN, 12)
        }

        // Placeholder text
        inputArea.text = ""
        inputArea.toolTipText = "Describe your task... (Enter to send, Shift+Enter for newline)"

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    val text = inputArea.text.trim()
                    if (text.isNotEmpty()) {
                        startExecution(text)
                        inputArea.text = ""
                    }
                }
            }
        })

        val sendButton = JButton("Execute").apply {
            icon = AllIcons.Actions.Execute
            toolTipText = "Execute through Routa → CRAFTERs → GATE pipeline"
            preferredSize = Dimension(100, 32)
        }

        sendButton.addActionListener {
            val text = inputArea.text.trim()
            if (text.isNotEmpty()) {
                startExecution(text)
                inputArea.text = ""
            }
        }

        val inputScroll = JScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(0, 48)
        }

        // Status hint
        val hintLabel = JBLabel("Routa DAG: Plan → Execute → Verify").apply {
            foreground = JBColor(0x484F58, 0x484F58)
            font = font.deriveFont(9f)
            border = JBUI.Borders.emptyTop(2)
        }

        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(inputScroll, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }

        panel.add(topRow, BorderLayout.CENTER)
        panel.add(hintLabel, BorderLayout.SOUTH)

        return panel
    }

    // ── Agent Loading ───────────────────────────────────────────────────

    private fun loadAgents() {
        scope.launch(Dispatchers.IO) {
            try {
                log.info("Loading ACP agents and LLM configs...")
                configService.reloadConfig()
                val config = configService.loadConfig()
                val agentKeys = config.agents.keys.toList()

                // Load LLM configs for ROUTA (KoogAgent)
                val llmConfigs = routaService.getAvailableLlmConfigs()
                val activeLlmConfig = routaService.getActiveLlmConfig()

                withContext(Dispatchers.EDT) {
                    // Set available LLM models for ROUTA section
                    if (llmConfigs.isNotEmpty()) {
                        val modelNames = llmConfigs.map { it.name.ifBlank { "${it.provider}/${it.model}" } }
                        routaSection.setAvailableModels(modelNames)

                        if (activeLlmConfig != null) {
                            val activeName = activeLlmConfig.name.ifBlank { "${activeLlmConfig.provider}/${activeLlmConfig.model}" }
                            routaSection.setSelectedModel(activeName)
                        }
                        log.info("ROUTA LLM: ${llmConfigs.size} model(s) available, active=${activeLlmConfig?.let { "${it.provider}/${it.model}" } ?: "none"}")
                    } else {
                        log.warn("No LLM configs found at ~/.autodev/config.yaml — ROUTA will use ACP agent as fallback")
                    }

                    if (agentKeys.isEmpty()) {
                        val msg = "⚠️ No ACP agents detected. Configure agents in ~/.acp-manager/config.yaml"
                        log.warn(msg)
                        routaSection.setPlanningText(msg)
                        return@withContext
                    }

                    // Set available ACP agents for CRAFTERs
                    crafterSection.setAvailableModels(agentKeys)

                    val defaultAgent = config.activeAgent ?: agentKeys.firstOrNull()
                    if (defaultAgent != null) {
                        crafterSection.setSelectedModel(defaultAgent)
                        // Initialize the Routa service with the default agent
                        log.info("Initializing Routa service: CRAFTER=$defaultAgent, ROUTA=KoogAgent(${activeLlmConfig?.model ?: "fallback"})")
                        routaService.initialize(
                            crafterAgent = defaultAgent,
                            routaAgent = defaultAgent,
                            gateAgent = defaultAgent,
                        )

                        val llmInfo = if (activeLlmConfig != null) {
                            "ROUTA: ${activeLlmConfig.provider}/${activeLlmConfig.model} (KoogAgent)"
                        } else {
                            "ROUTA: fallback to ACP"
                        }
                        routaSection.setPlanningText("✓ Ready. CRAFTER: $defaultAgent | $llmInfo")
                    } else {
                        log.warn("No default agent found")
                        routaSection.setPlanningText("⚠️ No default agent configured")
                    }

                    log.info("Dispatcher ready. ${agentKeys.size} ACP agent(s): ${agentKeys.joinToString(", ")}")
                }
            } catch (e: Exception) {
                log.warn("Failed to load agents: ${e.message}", e)
                withContext(Dispatchers.EDT) {
                    routaSection.setPlanningText("❌ Failed to load agents: ${e.message}")
                }
            }
        }
    }

    // ── Routa Service Observation ───────────────────────────────────────

    private fun observeRoutaService() {
        // Observe orchestration phases
        scope.launch {
            routaService.phase.collectLatest { phase ->
                handlePhaseChange(phase)
            }
        }

        // Observe coordination state (for phase mapping to UI)
        scope.launch {
            routaService.coordinationState.collectLatest { state ->
                routaSection.updatePhase(state.phase)

                // Update GATE section based on phase
                when (state.phase) {
                    CoordinationPhase.VERIFYING -> gateSection.updateStatus(true)
                    CoordinationPhase.COMPLETED -> gateSection.updateStatus(false)
                    else -> {}
                }
            }
        }

        // Observe ROUTA streaming chunks
        scope.launch {
            routaService.routaChunks.collect { chunk ->
                routaSection.appendChunk(chunk)
            }
        }

        // Observe GATE streaming chunks
        scope.launch {
            routaService.gateChunks.collect { chunk ->
                gateSection.appendChunk(chunk)
            }
        }

        // Observe CRAFTER states
        scope.launch {
            routaService.crafterStates.collectLatest { states ->
                crafterSection.updateCrafterStates(states)
            }
        }

        // Observe MCP server URL
        scope.launch {
            routaService.mcpServerUrl.collectLatest { url ->
                crafterSection.setMcpServerUrl(url)
            }
        }

        // Observe running state (for UI enable/disable)
        scope.launch {
            routaService.isRunning.collectLatest { running ->
                // Could disable input while running
            }
        }
    }

    // ── Phase Handling ──────────────────────────────────────────────────

    private fun handlePhaseChange(phase: OrchestratorPhase) {
        when (phase) {
            is OrchestratorPhase.Initializing -> {
                routaSection.updatePhase(CoordinationPhase.IDLE)
            }

            is OrchestratorPhase.Planning -> {
                routaSection.updatePhase(CoordinationPhase.PLANNING)
                routaSection.clear()
                crafterSection.clear()
                gateSection.clear()
            }

            is OrchestratorPhase.PlanReady -> {
                routaSection.updatePhase(CoordinationPhase.READY)
                routaSection.setPlanningText(phase.planOutput)
            }

            is OrchestratorPhase.TasksRegistered -> {
                log.info("${phase.count} tasks registered")
            }

            is OrchestratorPhase.WaveStarting -> {
                routaSection.updatePhase(CoordinationPhase.EXECUTING)
            }

            is OrchestratorPhase.CrafterRunning -> {
                // CrafterSectionPanel handles this via crafterStates
            }

            is OrchestratorPhase.CrafterCompleted -> {
                // CrafterSectionPanel handles this via crafterStates
            }

            is OrchestratorPhase.VerificationStarting -> {
                gateSection.updateStatus(true)
                gateSection.clear()
            }

            is OrchestratorPhase.VerificationCompleted -> {
                gateSection.updateStatus(false)
            }

            is OrchestratorPhase.NeedsFix -> {
                routaSection.updatePhase(CoordinationPhase.NEEDS_FIX)
            }

            is OrchestratorPhase.Completed -> {
                routaSection.updatePhase(CoordinationPhase.COMPLETED)
            }

            is OrchestratorPhase.MaxWavesReached -> {
                routaSection.updatePhase(CoordinationPhase.COMPLETED)
            }
        }
    }

    // ── Execution ───────────────────────────────────────────────────────

    private fun startExecution(userInput: String) {
        if (routaService.isRunning.value) {
            log.info("Already running, ignoring request")
            return
        }

        // Check if service is initialized
        if (!routaService.isInitialized()) {
            log.warn("Service not initialized yet, please wait for agents to load")
            routaSection.setPlanningText("⚠️ Service not initialized. Please wait for agents to load or check your configuration.")
            return
        }

        // Re-initialize with current model selections
        val crafterModel = routaService.crafterModelKey.value
        if (crafterModel.isNotBlank()) {
            routaService.initialize(
                crafterAgent = crafterModel,
                routaAgent = routaService.routaModelKey.value.ifBlank { crafterModel },
                gateAgent = routaService.gateModelKey.value.ifBlank { crafterModel },
            )
        }

        // Clear all panels
        routaSection.clear()
        crafterSection.clear()
        gateSection.clear()

        scope.launch {
            try {
                val result = routaService.execute(userInput)
                handleResult(result)
            } catch (e: Exception) {
                log.warn("Execution failed: ${e.message}", e)
                routaSection.updatePhase(CoordinationPhase.FAILED)
                routaSection.setPlanningText("Execution failed: ${e.message}")
            }
        }
    }

    private fun handleResult(result: OrchestratorResult) {
        when (result) {
            is OrchestratorResult.Success -> {
                routaSection.updatePhase(CoordinationPhase.COMPLETED)
                val summary = result.taskSummaries.joinToString("\n") { task ->
                    "  ${task.title}: ${task.status} (verdict: ${task.verdict ?: "N/A"})"
                }
                routaSection.appendChunk(
                    com.phodal.routa.core.provider.StreamChunk.Text("\n\n--- Results ---\n$summary")
                )

                // Update GATE verdict from task summaries
                val allApproved = result.taskSummaries.all {
                    it.verdict == com.phodal.routa.core.model.VerificationVerdict.APPROVED
                }
                if (allApproved) {
                    gateSection.setVerdict(com.phodal.routa.core.model.VerificationVerdict.APPROVED)
                }
            }

            is OrchestratorResult.NoTasks -> {
                routaSection.setPlanningText("No tasks generated from the plan:\n${result.planOutput}")
            }

            is OrchestratorResult.MaxWavesReached -> {
                routaSection.appendChunk(
                    com.phodal.routa.core.provider.StreamChunk.Text(
                        "\n\nMax waves (${result.waves}) reached. Some tasks may be incomplete."
                    )
                )
            }

            is OrchestratorResult.Failed -> {
                routaSection.updatePhase(CoordinationPhase.FAILED)
                routaSection.setPlanningText("Orchestration failed: ${result.error}")
            }
        }
    }

    override fun dispose() {
        routaService.reset()
        scope.cancel()
    }
}
