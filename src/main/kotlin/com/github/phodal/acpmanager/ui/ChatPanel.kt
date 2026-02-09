package com.github.phodal.acpmanager.ui

import com.github.phodal.acpmanager.acp.AgentSession
import com.github.phodal.acpmanager.acp.AgentSessionState
import com.github.phodal.acpmanager.acp.ChatMessage
import com.github.phodal.acpmanager.acp.MessageRole
import com.github.phodal.acpmanager.config.AcpConfigService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
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
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val messagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
    }
    private val scrollPane: JBScrollPane
    private val inputArea: JBTextArea
    private val inputToolbar: ChatInputToolbar
    private var streamingPanel: StreamingMessagePanel? = null
    private var thinkingPanel: StreamingMessagePanel? = null

    private var lastRenderedMessageCount = 0

    init {
        // Messages area
        scrollPane = JBScrollPane(messagesPanel).apply {
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

        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
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
        log.info("ChatPanel: Initial state for '${session.agentKey}': messages=${currentState.messages.size}, connected=${currentState.isConnected}")
        updateUI(currentState)

        // Start observing state changes for future updates
        startStateObserver()
    }

    private fun startStateObserver() {
        scope.launch(Dispatchers.Default) {
            log.info("ChatPanel: Starting state observer for '${session.agentKey}'")
            session.state.collectLatest { state ->
                log.info("ChatPanel: Received state update for '${session.agentKey}': messages=${state.messages.size}, streaming='${state.currentStreamingText.take(20)}...', processing=${state.isProcessing}")
                ApplicationManager.getApplication().invokeLater {
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: AgentSessionState) {
        log.info("ChatPanel: updateUI called for '${session.agentKey}': messages=${state.messages.size}, streaming='${state.currentStreamingText.take(20)}...'")

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

        // Update messages
        if (state.messages.size != lastRenderedMessageCount) {
            log.info("ChatPanel: Rendering ${state.messages.size} messages (was $lastRenderedMessageCount)")
            renderMessages(state.messages)
            lastRenderedMessageCount = state.messages.size
        }

        // Update streaming content
        updateStreamingContent(state)
    }

    private fun renderMessages(messages: List<ChatMessage>) {
        // Remove streaming panels temporarily
        streamingPanel?.let { messagesPanel.remove(it) }
        thinkingPanel?.let { messagesPanel.remove(it) }

        // Remove old messages beyond what we already rendered
        while (messagesPanel.componentCount > 0) {
            messagesPanel.remove(messagesPanel.componentCount - 1)
        }

        // Re-render all messages
        for (message in messages) {
            val panel = MessagePanel(message)
            panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
            panel.alignmentX = Component.LEFT_ALIGNMENT
            messagesPanel.add(panel)
            messagesPanel.add(Box.createVerticalStrut(2))
        }

        // Re-add streaming panels if needed
        streamingPanel?.let {
            it.alignmentX = Component.LEFT_ALIGNMENT
            messagesPanel.add(it)
        }
        thinkingPanel?.let {
            it.alignmentX = Component.LEFT_ALIGNMENT
            messagesPanel.add(it)
        }

        messagesPanel.revalidate()
        messagesPanel.repaint()

        // Scroll to bottom
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun updateStreamingContent(state: AgentSessionState) {
        // Handle thinking content
        if (state.currentThinkingText.isNotBlank()) {
            if (thinkingPanel == null) {
                thinkingPanel = StreamingMessagePanel("", isThinking = true).apply {
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                messagesPanel.add(thinkingPanel)
            }
            thinkingPanel?.updateText(state.currentThinkingText)
        } else {
            thinkingPanel?.let {
                messagesPanel.remove(it)
                thinkingPanel = null
            }
        }

        // Handle streaming content
        if (state.currentStreamingText.isNotBlank()) {
            if (streamingPanel == null) {
                streamingPanel = StreamingMessagePanel("").apply {
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                messagesPanel.add(streamingPanel)
            }
            streamingPanel?.updateText(state.currentStreamingText)
        } else {
            streamingPanel?.let {
                messagesPanel.remove(it)
                streamingPanel = null
            }
        }

        messagesPanel.revalidate()
        messagesPanel.repaint()

        // Scroll to bottom during streaming
        if (state.isProcessing) {
            SwingUtilities.invokeLater {
                val bar = scrollPane.verticalScrollBar
                bar.value = bar.maximum
            }
        }
    }

    private fun sendMessage() {
        val text = inputArea.text?.trim() ?: return
        if (text.isBlank()) return

        log.info("sendMessage called for agent '${session.agentKey}' with text length=${text.length}")
        inputArea.text = ""

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
    }
}
