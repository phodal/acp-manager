package com.github.phodal.acpmanager.acp

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import com.github.phodal.acpmanager.claudecode.ClaudeCodeClient
import com.github.phodal.acpmanager.config.AcpAgentConfig
import com.phodal.routa.core.mcp.RoutaMcpWebSocketServer
import com.github.phodal.acpmanager.config.AcpConfigService
import com.github.phodal.acpmanager.ui.renderer.PlanEntry
import com.github.phodal.acpmanager.ui.renderer.PlanEntryStatus
import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.ConcurrentHashMap

private val log = logger<AcpSessionManager>()

/**
 * Represents a chat message in the conversation.
 */
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolCallId: String? = null,
    val toolCallStatus: ToolCallStatus? = null,
    val toolCallKind: String? = null,
    val isThinking: Boolean = false,
    val references: List<MessageReference> = emptyList(),
)

/**
 * Represents a reference to a file or symbol in a message.
 */
data class MessageReference(
    val type: String,  // "file" or "symbol"
    val displayText: String,
    val insertText: String,
    val metadata: Map<String, Any> = emptyMap()
)

enum class MessageRole {
    USER, ASSISTANT, TOOL_CALL, TOOL_RESULT, THINKING, INFO, ERROR
}

/**
 * Represents the state of a single agent session.
 */
data class AgentSessionState(
    val agentKey: String,
    val isConnected: Boolean = false,
    val isProcessing: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val currentStreamingText: String = "",
    val currentThinkingText: String = "",
    val error: String? = null,
)

/**
 * Manages a single agent session.
 */
class AgentSession(
    val agentKey: String,
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    private var client: AcpClient? = null
    private var claudeClient: ClaudeCodeClient? = null
    private var claudeEventJob: Job? = null
    private var managedProcess: ManagedProcess? = null
    private var stderrJob: Job? = null
    private var isClaudeCodeMode = false
    private var routaMcpServer: RoutaMcpWebSocketServer? = null

    private val _state = MutableStateFlow(AgentSessionState(agentKey))
    val state: StateFlow<AgentSessionState> = _state.asStateFlow()

    // Render events flow for UI updates
    private val _renderEvents = MutableSharedFlow<RenderEvent>(
        replay = 0,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val renderEvents: SharedFlow<RenderEvent> = _renderEvents.asSharedFlow()

    // Tool call dedup tracking
    private val renderedToolCallIds = mutableSetOf<String>()
    private val toolCallTitles = mutableMapOf<String, String>()
    private val startedToolCallIds = mutableSetOf<String>()

    // Connection lock to prevent concurrent connect() calls
    private val connectMutex = Mutex()
    @Volatile
    private var isConnecting = false

    val isConnected: Boolean get() {
        return if (isClaudeCodeMode) {
            claudeClient?.isConnected == true
        } else {
            client?.isConnected == true && managedProcess?.isAlive() == true
        }
    }

    /**
     * Connect to the ACP agent.
     */
    suspend fun connect(config: AcpAgentConfig) {
        // Use mutex to prevent concurrent connection attempts
        connectMutex.withLock {
            if (isConnected) {
                log.info("Session '$agentKey' already connected")
                return
            }
            if (isConnecting) {
                log.info("Session '$agentKey' is already connecting, skipping")
                return
            }
            isConnecting = true
        }

        try {
            // Clean up stale client
            disconnect()

            updateState { copy(error = null) }

            val cwd = project.basePath ?: System.getProperty("user.dir") ?: "."

            // Check if this is Claude Code (non-standard API)
            if (config.isClaudeCode()) {
                connectClaudeCode(config, cwd)
            } else {
                connectStandardAcp(config, cwd)
            }

            updateState { copy(isConnected = true, error = null) }
            emitRenderEvent(RenderEvent.Connected(agentKey))
            addMessage(ChatMessage(MessageRole.INFO, "Connected to agent '$agentKey'"))

            log.info("Session '$agentKey' connected successfully")
        } catch (e: Exception) {
            log.warn("Failed to connect to agent '$agentKey': ${e.message}", e)
            updateState { copy(isConnected = false, error = "Connection failed: ${e.message}") }
            emitRenderEvent(RenderEvent.Error("Connection failed: ${e.message}"))
            addMessage(ChatMessage(MessageRole.ERROR, "Connection failed: ${e.message}"))
            throw e
        } finally {
            isConnecting = false
        }
    }

    /**
     * Connect using Claude Code's stream-json protocol.
     */
    private fun connectClaudeCode(config: AcpAgentConfig, cwd: String) {
        log.info("Connecting to Claude Code for '$agentKey' using stream-json mode")
        isClaudeCodeMode = true

        // Start the Routa MCP server so Claude Code can use coordination tools
        val mcpServer = RoutaMcpWebSocketServer(workspaceId = agentKey)
        mcpServer.start()
        this.routaMcpServer = mcpServer
        val mcpConfigJson = mcpServer.toMcpConfigJson()
        log.info("Routa MCP server started on port ${mcpServer.port} for '$agentKey'")
        log.info("MCP Config: $mcpConfigJson")

        val claudeCodeClient = ClaudeCodeClient(
            scope = scope,
            binaryPath = config.command,
            workingDirectory = cwd,
            additionalArgs = config.args,
            envVars = config.env,
            permissionMode = if (config.autoApprove) "bypassPermissions" else "acceptEdits",
            allowedTools = config.allowedTools,
            mcpConfigs = listOf(mcpConfigJson)
        )

        // Forward Claude Code events to our render events flow
        claudeEventJob = scope.launch {
            claudeCodeClient.renderEvents.collect { event ->
                emitRenderEvent(event)
                // Also add to messages for state tracking
                when (event) {
                    is RenderEvent.UserMessage -> addMessage(ChatMessage(MessageRole.USER, event.content))
                    is RenderEvent.MessageEnd -> addMessage(ChatMessage(MessageRole.ASSISTANT, event.fullContent))
                    is RenderEvent.ThinkingEnd -> addMessage(ChatMessage(MessageRole.THINKING, event.fullContent, isThinking = true))
                    is RenderEvent.ToolCallStart -> addMessage(ChatMessage(
                        role = MessageRole.TOOL_CALL,
                        content = event.title ?: event.toolName,
                        toolCallId = event.toolCallId,
                        toolCallKind = event.kind
                    ))
                    is RenderEvent.ToolCallEnd -> addMessage(ChatMessage(
                        role = MessageRole.TOOL_RESULT,
                        content = "${event.title ?: ""}: ${event.output ?: "Done"}",
                        toolCallId = event.toolCallId,
                        toolCallStatus = event.status
                    ))
                    is RenderEvent.Error -> addMessage(ChatMessage(MessageRole.ERROR, event.message))
                    is RenderEvent.Info -> addMessage(ChatMessage(MessageRole.INFO, event.message))
                    else -> { /* Other events don't need message tracking */ }
                }
            }
        }

        claudeCodeClient.start()
        this.claudeClient = claudeCodeClient

        log.info("Claude Code client started for '$agentKey'")
    }

    /**
     * Connect using standard ACP protocol.
     */
    private suspend fun connectStandardAcp(config: AcpAgentConfig, cwd: String) {
        log.info("Connecting to standard ACP agent for '$agentKey'")
        isClaudeCodeMode = false

        val processManager = AcpProcessManager.getInstance()
        val managed = processManager.getOrCreateProcess(agentKey, config, cwd)
        this.managedProcess = managed

        // Start stderr reading
        stderrJob = scope.launch(Dispatchers.IO) {
            try {
                managed.errorStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        log.info("[$agentKey stderr] $line")
                    }
                }
            } catch (e: Exception) {
                log.warn("[$agentKey] stderr reading failed: ${e.message}")
            }
        }

        log.info("Creating AcpClient for '$agentKey', process alive=${managed.isAlive()}")

        val acpClient = AcpClient(
            coroutineScope = scope,
            input = managed.inputStream.asSource(),
            output = managed.outputStream.asSink(),
            cwd = cwd,
            agentName = agentKey,
        )

        // Set up permission handler
        acpClient.onPermissionRequest = { toolCall, options ->
            handlePermissionRequest(toolCall, options, config)
        }

        log.info("Calling acpClient.connect() for '$agentKey'...")
        acpClient.connect()
        log.info("acpClient.connect() completed for '$agentKey'")
        this.client = acpClient
    }

    /**
     * Send a prompt message and stream responses.
     */
    suspend fun sendMessage(text: String, references: List<MessageReference> = emptyList()) {
        log.info("sendMessage called for '$agentKey' with text: ${text.take(50)}...")

        // Use Claude Code client if in Claude mode
        if (isClaudeCodeMode) {
            sendMessageClaudeCode(text, references)
            return
        }

        val acpClient = client ?: throw IllegalStateException("Not connected")

        // Emit user message event with references
        emitRenderEvent(RenderEvent.UserMessage(text, references))
        addMessage(ChatMessage(MessageRole.USER, text, references = references))
        updateState { copy(isProcessing = true, currentStreamingText = "", currentThinkingText = "") }

        // Clear dedup state
        renderedToolCallIds.clear()
        toolCallTitles.clear()
        startedToolCallIds.clear()

        var receivedAnyChunk = false
        var inThought = false
        var inMessage = false
        val messageBuffer = StringBuilder()
        val thoughtBuffer = StringBuilder()

        try {
            log.info("Starting prompt collection for '$agentKey'...")
            acpClient.prompt(text).collect { event ->
                log.info("Received event for '$agentKey': ${event::class.simpleName}")
                when (event) {
                    is Event.SessionUpdateEvent -> {
                        processSessionUpdateWithEvents(
                            event.update,
                            messageBuffer,
                            thoughtBuffer,
                            { receivedAnyChunk },
                            { receivedAnyChunk = it },
                            { inThought },
                            { inThought = it },
                            { inMessage },
                            { inMessage = it },
                        )
                    }

                    is Event.PromptResponseEvent -> {
                        // Finalize thinking
                        if (inThought && thoughtBuffer.isNotBlank()) {
                            emitRenderEvent(RenderEvent.ThinkingEnd(thoughtBuffer.toString()))
                            addMessage(ChatMessage(MessageRole.THINKING, thoughtBuffer.toString(), isThinking = true))
                            thoughtBuffer.clear()
                        }

                        // Finalize message
                        if (messageBuffer.isNotBlank()) {
                            emitRenderEvent(RenderEvent.MessageEnd(messageBuffer.toString()))
                            addMessage(ChatMessage(MessageRole.ASSISTANT, messageBuffer.toString()))
                            messageBuffer.clear()
                        }

                        if (!receivedAnyChunk) {
                            emitRenderEvent(RenderEvent.Info("Agent ended without output (stopReason=${event.response.stopReason})"))
                            addMessage(ChatMessage(MessageRole.INFO, "Agent ended without output (stopReason=${event.response.stopReason})"))
                        }

                        emitRenderEvent(RenderEvent.PromptComplete(event.response.stopReason?.name))
                        updateState {
                            copy(
                                isProcessing = false,
                                currentStreamingText = "",
                                currentThinkingText = "",
                            )
                        }
                    }
                }
            }

            log.info("Prompt collection completed for '$agentKey'")
            acpClient.incrementPromptCount()
        } catch (e: Exception) {
            log.warn("ACP prompt failed for '$agentKey': ${e.message}", e)

            // Finalize any pending content
            if (thoughtBuffer.isNotBlank()) {
                emitRenderEvent(RenderEvent.ThinkingEnd(thoughtBuffer.toString()))
                addMessage(ChatMessage(MessageRole.THINKING, thoughtBuffer.toString(), isThinking = true))
            }
            if (messageBuffer.isNotBlank()) {
                emitRenderEvent(RenderEvent.MessageEnd(messageBuffer.toString()))
                addMessage(ChatMessage(MessageRole.ASSISTANT, messageBuffer.toString()))
            }

            emitRenderEvent(RenderEvent.Error("Error: ${e.message}"))
            addMessage(ChatMessage(MessageRole.ERROR, "Error: ${e.message}"))
            updateState { copy(isProcessing = false, currentStreamingText = "", currentThinkingText = "") }
        }
    }

    /**
     * Send a prompt using Claude Code's stream-json protocol.
     */
    private suspend fun sendMessageClaudeCode(text: String, references: List<MessageReference> = emptyList()) {
        val claudeCodeClient = claudeClient ?: throw IllegalStateException("Claude Code client not connected")

        updateState { copy(isProcessing = true, currentStreamingText = "", currentThinkingText = "") }

        try {
            log.info("Sending prompt to Claude Code for '$agentKey'...")
            claudeCodeClient.sendPrompt(text)
            log.info("Claude Code prompt completed for '$agentKey'")
        } catch (e: Exception) {
            log.warn("Claude Code prompt failed for '$agentKey': ${e.message}", e)
            emitRenderEvent(RenderEvent.Error("Error: ${e.message}"))
            addMessage(ChatMessage(MessageRole.ERROR, "Error: ${e.message}"))
        } finally {
            updateState { copy(isProcessing = false, currentStreamingText = "", currentThinkingText = "") }
        }
    }

    @OptIn(com.agentclientprotocol.annotations.UnstableApi::class)
    private fun processSessionUpdateWithEvents(
        update: SessionUpdate,
        messageBuffer: StringBuilder,
        thoughtBuffer: StringBuilder,
        getReceivedChunk: () -> Boolean,
        setReceivedChunk: (Boolean) -> Unit,
        getInThought: () -> Boolean,
        setInThought: (Boolean) -> Unit,
        getInMessage: () -> Boolean,
        setInMessage: (Boolean) -> Unit,
    ) {
        when (update) {
            is SessionUpdate.AgentMessageChunk -> {
                // Transition from thinking to message
                if (getInThought() && thoughtBuffer.isNotBlank()) {
                    emitRenderEvent(RenderEvent.ThinkingEnd(thoughtBuffer.toString()))
                    addMessage(ChatMessage(MessageRole.THINKING, thoughtBuffer.toString(), isThinking = true))
                    thoughtBuffer.clear()
                    setInThought(false)
                    updateState { copy(currentThinkingText = "") }
                }

                // Start message streaming if not already
                if (!getInMessage()) {
                    emitRenderEvent(RenderEvent.MessageStart())
                    setInMessage(true)
                }

                setReceivedChunk(true)
                val text = AcpClient.extractText(update.content)
                messageBuffer.append(text)
                emitRenderEvent(RenderEvent.MessageChunk(text))
                updateState { copy(currentStreamingText = messageBuffer.toString()) }
            }

            is SessionUpdate.AgentThoughtChunk -> {
                // Start thinking if not already
                if (!getInThought()) {
                    emitRenderEvent(RenderEvent.ThinkingStart())
                    setInThought(true)
                }
                val thought = AcpClient.extractText(update.content)
                thoughtBuffer.append(thought)
                emitRenderEvent(RenderEvent.ThinkingChunk(thought))
                updateState { copy(currentThinkingText = thoughtBuffer.toString()) }
            }

            is SessionUpdate.PlanUpdate -> {
                val entries = update.entries.map { entry ->
                    PlanEntry(
                        content = entry.content,
                        status = when (entry.status) {
                            com.agentclientprotocol.model.PlanEntryStatus.COMPLETED -> PlanEntryStatus.COMPLETED
                            com.agentclientprotocol.model.PlanEntryStatus.IN_PROGRESS -> PlanEntryStatus.IN_PROGRESS
                            com.agentclientprotocol.model.PlanEntryStatus.PENDING -> PlanEntryStatus.PENDING
                        }
                    )
                }
                emitRenderEvent(RenderEvent.PlanUpdate(entries))

                val planText = buildString {
                    update.entries.forEachIndexed { index, entry ->
                        val marker = when (entry.status) {
                            com.agentclientprotocol.model.PlanEntryStatus.COMPLETED -> "[x]"
                            com.agentclientprotocol.model.PlanEntryStatus.IN_PROGRESS -> "[*]"
                            com.agentclientprotocol.model.PlanEntryStatus.PENDING -> "[ ]"
                        }
                        appendLine("${index + 1}. $marker ${entry.content}")
                    }
                }.trim()
                if (planText.isNotBlank()) {
                    addMessage(ChatMessage(MessageRole.INFO, "Plan:\n$planText"))
                }
            }

            is SessionUpdate.ToolCall -> handleToolCallUpdateWithEvents(
                update.toolCallId.value, update.title, update.kind?.name, update.status,
                update.rawInput?.toString(), update.rawOutput?.toString()
            )

            is SessionUpdate.ToolCallUpdate -> handleToolCallUpdateWithEvents(
                update.toolCallId.value, update.title, update.kind?.name, update.status,
                update.rawInput?.toString(), update.rawOutput?.toString()
            )

            is SessionUpdate.CurrentModeUpdate -> {
                emitRenderEvent(RenderEvent.ModeChange(update.currentModeId.value))
                addMessage(ChatMessage(MessageRole.INFO, "Mode switched to: ${update.currentModeId.value}"))
            }

            is SessionUpdate.ConfigOptionUpdate -> {
                val summary = update.configOptions.joinToString(", ") { it.name }
                if (summary.isNotBlank()) {
                    emitRenderEvent(RenderEvent.Info("Config updated: $summary"))
                    addMessage(ChatMessage(MessageRole.INFO, "Config updated: $summary"))
                }
            }

            else -> {
                log.debug("Unhandled session update: ${update::class.simpleName}")
            }
        }
    }

    private fun handleToolCallUpdateWithEvents(
        toolCallId: String?,
        title: String?,
        kind: String?,
        status: ToolCallStatus?,
        rawInput: String?,
        rawOutput: String?,
    ) {
        val id = toolCallId ?: ""
        val isTerminal = status == ToolCallStatus.COMPLETED || status == ToolCallStatus.FAILED
        val isRunning = status == ToolCallStatus.IN_PROGRESS || status == ToolCallStatus.PENDING

        // Track best title
        val currentTitle = title?.takeIf { it.isNotBlank() }
        if (id.isNotBlank() && currentTitle != null) {
            toolCallTitles[id] = currentTitle
        }

        // Render first IN_PROGRESS event
        if (isRunning) {
            if (id.isNotBlank() && !startedToolCallIds.contains(id)) {
                val toolTitle = toolCallTitles[id] ?: currentTitle ?: "tool"
                // Emit tool call start event
                emitRenderEvent(RenderEvent.ToolCallStart(
                    toolCallId = id,
                    toolName = kind ?: "tool",
                    title = toolTitle,
                    kind = kind
                ))
                addMessage(ChatMessage(
                    role = MessageRole.TOOL_CALL,
                    content = toolTitle,
                    toolCallId = id,
                    toolCallStatus = status,
                    toolCallKind = kind,
                ))
                startedToolCallIds.add(id)
            } else if (id.isNotBlank()) {
                // Emit update event for status changes
                emitRenderEvent(RenderEvent.ToolCallUpdate(
                    toolCallId = id,
                    status = status ?: ToolCallStatus.IN_PROGRESS,
                    title = currentTitle
                ))
            }
            return
        }

        if (!isTerminal) return

        // Terminal state: render result
        val toolTitle = (if (id.isNotBlank()) toolCallTitles[id] else null) ?: currentTitle ?: "tool"
        val output = rawOutput?.takeIf { it.isNotBlank() } ?: if (status == ToolCallStatus.COMPLETED) "Done" else "Failed"

        // Emit tool call end event
        emitRenderEvent(RenderEvent.ToolCallEnd(
            toolCallId = id,
            status = status ?: ToolCallStatus.COMPLETED,
            title = toolTitle,
            output = output
        ))

        addMessage(ChatMessage(
            role = MessageRole.TOOL_RESULT,
            content = "$toolTitle: $output",
            toolCallId = id,
            toolCallStatus = status,
            toolCallKind = kind,
        ))

        // Clean up
        if (id.isNotBlank()) {
            renderedToolCallIds.add(id)
            toolCallTitles.remove(id)
            startedToolCallIds.remove(id)
        }
    }

    private fun handlePermissionRequest(
        toolCall: SessionUpdate.ToolCallUpdate,
        options: List<PermissionOption>,
        config: AcpAgentConfig,
    ): RequestPermissionResponse {
        // Auto-approve if configured
        if (config.autoApprove) {
            val allow = options.firstOrNull {
                it.kind == PermissionOptionKind.ALLOW_ONCE || it.kind == PermissionOptionKind.ALLOW_ALWAYS
            }
            if (allow != null) {
                return RequestPermissionResponse(RequestPermissionOutcome.Selected(allow.optionId), JsonNull)
            }
        }

        // For now, auto-approve with ALLOW_ONCE
        // TODO: Show permission dialog in UI
        val allow = options.firstOrNull {
            it.kind == PermissionOptionKind.ALLOW_ONCE
        } ?: options.firstOrNull {
            it.kind == PermissionOptionKind.ALLOW_ALWAYS
        }

        return if (allow != null) {
            log.info("Auto-approving permission for tool=${toolCall.title ?: "tool"}")
            RequestPermissionResponse(RequestPermissionOutcome.Selected(allow.optionId), JsonNull)
        } else {
            RequestPermissionResponse(RequestPermissionOutcome.Cancelled, JsonNull)
        }
    }

    /**
     * Cancel the current prompt.
     */
    suspend fun cancelPrompt() {
        try {
            if (isClaudeCodeMode) {
                claudeClient?.stop()
            } else {
                client?.cancel()
            }
            updateState { copy(isProcessing = false) }
            addMessage(ChatMessage(MessageRole.INFO, "Cancelled"))
        } catch (e: Exception) {
            log.warn("Failed to cancel prompt for '$agentKey'", e)
        }
    }

    /**
     * Disconnect from the agent.
     */
    suspend fun disconnect() {
        try {
            stderrJob?.cancel()
            stderrJob = null
            claudeEventJob?.cancel()
            claudeEventJob = null

            if (isClaudeCodeMode) {
                claudeClient?.stop()
                claudeClient = null
                routaMcpServer?.stop()
                routaMcpServer = null
            } else {
                client?.disconnect()
                client = null
            }
        } catch (_: Exception) {
        }
        managedProcess = null
        isClaudeCodeMode = false
        emitRenderEvent(RenderEvent.Disconnected(agentKey))
        updateState { copy(isConnected = false, isProcessing = false) }
    }

    /**
     * Disconnect and terminate the underlying process.
     */
    suspend fun disconnectAndTerminate() {
        disconnect()
        AcpProcessManager.getInstance().terminateProcess(agentKey)
    }

    /**
     * Clear the chat history and render events.
     */
    fun clearMessages() {
        updateState { copy(messages = emptyList(), currentStreamingText = "", currentThinkingText = "") }
    }

    private fun addMessage(message: ChatMessage) {
        updateState { copy(messages = messages + message) }
    }

    private fun emitRenderEvent(event: RenderEvent) {
        _renderEvents.tryEmit(event)
    }

    private inline fun updateState(transform: AgentSessionState.() -> AgentSessionState) {
        _state.update { it.transform() }
    }

    override fun dispose() {
        runBlocking {
            try {
                disconnect()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Project-level service that manages multiple ACP agent sessions.
 *
 * Supports concurrent sessions with different agents, session lifecycle management,
 * and provides observable state for UI binding.
 */
@Service(Service.Level.PROJECT)
class AcpSessionManager(private val project: Project) : Disposable {

    private val sessions = ConcurrentHashMap<String, AgentSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeSessionKey = MutableStateFlow<String?>(null)
    val activeSessionKey: StateFlow<String?> = _activeSessionKey.asStateFlow()

    private val _sessionKeys = MutableStateFlow<List<String>>(emptyList())
    val sessionKeys: StateFlow<List<String>> = _sessionKeys.asStateFlow()

    /**
     * Get or create a session for the given agent key.
     */
    fun getOrCreateSession(agentKey: String): AgentSession {
        return sessions.getOrPut(agentKey) {
            AgentSession(agentKey, project, scope).also {
                updateSessionKeys()
            }
        }
    }

    /**
     * Get an existing session.
     */
    fun getSession(agentKey: String): AgentSession? = sessions[agentKey]

    /**
     * Get the active session.
     */
    fun getActiveSession(): AgentSession? {
        val key = _activeSessionKey.value ?: return null
        return sessions[key]
    }

    /**
     * Set the active session.
     */
    fun setActiveSession(agentKey: String) {
        _activeSessionKey.value = agentKey
    }

    /**
     * Connect to an agent and set it as active.
     */
    suspend fun connectAgent(agentKey: String): AgentSession {
        val configService = AcpConfigService.getInstance(project)
        val config = configService.getAgentConfig(agentKey)
            ?: throw IllegalArgumentException("Agent '$agentKey' not found in config")

        val session = getOrCreateSession(agentKey)
        session.connect(config)
        setActiveSession(agentKey)

        // Always update session keys after successful connection
        // This ensures the UI is notified even if the session already existed
        updateSessionKeys()

        return session
    }

    /**
     * Disconnect from a specific agent.
     */
    suspend fun disconnectAgent(agentKey: String) {
        sessions[agentKey]?.disconnect()
    }

    /**
     * Disconnect and terminate a specific agent.
     */
    suspend fun disconnectAndTerminate(agentKey: String) {
        sessions[agentKey]?.disconnectAndTerminate()
    }

    /**
     * Remove a session entirely.
     */
    suspend fun removeSession(agentKey: String) {
        val session = sessions.remove(agentKey) ?: return
        session.disconnect()
        session.dispose()
        updateSessionKeys()
        if (_activeSessionKey.value == agentKey) {
            _activeSessionKey.value = sessions.keys.firstOrNull()
        }
    }

    /**
     * Get all session keys.
     */
    fun getAllSessionKeys(): List<String> = sessions.keys.toList()

    /**
     * Get all connected session keys.
     */
    fun getConnectedSessionKeys(): List<String> {
        return sessions.entries
            .filter { it.value.isConnected }
            .map { it.key }
    }

    private fun updateSessionKeys() {
        val keys = sessions.keys.toList()
        log.info("AcpSessionManager: updateSessionKeys called, keys=$keys")
        _sessionKeys.value = keys
    }

    override fun dispose() {
        runBlocking {
            sessions.values.forEach { session ->
                try {
                    session.disconnect()
                    session.dispose()
                } catch (_: Exception) {
                }
            }
        }
        sessions.clear()
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): AcpSessionManager = project.service()
    }
}
