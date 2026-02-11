package com.github.phodal.acpmanager.dispatcher.ui

import com.github.phodal.acpmanager.ui.renderer.RenderEvent
import com.intellij.openapi.diagnostic.logger
import com.phodal.routa.core.provider.StreamChunk
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val log = logger<EventLogger>()

/**
 * Logs all RenderEvent and StreamChunk events to a debug file for troubleshooting.
 *
 * When enabled, creates timestamped log files in ~/.acp-manager/logs/
 * to help diagnose missing messages or rendering issues.
 */
object EventLogger {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    private var logFile: File? = null
    private var isEnabled = false

    /**
     * Enable logging to file. Creates a new log file with timestamp.
     */
    fun enable() {
        if (isEnabled) return

        try {
            val logDir = File(System.getProperty("user.home"), ".acp-manager/logs").apply {
                mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            logFile = File(logDir, "dispatcher_events_$timestamp.log")

            logFile?.writeText("=== Dispatcher Event Log Started at ${dateFormat.format(Date())} ===\n\n")
            isEnabled = true

            log.info("EventLogger enabled: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            log.warn("Failed to enable EventLogger: ${e.message}", e)
        }
    }

    /**
     * Disable logging.
     */
    fun disable() {
        if (!isEnabled) return

        try {
            logFile?.appendText("\n=== Dispatcher Event Log Ended at ${dateFormat.format(Date())} ===\n")
            isEnabled = false
            logFile = null
            log.info("EventLogger disabled")
        } catch (e: Exception) {
            log.warn("Failed to disable EventLogger: ${e.message}", e)
        }
    }

    /**
     * Log a RenderEvent.
     */
    fun logRenderEvent(agentId: String, event: RenderEvent) {
        if (!isEnabled) return

        try {
            val timestamp = dateFormat.format(Date())
            val eventInfo = buildString {
                append("[$timestamp] [RENDER] [$agentId] ${event::class.simpleName}")

                when (event) {
                    is RenderEvent.MessageStart -> append(" ()")
                    is RenderEvent.MessageChunk -> {
                        val preview = event.content.take(60).replace("\n", "\\n")
                        val suffix = if (event.content.length > 60) "..." else ""
                        append(" (${event.content.length} chars): \"$preview$suffix\"")
                    }
                    is RenderEvent.MessageEnd -> {
                        val preview = event.fullContent.take(60).replace("\n", "\\n")
                        val suffix = if (event.fullContent.length > 60) "..." else ""
                        append(" (total ${event.fullContent.length} chars): \"$preview$suffix\"")
                    }
                    is RenderEvent.ThinkingStart -> append(" ()")
                    is RenderEvent.ThinkingChunk -> {
                        val preview = event.content.take(60).replace("\n", "\\n")
                        val suffix = if (event.content.length > 60) "..." else ""
                        append(" (${event.content.length} chars): \"$preview$suffix\"")
                    }
                    is RenderEvent.ThinkingEnd -> {
                        val preview = event.fullContent.take(60).replace("\n", "\\n")
                        val suffix = if (event.fullContent.length > 60) "..." else ""
                        append(" (total ${event.fullContent.length} chars): \"$preview$suffix\"")
                    }
                    is RenderEvent.ToolCallStart -> append(" (tool=${event.toolName}, id=${event.toolCallId})")
                    is RenderEvent.ToolCallUpdate -> append(" (id=${event.toolCallId}, status=${event.status})")
                    is RenderEvent.ToolCallParameterUpdate -> {
                        val preview = event.partialParameters.take(100).replace("\n", " ")
                        val suffix = if (event.partialParameters.length > 100) "..." else ""
                        append(" (id=${event.toolCallId}, params: \"$preview$suffix\")")
                    }
                    is RenderEvent.ToolCallEnd -> {
                        val outputPreview = event.output?.take(100)?.replace("\n", " ") ?: ""
                        val suffix = if ((event.output?.length ?: 0) > 100) "..." else ""
                        append(" (id=${event.toolCallId}, status=${event.status}, output: \"$outputPreview$suffix\")")
                    }
                    is RenderEvent.Info -> append(" (${event.message})")
                    is RenderEvent.Error -> append(" (${event.message})")
                    is RenderEvent.PromptComplete -> append(" (${event.stopReason})")
                    else -> append(" (${event.timestamp})")
                }
            }

            logFile?.appendText("$eventInfo\n")
        } catch (e: Exception) {
            // Silently fail to avoid disrupting the UI
            log.debug("Failed to log RenderEvent: ${e.message}")
        }
    }

    /**
     * Log a StreamChunk.
     */
    fun logStreamChunk(agentId: String, chunk: StreamChunk) {
        if (!isEnabled) return

        try {
            val timestamp = dateFormat.format(Date())
            val chunkInfo = buildString {
                append("[$timestamp] [STREAM] [$agentId] ${chunk::class.simpleName}")

                when (chunk) {
                    is StreamChunk.Text -> {
                        val preview = chunk.content.take(60).replace("\n", "\\n")
                        val suffix = if (chunk.content.length > 60) "..." else ""
                        append(" (${chunk.content.length} chars): \"$preview$suffix\"")
                    }
                    is StreamChunk.Thinking -> {
                        val preview = chunk.content.take(60).replace("\n", "\\n")
                        val suffix = if (chunk.content.length > 60) "..." else ""
                        append(" (phase=${chunk.phase}, ${chunk.content.length} chars): \"$preview$suffix\"")
                    }
                    is StreamChunk.ToolCall -> {
                        val argsPreview = chunk.arguments?.take(100)?.replace("\n", " ") ?: ""
                        val argsSuffix = if ((chunk.arguments?.length ?: 0) > 100) "..." else ""
                        append(" (name=${chunk.name}, status=${chunk.status}, args: \"$argsPreview$argsSuffix\")")
                    }
                    is StreamChunk.Error -> append(" (${chunk.message})")
                    is StreamChunk.Completed -> append(" (${chunk.stopReason})")
                    is StreamChunk.CompletionReport -> append(" (success=${chunk.success}, summary=${chunk.summary})")
                    else -> {}
                }
            }

            logFile?.appendText("$chunkInfo\n")
        } catch (e: Exception) {
            log.debug("Failed to log StreamChunk: ${e.message}")
        }
    }

    /**
     * Log a plain text message.
     */
    fun log(message: String) {
        if (!isEnabled) return

        try {
            val timestamp = dateFormat.format(Date())
            logFile?.appendText("[$timestamp] [INFO] $message\n")
        } catch (e: Exception) {
            log.debug("Failed to log message: ${e.message}")
        }
    }
}
