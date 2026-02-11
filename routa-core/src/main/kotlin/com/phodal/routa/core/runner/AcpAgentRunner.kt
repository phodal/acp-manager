package com.phodal.routa.core.runner

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.SessionUpdate
import com.phodal.routa.core.acp.AcpProcessManager
import com.phodal.routa.core.acp.RoutaAcpClient
import com.phodal.routa.core.config.AcpAgentConfig
import com.phodal.routa.core.model.AgentRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.asSink
import kotlinx.io.asSource

/**
 * Agent runner that uses ACP protocol to spawn a real AI agent process.
 *
 * When used as the CRAFTER backend, this spawns an actual coding agent
 * (Codex, Claude Code, Kimi CLI, etc.) via ACP protocol. The agent
 * can read/write files, run terminal commands, and do real implementation work.
 *
 * Usage:
 * ```kotlin
 * val runner = AcpAgentRunner(
 *     agentKey = "codex",
 *     config = AcpAgentConfig(command = "codex", args = listOf("--full-auto")),
 *     cwd = "/path/to/project"
 * )
 * val output = runner.run(AgentRole.CRAFTER, crafterId, taskPrompt)
 * ```
 */
class AcpAgentRunner(
    private val agentKey: String,
    private val config: AcpAgentConfig,
    private val cwd: String,
    private val onUpdate: ((String) -> Unit)? = null,
) : AgentRunner {

    private val processManager = AcpProcessManager.getInstance()

    override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val processKey = "$agentKey-$agentId"

        // Spawn (or reuse) the agent process
        val managed = processManager.getOrCreateProcess(
            agentKey = processKey,
            command = config.getCommandLine(),
            cwd = cwd,
            env = config.env,
        )

        // Connect via ACP protocol
        val client = RoutaAcpClient(
            coroutineScope = scope,
            input = managed.inputStream.asSource(),
            output = managed.outputStream.asSink(),
            cwd = cwd,
            agentName = agentKey,
        )

        val resultBuilder = StringBuilder()

        client.onSessionUpdate = { update ->
            when (update) {
                is SessionUpdate.AgentMessageChunk -> {
                    val text = RoutaAcpClient.extractText(update.content)
                    resultBuilder.append(text)
                    onUpdate?.invoke(text)
                }
                is SessionUpdate.ToolCallUpdate -> {
                    val toolName = update.title ?: "tool"
                    onUpdate?.invoke("[Tool: $toolName (${update.status})]\n")
                }
                else -> { /* Plan updates, mode changes, thought chunks, etc. */ }
            }
        }

        try {
            client.connect()

            // Send the task prompt
            client.prompt(prompt).collect { event ->
                when (event) {
                    is Event.SessionUpdateEvent -> {
                        // Already handled by onSessionUpdate callback
                    }
                    is Event.PromptResponseEvent -> {
                        // Response complete
                        val stopReason = event.response.stopReason
                        onUpdate?.invoke("\n[ACP agent finished: $stopReason]\n")
                    }
                }
            }
        } finally {
            client.disconnect()
            processManager.terminateProcess(processKey)
            scope.coroutineContext[Job]?.cancel()
        }

        return resultBuilder.toString().ifEmpty {
            "[ACP agent completed without text output]"
        }
    }
}
