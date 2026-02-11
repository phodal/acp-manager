package com.github.phodal.acpmanager.dispatcher.ui

import com.github.phodal.acpmanager.ui.renderer.AcpEventRenderer
import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.provider.ThinkingPhase
import com.phodal.routa.core.provider.ToolCallStatus

/**
 * Converts [StreamChunk] (from routa-core) to [RenderEvent] and dispatches
 * to an [AcpEventRenderer].
 *
 * This adapter bridges the gap between the routa streaming protocol and
 * the UI renderer event model. It is reused by every agent panel
 * (ROUTA, CRAFTER-N, GATE) so that all agents share the same rendering logic.
 *
 * Extracted from the former `CrafterDetailPanel.appendChunk()`.
 */
class StreamChunkAdapter(
    private val renderer: AcpEventRenderer,
    private val agentId: String = "unknown"
) {

    // State tracking for StreamChunk → RenderEvent conversion
    private var messageStarted = false
    private val messageBuffer = StringBuilder()
    private var toolCallCounter = 0
    private var currentToolCallId: String? = null

    /**
     * Feed a [StreamChunk] into the renderer.
     *
     * @param chunk The streaming chunk from routa-core.
     * @return An optional completion callback (currently used for status updates).
     */
    fun appendChunk(chunk: StreamChunk) {
        // Log for debugging
        EventLogger.logStreamChunk(agentId, chunk)

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
     */
    fun finalizeMessage() {
        if (messageStarted) {
            renderer.onEvent(RenderEvent.MessageEnd(messageBuffer.toString()))
            messageStarted = false
            messageBuffer.clear()
        }
    }

    /**
     * Clear the adapter state and the underlying renderer.
     */
    fun clear() {
        finalizeMessage()
        messageStarted = false
        messageBuffer.clear()
        toolCallCounter = 0
        currentToolCallId = null
        renderer.clear()
    }
}
