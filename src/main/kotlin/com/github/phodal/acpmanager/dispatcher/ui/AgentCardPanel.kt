package com.github.phodal.acpmanager.dispatcher.ui

import com.github.phodal.acpmanager.claudecode.CrafterRenderer
import com.github.phodal.acpmanager.ui.renderer.AcpEventRenderer
import com.intellij.ui.JBColor
import com.phodal.routa.core.provider.StreamChunk
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Unified agent panel that wraps an [AcpEventRenderer] + [StreamChunkAdapter].
 *
 * Every agent in the dispatcher (ROUTA, each CRAFTER task, GATE) gets one
 * [AgentCardPanel]. It owns:
 * - A [CrafterRenderer] for rendering streaming events
 * - A [StreamChunkAdapter] that converts [StreamChunk] to [RenderEvent]
 * - A [JScrollPane] that wraps the renderer's container
 *
 * The scroll pane is the component shown in the left panel when this agent
 * is selected in the sidebar.
 */
class AgentCardPanel(
    val agentId: String,
    val role: AgentRole,
) : JPanel(BorderLayout()) {

    /** The role of an agent in the DAG. */
    enum class AgentRole {
        ROUTA, CRAFTER, GATE
    }

    companion object {
        val BG_COLOR = JBColor(0x0D1117, 0x0D1117)
    }

    val renderer: AcpEventRenderer = CrafterRenderer(
        agentKey = agentId,
        scrollCallback = {
            SwingUtilities.invokeLater {
                val vertical = rendererScroll.verticalScrollBar
                vertical.value = vertical.maximum
            }
        }
    )

    private val adapter = StreamChunkAdapter(renderer, agentId)

    val rendererScroll: JScrollPane = JScrollPane(renderer.container).apply {
        border = javax.swing.BorderFactory.createEmptyBorder()
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        viewport.background = BG_COLOR
    }

    // ── Status tracking ──────────────────────────────────────────────────

    /** Human-readable title (task title for CRAFTERs, role name for ROUTA/GATE). */
    var title: String = when (role) {
        AgentRole.ROUTA -> "ROUTA"
        AgentRole.CRAFTER -> "CRAFTER"
        AgentRole.GATE -> "GATE"
    }
        private set

    /** Current status text (e.g. "PLANNING", "ACTIVE", "COMPLETED"). */
    var statusText: String = "IDLE"
        private set

    /** Current status color. */
    var statusColor: java.awt.Color = JBColor(0x6B7280, 0x6B7280)
        private set

    init {
        isOpaque = true
        background = BG_COLOR
        add(rendererScroll, BorderLayout.CENTER)
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Append a streaming chunk to this agent's renderer.
     */
    fun appendChunk(chunk: StreamChunk) {
        SwingUtilities.invokeLater {
            adapter.appendChunk(chunk)
        }
    }

    /**
     * Update the displayed status of this agent.
     */
    fun updateStatus(text: String, color: java.awt.Color) {
        statusText = text
        statusColor = color
    }

    /**
     * Update the title (primarily for CRAFTERs when task title arrives).
     */
    fun updateTitle(newTitle: String) {
        title = newTitle
    }

    /**
     * Clear the renderer and reset state.
     */
    fun clear() {
        SwingUtilities.invokeLater {
            adapter.clear()
            statusText = "IDLE"
            statusColor = JBColor(0x6B7280, 0x6B7280)
        }
    }
}
