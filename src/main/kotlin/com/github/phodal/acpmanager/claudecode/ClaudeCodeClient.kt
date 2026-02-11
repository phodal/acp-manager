package com.github.phodal.acpmanager.claudecode

import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.*

private val log = logger<ClaudeCodeClient>()

/**
 * Kotlin client for Claude Code binary.
 *
 * Launches `claude -p --output-format stream-json --input-format stream-json`
 * and communicates via JSON lines over stdio.
 *
 * Emits [RenderEvent] objects for UI rendering.
 */
class ClaudeCodeClient(
    private val scope: CoroutineScope,
    private val binaryPath: String,
    private val workingDirectory: String,
    private val model: String? = null,
    private val permissionMode: String? = null,
    private val additionalArgs: List<String> = emptyList(),
    private val envVars: Map<String, String> = emptyMap(),
    private val allowedTools: List<String> = emptyList(),
    /**
     * MCP config JSON string or file path(s) to pass via `--mcp-config`.
     * Used to connect Claude Code to the RoutaMcpServer for multi-agent coordination.
     * Example: `{"mcpServers":{"routa":{"url":"http://127.0.0.1:12345/sse","type":"sse"}}}`
     */
    private val mcpConfigs: List<String> = emptyList(),
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var sessionId: String? = null

    private val toolUseNames = mutableMapOf<String, String>()
    private val renderedToolIds = mutableSetOf<String>()
    private val toolUseInputs = mutableMapOf<String, Map<String, Any>>()
    private val toolParameterBuffers = mutableMapOf<String, StringBuilder>()

    private val _renderEvents = MutableSharedFlow<RenderEvent>(
        replay = 0,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val renderEvents: SharedFlow<RenderEvent> = _renderEvents.asSharedFlow()

    val isConnected: Boolean get() = process?.isAlive == true

    /**
     * Start the Claude Code process with stream-json mode.
     */
    fun start() {
        val cmd = mutableListOf(binaryPath, "-p")
        cmd.addAll(listOf("--output-format", "stream-json"))
        cmd.addAll(listOf("--input-format", "stream-json"))
        cmd.add("--verbose")
        cmd.add("--include-partial-messages")

        model?.let { cmd.addAll(listOf("--model", it)) }
        val effectivePermissionMode = permissionMode ?: "acceptEdits"
        cmd.addAll(listOf("--permission-mode", effectivePermissionMode))
        cmd.addAll(listOf("--disallowed-tools", "AskUserQuestion"))

        // Add allowed tools for auto-approval
        if (allowedTools.isNotEmpty()) {
            cmd.addAll(listOf("--allowedTools", allowedTools.joinToString(",")))
        }

        // Add MCP server configs (e.g., RoutaMcpServer for multi-agent coordination)
        for (mcpConfig in mcpConfigs) {
            cmd.addAll(listOf("--mcp-config", mcpConfig))
        }

        cmd.addAll(additionalArgs)

        log.info("[ClaudeCode] Starting: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
        pb.directory(File(workingDirectory))
        pb.redirectErrorStream(false)

        envVars.forEach { (k, v) -> pb.environment()[k] = v }
        pb.environment()["PWD"] = workingDirectory

        val proc = pb.start()
        process = proc
        writer = proc.outputStream.bufferedWriter()
        reader = proc.inputStream.bufferedReader()

        // Drain stderr asynchronously
        scope.launch(Dispatchers.IO) {
            try {
                proc.errorStream.bufferedReader().use { err ->
                    err.lineSequence().forEach { line ->
                        log.debug("[ClaudeCode stderr] $line")
                    }
                }
            } catch (_: Exception) { }
        }

        log.info("[ClaudeCode] Process started (pid=${proc.pid()})")
        emitEvent(RenderEvent.Connected("claude-code"))
    }

    /**
     * Send a prompt and emit render events.
     */
    suspend fun sendPrompt(text: String) {
        val proc = process ?: throw IllegalStateException("ClaudeCodeClient not started")
        val w = writer ?: throw IllegalStateException("Writer not available")
        val r = reader ?: throw IllegalStateException("Reader not available")

        // Emit user message
        emitEvent(RenderEvent.UserMessage(text))

        // Send user message to Claude
        val userJson = buildClaudeUserInput(text, sessionId)
        withContext(Dispatchers.IO) {
            w.write(userJson)
            w.newLine()
            w.flush()
        }

        var inThinking = false
        var inText = false
        var hasRenderedStreamContent = false
        val thinkingBuffer = StringBuilder()
        val messageBuffer = StringBuilder()

        // Read response lines until we get a result message
        withContext(Dispatchers.IO) {
            while (proc.isAlive) {
                val line = try { r.readLine() } catch (_: IOException) { null }
                if (line == null) break

                val msg = parseClaudeOutputLine(line) ?: continue
                processMessage(msg, thinkingBuffer, messageBuffer,
                    { inThinking }, { inThinking = it },
                    { inText }, { inText = it },
                    { hasRenderedStreamContent }, { hasRenderedStreamContent = it }
                )

                if (msg.type == ClaudeMessageType.RESULT) break
            }
        }

        if (process?.isAlive != true) {
            emitEvent(RenderEvent.Error("Claude Code process exited unexpectedly"))
        }
    }

    private fun emitEvent(event: RenderEvent) {
        _renderEvents.tryEmit(event)
    }

    @Suppress("LongParameterList")
    private fun processMessage(
        msg: ClaudeOutputMessage,
        thinkingBuffer: StringBuilder,
        messageBuffer: StringBuilder,
        getInThinking: () -> Boolean,
        setInThinking: (Boolean) -> Unit,
        getInText: () -> Boolean,
        setInText: (Boolean) -> Unit,
        getHasRendered: () -> Boolean,
        setHasRendered: (Boolean) -> Unit,
    ) {
        when (msg.type) {
            ClaudeMessageType.SYSTEM -> {
                if (msg.subtype == "init") {
                    sessionId = msg.sessionId
                    log.info("[ClaudeCode] Initialized (session=$sessionId)")
                }
            }

            ClaudeMessageType.STREAM_EVENT -> {
                val event = msg.streamEvent ?: return
                processStreamEvent(event, thinkingBuffer, messageBuffer,
                    getInThinking, setInThinking, getInText, setInText, setHasRendered)
            }

            ClaudeMessageType.ASSISTANT -> {
                for (c in msg.content) {
                    if (c.type == "tool_use") {
                        val toolId = c.id ?: ""
                        val toolName = c.name ?: "unknown"
                        toolUseNames[toolId] = toolName

                        val inputMap = c.input?.let { parseJsonToMap(it) } ?: emptyMap()
                        toolUseInputs[toolId] = inputMap

                        if (!renderedToolIds.contains(toolId)) {
                            val mappedName = mapClaudeToolName(toolName)
                            emitEvent(RenderEvent.ToolCallStart(
                                toolCallId = toolId,
                                toolName = mappedName,
                                title = formatToolTitle(toolName, inputMap),
                                kind = toolName
                            ))
                            renderedToolIds.add(toolId)
                        }
                    }
                }
            }

            ClaudeMessageType.USER -> {
                for (c in msg.content) {
                    if (c.type == "tool_result") {
                        val toolId = c.toolUseId ?: ""
                        val toolName = toolUseNames[toolId] ?: "unknown"
                        val mappedName = mapClaudeToolName(toolName)
                        val isErr = c.isError == true
                        val output = extractToolResultText(c)
                        val status = if (isErr) {
                            com.agentclientprotocol.model.ToolCallStatus.FAILED
                        } else {
                            com.agentclientprotocol.model.ToolCallStatus.COMPLETED
                        }
                        emitEvent(RenderEvent.ToolCallEnd(
                            toolCallId = toolId,
                            status = status,
                            title = toolName,
                            output = output
                        ))
                    }
                }
            }

            ClaudeMessageType.RESULT -> {
                val resultText = msg.result ?: ""
                if (resultText.isNotEmpty() && !getHasRendered()) {
                    emitEvent(RenderEvent.MessageStart())
                    emitEvent(RenderEvent.MessageChunk(resultText))
                    emitEvent(RenderEvent.MessageEnd(resultText))
                }
                emitEvent(RenderEvent.PromptComplete(msg.subtype))
            }

            ClaudeMessageType.UNKNOWN -> {
                log.debug("[ClaudeCode] Unknown message type: ${msg.rawJson}")
            }
        }
    }

    @Suppress("LongParameterList")
    private fun processStreamEvent(
        event: ClaudeStreamEvent,
        thinkingBuffer: StringBuilder,
        messageBuffer: StringBuilder,
        getInThinking: () -> Boolean,
        setInThinking: (Boolean) -> Unit,
        getInText: () -> Boolean,
        setInText: (Boolean) -> Unit,
        setHasRendered: (Boolean) -> Unit,
    ) {
        when (event.type) {
            "content_block_start" -> {
                val block = event.contentBlock ?: return
                when (block.type) {
                    "thinking" -> {
                        setInThinking(true)
                        emitEvent(RenderEvent.ThinkingStart())
                    }
                    "text" -> {
                        setInText(true)
                        emitEvent(RenderEvent.MessageStart())
                    }
                    "tool_use" -> {
                        val toolId = block.id ?: ""
                        val toolName = block.name ?: "unknown"
                        toolUseNames[toolId] = toolName
                    }
                }
            }

            "content_block_delta" -> {
                val delta = event.delta ?: return
                when (delta.type) {
                    "thinking_delta" -> {
                        delta.thinking?.let {
                            setHasRendered(true)
                            thinkingBuffer.append(it)
                            emitEvent(RenderEvent.ThinkingChunk(it))
                        }
                    }
                    "text_delta" -> {
                        delta.text?.let {
                            setHasRendered(true)
                            messageBuffer.append(it)
                            emitEvent(RenderEvent.MessageChunk(it))
                        }
                    }
                    "input_json_delta" -> {
                        delta.partialJson?.let { partialJson ->
                            setHasRendered(true)
                            val index = event.index ?: return
                            val toolId = "tool_$index"
                            val buffer = toolParameterBuffers.getOrPut(toolId) { StringBuilder() }
                            buffer.append(partialJson)
                            emitEvent(RenderEvent.ToolCallParameterUpdate(
                                toolCallId = toolId,
                                partialParameters = buffer.toString()
                            ))
                        }
                    }
                    "signature_delta" -> {
                        delta.signature?.let { signature ->
                            emitEvent(RenderEvent.ThinkingSignature(signature))
                        }
                    }
                }
            }

            "content_block_stop" -> {
                if (getInThinking()) {
                    setInThinking(false)
                    emitEvent(RenderEvent.ThinkingEnd(thinkingBuffer.toString()))
                    thinkingBuffer.clear()
                }
                if (getInText()) {
                    setInText(false)
                    emitEvent(RenderEvent.MessageEnd(messageBuffer.toString()))
                    messageBuffer.clear()
                }
            }
        }
    }

    private fun formatToolTitle(toolName: String, params: Map<String, Any>): String {
        return when (toolName) {
            "Read", "Write", "Edit" -> {
                val path = params["file_path"] ?: params["path"] ?: ""
                "$toolName: $path"
            }
            "Bash" -> {
                val cmd = params["command"]?.toString()?.take(50) ?: ""
                "Bash: $cmd"
            }
            "Task" -> {
                val desc = params["description"]?.toString() ?: ""
                val subType = params["subagent_type"]?.toString() ?: ""
                if (desc.isNotEmpty()) {
                    if (subType.isNotEmpty()) "Task [$subType]: $desc" else "Task: $desc"
                } else {
                    "Task"
                }
            }
            else -> toolName
        }
    }

    /**
     * Stop the Claude Code process.
     */
    fun stop() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroyForcibly() } catch (_: Exception) {}

        writer = null
        reader = null
        process = null
        sessionId = null
        toolUseNames.clear()
        toolUseInputs.clear()
        renderedToolIds.clear()

        emitEvent(RenderEvent.Disconnected("claude-code"))
        log.info("[ClaudeCode] Stopped")
    }
}

