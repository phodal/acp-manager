package com.github.phodal.acpmanager.claudecode.panels

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Compact streaming panel for displaying assistant messages without header.
 *
 * Used in CRAFTER panels where space is at a premium and the "Assistant" 
 * header is redundant since the context is already clear.
 *
 * Design principles:
 * - No header (saves vertical space)
 * - Smooth streaming display
 * - Minimal padding
 */
class CompactStreamingPanel(
    private val textColor: Color
) : JPanel(), StreamingPanel {

    override val component: JPanel get() = this

    private val contentArea: JTextArea

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 4, 2, 4)

        // Content area - no header, just the text
        contentArea = JTextArea().apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        add(contentArea)
    }

    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }

    override fun getMinimumSize(): Dimension {
        val pref = preferredSize
        return Dimension(0, pref.height)
    }

    override fun updateContent(content: String) {
        contentArea.text = content
        revalidate()
        repaint()
        parent?.revalidate()
    }

    override fun finalize(content: String, signature: String?) {
        contentArea.text = content
        revalidate()
        repaint()
        parent?.revalidate()
    }

    /**
     * Finalize without signature (convenience method).
     */
    fun finalize(content: String) {
        finalize(content, null)
    }
}

