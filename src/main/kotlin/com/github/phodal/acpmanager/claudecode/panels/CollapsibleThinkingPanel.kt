package com.github.phodal.acpmanager.claudecode.panels

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Collapsible panel for displaying thinking/reasoning content.
 * Defaults to collapsed state with a subtle appearance.
 * Shows a brief preview when collapsed.
 */
class CollapsibleThinkingPanel(
    accentColor: Color
) : BaseCollapsiblePanel(accentColor, initiallyExpanded = false), StreamingPanel {

    private val contentArea: JTextArea
    private val signatureLabel: JBLabel
    private val previewLabel: JBLabel
    private var fullContent: String = ""

    init {
        // Use subtle border
        border = JBUI.Borders.empty(2, 8)

        // Set header with thinking icon - more subtle
        headerTitle.text = "💡 Thinking"
        headerTitle.font = headerTitle.font.deriveFont(Font.PLAIN, headerTitle.font.size2D - 1)
        headerTitle.foreground = UIUtil.getLabelDisabledForeground()

        // Preview label for collapsed state
        previewLabel = JBLabel().apply {
            foreground = UIUtil.getLabelDisabledForeground()
            font = font.deriveFont(Font.ITALIC, font.size2D - 2)
            border = JBUI.Borders.emptyLeft(8)
        }
        headerPanel.add(previewLabel, java.awt.BorderLayout.EAST)

        // Content area for full thinking text
        contentArea = JTextArea().apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1)
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.emptyTop(4)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        contentPanel.add(contentArea)

        // Signature label
        signatureLabel = JBLabel().apply {
            foreground = UIUtil.getLabelDisabledForeground()
            font = font.deriveFont(font.size2D - 2)
            border = JBUI.Borders.emptyTop(2)
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
        }
        contentPanel.add(signatureLabel)

        // Make header icon more subtle
        headerIcon.foreground = UIUtil.getLabelDisabledForeground()
    }

    override fun updateContent(content: String) {
        fullContent = content
        contentArea.text = content
        updatePreview()
        revalidate()
        repaint()
        parent?.revalidate()
    }

    override fun finalize(content: String, signature: String?) {
        fullContent = content
        contentArea.text = content
        headerTitle.text = "💡 Thinking"

        signature?.let {
            signatureLabel.text = "✓ Verified (${it.take(8)}...)"
            signatureLabel.isVisible = true
        }

        updatePreview()
        revalidate()
        repaint()
        parent?.revalidate()
    }

    private fun updatePreview() {
        if (fullContent.isNotEmpty()) {
            val preview = fullContent.take(50).replace("\n", " ").trim()
            previewLabel.text = if (fullContent.length > 50) "$preview..." else preview
            previewLabel.isVisible = !isExpanded
        } else {
            previewLabel.isVisible = false
        }
    }

    override fun updateExpandedState() {
        super.updateExpandedState()
        previewLabel.isVisible = !isExpanded && fullContent.isNotEmpty()
    }

    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }
}

