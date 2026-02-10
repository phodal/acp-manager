package com.github.phodal.acpmanager.claudecode.panels

import com.agentclientprotocol.model.ToolCallStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

/**
 * Collapsible panel for displaying tool call information.
 * Shows tool name, status, parameters (input), and output.
 * Can be expanded to view full details.
 *
 * UI improvements:
 * - Compact layout when collapsed (minimal height)
 * - Auto-expand on completion to show output
 * - Always visible title with status icon
 */
class ToolCallPanel(
    private val toolName: String,
    private var title: String,
    accentColor: Color
) : BaseCollapsiblePanel(accentColor, initiallyExpanded = false) {

    private val statusIcon: JBLabel
    private var currentStatus: ToolCallStatus = ToolCallStatus.PENDING
    private var isCompleted = false

    // Content sections
    private val inputSection: CollapsibleSection
    private val outputSection: CollapsibleSection

    // Summary label for compact display when collapsed
    private val summaryLabel: JBLabel

    init {
        // Use more compact border
        border = JBUI.Borders.empty(1, 8)

        // Add status icon before the expand icon
        statusIcon = JBLabel("○").apply {
            foreground = headerColor
            font = font.deriveFont(Font.BOLD)
        }
        headerPanel.add(statusIcon, BorderLayout.WEST)

        // Move expand icon to after status
        headerPanel.remove(headerIcon)
        val iconPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(statusIcon)
            add(headerIcon)
        }
        headerPanel.add(iconPanel, BorderLayout.WEST)

        headerTitle.text = "🔧 $title"

        // Summary label for showing brief output when collapsed
        summaryLabel = JBLabel().apply {
            foreground = UIUtil.getLabelDisabledForeground()
            font = font.deriveFont(font.size2D - 2)
            isVisible = false
        }
        headerPanel.add(summaryLabel, BorderLayout.EAST)

        // Input section (parameters) - more compact
        inputSection = CollapsibleSection("📥 Input", UIUtil.getLabelDisabledForeground())
        inputSection.isVisible = false
        contentPanel.add(inputSection)

        // Output section
        outputSection = CollapsibleSection("📤 Output", UIUtil.getLabelDisabledForeground())
        outputSection.isVisible = false
        contentPanel.add(outputSection)
    }

    /**
     * Update the tool call status.
     */
    fun updateStatus(status: ToolCallStatus, newTitle: String? = null) {
        if (isCompleted) return
        currentStatus = status
        newTitle?.let {
            title = it
            headerTitle.text = "🔧 $it"
        }
        statusIcon.text = when (status) {
            ToolCallStatus.IN_PROGRESS -> "▶"
            ToolCallStatus.PENDING -> "○"
            else -> "■"
        }
        revalidate()
        repaint()
    }

    /**
     * Update the input parameters.
     */
    fun updateParameters(params: String) {
        if (isCompleted) return
        inputSection.setContent(params)
        inputSection.isVisible = params.isNotEmpty()
        revalidate()
        repaint()
        parent?.revalidate()
    }

    /**
     * Complete the tool call with final status and output.
     * Auto-expands the panel if there's output to show.
     */
    fun complete(status: ToolCallStatus, output: String?) {
        isCompleted = true
        currentStatus = status

        val (icon, color) = if (status == ToolCallStatus.COMPLETED) {
            "✓" to JBColor(Color(0x2E7D32), Color(0x81C784))
        } else {
            "✗" to JBColor.RED
        }

        statusIcon.text = icon
        statusIcon.foreground = color
        setHeaderColor(color)

        // Show output if available
        if (!output.isNullOrBlank()) {
            outputSection.setContent(output)
            outputSection.isVisible = true

            // Show brief summary in header when collapsed
            val summary = output.take(60).replace("\n", " ")
            summaryLabel.text = if (output.length > 60) "$summary..." else summary
            summaryLabel.foreground = color
            summaryLabel.isVisible = !isExpanded
            // Keep collapsed by default - user can expand if needed
        } else {
            // For completed without output, show "Done" summary
            summaryLabel.text = if (status == ToolCallStatus.COMPLETED) "Done" else "Failed"
            summaryLabel.foreground = color
            summaryLabel.isVisible = !isExpanded
        }

        revalidate()
        repaint()
        parent?.revalidate()
    }

    override fun updateExpandedState() {
        super.updateExpandedState()
        // Hide summary when expanded, show when collapsed
        summaryLabel.isVisible = !isExpanded && isCompleted
    }

    /**
     * Get the current status.
     */
    fun getStatus(): ToolCallStatus = currentStatus
}

