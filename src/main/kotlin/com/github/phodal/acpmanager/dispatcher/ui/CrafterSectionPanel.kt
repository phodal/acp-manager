package com.github.phodal.acpmanager.dispatcher.ui

import com.github.phodal.acpmanager.claudecode.CrafterRenderer
import com.phodal.routa.core.viewmodel.CrafterStreamState
import com.github.phodal.acpmanager.ui.renderer.AcpEventRenderer
import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.provider.ThinkingPhase
import com.phodal.routa.core.provider.ToolCallStatus
import java.awt.*
import javax.swing.*

/**
 * CRAFTERs section — the main focus area of the DAG UI.
 *
 * Shows:
 * - Header with "CRAFTERs" label, active count, and ACP model config
 * - Vertical list of all CRAFTER agents (all visible at once, not tabbed)
 * - Each CRAFTER shows a compact CrafterDetailPanel with task info and streaming output
 *
 * This is the largest section since CRAFTERs do the actual implementation work.
 */
class CrafterSectionPanel : JPanel(BorderLayout()) {

    companion object {
        val CRAFTER_ACCENT = JBColor(0x10B981, 0x10B981)
        val CRAFTER_BG = JBColor(0x0D1117, 0x0D1117)
    }

    /** Vertical list panel to hold all CRAFTER detail panels */
    private val crafterListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    /** Scrollable container for the CRAFTER list */
    private val crafterScrollPane = JScrollPane(crafterListPanel).apply {
        border = JBUI.Borders.empty()
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        isOpaque = false
        viewport.isOpaque = false
    }

    private val activeCountLabel = JBLabel("0 active").apply {
        foreground = JBColor(0x8B949E, 0x8B949E)
        font = font.deriveFont(10f)
    }

    private val mcpUrlLabel = JBLabel("").apply {
        foreground = JBColor(0x58A6FF, 0x58A6FF)  // blue link-style
        font = font.deriveFont(9f)
        toolTipText = "MCP Server SSE endpoint for Claude Code coordination tools"
        isVisible = false
    }

    private val modelCombo = JComboBox<String>().apply {
        preferredSize = Dimension(160, 24)
        font = font.deriveFont(11f)
        toolTipText = "ACP Model for CRAFTER agents"
    }

    /** Callback when the model is changed. */
    var onModelChanged: (String) -> Unit = {}

    /** Callback when a CRAFTER agent should be stopped. */
    var onStopCrafter: (String) -> Unit = {}

    /** Maps agentId → CrafterDetailPanel for updating. */
    private val detailPanels = mutableMapOf<String, CrafterDetailPanel>()

    /** Empty state label */
    private val emptyLabel = JBLabel("Waiting for ROUTA to plan tasks...").apply {
        foreground = JBColor(0x6B7280, 0x6B7280)
        font = font.deriveFont(Font.ITALIC, 12f)
    }

    // DAG connectors
    private val dagUpConnector = createDagConnector()
    private val dagDownConnector = createDagConnector()

    init {
        isOpaque = true
        background = CRAFTER_BG
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor(0x21262D, 0x21262D)),
            JBUI.Borders.empty(4, 12)
        )

        // Header
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(JBLabel(AllIcons.Nodes.Gvariable).apply {
                    toolTipText = "CRAFTER Agents"
                })
                add(JBLabel("CRAFTERs").apply {
                    foreground = CRAFTER_ACCENT
                    font = font.deriveFont(Font.BOLD, 12f)
                })
                add(JBLabel("│").apply { foreground = JBColor(0x30363D, 0x30363D) })
                add(activeCountLabel)
                add(mcpUrlLabel)
            }
            add(leftPanel, BorderLayout.WEST)

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(JBLabel("Model:").apply {
                    foreground = JBColor(0x8B949E, 0x8B949E)
                    font = font.deriveFont(10f)
                })
                add(modelCombo)
            }
            add(rightPanel, BorderLayout.EAST)
        }

        // Initially show empty state
        val emptyPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(emptyLabel)
        }
        crafterListPanel.add(emptyPanel)

        // Layout: top connector + header + scrollable list + bottom connector
        val mainContent = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerPanel, BorderLayout.NORTH)
            add(crafterScrollPane, BorderLayout.CENTER)
        }

        add(dagUpConnector, BorderLayout.NORTH)
        add(mainContent, BorderLayout.CENTER)
        add(dagDownConnector, BorderLayout.SOUTH)

        // Wire model combo
        modelCombo.addActionListener {
            val selected = modelCombo.selectedItem as? String ?: return@addActionListener
            onModelChanged(selected)
        }
    }

    /**
     * Set the MCP server URL to display.
     */
    fun setMcpServerUrl(url: String?) {
        SwingUtilities.invokeLater {
            if (url != null) {
                mcpUrlLabel.text = "│ MCP: $url"
                mcpUrlLabel.isVisible = true
            } else {
                mcpUrlLabel.text = ""
                mcpUrlLabel.isVisible = false
            }
        }
    }

    /**
     * Set available ACP models for CRAFTERs.
     */
    fun setAvailableModels(models: List<String>) {
        modelCombo.removeAllItems()
        models.forEach { modelCombo.addItem(it) }
    }

    /**
     * Set the selected model.
     */
    fun setSelectedModel(model: String) {
        modelCombo.selectedItem = model
    }

    /**
     * Update all CRAFTER states at once.
     * Creates/updates panels in the vertical list as needed.
     */
    fun updateCrafterStates(states: Map<String, CrafterStreamState>) {
        SwingUtilities.invokeLater {
            val activeCount = states.values.count { it.status == AgentStatus.ACTIVE }
            val totalCount = states.size
            activeCountLabel.text = "$activeCount/$totalCount active"

            // Remove empty state if crafters exist
            if (states.isNotEmpty() && detailPanels.isEmpty()) {
                crafterListPanel.removeAll()
            }

            // Add or update panels in the list
            for ((agentId, state) in states) {
                val existing = detailPanels[agentId]
                if (existing != null) {
                    existing.update(state)
                } else {
                    // Create new panel with stop callback
                    val panel = CrafterDetailPanel(
                        onStopClick = { onStopCrafter(agentId) }
                    )
                    panel.update(state)
                    detailPanels[agentId] = panel
                    crafterListPanel.add(panel)
                    crafterListPanel.add(Box.createVerticalStrut(4)) // spacing between panels
                }
            }

            revalidate()
            repaint()

            // Scroll to bottom to show latest CRAFTER
            SwingUtilities.invokeLater {
                val vertical = crafterScrollPane.verticalScrollBar
                vertical.value = vertical.maximum
            }
        }
    }

    /**
     * Append a streaming chunk to a specific CRAFTER's panel.
     */
    fun appendChunk(agentId: String, chunk: StreamChunk) {
        SwingUtilities.invokeLater {
            detailPanels[agentId]?.appendChunk(chunk)
        }
    }

    /**
     * Clear all panels and reset.
     */
    fun clear() {
        SwingUtilities.invokeLater {
            crafterListPanel.removeAll()
            detailPanels.clear()
            activeCountLabel.text = "0 active"
            mcpUrlLabel.text = ""
            mcpUrlLabel.isVisible = false
            // Show empty state
            val emptyPanel = JPanel(GridBagLayout()).apply {
                isOpaque = false
                add(JBLabel("Waiting for ROUTA to plan tasks...").apply {
                    foreground = JBColor(0x6B7280, 0x6B7280)
                    font = font.deriveFont(Font.ITALIC, 12f)
                })
            }
            crafterListPanel.add(emptyPanel)
            revalidate()
            repaint()
        }
    }

    private fun getStatusIcon(status: AgentStatus): Icon = when (status) {
        AgentStatus.ACTIVE -> AllIcons.Process.Step_1
        AgentStatus.COMPLETED -> AllIcons.RunConfigurations.TestPassed
        AgentStatus.ERROR -> AllIcons.RunConfigurations.TestFailed
        AgentStatus.CANCELLED -> AllIcons.RunConfigurations.TestIgnored
        AgentStatus.PENDING -> AllIcons.RunConfigurations.TestNotRan
    }

    private fun createDagConnector(): JPanel {
        return object : JPanel() {
            init {
                isOpaque = false
                preferredSize = Dimension(0, 10)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(0x30363D, 0x30363D)
                val cx = width / 2
                g2.drawLine(cx, 0, cx, height)
            }
        }
    }
}

/**
 * Individual CRAFTER detail panel — shown inside a tab.
 *
 * Uses [ClaudeCodeRenderer] to properly render streaming events (thinking, tool calls, messages)
 * with the same high-quality rendering used in the main chat panel.
 *
 * Shows:
 * - Task info header: title, agent ID, status, stop button, progress bar
 * - Task details (collapsible)
 * - Rendered streaming output via [AcpEventRenderer]
 */
class CrafterDetailPanel(
    private val onStopClick: () -> Unit = {}
) : JPanel(BorderLayout()) {

    private val taskTitleLabel = JBLabel("").apply {
        foreground = JBColor(0xC9D1D9, 0xC9D1D9)
        font = font.deriveFont(Font.BOLD, 12f)
    }

    private val taskIdLabel = JBLabel("").apply {
        foreground = JBColor(0x6B7280, 0x6B7280)
        font = font.deriveFont(9f)
    }

    private val statusLabel = JBLabel("PENDING").apply {
        foreground = JBColor(0x6B7280, 0x6B7280)
        font = font.deriveFont(Font.BOLD, 10f)
    }

    private val stopButton = JButton(AllIcons.Actions.Suspend).apply {
        toolTipText = "Stop this CRAFTER agent"
        preferredSize = Dimension(24, 24)
        isContentAreaFilled = false
        isBorderPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false // Only visible when running
    }

    private val progressBar = JProgressBar(0, 100).apply {
        value = 0
        preferredSize = Dimension(0, 3)
        isStringPainted = false
        background = JBColor(0x21262D, 0x21262D)
        foreground = CrafterSectionPanel.CRAFTER_ACCENT
    }

    // Task details (collapsible)
    private val taskDetailsArea = JTextArea(3, 40).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = JBColor(0x161B22, 0x161B22)
        foreground = JBColor(0x8B949E, 0x8B949E)
        font = Font("SansSerif", Font.PLAIN, 11)
        border = JBUI.Borders.empty(4)
    }

    private val taskDetailsScroll = JScrollPane(taskDetailsArea).apply {
        border = BorderFactory.createLineBorder(JBColor(0x21262D, 0x21262D))
        preferredSize = Dimension(0, 60)
        isVisible = false
    }

    private val detailsToggle = JBLabel("▶ Task Details").apply {
        foreground = JBColor(0x6B7280, 0x6B7280)
        font = font.deriveFont(Font.BOLD, 10f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private var detailsExpanded = false

    // ── Renderer-based output (replaces manual thinking/toolCall/output panels) ──

    private val rendererScroll: JScrollPane

    private val renderer: AcpEventRenderer = CrafterRenderer(
        agentKey = "crafter",
        scrollCallback = {
            SwingUtilities.invokeLater {
                val vertical = rendererScroll.verticalScrollBar
                vertical.value = vertical.maximum
            }
        }
    )

    // State tracking for StreamChunk → RenderEvent conversion
    private var messageStarted = false
    private val messageBuffer = StringBuilder()
    private var toolCallCounter = 0
    private var currentToolCallId: String? = null

    init {
        rendererScroll = JScrollPane(renderer.container).apply {
            border = BorderFactory.createLineBorder(JBColor(0x21262D, 0x21262D))
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        isOpaque = true
        background = JBColor(0x0D1117, 0x0D1117)
        border = JBUI.Borders.empty(4)

        // Wire up stop button
        stopButton.addActionListener {
            onStopClick()
        }

        // Top: task info + status + stop button
        val infoPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)

            val leftPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(taskTitleLabel)
                add(Box.createVerticalStrut(2))
                add(taskIdLabel)
            }
            add(leftPanel, BorderLayout.WEST)

            // Right side: status + stop button
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(statusLabel)
                add(stopButton)
            }
            add(rightPanel, BorderLayout.EAST)
        }

        // Task details section (collapsible)
        val detailsSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            add(detailsToggle, BorderLayout.NORTH)
            add(taskDetailsScroll, BorderLayout.CENTER)
        }

        detailsToggle.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                detailsExpanded = !detailsExpanded
                taskDetailsScroll.isVisible = detailsExpanded
                detailsToggle.text = if (detailsExpanded) "▼ Task Details" else "▶ Task Details"
                revalidate()
                repaint()
            }
        })

        // Top section: info + progress + details only (no thinking/toolCall/completion — renderer handles those)
        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(infoPanel)
            add(progressBar)
            add(Box.createVerticalStrut(4))
            add(detailsSection)
        }

        // Split: top info + bottom renderer output
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            topComponent = topSection
            bottomComponent = rendererScroll
            dividerLocation = 80
            resizeWeight = 0.15
            border = JBUI.Borders.empty()
        }

        add(splitPane, BorderLayout.CENTER)
    }

    /**
     * Update the full state of this CRAFTER.
     */
    fun update(state: CrafterStreamState) {
        taskTitleLabel.text = state.taskTitle.ifBlank { "Task ${state.taskId.take(8)}" }
        taskIdLabel.text = "Agent: ${state.agentId.take(8)}  |  Task: ${state.taskId.take(8)}"

        val (statusText, statusColor) = when (state.status) {
            AgentStatus.PENDING -> "PENDING" to JBColor(0x6B7280, 0x6B7280)
            AgentStatus.ACTIVE -> "RUNNING" to JBColor(0x3B82F6, 0x3B82F6)
            AgentStatus.COMPLETED -> "COMPLETED" to CrafterSectionPanel.CRAFTER_ACCENT
            AgentStatus.ERROR -> "ERROR" to JBColor(0xEF4444, 0xEF4444)
            AgentStatus.CANCELLED -> "CANCELLED" to JBColor(0xF59E0B, 0xF59E0B)
        }
        statusLabel.text = statusText
        statusLabel.foreground = statusColor

        // Show stop button only when agent is actively running
        stopButton.isVisible = state.status == AgentStatus.ACTIVE

        progressBar.value = when (state.status) {
            AgentStatus.COMPLETED -> 100
            AgentStatus.ACTIVE -> 50
            AgentStatus.ERROR -> progressBar.value
            else -> 0
        }
        progressBar.foreground = statusColor
    }

    /**
     * Append a streaming chunk to the output.
     *
     * Converts [StreamChunk] (from routa-core) to [RenderEvent] and dispatches
     * to the embedded [ClaudeCodeRenderer] for proper rendering of thinking,
     * tool calls, messages, etc.
     */
    fun appendChunk(chunk: StreamChunk) {
        when (chunk) {
            is StreamChunk.Text -> {
                if (!messageStarted) {
                    renderer.onEvent(RenderEvent.MessageStart())
                    messageStarted = true
                }
                messageBuffer.append(chunk.content)
                renderer.onEvent(RenderEvent.MessageChunk(chunk.content))
            }

            is StreamChunk.Thinking -> {
                finalizeMessage()
                when (chunk.phase) {
                    ThinkingPhase.START -> renderer.onEvent(RenderEvent.ThinkingStart())
                    ThinkingPhase.CHUNK -> renderer.onEvent(RenderEvent.ThinkingChunk(chunk.content))
                    ThinkingPhase.END -> renderer.onEvent(RenderEvent.ThinkingEnd(chunk.content))
                }
            }

            is StreamChunk.ToolCall -> {
                finalizeMessage()
                handleToolCallChunk(chunk)
            }

            is StreamChunk.Error -> {
                finalizeMessage()
                renderer.onEvent(RenderEvent.Error(chunk.message))
            }

            is StreamChunk.Completed -> {
                finalizeMessage()
                statusLabel.text = "COMPLETED"
                statusLabel.foreground = CrafterSectionPanel.CRAFTER_ACCENT
                progressBar.value = 100
                progressBar.foreground = CrafterSectionPanel.CRAFTER_ACCENT
                renderer.onEvent(RenderEvent.PromptComplete(chunk.stopReason))
            }

            is StreamChunk.CompletionReport -> {
                finalizeMessage()
                val icon = if (chunk.success) "✓" else "✗"
                val filesInfo = if (chunk.filesModified.isNotEmpty()) {
                    " | Files: ${chunk.filesModified.joinToString(", ")}"
                } else ""
                renderer.onEvent(RenderEvent.Info("$icon ${chunk.summary}$filesInfo"))
            }

            else -> {}
        }
    }

    /**
     * Convert a routa [StreamChunk.ToolCall] to appropriate [RenderEvent] tool call events.
     */
    private fun handleToolCallChunk(chunk: StreamChunk.ToolCall) {
        when (chunk.status) {
            ToolCallStatus.STARTED -> {
                val id = "tc-${toolCallCounter++}"
                currentToolCallId = id
                renderer.onEvent(
                    RenderEvent.ToolCallStart(
                        toolCallId = id,
                        toolName = chunk.name,
                        title = chunk.name,
                        kind = null,
                    )
                )
                // Send parameters if available
                chunk.arguments?.let { args ->
                    renderer.onEvent(
                        RenderEvent.ToolCallParameterUpdate(
                            toolCallId = id,
                            partialParameters = args,
                        )
                    )
                }
            }

            ToolCallStatus.IN_PROGRESS -> {
                val id = currentToolCallId ?: "tc-${toolCallCounter++}"
                renderer.onEvent(
                    RenderEvent.ToolCallUpdate(
                        toolCallId = id,
                        status = com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS,
                        title = chunk.name,
                    )
                )
                // Update parameters if available
                chunk.arguments?.let { args ->
                    renderer.onEvent(
                        RenderEvent.ToolCallParameterUpdate(
                            toolCallId = id,
                            partialParameters = args,
                        )
                    )
                }
            }

            ToolCallStatus.COMPLETED -> {
                val id = currentToolCallId ?: "tc-${toolCallCounter++}"
                renderer.onEvent(
                    RenderEvent.ToolCallEnd(
                        toolCallId = id,
                        status = com.agentclientprotocol.model.ToolCallStatus.COMPLETED,
                        title = chunk.name,
                        output = chunk.result,
                    )
                )
                currentToolCallId = null
            }

            ToolCallStatus.FAILED -> {
                val id = currentToolCallId ?: "tc-${toolCallCounter++}"
                renderer.onEvent(
                    RenderEvent.ToolCallEnd(
                        toolCallId = id,
                        status = com.agentclientprotocol.model.ToolCallStatus.FAILED,
                        title = chunk.name,
                        output = chunk.result,
                    )
                )
                currentToolCallId = null
            }
        }
    }

    /**
     * Finalize any in-progress message streaming.
     * Called before switching to thinking, tool call, or completion events.
     */
    private fun finalizeMessage() {
        if (messageStarted) {
            renderer.onEvent(RenderEvent.MessageEnd(messageBuffer.toString()))
            messageStarted = false
            messageBuffer.clear()
        }
    }

    /**
     * Set the task details text (objective, scope, criteria).
     */
    fun setTaskDetails(details: String) {
        taskDetailsArea.text = details
        taskDetailsArea.caretPosition = 0
    }
}
