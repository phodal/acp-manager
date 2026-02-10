package com.github.phodal.acpmanager.ui

import com.github.phodal.acpmanager.acp.AgentSession
import com.github.phodal.acpmanager.acp.AgentSessionState
import com.github.phodal.acpmanager.config.AcpConfigService
import com.github.phodal.acpmanager.ide.IdeAcpClient
import com.github.phodal.acpmanager.ui.completion.CompletionManager
import com.github.phodal.acpmanager.ui.renderer.AcpEventRenderer
import com.github.phodal.acpmanager.ui.renderer.AcpEventRendererRegistry
import com.github.phodal.acpmanager.ui.renderer.DefaultRendererFactory
import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
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

private val log = logger<ChatPanel>()

/**
 * Chat panel for a single ACP agent session.
 *
 * Displays the conversation timeline and provides input for sending messages.
 * Updates agent connection status in the toolbar selector.
 */
class ChatPanel(
    private val project: Project,
    private val session: AgentSession,
    private val agentType: String = "default",
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val scrollPane: JBScrollPane
    private val inputArea: JBTextArea
    private val inputToolbar: ChatInputToolbar
    private lateinit var completionManager: CompletionManager

    // Event-driven renderer
    private val renderer: AcpEventRenderer

    init {
        // Ensure default factory is registered
        if (AcpEventRendererRegistry.getFactory("default") == null) {
            AcpEventRendererRegistry.setDefaultFactory(DefaultRendererFactory())
        }

        // Create renderer with scroll callback
        renderer = AcpEventRendererRegistry.createRenderer(
            agentKey = session.agentKey,
            agentType = agentType,
            scrollCallback = { scrollToBottom() }
        )

        // Messages area using renderer's container
        scrollPane = JBScrollPane(renderer.container).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
        }

        // Input area - larger and more prominent
        inputArea = JBTextArea(4, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
            font = UIUtil.getLabelFont().deriveFont(14f)
            emptyText.text = "Type your message here... (Shift+Enter for newline, Enter to send)"
        }

        // Initialize completion manager
        completionManager = CompletionManager(project, inputArea)

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Try completion handlers first
                if (completionManager.handleKeyPress(e)) {
                    return
                }

                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    sendMessage()
                }
            }
        })

        // Input toolbar (bottom: agent selector + send button)
        inputToolbar = ChatInputToolbar(
            project = project,
            onSendClick = { sendMessage() },
            onStopClick = { cancelMessage() }
        )

        // Input panel layout
        val inputPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineTop(JBColor.border())

            // Text input in center
            add(JBScrollPane(inputArea).apply {
                preferredSize = Dimension(0, JBUI.scale(100))
                border = JBUI.Borders.empty(4, 8)
            }, BorderLayout.CENTER)

            // Toolbar at bottom
            add(inputToolbar, BorderLayout.SOUTH)
        }

        add(scrollPane, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)

        // Immediately render current state (synchronously)
        val currentState = session.state.value
        log.info("ChatPanel: Initial state for '${session.agentKey}': connected=${currentState.isConnected}")
        updateToolbarState(currentState)

        // Start observing render events
        startRenderEventObserver()

        // Start observing state changes for toolbar updates
        startStateObserver()
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun startRenderEventObserver() {
        scope.launch(Dispatchers.Default) {
            log.info("ChatPanel: Starting render event observer for '${session.agentKey}'")
            session.renderEvents.collect { event ->
                log.info("ChatPanel: Received render event for '${session.agentKey}': ${event::class.simpleName}")
                ApplicationManager.getApplication().invokeLater {
                    renderer.onEvent(event)
                }
            }
        }
    }

    private fun startStateObserver() {
        scope.launch(Dispatchers.Default) {
            log.info("ChatPanel: Starting state observer for '${session.agentKey}'")
            session.state.collectLatest { state ->
                log.info("ChatPanel: Received state update for '${session.agentKey}': processing=${state.isProcessing}")
                ApplicationManager.getApplication().invokeLater {
                    updateToolbarState(state)
                }
            }
        }
    }

    private fun updateToolbarState(state: AgentSessionState) {
        // Update toolbar
        inputToolbar.setProcessing(state.isProcessing)
        inputToolbar.setSendEnabled(!state.isProcessing && state.isConnected)
        inputArea.isEnabled = !state.isProcessing && state.isConnected

        // Update connection status in the selector
        val connectionStatus = when {
            state.error != null -> AgentConnectionStatus.ERROR
            state.isConnected -> AgentConnectionStatus.CONNECTED
            else -> AgentConnectionStatus.DISCONNECTED
        }
        inputToolbar.updateAgentStatus(session.agentKey, connectionStatus)

        // Update status text
        val statusText = when {
            state.error != null -> "Error: ${state.error}"
            !state.isConnected -> "Disconnected"
            state.isProcessing -> "Processing..."
            else -> "Connected"
        }
        inputToolbar.setStatusText(statusText)
    }

    private fun sendMessage() {
        val text = inputArea.text?.trim() ?: return
        if (text.isBlank()) return

        log.info("sendMessage called for agent '${session.agentKey}' with text length=${text.length}")

        // Close any open completion popups
        completionManager.closeAllPopups()

        inputArea.text = ""

        // Check for @ mentions and send at-mention notification if detected
        detectAndSendAtMention(text)

        scope.launch(Dispatchers.IO) {
            try {
                // Ensure connected before sending
                if (!session.isConnected) {
                    log.info("Session '${session.agentKey}' not connected, attempting to connect first")
                    val configService = AcpConfigService.getInstance(project)
                    val config = configService.getAgentConfig(session.agentKey)
                    log.info("Got config for '${session.agentKey}': ${config?.command} ${config?.args?.joinToString(" ")}")
                    if (config != null) {
                        log.info("Connecting to agent '${session.agentKey}'...")
                        session.connect(config)
                        log.info("Connected to agent '${session.agentKey}', isConnected=${session.isConnected}")
                    } else {
                        log.warn("Agent config not found for '${session.agentKey}'")
                        ApplicationManager.getApplication().invokeLater {
                            inputToolbar.setStatusText("Error: Agent config not found")
                        }
                        return@launch
                    }
                }

                log.info("Sending message to agent '${session.agentKey}'...")
                session.sendMessage(text)
                log.info("Message sent to agent '${session.agentKey}'")
            } catch (e: Exception) {
                log.warn("Failed to send message to '${session.agentKey}'", e)
                ApplicationManager.getApplication().invokeLater {
                    inputToolbar.setStatusText("Error: ${e.message}")
                }
            }
        }
    }

    /**
     * Detect @ mentions in the message and send at-mention notification if found.
     */
    private fun detectAndSendAtMention(text: String) {
        // Simple @ mention detection: look for @ followed by word characters
        if (!text.contains("@")) return

        try {
            val ideClient = IdeAcpClient.getInstance(project)
            val context = ideClient.ideNotifications.captureEditorContext()

            if (context != null) {
                log.info("At-mention detected in message, sending notification for file: ${context.filePath}")
                ideClient.ideNotifications.sendAtMentioned(
                    filePath = context.filePath,
                    startLine = context.startLine,
                    endLine = context.endLine
                )

                // Show visual feedback
                ApplicationManager.getApplication().invokeLater {
                    inputToolbar.setStatusText("Context shared: ${context.filePath}")
                }
            }
        } catch (e: Exception) {
            log.debug("Error detecting at-mention: ${e.message}")
        }
    }

    private fun cancelMessage() {
        scope.launch(Dispatchers.IO) {
            session.cancelPrompt()
        }
    }

    /**
     * Update the input toolbar with agent list and callbacks.
     */
    fun updateInputToolbar(
        agents: Map<String, com.github.phodal.acpmanager.config.AcpAgentConfig>,
        currentAgentKey: String?,
        onAgentSelect: (String) -> Unit,
        onConfigureClick: () -> Unit
    ) {
        inputToolbar.setAgents(agents)
        inputToolbar.setCurrentAgent(currentAgentKey)
        inputToolbar.setOnAgentSelect(onAgentSelect)
        inputToolbar.setOnConfigureClick(onConfigureClick)

        // Set initial status for the current agent
        if (currentAgentKey != null && session.isConnected) {
            inputToolbar.updateAgentStatus(currentAgentKey, AgentConnectionStatus.CONNECTED)
        }

        // Refresh all statuses
        inputToolbar.refreshAllStatuses()
    }

    override fun dispose() {
        scope.cancel()
        renderer.dispose()
    }
}
