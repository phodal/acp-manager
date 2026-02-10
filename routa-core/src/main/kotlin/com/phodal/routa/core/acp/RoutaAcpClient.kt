package com.phodal.routa.core.acp

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonNull

/**
 * ACP Client for routa-core — platform-independent version.
 *
 * Connects to an ACP agent over stdio (JSON-RPC), creates a session,
 * and sends prompts with streaming event collection.
 *
 * Extracted from the IntelliJ plugin, with no IDE dependencies.
 */
class RoutaAcpClient(
    private val coroutineScope: CoroutineScope,
    private val input: RawSource,
    private val output: RawSink,
    private val clientName: String = "routa-core",
    private val clientVersion: String = "0.1.0",
    private val cwd: String = "",
    private val agentName: String = "acp-agent",
) {
    private var protocol: Protocol? = null
    private var client: Client? = null
    private var session: ClientSession? = null

    val isConnected: Boolean get() = session != null

    /**
     * Callback for session update notifications.
     */
    var onSessionUpdate: ((SessionUpdate) -> Unit)? = null

    /**
     * Connect to the ACP agent: transport → protocol → session.
     */
    suspend fun connect() {
        val transport = StdioTransport(
            parentScope = coroutineScope,
            ioDispatcher = Dispatchers.Default,
            input = input.buffered(),
            output = output.buffered(),
            name = clientName
        )
        val proto = Protocol(coroutineScope, transport)
        proto.start()

        val acpClient = Client(proto)
        this.protocol = proto
        this.client = acpClient

        val clientInfo = ClientInfo(
            protocolVersion = 1,
            capabilities = ClientCapabilities(
                fs = FileSystemCapability(
                    readTextFile = true,
                    writeTextFile = true,
                    _meta = JsonNull
                ),
                terminal = true,
                _meta = JsonNull
            ),
            implementation = Implementation(
                name = clientName,
                version = clientVersion,
                title = "Routa Multi-Agent Orchestrator",
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
                return RoutaAcpSessionOps(
                    onSessionUpdate = { update -> onSessionUpdate?.invoke(update) },
                    cwd = cwd,
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
    }

    /**
     * Send a prompt to the agent and collect streaming events.
     */
    fun prompt(text: String): Flow<Event> = flow {
        val sess = session ?: throw IllegalStateException("ACP client not connected")
        val contentBlocks = listOf(ContentBlock.Text(text, Annotations(), JsonNull))
        val eventFlow = sess.prompt(contentBlocks, JsonNull)
        eventFlow.collect { event -> emit(event) }
    }

    /**
     * Disconnect and clean up.
     */
    suspend fun disconnect() {
        try {
            protocol?.close()
        } catch (_: Exception) {
        }
        protocol = null
        client = null
        session = null
    }

    companion object {
        /**
         * Extract text from an ACP ContentBlock.
         */
        fun extractText(block: ContentBlock): String = when (block) {
            is ContentBlock.Text -> block.text
            is ContentBlock.Resource -> block.resource.toString().take(500)
            is ContentBlock.ResourceLink -> "[ResourceLink: ${block.name}]"
            is ContentBlock.Image -> "[Image]"
            is ContentBlock.Audio -> "[Audio]"
        }

        /**
         * Default permission response: auto-approve.
         */
        fun defaultPermissionResponse(options: List<PermissionOption>): RequestPermissionResponse {
            val allow = options.firstOrNull {
                it.kind == PermissionOptionKind.ALLOW_ONCE || it.kind == PermissionOptionKind.ALLOW_ALWAYS
            }
            return if (allow != null) {
                RequestPermissionResponse(RequestPermissionOutcome.Selected(allow.optionId), JsonNull)
            } else {
                RequestPermissionResponse(RequestPermissionOutcome.Cancelled, JsonNull)
            }
        }
    }
}
