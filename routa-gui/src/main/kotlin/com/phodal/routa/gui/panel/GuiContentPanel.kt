package com.phodal.routa.gui.panel

import com.phodal.routa.gui.viewmodel.AgentOutput
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Panel displaying the output content for the currently selected agent.
 *
 * Shows streaming text output in a scrollable text area with auto-scroll.
 */
class GuiContentPanel : JPanel() {

    private val titleLabel = JLabel("ROUTA").apply {
        foreground = Color(0xC6, 0xD0, 0xF5)
        font = font.deriveFont(Font.BOLD, 14f)
    }

    private val textArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = Color(0x1E, 0x1E, 0x2E)
        foreground = Color(0xC6, 0xD0, 0xF5)
        font = Font("Monospaced", Font.PLAIN, 13)
        border = EmptyBorder(8, 8, 8, 8)
        caretColor = Color(0xC6, 0xD0, 0xF5)
    }

    private val scrollPane = JScrollPane(textArea).apply {
        border = BorderFactory.createEmptyBorder()
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        viewport.background = Color(0x1E, 0x1E, 0x2E)
    }

    init {
        layout = BorderLayout()
        background = Color(0x1E, 0x1E, 0x2E)

        val titleBar = JPanel(BorderLayout()).apply {
            background = Color(0x24, 0x25, 0x3A)
            border = EmptyBorder(8, 12, 8, 12)
            add(titleLabel, BorderLayout.WEST)
        }

        add(titleBar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Update the displayed title and content.
     */
    fun updateContent(title: String, output: AgentOutput?) {
        titleLabel.text = title
        val newText = output?.fullText ?: ""
        if (textArea.text != newText) {
            textArea.text = newText
            // Auto-scroll to bottom
            textArea.caretPosition = textArea.document.length
        }
    }
}
