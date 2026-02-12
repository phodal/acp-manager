package com.phodal.routa.gui.panel

import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Input panel with a text field and send/stop buttons.
 *
 * Provides callbacks for submit and stop actions. Supports Enter key to submit.
 */
class GuiInputPanel(
    private val onSubmit: (String) -> Unit,
    private val onStop: () -> Unit,
) : JPanel() {

    private val inputField = JTextField().apply {
        background = Color(0x31, 0x32, 0x44)
        foreground = Color(0xC6, 0xD0, 0xF5)
        caretColor = Color(0xC6, 0xD0, 0xF5)
        font = Font("SansSerif", Font.PLAIN, 13)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(0x45, 0x47, 0x5A), 1, true),
            EmptyBorder(6, 10, 6, 10)
        )
    }

    private val sendButton = JButton("Send").apply {
        background = Color(0x89, 0xB4, 0xFA)
        foreground = Color(0x1E, 0x1E, 0x2E)
        isFocusPainted = false
        font = font.deriveFont(Font.BOLD, 12f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val stopButton = JButton("Stop").apply {
        background = Color(0xF3, 0x8B, 0xA8)
        foreground = Color(0x1E, 0x1E, 0x2E)
        isFocusPainted = false
        font = font.deriveFont(Font.BOLD, 12f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
    }

    init {
        layout = BorderLayout(8, 0)
        background = Color(0x1E, 0x1E, 0x2E)
        border = EmptyBorder(8, 12, 8, 12)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            background = Color(0x1E, 0x1E, 0x2E)
            add(stopButton)
            add(sendButton)
        }

        add(inputField, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.EAST)

        // Enter key submits
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    submitInput()
                }
            }
        })

        sendButton.addActionListener { submitInput() }
        stopButton.addActionListener { onStop() }
    }

    /**
     * Set the running state to toggle send/stop button visibility.
     */
    fun setRunning(running: Boolean) {
        sendButton.isVisible = !running
        stopButton.isVisible = running
        inputField.isEnabled = !running
    }

    private fun submitInput() {
        val text = inputField.text.trim()
        if (text.isNotEmpty()) {
            onSubmit(text)
            inputField.text = ""
        }
    }
}
