package com.github.phodal.acpmanager.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonNull

private val log = logger<AcpClient>()

/**
 * ACP Client that connects to external ACP agents (e.g., Claude CLI, Codex CLI, Gemini CLI).
 *
 * Handles the full ACP lifecycle: transport setup, protocol initialization, session creation,
 * and prompt streaming.
 */
class AcpClient(
    private val coroutineScope: CoroutineScope,
    private val input: RawSource,
    private val output: RawSink,
    private val clientName: String = "acp-manager",
    private val clientVersion: String = "0.0.1",
    private val cwd: String = "",
    private val agentName: String = "acp-agent",
) {
    private var protocol: Protocol? = null
    private var client: Client? = null
    private var session: ClientSession? = null

    val isConnected: Boolean get() = session != null

    var promptCount: Int = 0
        private set

    /**
     * Callback for session update notifications.
     */
    var onSessionUpdate: ((SessionUpdate) -> Unit)? = null

    /**
     * Callback for permission requests from the agent.
     */
    var onPermissionRequest: ((SessionUpdate.ToolCallUpdate, List<PermissionOption>) -> RequestPermissionResponse)? = null

    /**
     * Connect to the ACP agent: set up transport, initialize protocol, create session.
     */
    suspend fun connect() {
        val transport = StdioTransport(
            parentScope = coroutineScope,
            ioDispatcher = Dispatchers.IO,
            input = input.buffered(),
            output = output.buffered(),
            name = clientName
        )
        val proto = Protocol(coroutineScope, transport)
        proto.start()

        val acpClient = Client(proto)
        this.protocol = proto
        this.client = acpClient

        val fsCapabilities = FileSystemCapability(
            readTextFile = true,
            writeTextFile = true,
            _meta = JsonNull
        )

        val clientInfo = ClientInfo(
            protocolVersion = 1,
            capabilities = ClientCapabilities(
                fs = fsCapabilities,
                terminal = true,
                _meta = JsonNull
            ),
            implementation = Implementation(
                name = clientName,
                version = clientVersion,
                title = "ACP Manager (IntelliJ Plugin)",
                _meta = JsonNull
            ),
            _meta = JsonNull
        )

        acpClient.initialize(clientInfo, JsonNull)

        val operationsFactory = object : ClientOperationsFactory {
            override suspend fun createClientOperations(
                sessionId: SessionId,
                sessionResponse: AcpCreatedSessionResponse,
            ): ClientSessionOperations {
                return AcpClientSessionOps(
                    onSessionUpdate = { update -> onSessionUpdate?.invoke(update) },
                    onPermissionRequest = { toolCall, options ->
                        onPermissionRequest?.invoke(toolCall, options)
                            ?: defaultPermissionResponse(options)
                    },
                    cwd = cwd,
                    enableFs = true,
                    enableTerminal = true,
                )
            }
        }

        val acpSession = acpClient.newSession(
            SessionCreationParameters(
                cwd = cwd,
                mcpServers = emptyList(),
                _meta = JsonNull
            ),
            operationsFactory
        )
        this.session = acpSession

        log.info("ACP client connected successfully (session=${acpSession.sessionId}, agent=$agentName)")
    }

    /**
     * Send a prompt to the agent and collect streaming events.
     */
    fun prompt(text: String): Flow<Event> = flow {
        val sess = session ?: throw IllegalStateException("ACP client not connected")

        val contentBlocks = listOf(ContentBlock.Text(text, Annotations(), JsonNull))
        val eventFlow = sess.prompt(contentBlocks, JsonNull)

        eventFlow.collect { event ->
            emit(event)
        }
    }

    /**
     * Cancel the current prompt turn.
     */
    suspend fun cancel() {
        try {
            session?.cancel()
        } catch (e: Exception) {
            log.warn("Failed to cancel ACP session", e)
        }
    }

    /**
     * Disconnect from the agent and clean up resources.
     */
    suspend fun disconnect() {
        try {
            protocol?.close()
        } catch (_: Exception) {
        }
        protocol = null
        client = null
        session = null
        log.info("ACP client disconnected")
    }

    /**
     * Increment prompt count (called after successful prompt).
     */
    fun incrementPromptCount() {
        promptCount++
    }

    companion object {
        private fun defaultPermissionResponse(
            options: List<PermissionOption>,
        ): RequestPermissionResponse {
            val allow = options.firstOrNull {
                it.kind == PermissionOptionKind.ALLOW_ONCE || it.kind == PermissionOptionKind.ALLOW_ALWAYS
            }
            return if (allow != null) {
                RequestPermissionResponse(RequestPermissionOutcome.Selected(allow.optionId), JsonNull)
            } else {
                RequestPermissionResponse(RequestPermissionOutcome.Cancelled, JsonNull)
            }
        }

        /**
         * Extract text content from an ACP ContentBlock.
         */
        fun extractText(block: ContentBlock): String {
            return when (block) {
                is ContentBlock.Text -> block.text
                is ContentBlock.Resource -> {
                    val resourceStr = block.resource.toString()
                    if (resourceStr.length > 500) "[Resource: ${resourceStr.take(500)}...]"
                    else resourceStr
                }
                is ContentBlock.ResourceLink -> "[Resource Link: ${block.name} (${block.uri})]"
                is ContentBlock.Image -> "[Image: mimeType=${block.mimeType}]"
                is ContentBlock.Audio -> "[Audio: mimeType=${block.mimeType}]"
            }
        }
    }
}
