package com.phodal.routa.core.koog

/**
 * Streaming filter that intercepts `<tool_call>` XML blocks from LLM output.
 *
 * As text arrives token-by-token during LLM streaming, this filter:
 * - Emits clean text (outside tool call blocks) via [onText]
 * - Buffers text inside `<tool_call>...</tool_call>` blocks
 * - When a complete block is found, parses it and returns the [ToolCallExtractor.ToolCall]
 *
 * This prevents raw XML from showing in the UI. Instead, the caller can emit
 * proper `StreamChunk.ToolCall` events that render as nice collapsible panels.
 *
 * ## Usage
 *
 * ```kotlin
 * val filter = ToolCallStreamFilter()
 *
 * executor.executeStreaming(prompt, model).collect { frame ->
 *     when (frame) {
 *         is StreamFrame.Append -> {
 *             val completedCalls = filter.feed(frame.text) { cleanText ->
 *                 onChunk(StreamChunk.Text(cleanText))
 *             }
 *             for (call in completedCalls) {
 *                 onChunk(StreamChunk.ToolCall(call.name, ToolCallStatus.STARTED, ...))
 *             }
 *         }
 *         is StreamFrame.End -> {
 *             filter.flush { remaining -> onChunk(StreamChunk.Text(remaining)) }
 *         }
 *     }
 * }
 * ```
 */
class ToolCallStreamFilter {

    private val fullBuffer = StringBuilder()
    private var lastEmittedPos = 0
    private var inToolCall = false
    private var toolCallStartPos = -1

    companion object {
        private const val OPEN_TAG = "<tool_call>"
        private const val CLOSE_TAG = "</tool_call>"
    }

    /**
     * Feed a chunk of streaming text.
     *
     * @param text The new text chunk from the LLM stream.
     * @param onText Callback for clean text that should be displayed to the user.
     * @return List of complete tool calls found in this chunk (may be empty).
     */
    fun feed(text: String, onText: (String) -> Unit): List<ToolCallExtractor.ToolCall> {
        fullBuffer.append(text)
        val completedToolCalls = mutableListOf<ToolCallExtractor.ToolCall>()
        val current = fullBuffer.toString()

        while (true) {
            if (!inToolCall) {
                // Look for <tool_call> opening tag from where we last emitted
                val tagStart = current.indexOf(OPEN_TAG, lastEmittedPos)

                if (tagStart == -1) {
                    // No opening tag found.
                    // Emit text up to a safe point (leave room for partial tag at the end)
                    val safeEnd = maxOf(lastEmittedPos, current.length - OPEN_TAG.length)
                    if (safeEnd > lastEmittedPos) {
                        onText(current.substring(lastEmittedPos, safeEnd))
                        lastEmittedPos = safeEnd
                    }
                    break
                } else {
                    // Found opening tag — emit the clean text before it
                    if (tagStart > lastEmittedPos) {
                        onText(current.substring(lastEmittedPos, tagStart))
                    }
                    inToolCall = true
                    toolCallStartPos = tagStart
                    lastEmittedPos = tagStart
                }
            }

            if (inToolCall) {
                // Look for </tool_call> closing tag
                val tagEnd = current.indexOf(CLOSE_TAG, toolCallStartPos)

                if (tagEnd == -1) {
                    // Closing tag not found yet — wait for more text
                    break
                } else {
                    // Found complete tool call block
                    val endPos = tagEnd + CLOSE_TAG.length
                    val toolCallBlock = current.substring(toolCallStartPos, endPos)
                    val parsed = ToolCallExtractor.extractToolCalls(toolCallBlock)
                    completedToolCalls.addAll(parsed)

                    lastEmittedPos = endPos
                    inToolCall = false
                    toolCallStartPos = -1
                }
            }
        }

        return completedToolCalls
    }

    /**
     * Flush any remaining buffered text.
     *
     * Call this when streaming ends to emit any text still in the buffer.
     * If we're in the middle of an incomplete `<tool_call>` block (malformed),
     * it will be emitted as regular text.
     *
     * Note: Does NOT reset the filter. Call [getFullText] after flush to get
     * the complete accumulated text, then call [reset] when done.
     */
    fun flush(onText: (String) -> Unit) {
        val current = fullBuffer.toString()
        val remaining = current.substring(lastEmittedPos)
        if (remaining.isNotEmpty()) {
            onText(remaining)
        }
        lastEmittedPos = current.length
    }

    /**
     * Get the full accumulated text from this iteration (including tool call blocks).
     * Useful for building conversation history.
     */
    fun getFullText(): String = fullBuffer.toString()

    /**
     * Reset the filter for a new iteration.
     */
    fun reset() {
        fullBuffer.clear()
        lastEmittedPos = 0
        inToolCall = false
        toolCallStartPos = -1
    }
}
