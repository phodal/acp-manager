package com.github.phodal.acpmanager.claudecode.panels

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Panel for displaying user or assistant messages.
 */
class MessagePanel(
    private val name: String,
    private val content: String,
    private val timestamp: Long,
    private val headerColor: Color
) : JPanel(), RenderPanel {

    override val component: JPanel get() = this

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
            add(JBLabel(name ?: "Unknown").apply {
                foreground = headerColor
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
            add(JBLabel(SimpleDateFormat("HH:mm:ss").format(Date(timestamp))).apply {
                foreground = UIUtil.getLabelDisabledForeground()
                font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 2)
            }, BorderLayout.EAST)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        add(header)

        val textArea = JTextArea(content).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.emptyTop(2)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        add(textArea)
    }

    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }

    override fun getMinimumSize(): Dimension {
        val pref = preferredSize
        return Dimension(0, pref.height)
    }
}

