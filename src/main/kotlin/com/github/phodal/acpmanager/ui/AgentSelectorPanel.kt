package com.github.phodal.acpmanager.ui

import com.github.phodal.acpmanager.acp.AcpProcessManager
import com.github.phodal.acpmanager.acp.AcpSessionManager
import com.github.phodal.acpmanager.config.AcpAgentConfig
import com.github.phodal.acpmanager.config.AcpAgentPresets
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

/**
 * Connection status for an ACP agent.
 */
enum class AgentConnectionStatus {
    /** Agent is connected and responding */
    CONNECTED,
    /** Agent process is running but session not yet established */
    CONNECTING,
    /** Agent is not connected */
    DISCONNECTED,
    /** Agent connection failed or process crashed */
    ERROR,
    /** Agent command is not available (not found in PATH) */
    UNAVAILABLE,
}

/**
 * Independent agent selector panel that shows each agent with a colored connection status dot.
 *
 * Layout: [StatusDot AgentName] as a custom-rendered combo box.
 *
 * Color coding:
 * - Green:  Connected and ready
 * - Yellow: Connecting / process running but session not established
 * - Red:    Error / process crashed
 * - Gray:   Disconnected (not started)
 */
class AgentSelectorPanel(
    private val project: Project,
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {

    private val log = Logger.getInstance(AgentSelectorPanel::class.java)

    private val agentComboBox = JComboBox<AgentDisplayItem>()
    private var agents: Map<String, AcpAgentConfig> = emptyMap()
    private var agentKeys: List<String> = emptyList()
    private var isUpdating = false

    // Callbacks
    var onAgentSelected: (String) -> Unit = {}
    var onConfigureClick: () -> Unit = {}

    // Track statuses
    private val agentStatuses = mutableMapOf<String, AgentConnectionStatus>()

    private val CONFIGURE_SENTINEL = AgentDisplayItem("__configure__", "Configure Agents...", AgentConnectionStatus.DISCONNECTED)

    init {
        isOpaque = false

        // Pass agentStatuses to renderer so it can get the latest status
        agentComboBox.renderer = AgentListCellRenderer { key -> agentStatuses[key] }
        agentComboBox.preferredSize = Dimension(220, 28)
        agentComboBox.addActionListener {
            if (isUpdating) return@addActionListener
            val selected = agentComboBox.selectedItem as? AgentDisplayItem ?: return@addActionListener
            if (selected.key == CONFIGURE_SENTINEL.key) {
                onConfigureClick()
                // Revert selection to previous
                restoreLastSelection()
            } else {
                onAgentSelected(selected.key)
            }
        }
        add(agentComboBox)

        // Config gear button
        val configButton = JButton(AllIcons.General.Settings).apply {
            toolTipText = "Configure ACP Agents"
            preferredSize = Dimension(28, 28)
            isBorderPainted = false
            isContentAreaFilled = false
            addActionListener { onConfigureClick() }
        }
        add(configButton)
    }

    /**
     * Set the list of available agents.
     */
    fun setAgents(agentsMap: Map<String, AcpAgentConfig>) {
        agents = agentsMap
        agentKeys = agentsMap.keys.toList()
        rebuildComboBox()
    }

    /**
     * Update the connection status for a specific agent.
     */
    fun updateAgentStatus(agentKey: String, status: AgentConnectionStatus) {
        agentStatuses[agentKey] = status
        // Refresh display
        agentComboBox.repaint()
    }

    /**
     * Refresh all agent statuses by checking command availability, process manager and session manager.
     */
    fun refreshAllStatuses() {
        val processManager = AcpProcessManager.getInstance()
        val sessionManager = AcpSessionManager.getInstance(project)

        log.info("Refreshing agent statuses for ${agentKeys.size} agents")

        for (key in agentKeys) {
            val config = agents[key]
            val status = resolveAgentStatus(key, config, processManager, sessionManager)
            val oldStatus = agentStatuses[key]
            if (oldStatus != status) {
                log.info("Agent '$key' status changed: $oldStatus -> $status")
            }
            agentStatuses[key] = status
        }
        agentComboBox.repaint()
    }

    /**
     * Get the currently selected agent key.
     */
    fun getSelectedAgentKey(): String? {
        val selected = agentComboBox.selectedItem as? AgentDisplayItem ?: return null
        return if (selected.key == CONFIGURE_SENTINEL.key) null else selected.key
    }

    /**
     * Set the current agent by key.
     */
    fun setCurrentAgent(agentKey: String?) {
        if (agentKey == null) return
        isUpdating = true
        try {
            for (i in 0 until agentComboBox.itemCount) {
                val item = agentComboBox.getItemAt(i)
                if (item.key == agentKey) {
                    agentComboBox.selectedIndex = i
                    break
                }
            }
        } finally {
            isUpdating = false
        }
    }

    /**
     * Get the status of a specific agent.
     */
    fun getAgentStatus(agentKey: String): AgentConnectionStatus {
        return agentStatuses[agentKey] ?: AgentConnectionStatus.DISCONNECTED
    }

    private fun resolveAgentStatus(
        agentKey: String,
        config: AcpAgentConfig?,
        processManager: AcpProcessManager,
        sessionManager: AcpSessionManager,
    ): AgentConnectionStatus {
        // First check if command is available
        if (config != null) {
            val command = config.command
            val isAvailable = AcpAgentPresets.isCommandAvailable(command)
            log.debug("Agent '$agentKey' command='$command' available=$isAvailable")
            if (!isAvailable) {
                return AgentConnectionStatus.UNAVAILABLE
            }
        }

        val session = sessionManager.getSession(agentKey)
        val processRunning = processManager.isRunning(agentKey)

        log.debug("Agent '$agentKey': session=${session != null}, isConnected=${session?.isConnected}, processRunning=$processRunning")

        return when {
            session != null && session.isConnected -> AgentConnectionStatus.CONNECTED
            processRunning && (session == null || !session.isConnected) -> AgentConnectionStatus.CONNECTING
            session?.state?.value?.error != null -> AgentConnectionStatus.ERROR
            else -> AgentConnectionStatus.DISCONNECTED
        }
    }

    private fun rebuildComboBox() {
        isUpdating = true
        try {
            agentComboBox.removeAllItems()

            for (key in agentKeys) {
                val config = agents[key] ?: continue
                val displayName = config.description.ifBlank { key }
                val status = agentStatuses[key] ?: AgentConnectionStatus.DISCONNECTED
                agentComboBox.addItem(AgentDisplayItem(key, displayName, status))
            }

            agentComboBox.addItem(CONFIGURE_SENTINEL)
        } finally {
            isUpdating = false
        }
    }

    private fun restoreLastSelection() {
        isUpdating = true
        try {
            // Select first non-sentinel item
            for (i in 0 until agentComboBox.itemCount) {
                val item = agentComboBox.getItemAt(i)
                if (item.key != CONFIGURE_SENTINEL.key) {
                    agentComboBox.selectedIndex = i
                    break
                }
            }
        } finally {
            isUpdating = false
        }
    }
}

/**
 * Data class for items in the agent selector combo box.
 */
data class AgentDisplayItem(
    val key: String,
    val displayName: String,
    val status: AgentConnectionStatus,
) {
    override fun toString(): String = displayName
}

/**
 * Custom cell renderer that draws a colored status dot next to the agent name.
 *
 * @param statusProvider A function that returns the current status for a given agent key.
 *                       This allows the renderer to always use the latest status from the map.
 */
class AgentListCellRenderer(
    private val statusProvider: (String) -> AgentConnectionStatus?
) : ListCellRenderer<AgentDisplayItem> {
    private val panel = JPanel(BorderLayout())
    private val dotLabel = JBLabel()
    private val nameLabel = JBLabel()

    init {
        panel.isOpaque = true
        panel.border = JBUI.Borders.empty(2, 6)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(dotLabel)
            add(nameLabel)
        }
        panel.add(leftPanel, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out AgentDisplayItem>?,
        value: AgentDisplayItem?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        if (value == null) {
            nameLabel.text = ""
            dotLabel.text = ""
            return panel
        }

        // Configure selection colors
        if (isSelected) {
            panel.background = UIUtil.getListSelectionBackground(true)
            nameLabel.foreground = UIUtil.getListSelectionForeground(true)
        } else {
            panel.background = UIUtil.getListBackground()
            nameLabel.foreground = UIUtil.getListForeground()
        }

        // Handle "Configure Agents..." option
        if (value.key == "__configure__") {
            dotLabel.text = ""
            dotLabel.icon = AllIcons.General.Settings
            nameLabel.text = value.displayName
            nameLabel.font = nameLabel.font.deriveFont(Font.ITALIC)
            return panel
        }

        // Regular agent item
        nameLabel.text = value.displayName
        nameLabel.font = UIUtil.getLabelFont()

        // Status dot (Unicode circle character)
        // Use statusProvider to get the latest status, falling back to the item's status
        val currentStatus = statusProvider(value.key) ?: value.status
        dotLabel.icon = null
        dotLabel.text = "\u25CF" // filled circle
        dotLabel.font = dotLabel.font.deriveFont(10f)
        dotLabel.foreground = getStatusColor(currentStatus)

        return panel
    }

    companion object {
        fun getStatusColor(status: AgentConnectionStatus): Color {
            return when (status) {
                AgentConnectionStatus.CONNECTED -> JBColor(Color(0x4CAF50), Color(0x81C784))     // Green
                AgentConnectionStatus.CONNECTING -> JBColor(Color(0xFFC107), Color(0xFFD54F))    // Yellow/Amber
                AgentConnectionStatus.ERROR -> JBColor(Color(0xF44336), Color(0xEF9A9A))         // Red
                AgentConnectionStatus.DISCONNECTED -> JBColor(Color(0x2196F3), Color(0x64B5F6)) // Blue (ready to connect)
                AgentConnectionStatus.UNAVAILABLE -> JBColor(Color(0x9E9E9E), Color(0x757575))  // Gray (command not found)
            }
        }
    }
}
