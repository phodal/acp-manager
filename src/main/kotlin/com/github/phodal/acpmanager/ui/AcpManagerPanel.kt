package com.github.phodal.acpmanager.ui

import com.github.phodal.acpmanager.acp.AcpSessionManager
import com.github.phodal.acpmanager.config.AcpConfigService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

private val log = logger<AcpManagerPanel>()

/**
 * Main panel for ACP Manager.
 *
 * UI layout:
 * - Center: Tabbed chat panels for each agent session
 * - Bottom (in welcome tab): Agent selection and input area
 *
 * Features:
 * - Agent selector with colored status indicators (green/yellow/red/gray)
 * - Automatic connection when sending first message
 * - Status refresh timer for connection indicators
 */
class AcpManagerPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionManager = AcpSessionManager.getInstance(project)
    private val configService = AcpConfigService.getInstance(project)

    private val tabbedPane = JTabbedPane(JTabbedPane.TOP)
    private val chatPanels = mutableMapOf<String, ChatPanel>()
    private val emptyPanel: JPanel

    // Track the selected agent key in the welcome panel
    @Volatile
    private var selectedAgentKey: String? = null
    private var welcomeToolbar: ChatInputToolbar? = null

    // Status refresh timer
    private var statusRefreshJob: Job? = null

    init {
        emptyPanel = createEmptyPanel()
        setContent(tabbedPane)
        tabbedPane.addTab("Welcome", emptyPanel)

        // Load config and auto-detect agents
        configService.reloadConfig()

        // Set initial selected agent
        val config = configService.loadConfig()
        selectedAgentKey = config.activeAgent ?: config.agents.keys.firstOrNull()

        // Watch session changes
        startSessionObserver()

        // Start periodic status refresh
        startStatusRefresh()
    }

    private fun createEmptyPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            // Welcome content in center
            val welcomeContent = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
                val gbc = GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    anchor = GridBagConstraints.CENTER
                }

                val titleLabel = JBLabel("Welcome to ACP Manager").apply {
                    font = font.deriveFont(Font.BOLD, 18f)
                    foreground = UIUtil.getLabelForeground()
                }
                add(titleLabel, gbc)

                gbc.gridy = 1
                gbc.insets = Insets(12, 0, 0, 0)

                val config = configService.loadConfig()
                val agentCount = config.agents.size

                val subtitleLabel = JBLabel(
                    if (agentCount > 0) {
                        "Detected $agentCount agent(s) from PATH and config files"
                    } else {
                        "No agents detected"
                    }
                ).apply {
                    foreground = UIUtil.getLabelDisabledForeground()
                }
                add(subtitleLabel, gbc)

                gbc.gridy = 2
                gbc.insets = Insets(24, 0, 0, 0)
                val hintLabel = JBLabel(
                    "<html><center>" +
                            "Select an agent below and type your message to start" +
                            "</center></html>"
                ).apply {
                    foreground = UIUtil.getLabelDisabledForeground()
                    font = font.deriveFont(font.size2D - 0.5f)
                }
                add(hintLabel, gbc)
            }
            add(welcomeContent, BorderLayout.CENTER)

            // Input area at bottom
            val inputArea = JBTextArea(4, 40).apply {
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(8)
                font = UIUtil.getLabelFont().deriveFont(14f)
                emptyText.text = "Select an agent and type your message... (Enter to send)"
            }

            inputArea.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                        e.consume()
                        val text = inputArea.text.trim()
                        if (text.isNotEmpty()) {
                            startFirstSession(text)
                            inputArea.text = ""
                        }
                    }
                }
            })

            val inputToolbar = ChatInputToolbar(
                project = project,
                onSendClick = {
                    val text = inputArea.text.trim()
                    if (text.isNotEmpty()) {
                        startFirstSession(text)
                        inputArea.text = ""
                    }
                },
                onStopClick = {}
            ).apply {
                val config = configService.loadConfig()
                setAgents(config.agents)
                val activeKey = config.activeAgent ?: config.agents.keys.firstOrNull()
                if (activeKey != null) {
                    setCurrentAgent(activeKey)
                    selectedAgentKey = activeKey
                }
                setOnAgentSelect { newKey ->
                    selectedAgentKey = newKey
                    log.info("Welcome panel: selected agent changed to '$newKey'")
                }
                setOnConfigureClick { showConfigDialog() }
            }
            welcomeToolbar = inputToolbar

            val inputPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLineTop(JBColor.border())
                add(
                    JBScrollPane(inputArea).apply {
                        preferredSize = Dimension(0, JBUI.scale(100))
                        border = JBUI.Borders.empty(4, 8)
                    }, BorderLayout.CENTER
                )
                add(inputToolbar, BorderLayout.SOUTH)
            }
            add(inputPanel, BorderLayout.SOUTH)
        }
    }

    private fun startFirstSession(initialMessage: String) {
        // Use the agent selected in the dropdown, not just from config
        val agentKey = selectedAgentKey
            ?: welcomeToolbar?.getSelectedAgentKey()
            ?: configService.loadConfig().let { it.activeAgent ?: it.agents.keys.firstOrNull() }

        if (agentKey == null) {
            Messages.showWarningDialog(
                project,
                "No agents available. Please configure agents first.",
                "No Agents"
            )
            return
        }

        log.info("Starting first session with agent '$agentKey', message: '${initialMessage.take(50)}...'")

        // Update status to connecting
        welcomeToolbar?.updateAgentStatus(agentKey, AgentConnectionStatus.CONNECTING)

        scope.launch(Dispatchers.IO) {
            try {
                // Connect the agent - this triggers sessionKeys update
                val session = sessionManager.connectAgent(agentKey)

                // Update status to connected
                ApplicationManager.getApplication().invokeLater {
                    welcomeToolbar?.updateAgentStatus(agentKey, AgentConnectionStatus.CONNECTED)
                }

                // Wait for ChatPanel to be created and start observing
                // This ensures the ChatPanel is ready to receive state updates
                var retries = 0
                while (!chatPanels.containsKey(agentKey) && retries < 50) {
                    delay(20)
                    retries++
                }

                if (!chatPanels.containsKey(agentKey)) {
                    log.warn("ChatPanel for '$agentKey' was not created in time, sending message anyway")
                }

                // Send the initial message
                log.info("Agent '$agentKey' connected, sending initial message: ${initialMessage.take(50)}...")
                session.sendMessage(initialMessage)
                log.info("Initial message sent to '$agentKey'")
            } catch (e: Exception) {
                log.warn("Failed to start session with agent '$agentKey': ${e.message}", e)
                ApplicationManager.getApplication().invokeLater {
                    welcomeToolbar?.updateAgentStatus(agentKey, AgentConnectionStatus.ERROR)
                    Messages.showErrorDialog(
                        project,
                        "Failed to start session with '$agentKey': ${e.message}",
                        "Connection Error"
                    )
                }
            }
        }
    }

    private fun startSessionObserver() {
        scope.launch {
            sessionManager.sessionKeys.collectLatest { keys ->
                log.info("AcpManagerPanel: sessionKeys changed: $keys")
                ApplicationManager.getApplication().invokeLater {
                    updateUI(keys)
                }
            }
        }
    }

    /**
     * Start a periodic job to refresh agent connection statuses.
     */
    private fun startStatusRefresh() {
        statusRefreshJob = scope.launch {
            while (isActive) {
                delay(3000) // Refresh every 3 seconds
                ApplicationManager.getApplication().invokeLater {
                    welcomeToolbar?.refreshAllStatuses()
                    chatPanels.values.forEach { panel ->
                        // Each chat panel's toolbar also gets refreshed
                    }
                }
            }
        }
    }

    private fun updateUI(sessionKeys: List<String>) {
        log.info("AcpManagerPanel: updateUI called with sessionKeys=$sessionKeys, existing chatPanels=${chatPanels.keys}")

        // Remove tabs for disconnected sessions
        val existingKeys = chatPanels.keys.toList()
        existingKeys.forEach { key ->
            if (!sessionKeys.contains(key)) {
                val panel = chatPanels.remove(key)
                val index = (0 until tabbedPane.tabCount).find {
                    tabbedPane.getComponentAt(it) == panel
                }
                if (index != null && index >= 0) {
                    tabbedPane.removeTabAt(index)
                }
            }
        }

        // Add/update tabs for connected sessions
        sessionKeys.forEach { key ->
            if (!chatPanels.containsKey(key)) {
                log.info("AcpManagerPanel: Creating ChatPanel for '$key'")
                // Get or create session
                val session = sessionManager.getOrCreateSession(key)

                // Create new chat panel
                val chatPanel = ChatPanel(project, session)
                chatPanels[key] = chatPanel

                // Configure the input toolbar
                val config = configService.loadConfig()
                chatPanel.updateInputToolbar(
                    agents = config.agents,
                    currentAgentKey = key,
                    onAgentSelect = { selectedKey ->
                        // Switch to or create session for selected agent
                        scope.launch(Dispatchers.IO) {
                            try {
                                sessionManager.connectAgent(selectedKey)
                            } catch (e: Exception) {
                                ApplicationManager.getApplication().invokeLater {
                                    Messages.showErrorDialog(
                                        project,
                                        "Failed to connect to agent: ${e.message}",
                                        "Connection Error"
                                    )
                                }
                            }
                        }
                    },
                    onConfigureClick = { showConfigDialog() }
                )

                // Remove welcome tab if this is the first session
                if (tabbedPane.tabCount == 1 && tabbedPane.getComponentAt(0) == emptyPanel) {
                    tabbedPane.removeTabAt(0)
                }

                // Get display name from config
                val displayName = config.agents[key]?.description?.ifBlank { key } ?: key
                tabbedPane.addTab(displayName, chatPanel)
                tabbedPane.selectedComponent = chatPanel
            }
        }

        // Show welcome tab if no sessions
        if (chatPanels.isEmpty() && tabbedPane.componentCount == 0) {
            tabbedPane.addTab("Welcome", emptyPanel)
        }
    }

    private fun showConfigDialog() {
        val config = configService.loadConfig()
        val message = buildString {
            appendLine("Detected ${config.agents.size} ACP Agent(s):")
            appendLine()
            if (config.agents.isEmpty()) {
                appendLine("No agents found.")
                appendLine()
                appendLine("Agents are auto-detected from:")
                appendLine("1. System PATH (common CLIs)")
                appendLine("2. ~/.autodev/config.yaml")
                appendLine("3. ~/.acp-manager/config.yaml")
            } else {
                config.agents.forEach { (key, agent) ->
                    val status = welcomeToolbar?.agentSelector?.getAgentStatus(key)
                        ?: AgentConnectionStatus.DISCONNECTED
                    val statusIcon = when (status) {
                        AgentConnectionStatus.CONNECTED -> "[OK]"
                        AgentConnectionStatus.CONNECTING -> "[...]"
                        AgentConnectionStatus.ERROR -> "[ERR]"
                        AgentConnectionStatus.DISCONNECTED -> "[ ]"
                        AgentConnectionStatus.UNAVAILABLE -> "[N/A]"
                    }
                    appendLine("$statusIcon $key: ${agent.command} ${agent.args.joinToString(" ")}")
                }
                appendLine()
                appendLine("Sources:")
                appendLine("  Auto-detected from PATH")
                appendLine("  ~/.autodev/config.yaml")
                appendLine("  ~/.acp-manager/config.yaml")
            }
            appendLine()
            appendLine("To add custom agents, edit:")
            appendLine(configService.getGlobalConfigFile().absolutePath)
        }

        Messages.showInfoMessage(
            project,
            message,
            "ACP Agent Configuration"
        )
    }

    override fun dispose() {
        statusRefreshJob?.cancel()
        scope.cancel()
        chatPanels.values.forEach { it.dispose() }
        chatPanels.clear()
    }
}
