package com.github.phodal.acpmanager.ui.completion

import com.github.phodal.acpmanager.ui.mention.MentionItem
import com.github.phodal.acpmanager.ui.mention.MentionProvider
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.components.JBTextArea
import java.awt.Point
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

private val log = logger<MentionCompletionHandler>()

/**
 * Handles @ mention completion in the chat input area.
 * Detects @ triggers, shows popup, and manages selection.
 */
class MentionCompletionHandler(
    private val inputArea: JBTextArea,
    private val providers: List<MentionProvider>,
    private val onMentionInserted: ((MentionItem) -> Unit)? = null
) {
    private var currentPopup: MentionCompletionPopup? = null
    private var mentionStartPos: Int = -1

    /**
     * Handle text change in the input area.
     * Shows/updates popup if @ is detected.
     */
    fun handleTextChange(text: String, caretPos: Int) {
        // Find the @ mention context
        val mentionContext = findMentionContext(text, caretPos)

        if (mentionContext != null) {
            // We're in a mention context, show/update popup
            val query = mentionContext.query
            val items = getMentionItems(query)

            if (items.isNotEmpty()) {
                showPopup(items, mentionContext.startPos)
            } else {
                closePopup()
            }
        } else {
            // Not in a mention context, close popup
            closePopup()
        }
    }

    /**
     * Handle key press in the input area.
     * Returns true if the event was consumed by the handler.
     * 
     * Note: The JBPopup handles its own navigation (Up/Down/Enter),
     * so we only handle Escape to close the popup.
     */
    fun handleKeyPress(e: KeyEvent): Boolean {
        val popup = currentPopup ?: return false

        return when (e.keyCode) {
            KeyEvent.VK_ESCAPE -> {
                closePopup()
                true
            }
            else -> false
        }
    }

    /**
     * Find mention context at the given caret position.
     * Returns null if not in a mention context.
     */
    private fun findMentionContext(text: String, caretPos: Int): MentionContext? {
        if (caretPos <= 0) return null

        // Look backwards from caret to find @ symbol
        var pos = caretPos - 1
        while (pos >= 0 && text[pos] != '@' && text[pos] != ' ' && text[pos] != '\n') {
            pos--
        }

        if (pos < 0 || text[pos] != '@') return null

        // Found @, extract query from @ to caret
        val atPos = pos
        val query = text.substring(atPos + 1, caretPos).trim()

        // Check if there's a space or newline between @ and query start
        if (atPos + 1 < caretPos && text[atPos + 1] == ' ') {
            return null // @ followed by space, not a mention
        }

        return MentionContext(atPos, query)
    }

    /**
     * Get mention items from all providers matching the query.
     */
    private fun getMentionItems(query: String): List<MentionItem> {
        val items = mutableListOf<MentionItem>()
        for (provider in providers) {
            items.addAll(provider.getMentions(query))
        }
        return items
    }

    /**
     * Show the mention popup.
     */
    private fun showPopup(items: List<MentionItem>, mentionStartPos: Int) {
        // Close existing popup
        closePopup()

        // Set mention start position after closing to avoid reset
        this.mentionStartPos = mentionStartPos

        // Create and show new popup
        val popup = MentionCompletionPopup(
            items = items,
            onSelect = { item -> insertMention(item) },
            onClose = { currentPopup = null }
        )

        // Calculate popup position (below the @ symbol)
        val caretPos = inputArea.caretPosition
        val rect = inputArea.modelToView(caretPos) ?: return
        val point = Point(rect.x, rect.y + rect.height)
        SwingUtilities.convertPointToScreen(point, inputArea)

        popup.show(inputArea, point.x, point.y)
        currentPopup = popup
    }

    /**
     * Close the current popup.
     */
    fun closePopup() {
        currentPopup?.close()
        currentPopup = null
        mentionStartPos = -1
    }

    /**
     * Insert the selected mention into the input area.
     */
    private fun insertMention(item: MentionItem) {
        val text = inputArea.text
        val caretPos = inputArea.caretPosition

        if (mentionStartPos < 0 || mentionStartPos >= text.length) {
            return
        }

        // Replace from @ to caret with the insert text
        val before = text.substring(0, mentionStartPos)
        val after = text.substring(caretPos)
        val newText = before + item.insertText + after

        inputArea.text = newText
        inputArea.caretPosition = (before + item.insertText).length

        // Notify about the inserted mention
        onMentionInserted?.invoke(item)
    }

    /**
     * Context for a mention being typed.
     */
    private data class MentionContext(
        val startPos: Int,
        val query: String
    )
}

