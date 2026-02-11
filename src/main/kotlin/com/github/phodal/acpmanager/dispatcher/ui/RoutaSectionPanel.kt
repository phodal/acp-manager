package com.github.phodal.acpmanager.dispatcher.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.phodal.routa.core.coordinator.CoordinationPhase
import com.phodal.routa.core.provider.StreamChunk
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Routa (Coordinator) section panel — the top section of the DAG UI.
 *
 * Shows:
 * - Header: "ROUTA" label + phase indicator + status dot + LLM model selector
 * - Collapsible streaming area: planning output in real-time
 * - Plan summary when planning is complete
 *
 * ROUTA uses KoogAgent (LLM) for planning, so the model selector
 * allows choosing which LLM model to use (from ~/.autodev/config.yaml).
 *
 * Compact by default, expandable to show full planning output.
 */
class RoutaSectionPanel : JPanel(BorderLayout()) {

    // ── Phase Colors ──────────────────────────────────────────────────
    companion object {
        val ROUTA_ACCENT = JBColor(0x58A6FF, 0x58A6FF)
        val PHASE_PLANNING = JBColor(0xF59E0B, 0xF59E0B)
        val PHASE_READY = JBColor(0x3B82F6, 0x3B82F6)
        val PHASE_EXECUTING = JBColor(0x10B981, 0x10B981)
        val PHASE_COMPLETED = JBColor(0x10B981, 0x10B981)
        val PHASE_FAILED = JBColor(0xEF4444, 0xEF4444)
        val PHASE_IDLE = JBColor(0x6B7280, 0x9CA3AF)
    }

    private val phaseLabel = JBLabel("IDLE").apply {
        foreground = PHASE_IDLE
        font = font.deriveFont(Font.BOLD, 10f)
    }

    private val statusDot = JBLabel("●").apply {
        foreground = PHASE_IDLE
        font = font.deriveFont(10f)
    }

    private val previewLabel = JBLabel("").apply {
        foreground = JBColor(0x8B949E, 0x8B949E)
        font = Font("Monospaced", Font.PLAIN, 10)
    }

    // LLM model selector for KoogAgent
    private val modelCombo = JComboBox<String>().apply {
        preferredSize = Dimension(180, 24)
        font = font.deriveFont(11f)
        toolTipText = "LLM Model for ROUTA planning (KoogAgent)"
    }

    /** Callback when the LLM model is changed. */
    var onModelChanged: (String) -> Unit = {}

    // Streaming output area
    private val outputArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = JBColor(0x0D1117, 0x0D1117)
        foreground = JBColor(0xC0C0C0, 0xA0A0A0)
        font = Font("Monospaced", Font.PLAIN, 11)
        border = JBUI.Borders.empty(4)
        rows = 4
    }

    private val outputScroll = JScrollPane(outputArea).apply {
        border = BorderFactory.createLineBorder(JBColor(0x21262D, 0x21262D))
        preferredSize = Dimension(0, 80)
        isVisible = false
    }

    private var expanded = false
    private val toggleLabel = JBLabel("▶ ROUTA").apply {
        foreground = ROUTA_ACCENT
        font = font.deriveFont(Font.BOLD, 11f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    // DAG connector — visual indicator showing flow to CRAFTERs
    private val dagConnector = JPanel().apply {
        isOpaque = false
        preferredSize = Dimension(0, 12)
    }

    init {
        isOpaque = true
        background = JBColor(0x0D1117, 0x0D1117)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor(0x21262D, 0x21262D)),
            JBUI.Borders.empty(6, 12)
        )

        // Header row
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(JBLabel(AllIcons.Actions.Lightning).apply {
                    toolTipText = "Routa Coordinator"
                })
                add(toggleLabel)
                add(JBLabel("│").apply { foreground = JBColor(0x30363D, 0x30363D) })
                add(previewLabel)
            }
            add(leftPanel, BorderLayout.WEST)

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(JBLabel("LLM:").apply {
                    foreground = JBColor(0x8B949E, 0x8B949E)
                    font = font.deriveFont(10f)
                })
                add(modelCombo)
                add(JBLabel("│").apply { foreground = JBColor(0x30363D, 0x30363D) })
                add(statusDot)
                add(phaseLabel)
            }
            add(rightPanel, BorderLayout.EAST)
        }

        // Center: streaming output
        val centerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(outputScroll, BorderLayout.CENTER)
        }

        add(headerPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)

        // DAG connector at bottom
        dagConnector.add(createDagArrow())
        add(dagConnector, BorderLayout.SOUTH)

        // Toggle
        toggleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                expanded = !expanded
                outputScroll.isVisible = expanded
                toggleLabel.text = if (expanded) "▼ ROUTA" else "▶ ROUTA"
                revalidate()
                repaint()
            }
        })

        // Wire model combo
        modelCombo.addActionListener {
            val selected = modelCombo.selectedItem as? String ?: return@addActionListener
            onModelChanged(selected)
        }
    }

    /**
     * Set available LLM models for ROUTA (from ~/.autodev/config.yaml).
     */
    fun setAvailableModels(models: List<String>) {
        modelCombo.removeAllItems()
        models.forEach { modelCombo.addItem(it) }
    }

    /**
     * Set the selected LLM model.
     */
    fun setSelectedModel(model: String) {
        modelCombo.selectedItem = model
    }

    /**
     * Update the coordination phase display.
     */
    fun updatePhase(phase: CoordinationPhase) {
        val (text, color) = when (phase) {
            CoordinationPhase.IDLE -> "IDLE" to PHASE_IDLE
            CoordinationPhase.PLANNING -> "PLANNING" to PHASE_PLANNING
            CoordinationPhase.READY -> "READY" to PHASE_READY
            CoordinationPhase.EXECUTING -> "EXECUTING" to PHASE_EXECUTING
            CoordinationPhase.WAVE_COMPLETE -> "WAVE DONE" to PHASE_EXECUTING
            CoordinationPhase.VERIFYING -> "VERIFYING" to PHASE_PLANNING
            CoordinationPhase.NEEDS_FIX -> "NEEDS FIX" to PHASE_FAILED
            CoordinationPhase.COMPLETED -> "COMPLETED" to PHASE_COMPLETED
            CoordinationPhase.FAILED -> "FAILED" to PHASE_FAILED
        }
        phaseLabel.text = text
        phaseLabel.foreground = color
        statusDot.foreground = color
    }

    /**
     * Append a streaming text chunk to the output area.
     */
    fun appendChunk(chunk: StreamChunk) {
        SwingUtilities.invokeLater {
            when (chunk) {
                is StreamChunk.Text -> {
                    outputArea.append(chunk.content)
                    updatePreview()
                    autoScroll()
                }

                is StreamChunk.ToolCall -> {
                    outputArea.append("\n[${chunk.status}] ${chunk.name}")
                    if (chunk.result != null) {
                        outputArea.append(" → ${chunk.result!!.take(100)}")
                    }
                    outputArea.append("\n")
                    autoScroll()
                }

                is StreamChunk.Error -> {
                    outputArea.append("\n[ERROR] ${chunk.message}\n")
                    autoScroll()
                }

                is StreamChunk.Completed -> {
                    outputArea.append("\n[Completed: ${chunk.stopReason}]\n")
                    autoScroll()
                }

                else -> {}
            }

            // Auto-expand when content arrives
            if (!expanded && outputArea.text.isNotBlank()) {
                expanded = true
                outputScroll.isVisible = true
                toggleLabel.text = "▼ ROUTA"
                revalidate()
                repaint()
            }
        }
    }

    /**
     * Set the full planning text (replaces streaming content).
     */
    fun setPlanningText(text: String) {
        SwingUtilities.invokeLater {
            outputArea.text = text
            updatePreview()
        }
    }

    /**
     * Clear all output.
     */
    fun clear() {
        SwingUtilities.invokeLater {
            outputArea.text = ""
            previewLabel.text = ""
            updatePhase(CoordinationPhase.IDLE)
        }
    }

    private fun updatePreview() {
        val fullText = outputArea.text
        val preview = fullText.replace('\n', ' ').trim().take(60)
        previewLabel.text = if (preview.length < fullText.length) "$preview…" else preview
    }

    private fun autoScroll() {
        outputArea.caretPosition = outputArea.document.length
    }

    private fun createDagArrow(): JPanel {
        return object : JPanel() {
            init {
                isOpaque = false
                preferredSize = Dimension(20, 12)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(0x30363D, 0x30363D)
                val cx = width / 2
                g2.drawLine(cx, 0, cx, height - 4)
                // Arrow head
                g2.drawLine(cx - 3, height - 7, cx, height - 4)
                g2.drawLine(cx + 3, height - 7, cx, height - 4)
            }
        }
    }
}
