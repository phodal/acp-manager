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
import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.runner.OrchestratorPhase
import com.phodal.routa.core.runner.OrchestratorResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

private val log = logger<DispatcherPanel>()

/**
 * Main panel for the Multi-Agent Dispatcher ToolWindow tab.
 *
 * Layout: left-right split.
 *
 * ```
 * ┌───────────────────────────┬─────────────────────────┐
 * │  Phase + Agent + Model    │  ROUTA        PLANNING   │
 * ├───────────────────────────┤  CRAFTER-1 << ACTIVE     │
 * │                           │  CRAFTER-2    PENDING     │
 * │  [Selected Agent's full   │  GATE         INACTIVE    │
 * │   AcpEventRenderer]       ├─────────────────────────┤
 * │                           │                           │
 * │                           │                           │
 * ├───────────────────────────┤                           │
 * │  [agent] [input...] [>]  │                           │
 * └───────────────────────────┴─────────────────────────┘
 * ```
 *
 * Left ~65%: title bar (NORTH) + selected renderer (CENTER) + input (SOUTH)
 * Right ~35%: agent sidebar with flat card list (ROUTA, CRAFTER-N..., GATE)
 *
 * All agents use unified AcpEventRenderer via AgentCardPanel + StreamChunkAdapter.
 */
class DispatcherPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val scopeHolder = CoroutineScopeHolder.getInstance(project)
    private val scope = scopeHolder.createScope("DispatcherPanel")
    private val configService = AcpConfigService.getInstance(project)
    private val routaService = IdeaRoutaService.getInstance(project)

    // ── Agent Panels (one per agent) ─────────────────────────────────────

    /** All agent panels keyed by agent ID. */
    private val agentPanels = mutableMapOf<String, AgentCardPanel>()

    /** The fixed ROUTA panel. */
    private val routaPanel = AgentCardPanel("__routa__", AgentCardPanel.AgentRole.ROUTA)

    /** The fixed GATE panel. */
    private val gatePanel = AgentCardPanel("__gate__", AgentCardPanel.AgentRole.GATE)

    // ── Left Panel Components ────────────────────────────────────────────

    /** Title bar: phase dot + agent name + model selector. */
    private val phaseDot = JBLabel("●").apply {
        foreground = JBColor(0x6B7280, 0x9CA3AF)
        font = font.deriveFont(12f)
    }

    private val titleLabel = JBLabel("ROUTA").apply {
        foreground = JBColor(0xC9D1D9, 0xC9D1D9)
        font = font.deriveFont(Font.BOLD, 13f)
    }

    private val phaseLabel = JBLabel("IDLE").apply {
        foreground = JBColor(0x6B7280, 0x9CA3AF)
        font = font.deriveFont(Font.BOLD, 10f)
    }

    private val modelCombo = JComboBox<String>().apply {
        preferredSize = Dimension(160, 24)
        font = font.deriveFont(11f)
        toolTipText = "Select agent model"
    }

    /** CardLayout area that swaps the displayed renderer scroll pane. */
    private val rendererCardPanel = JPanel(CardLayout()).apply {
        isOpaque = true
        background = JBColor(0x0D1117, 0x0D1117)
    }

    // ── Right Panel ──────────────────────────────────────────────────────

    private val sidebar = AgentSidebarPanel()

    // ── Input Panel Components ───────────────────────────────────────────

    // Agent selector for input panel
    private val agentLabel = JBLabel("Select Agent").apply {
        foreground = JBColor(0x8B949E, 0x8B949E)
        font = font.deriveFont(11f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Click to change agent"
    }

    private val agentPopup = JPopupMenu().apply {
        background = JBColor(0x161B22, 0x161B22)
        border = BorderFactory.createLineBorder(JBColor(0x30363D, 0x30363D))
    }

    // Status hint label
    private val hintLabel = JBLabel("Routa DAG: Plan → Execute → Verify").apply {
        foreground = JBColor(0x484F58, 0x484F58)
        font = font.deriveFont(9f)
        border = JBUI.Borders.emptyTop(2)
    }

    // MCP Server status components
    private val mcpStatusLabel = JBLabel("●").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(10f)
        toolTipText = "MCP Server Status"
    }

    private val mcpUrlLabel = JBLabel("MCP Server: not running").apply {
        foreground = JBColor(0x589DF6, 0x589DF6)
        font = font.deriveFont(9f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val mcpTransportLabel = JBLabel("").apply {
        foreground = JBColor(0x8B949E, 0x8B949E)
        font = font.deriveFont(8f)
    }

    private val mcpRefreshButton = JButton(AllIcons.Actions.Refresh).apply {
        isContentAreaFilled = false
        isBorderPainted = false
        preferredSize = Dimension(16, 16)
        toolTipText = "Check MCP Server Status"
    }

    private var currentMcpUrl: String? = null

    /** Currently selected agent ID in the sidebar. */
    private var selectedAgentId: String? = null

    init {
        // Enable event logging for debugging
        EventLogger.enable()
        EventLogger.log("=== DispatcherPanel initialized ===")

        // Register fixed panels
        agentPanels[routaPanel.agentId] = routaPanel
        agentPanels[gatePanel.agentId] = gatePanel

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

        // ── Left panel ──────────────────────────────────────────────────
        val leftPanel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(0x0D1117, 0x0D1117)
        }

        // Title bar
        val titleBar = createTitleBar()
        leftPanel.add(titleBar, BorderLayout.NORTH)

        // Renderer card area (center)
        rendererCardPanel.add(routaPanel.rendererScroll, routaPanel.agentId)
        rendererCardPanel.add(gatePanel.rendererScroll, gatePanel.agentId)
        leftPanel.add(rendererCardPanel, BorderLayout.CENTER)

        // Input area (bottom)
        val inputPanel = createInputPanel()
        leftPanel.add(inputPanel, BorderLayout.SOUTH)

        // ── Horizontal split: left + right sidebar ──────────────────────
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            leftComponent = leftPanel
            rightComponent = sidebar
            dividerLocation = 500
            resizeWeight = 0.65
            border = JBUI.Borders.empty()
            dividerSize = 4
            isContinuousLayout = true
        }

        mainPanel.add(splitPane, BorderLayout.CENTER)
        setContent(mainPanel)

        // ── Wire sidebar selection ──────────────────────────────────────
        sidebar.onAgentSelected = { agentId ->
            switchToAgent(agentId)
        }

        // Default: select ROUTA
        sidebar.selectAgent(sidebar.getRoutaId())

        // ── Wire model combo ────────────────────────────────────────────
        modelCombo.addActionListener {
            val selected = modelCombo.selectedItem as? String ?: return@addActionListener
            handleModelChange(selected)
        }
    }

    private fun createTitleBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(0x161B22, 0x161B22)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor(0x21262D, 0x21262D)),
                JBUI.Borders.empty(6, 12)
            )

            val leftContent = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(phaseDot)
                add(titleLabel)
                add(JBLabel("│").apply { foreground = JBColor(0x30363D, 0x30363D) })
                add(phaseLabel)
            }
            add(leftContent, BorderLayout.WEST)

            val rightContent = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(JBLabel("Model:").apply {
                    foreground = JBColor(0x8B949E, 0x8B949E)
                    font = font.deriveFont(10f)
                })
                add(modelCombo)
            }
            add(rightContent, BorderLayout.EAST)
        }
    }

    private fun createInputPanel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(0x161B22, 0x161B22)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineTop(JBColor(0x21262D, 0x21262D)),
                JBUI.Borders.empty(8, 12)
            )
        }

        // Unified input container
        val unifiedContainer = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(0x0D1117, 0x0D1117)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0x30363D, 0x30363D), 1, true),
                JBUI.Borders.empty(0)
            )
        }

        // Top row: Agent selector
        val settingsIcon = JBLabel(AllIcons.General.Settings).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Select Agent"
            border = JBUI.Borders.empty(0, 8, 0, 4)
        }

        val agentRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 4, 2, 4)
            add(settingsIcon)
            add(agentLabel)
        }

        val showAgentPopup = { e: MouseEvent ->
            agentPopup.show(e.component, 0, e.component.height)
        }
        settingsIcon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showAgentPopup(e)
        })
        agentLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showAgentPopup(e)
        })

        // Center: Input text area
        val inputArea = JBTextArea(3, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            background = JBColor(0x0D1117, 0x0D1117)
            foreground = JBColor(0xC9D1D9, 0xC9D1D9)
            border = JBUI.Borders.empty(4, 12, 4, 12)
            font = Font("SansSerif", Font.PLAIN, 13)
        }
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

        val inputScroll = JScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        // Bottom row: buttons
        val sendButton = JButton(AllIcons.Actions.Execute).apply {
            toolTipText = "Execute (Enter)"
            preferredSize = Dimension(32, 28)
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val stopButton = JButton(AllIcons.Actions.Suspend).apply {
            toolTipText = "Stop all agents"
            preferredSize = Dimension(32, 28)
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isVisible = false
        }

        val newSessionButton = JButton(AllIcons.Actions.Restart).apply {
            toolTipText = "Start new session (clear all panels)"
            preferredSize = Dimension(32, 28)
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        sendButton.addActionListener {
            val text = inputArea.text.trim()
            if (text.isNotEmpty()) {
                startExecution(text)
                inputArea.text = ""
            }
        }

        stopButton.addActionListener {
            stopExecution()
        }

        newSessionButton.addActionListener {
            startNewSession()
        }

        // Observe isRunning to toggle send/stop buttons
        scope.launch {
            routaService.isRunning.collectLatest { isRunning ->
                withContext(Dispatchers.EDT) {
                    sendButton.isVisible = !isRunning
                    stopButton.isVisible = isRunning
                    inputArea.isEnabled = !isRunning
                    newSessionButton.isEnabled = !isRunning
                }
            }
        }

        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 6, 8)
            add(newSessionButton)
            add(sendButton)
            add(stopButton)
        }

        unifiedContainer.add(agentRow, BorderLayout.NORTH)
        unifiedContainer.add(inputScroll, BorderLayout.CENTER)
        unifiedContainer.add(buttonRow, BorderLayout.SOUTH)

        // Bottom status panel (hint + MCP status)
        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
        }

        bottomPanel.add(hintLabel, BorderLayout.WEST)

        val mcpStatusPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(mcpStatusLabel)
            add(Box.createHorizontalStrut(4))
            add(mcpUrlLabel)
            add(Box.createHorizontalStrut(6))
            add(mcpTransportLabel)
            add(Box.createHorizontalStrut(4))
            add(mcpRefreshButton)
        }
        bottomPanel.add(mcpStatusPanel, BorderLayout.EAST)

        mcpUrlLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                currentMcpUrl?.let { url -> openUrlInBrowser(url) }
            }
        })

        mcpRefreshButton.addActionListener {
            checkMcpServerStatus()
        }

        panel.add(unifiedContainer, BorderLayout.CENTER)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
    }

    // ── Agent Selection ──────────────────────────────────────────────────

    /**
     * Switch the left panel to show the given agent's renderer.
     */
    private fun switchToAgent(agentId: String) {
        selectedAgentId = agentId
        val panel = agentPanels[agentId] ?: return

        // Update title bar
        titleLabel.text = panel.title
        phaseLabel.text = panel.statusText
        phaseLabel.foreground = panel.statusColor
        phaseDot.foreground = panel.statusColor

        // Show this agent's renderer in the card layout
        (rendererCardPanel.layout as CardLayout).show(rendererCardPanel, agentId)
    }

    /**
     * Auto-select the appropriate agent based on the current phase.
     */
    private fun autoSelectByPhase(phase: CoordinationPhase) {
        val targetId = when (phase) {
            CoordinationPhase.PLANNING, CoordinationPhase.READY -> sidebar.getRoutaId()
            CoordinationPhase.EXECUTING, CoordinationPhase.WAVE_COMPLETE -> {
                // Find the first active CRAFTER, or keep current
                agentPanels.entries
                    .firstOrNull { it.value.role == AgentCardPanel.AgentRole.CRAFTER && it.value.statusText == "ACTIVE" }
                    ?.key ?: selectedAgentId ?: sidebar.getRoutaId()
            }
            CoordinationPhase.VERIFYING -> sidebar.getGateId()
            CoordinationPhase.COMPLETED -> sidebar.getRoutaId()
            CoordinationPhase.FAILED -> sidebar.getRoutaId()
            CoordinationPhase.NEEDS_FIX -> sidebar.getRoutaId()
            CoordinationPhase.IDLE -> selectedAgentId ?: sidebar.getRoutaId()
        }
        SwingUtilities.invokeLater {
            sidebar.selectAgent(targetId)
        }
    }

    // ── Model Change Handling ────────────────────────────────────────────

    private fun handleModelChange(modelName: String) {
        routaService.crafterModelKey.value = modelName

        if (routaService.useAcpForRouta.value) {
            routaService.routaModelKey.value = modelName
            log.info("Agent model changed to: $modelName")
        } else {
            val configs = routaService.getAvailableLlmConfigs()
            val selected = configs.find { it.name == modelName }
            if (selected != null) {
                routaService.setLlmModelConfig(selected)
                log.info("LLM model changed to: ${selected.provider}/${selected.model}")
            }
        }
    }

    /**
     * Update the agent popup menu with available agents.
     */
    private fun updateAgentPopup(agents: List<String>, selectedAgent: String?) {
        agentPopup.removeAll()

        for (agent in agents) {
            val menuItem = JMenuItem(agent).apply {
                background = JBColor(0x161B22, 0x161B22)
                foreground = JBColor(0xC9D1D9, 0xC9D1D9)
                addActionListener {
                    modelCombo.selectedItem = agent
                    agentLabel.text = agent
                }
            }
            agentPopup.add(menuItem)
        }

        if (selectedAgent != null) {
            agentLabel.text = selectedAgent
        } else if (agents.isNotEmpty()) {
            agentLabel.text = agents.first()
        }
    }

    // ── Agent Loading ───────────────────────────────────────────────────

    private fun loadAgents() {
        scope.launch(Dispatchers.IO) {
            try {
                log.info("Loading ACP agents...")
                configService.reloadConfig()
                val config = configService.loadConfig()
                val agentKeys = config.agents.keys.toList()

                val useAcpForRouta = routaService.useAcpForRouta.value

                withContext(Dispatchers.EDT) {
                    if (agentKeys.isEmpty()) {
                        val msg = "No ACP agents detected. Configure agents in ~/.acp-manager/config.yaml"
                        log.warn(msg)
                        routaPanel.appendChunk(
                            com.phodal.routa.core.provider.StreamChunk.Text("⚠️ $msg")
                        )
                        return@withContext
                    }

                    // Populate model combo
                    modelCombo.removeAllItems()
                    if (useAcpForRouta) {
                        agentKeys.forEach { modelCombo.addItem(it) }
                    } else {
                        val llmConfigs = routaService.getAvailableLlmConfigs()
                        val modelNames = llmConfigs.map { it.name.ifBlank { "${it.provider}/${it.model}" } }
                        modelNames.forEach { modelCombo.addItem(it) }
                    }

                    // Prefer "claude" as default agent
                    val defaultAgent = when {
                        agentKeys.any { it.equals("claude", ignoreCase = true) } ->
                            agentKeys.first { it.equals("claude", ignoreCase = true) }
                        config.activeAgent != null && agentKeys.contains(config.activeAgent) ->
                            config.activeAgent
                        else -> agentKeys.firstOrNull()
                    }

                    if (defaultAgent != null) {
                        modelCombo.selectedItem = defaultAgent

                        updateAgentPopup(agentKeys, defaultAgent)

                        val routaMode = if (useAcpForRouta) "ACP Agent ($defaultAgent)" else "KoogAgent"
                        log.info("Initializing Routa service: CRAFTER=$defaultAgent, ROUTA=$routaMode")
                        routaService.initialize(
                            crafterAgent = defaultAgent,
                            routaAgent = defaultAgent,
                            gateAgent = defaultAgent,
                        )

                        routaPanel.appendChunk(
                            com.phodal.routa.core.provider.StreamChunk.Text(
                                "✓ Ready. Agent: $defaultAgent | Mode: $routaMode"
                            )
                        )
                    } else {
                        log.warn("No default agent found")
                        routaPanel.appendChunk(
                            com.phodal.routa.core.provider.StreamChunk.Text("⚠️ No default agent configured")
                        )
                        updateAgentPopup(agentKeys, null)
                    }

                    log.info("Dispatcher ready. ${agentKeys.size} ACP agent(s): ${agentKeys.joinToString(", ")}")
                }
            } catch (e: Exception) {
                log.warn("Failed to load agents: ${e.message}", e)
                withContext(Dispatchers.EDT) {
                    routaPanel.appendChunk(
                        com.phodal.routa.core.provider.StreamChunk.Text("❌ Failed to load agents: ${e.message}")
                    )
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

        // Observe coordination state → auto-select agent + update sidebar
        scope.launch {
            routaService.coordinationState.collectLatest { state ->
                withContext(Dispatchers.EDT) {
                    updatePhaseUI(state.phase)
                    autoSelectByPhase(state.phase)
                }
            }
        }

        // Observe ROUTA streaming chunks
        scope.launch {
            routaService.routaChunks.collect { chunk ->
                EventLogger.log("[DISPATCHER] ROUTA chunk received: ${chunk::class.simpleName}")
                routaPanel.appendChunk(chunk)
            }
        }

        // Observe GATE streaming chunks
        scope.launch {
            routaService.gateChunks.collect { chunk ->
                EventLogger.log("[DISPATCHER] GATE chunk received: ${chunk::class.simpleName}")
                gatePanel.appendChunk(chunk)
            }
        }

        // Observe CRAFTER streaming chunks
        scope.launch {
            routaService.crafterChunks.collect { (taskId, chunk) ->
                EventLogger.log("[DISPATCHER] CRAFTER[$taskId] chunk received: ${chunk::class.simpleName}")
                agentPanels[taskId]?.appendChunk(chunk)
            }
        }

        // Observe CRAFTER states → create panels dynamically
        scope.launch {
            routaService.crafterStates.collectLatest { states ->
                withContext(Dispatchers.EDT) {
                    handleCrafterStatesUpdate(states)
                }
            }
        }

        // Observe MCP server URL
        scope.launch {
            routaService.mcpServerUrl.collectLatest { url ->
                withContext(Dispatchers.EDT) {
                    currentMcpUrl = url
                    if (url != null) {
                        mcpUrlLabel.text = url
                        mcpUrlLabel.foreground = JBColor(0x589DF6, 0x589DF6)
                        mcpUrlLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        mcpStatusLabel.foreground = JBColor.GRAY
                        mcpStatusLabel.toolTipText = "Click refresh to check status"
                        mcpRefreshButton.isEnabled = true
                        mcpTransportLabel.text = detectMcpTransportType(url)
                        mcpTransportLabel.toolTipText = getTransportTooltip(url)
                        checkMcpServerStatus()
                    } else {
                        mcpUrlLabel.text = "MCP Server: not running"
                        mcpUrlLabel.foreground = JBColor.GRAY
                        mcpUrlLabel.cursor = Cursor.getDefaultCursor()
                        mcpStatusLabel.foreground = JBColor.GRAY
                        mcpStatusLabel.toolTipText = "MCP Server not started"
                        mcpRefreshButton.isEnabled = false
                        mcpTransportLabel.text = ""
                        mcpTransportLabel.toolTipText = null
                    }
                }
            }
        }
    }

    // ── CRAFTER State Management ─────────────────────────────────────────

    private fun handleCrafterStatesUpdate(
        states: Map<String, com.phodal.routa.core.viewmodel.CrafterStreamState>
    ) {
        var firstActiveId: String? = null

        for ((taskId, state) in states) {
            if (taskId !in agentPanels) {
                // New CRAFTER task: create panel + sidebar card
                val panel = AgentCardPanel(taskId, AgentCardPanel.AgentRole.CRAFTER)
                panel.updateTitle(state.taskTitle.ifBlank { "Task ${taskId.take(8)}" })
                agentPanels[taskId] = panel

                // Add to card layout for left-side rendering
                rendererCardPanel.add(panel.rendererScroll, taskId)

                // Add sidebar card
                sidebar.addCrafter(taskId, state.taskTitle.ifBlank { "Task ${taskId.take(8)}" })
            }

            // Update status
            val (statusText, statusColor) = when (state.status) {
                AgentStatus.PENDING -> "PENDING" to AgentSidebarPanel.STATUS_IDLE
                AgentStatus.ACTIVE -> "ACTIVE" to AgentSidebarPanel.STATUS_ACTIVE
                AgentStatus.COMPLETED -> "COMPLETED" to AgentSidebarPanel.STATUS_COMPLETED
                AgentStatus.ERROR -> "ERROR" to AgentSidebarPanel.STATUS_ERROR
                AgentStatus.CANCELLED -> "CANCELLED" to AgentSidebarPanel.STATUS_PLANNING
            }

            agentPanels[taskId]?.updateStatus(statusText, statusColor)
            agentPanels[taskId]?.updateTitle(state.taskTitle.ifBlank { "Task ${taskId.take(8)}" })
            sidebar.updateAgentStatus(taskId, statusText, statusColor)
            sidebar.updateAgentInfo(taskId, state.taskTitle.ifBlank { "Task ${taskId.take(8)}" })

            if (state.status == AgentStatus.ACTIVE && firstActiveId == null) {
                firstActiveId = taskId
            }
        }

        // Auto-select the first active CRAFTER
        if (firstActiveId != null) {
            val currentPanel = selectedAgentId?.let { agentPanels[it] }
            if (currentPanel == null || currentPanel.role != AgentCardPanel.AgentRole.CRAFTER ||
                currentPanel.statusText != "ACTIVE") {
                sidebar.selectAgent(firstActiveId)
            }
        }
    }

    // ── Phase UI Update ──────────────────────────────────────────────────

    private fun updatePhaseUI(phase: CoordinationPhase) {
        val (text, color) = when (phase) {
            CoordinationPhase.IDLE -> "IDLE" to JBColor(0x6B7280, 0x9CA3AF)
            CoordinationPhase.PLANNING -> "PLANNING" to JBColor(0xF59E0B, 0xF59E0B)
            CoordinationPhase.READY -> "READY" to JBColor(0x3B82F6, 0x3B82F6)
            CoordinationPhase.EXECUTING -> "EXECUTING" to JBColor(0x10B981, 0x10B981)
            CoordinationPhase.WAVE_COMPLETE -> "WAVE DONE" to JBColor(0x10B981, 0x10B981)
            CoordinationPhase.VERIFYING -> "VERIFYING" to JBColor(0xA78BFA, 0xA78BFA)
            CoordinationPhase.NEEDS_FIX -> "NEEDS FIX" to JBColor(0xEF4444, 0xEF4444)
            CoordinationPhase.COMPLETED -> "COMPLETED" to JBColor(0x10B981, 0x10B981)
            CoordinationPhase.FAILED -> "FAILED" to JBColor(0xEF4444, 0xEF4444)
        }

        // Update ROUTA sidebar card
        sidebar.updateAgentStatus(sidebar.getRoutaId(), text, color)
        routaPanel.updateStatus(text, color)

        // Update GATE sidebar based on phase
        when (phase) {
            CoordinationPhase.VERIFYING -> {
                sidebar.updateAgentStatus(sidebar.getGateId(), "VERIFYING", AgentSidebarPanel.STATUS_VERIFYING)
                gatePanel.updateStatus("VERIFYING", AgentSidebarPanel.STATUS_VERIFYING)
            }
            CoordinationPhase.COMPLETED -> {
                sidebar.updateAgentStatus(sidebar.getGateId(), "DONE", AgentSidebarPanel.STATUS_COMPLETED)
                gatePanel.updateStatus("DONE", AgentSidebarPanel.STATUS_COMPLETED)
            }
            else -> {}
        }

        // Update left title bar if the currently selected agent is affected
        if (selectedAgentId == sidebar.getRoutaId()) {
            phaseLabel.text = text
            phaseLabel.foreground = color
            phaseDot.foreground = color
        }
    }

    // ── Phase Handling ──────────────────────────────────────────────────

    private fun handlePhaseChange(phase: OrchestratorPhase) {
        when (phase) {
            is OrchestratorPhase.Initializing -> {
                // no-op
            }

            is OrchestratorPhase.Planning -> {
                clearAllPanels()
            }

            is OrchestratorPhase.PlanReady -> {
                routaPanel.appendChunk(
                    com.phodal.routa.core.provider.StreamChunk.Text("\n\n--- Plan Ready ---\n${phase.planOutput}")
                )
            }

            is OrchestratorPhase.TasksRegistered -> {
                log.info("${phase.count} tasks registered")
            }

            is OrchestratorPhase.WaveStarting -> {
                // handled by coordinationState observer
            }

            is OrchestratorPhase.CrafterRunning -> {
                // handled by crafterStates observer
            }

            is OrchestratorPhase.CrafterCompleted -> {
                // handled by crafterStates observer
            }

            is OrchestratorPhase.VerificationStarting -> {
                gatePanel.clear()
            }

            is OrchestratorPhase.VerificationCompleted -> {
                // handled by coordinationState observer
            }

            is OrchestratorPhase.NeedsFix -> {
                // handled by coordinationState observer
            }

            is OrchestratorPhase.Completed -> {
                // handled by coordinationState observer
            }

            is OrchestratorPhase.MaxWavesReached -> {
                // handled by coordinationState observer
            }
        }
    }

    // ── Execution ───────────────────────────────────────────────────────

    private fun startExecution(userInput: String) {
        if (routaService.isRunning.value) {
            log.info("Already running, ignoring request")
            return
        }

        if (!routaService.isInitialized()) {
            log.warn("Service not initialized yet")
            routaPanel.appendChunk(
                com.phodal.routa.core.provider.StreamChunk.Text(
                    "⚠️ Service not initialized. Please wait for agents to load."
                )
            )
            return
        }

        clearAllPanels()

        scope.launch {
            try {
                val result = routaService.execute(userInput)
                handleResult(result)
            } catch (e: Exception) {
                log.warn("Execution failed: ${e.message}", e)
                routaPanel.appendChunk(
                    com.phodal.routa.core.provider.StreamChunk.Text("❌ Execution failed: ${e.message}")
                )
            }
        }
    }

    private fun stopExecution() {
        log.info("Stopping execution...")
        routaService.stopExecution()
        routaPanel.appendChunk(
            com.phodal.routa.core.provider.StreamChunk.Text("\n\n⏹ Execution stopped by user.")
        )
    }

    private fun startNewSession() {
        log.info("Starting new session...")
        clearAllPanels()
        routaPanel.appendChunk(
            com.phodal.routa.core.provider.StreamChunk.Text("✓ New session started. Ready for input.")
        )
        sidebar.selectAgent(sidebar.getRoutaId())
    }

    /**
     * Clear all agent panels, remove dynamic CRAFTER panels, and reset sidebar.
     */
    private fun clearAllPanels() {
        // Clear fixed panels
        routaPanel.clear()
        gatePanel.clear()

        // Remove dynamic CRAFTER panels
        val crafterIds = agentPanels.keys.filter {
            it != routaPanel.agentId && it != gatePanel.agentId
        }
        for (id in crafterIds) {
            agentPanels[id]?.let { panel ->
                rendererCardPanel.remove(panel.rendererScroll)
            }
            agentPanels.remove(id)
        }

        // Reset sidebar (keeps ROUTA and GATE, removes CRAFTERs)
        sidebar.clear()

        rendererCardPanel.revalidate()
        rendererCardPanel.repaint()
    }

    private fun handleResult(result: OrchestratorResult) {
        when (result) {
            is OrchestratorResult.Success -> {
                val summary = result.taskSummaries.joinToString("\n") { task ->
                    "  ${task.title}: ${task.status} (verdict: ${task.verdict ?: "N/A"})"
                }
                routaPanel.appendChunk(
                    com.phodal.routa.core.provider.StreamChunk.Text("\n\n--- Results ---\n$summary")
                )

                val allApproved = result.taskSummaries.all {
                    it.verdict == com.phodal.routa.core.model.VerificationVerdict.APPROVED
                }
                if (allApproved) {
                    gatePanel.appendChunk(
                        com.phodal.routa.core.provider.StreamChunk.Text("✅ All tasks APPROVED")
                    )
                    sidebar.updateAgentStatus(sidebar.getGateId(), "APPROVED", AgentSidebarPanel.STATUS_COMPLETED)
                }
            }

            is OrchestratorResult.NoTasks -> {
                routaPanel.appendChunk(
                    com.phodal.routa.core.provider.StreamChunk.Text(
                        "No tasks generated from the plan:\n${result.planOutput}"
                    )
                )
            }

            is OrchestratorResult.MaxWavesReached -> {
                routaPanel.appendChunk(
                    com.phodal.routa.core.provider.StreamChunk.Text(
                        "\n\nMax waves (${result.waves}) reached. Some tasks may be incomplete."
                    )
                )
            }

            is OrchestratorResult.Failed -> {
                routaPanel.appendChunk(
                    com.phodal.routa.core.provider.StreamChunk.Text("❌ Orchestration failed: ${result.error}")
                )
            }
        }
    }

    // ── MCP Server Status ───────────────────────────────────────────────

    private fun detectMcpTransportType(url: String): String {
        return when {
            url.endsWith("/mcp") -> "[WS + SSE]"
            url.endsWith("/sse") -> "[SSE + WS]"
            else -> "[Unknown]"
        }
    }

    private fun getTransportTooltip(url: String): String {
        return when {
            url.endsWith("/mcp") -> """
                Available transports:
                • WebSocket: ws://...${url.substringAfter("http://")}
                • Legacy SSE: ${url.replace("/mcp", "/sse")}
            """.trimIndent()

            url.endsWith("/sse") -> """
                Available transports:
                • Legacy SSE: $url
                • WebSocket: ws://...${url.replace("/sse", "/mcp").substringAfter("http://")}
            """.trimIndent()

            else -> "MCP Server endpoint"
        }
    }

    private fun openUrlInBrowser(url: String) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } catch (e: Exception) {
            log.warn("Failed to open URL in browser: $url", e)
        }
    }

    private fun checkMcpServerStatus() {
        val url = currentMcpUrl ?: return

        scope.launch(Dispatchers.IO) {
            try {
                val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                val responseCode = connection.responseCode

                withContext(Dispatchers.EDT) {
                    updateMcpStatus(url, true, responseCode)
                }

                connection.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.EDT) {
                    updateMcpStatus(url, false, null)
                }
            }
        }
    }

    private fun updateMcpStatus(url: String, isRunning: Boolean, responseCode: Int?) {
        if (isRunning) {
            mcpStatusLabel.foreground = JBColor(0x3FB950, 0x3FB950)
            val statusText = if (responseCode != null) {
                "MCP Server is running (HTTP $responseCode)"
            } else {
                "MCP Server is running"
            }
            mcpStatusLabel.toolTipText = statusText
        } else {
            mcpStatusLabel.foreground = JBColor(0xF85149, 0xF85149)
            mcpStatusLabel.toolTipText = "MCP Server is not responding (connection failed)"
        }
    }

    override fun dispose() {
        EventLogger.log("=== DispatcherPanel disposed ===")
        EventLogger.disable()
        routaService.reset()
        scope.cancel()
    }
}
